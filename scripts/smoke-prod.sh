#!/usr/bin/env bash
#
# Test de fumee de la pile de production. Monte docker-compose.prod.yml, attend la sante
# des services, puis traverse tout le pipeline a travers le proxy.
#
#   SMOKE_PASSWORD='...' ./scripts/smoke-prod.sh          monte, eprouve, demonte
#   SMOKE_PASSWORD='...' ./scripts/smoke-prod.sh --keep   laisse la pile debout
#
# Rend 0 si tout passe, un code non nul en nommant l'etape qui a echoue.
set -euo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RACINE"

# --env-file n'est pas facultatif : « env_file: » alimente les variables DU CONTENEUR,
# tandis que les « ${...} » du fichier compose sont interpoles A SA LECTURE, depuis le
# shell ou depuis .env — jamais depuis .env.prod. Sans lui, la commande echoue sur
# POSTGRES_PASSWORD des la premiere ligne.
COMPOSE="docker compose --env-file .env.prod -f docker-compose.prod.yml"
GARDER=0
[ "${1:-}" = "--keep" ] && GARDER=1

etape() { printf '\n== %s\n' "$1"; }
echoue() { printf '\n!! ECHEC : %s\n' "$1" >&2; exit 1; }

nettoie() {
  if [ "$GARDER" -eq 1 ]; then
    printf '\nPile laissee debout (--keep). La demonter : %s down -v\n' "$COMPOSE"
  else
    $COMPOSE down -v >/dev/null 2>&1 || true
  fi
}
trap nettoie EXIT

# ---------------------------------------------------------------------------------------
etape "0. Prerequis"
[ -f .env.prod ] || echoue ".env.prod absent — le creer depuis .env.prod.example"
command -v openssl >/dev/null || echoue "openssl introuvable"

MOT_DE_PASSE="${SMOKE_PASSWORD:-}"
[ -n "$MOT_DE_PASSE" ] || echoue \
  "SMOKE_PASSWORD non defini — donner le mot de passe en clair de LEADFLOW_ADMIN_PASSWORD_HASH"

valeur() { grep -E "^$1=" .env.prod | head -1 | cut -d= -f2- | tr -d '\r'; }
UTILISATEUR="$(valeur LEADFLOW_ADMIN_USER)"
UTILISATEUR="${UTILISATEUR:-admin}"

# Le port publie se lit dans .env.prod, comme le compose le lit : sur une machine ou un
# autre serveur tient deja le 80, l'operateur pose WEB_PORT et le test doit le suivre.
PORT="$(valeur WEB_PORT)"
PORT="${PORT:-80}"
if [ "$PORT" = "80" ]; then BASE="${BASE:-http://localhost}"; else BASE="${BASE:-http://localhost:$PORT}"; fi
printf 'cible : %s\n' "$BASE"

# ---------------------------------------------------------------------------------------
etape "1. Montage de la pile"
$COMPOSE up -d --build

etape "2. Attente de la sante (jusqu'a 240 s)"
# On attend un etat, jamais une duree : RabbitMQ met une trentaine de secondes a devenir
# sain et le backend migre ensuite. Un sleep fixe rendrait ce script vert ou rouge selon
# la charge de la machine.
for i in $(seq 1 120); do
  if curl -fsS "$BASE/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    printf 'sain apres %s s\n' "$((i * 2))"
    break
  fi
  [ "$i" -eq 120 ] && echoue "la pile n'est pas devenue saine en 240 s ($COMPOSE logs backend)"
  sleep 2
done

# ---------------------------------------------------------------------------------------
etape "3. Nginx sert le dashboard et le routage cote client"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/")
[ "$code" = "200" ] || echoue "GET / rend $code, attendu 200"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/leads/42")
[ "$code" = "200" ] || echoue "GET /leads/42 rend $code, attendu 200 (try_files absent ?)"

etape "3b. Les en-tetes de securite sont poses"
# Sur / ET sur index.html : un add_header dans un location annule ceux du server, et
# index.html en pose un pour le cache. Eprouver la seule racine laisserait passer
# precisement le defaut le plus probable de ce fichier.
#
# Mais / et /index.html servent tous deux la MEME copie des en-tetes : / y arrive par
# try_files -> /index.html, donc les deux exercent le bloc « = /index.html » et rien
# d'autre. La copie du bloc des ressources hachees restait entierement non eprouvee. On
# derive un chemin qui y tombe en extrayant le premier script .js de la page rendue,
# plutot que de figer un nom de fichier hache qui change a chaque build.
INDEX_HTML=$(curl -fsS "$BASE/index.html")
# Ajouter || true evite que l'absence de .js ne tue le script sous set -euo pipefail.
HACHE=$(printf '%s' "$INDEX_HTML" | grep -oE 'src="[^"]+\.js"' | head -1 | sed -E 's/^src="//; s/"$//' || true)

CHEMINS=("/" "/index.html")
if [ -n "$HACHE" ]; then
  case "$HACHE" in
    /*) ;;
    *) HACHE="/$HACHE" ;;
  esac
  CHEMINS+=("$HACHE")
else
  printf 'aucun script .js trouve dans /index.html — bloc des ressources hachees non eprouve\n'
fi

for chemin in "${CHEMINS[@]}"; do
  ENTETES=$(curl -sSI "$BASE$chemin")
  printf '%s' "$ENTETES" | grep -qi '^x-content-type-options: *nosniff' \
    || echoue "X-Content-Type-Options absent sur $chemin"
  printf '%s' "$ENTETES" | grep -qi '^content-security-policy:.*default-src' \
    || echoue "Content-Security-Policy absente sur $chemin"
  printf '%s' "$ENTETES" | grep -qi "^content-security-policy:.*frame-ancestors 'none'" \
    || echoue "frame-ancestors absent de la CSP sur $chemin"
  printf '%s' "$ENTETES" | grep -qi '^referrer-policy:' \
    || echoue "Referrer-Policy absent sur $chemin"
  printf '%s' "$ENTETES" | grep -qi '^x-frame-options: *deny' \
    || echoue "X-Frame-Options absent sur $chemin"
done

etape "4. L'API est atteinte, et fermee"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/leads")
[ "$code" = "401" ] || echoue "GET /api/leads sans jeton rend $code, attendu 401"

etape "5. Connexion de l'operateur"
JETON=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$UTILISATEUR\",\"password\":\"$MOT_DE_PASSE\"}" \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$JETON" ] || echoue "aucun jeton rendu — verifier SMOKE_PASSWORD et LEADFLOW_ADMIN_PASSWORD_HASH"

etape "6. Creation d'une boutique par l'API"
# Une base de production demarre vide : db/dev n'est jamais charge. L'amorcage passe par
# l'API, jamais par du SQL. baseUrl pointe volontairement dans le vide — la synchronisation
# ERP n'est pas ce que ce script eprouve.
CREATION=$(curl -fsS -X POST "$BASE/api/admin/clients" \
  -H "Authorization: Bearer $JETON" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Boutique de fumee",
       "crmProviderId":"dolibarr",
       "assignmentStrategy":"ROUND_ROBIN",
       "crmSettings":{"baseUrl":"http://injoignable.invalid/api/index.php",
                      "apiKey":"cle-de-fumee"},
       "firstSalesRep":{"fullName":"Commercial de fumee",
                        "email":"commercial@example.test"}}')
CLE_PUBLIQUE=$(printf '%s' "$CREATION" | sed -n 's/.*"publicKey":"\([^"]*\)".*/\1/p')
SECRET=$(printf '%s' "$CREATION" | sed -n 's/.*"hmacSecret":"\([^"]*\)".*/\1/p')
CLIENT_ID=$(printf '%s' "$CREATION" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
[ -n "$CLE_PUBLIQUE" ] && [ -n "$SECRET" ] || echoue "creation de boutique sans cle ni secret"

etape "7. Soumission signee sur le webhook"
EMAIL="fumee-$(date +%s)@example.test"
# « source » est obligatoire : LeadCaptureService rend 400 sans lui. Il n'est pas mappe
# vers le pivot — il renseigne raw_lead_event.source, le canal d'origine du lead.
CORPS="{\"source\":\"test-de-fumee\",\"email\":\"$EMAIL\",\"societe\":\"Fumee SARL\",\"message\":\"Je veux un devis rapidement\"}"
T=$(date +%s)
V1=$(printf '%s' "$T.$CORPS" | openssl dgst -sha256 -hmac "$SECRET" -r | cut -d' ' -f1)
reponse=$(curl -s -w '\n%{http_code}' -X POST "$BASE/api/webhooks/leads/$CLE_PUBLIQUE" \
  -H 'Content-Type: application/json' \
  -H "X-Leadflow-Signature: t=$T,v1=$V1" \
  -d "$CORPS")
code=$(printf '%s' "$reponse" | tail -1)
EVENT_ID=$(printf '%s' "$reponse" | head -1 | sed -n 's/.*"eventId":"\([^"]*\)".*/\1/p')
[ "$code" = "202" ] || echoue "le webhook rend $code, attendu 202"

etape "8. Rejeu : meme signature, meme eventId"
reponse=$(curl -s -X POST "$BASE/api/webhooks/leads/$CLE_PUBLIQUE" \
  -H 'Content-Type: application/json' \
  -H "X-Leadflow-Signature: t=$T,v1=$V1" \
  -d "$CORPS")
REJEU=$(printf '%s' "$reponse" | sed -n 's/.*"eventId":"\([^"]*\)".*/\1/p')
[ "$REJEU" = "$EVENT_ID" ] || echoue "le rejeu rend $REJEU au lieu de $EVENT_ID"

etape "9. Le lead traverse le pipeline (jusqu'a 60 s)"
for i in $(seq 1 30); do
  if curl -fsS "$BASE/api/leads?clientId=$CLIENT_ID" \
       -H "Authorization: Bearer $JETON" | grep -q "$EMAIL"; then
    printf 'lead visible apres %s s\n' "$((i * 2))"
    break
  fi
  [ "$i" -eq 30 ] && echoue "le lead n'est pas apparu en 60 s ($COMPOSE logs backend)"
  sleep 2
done

etape "10. Le flux SSE traverse le proxy"
# Des octets arrivent du flux a travers nginx : le « location = /api/stream/leads »
# l'emporte bien sur le prefixe /api/, l'authentification passe, et le battement de 20 s
# ressort cote client.
BATTEMENT=$(curl -sN --max-time 30 "$BASE/api/stream/leads" \
  -H "Authorization: Bearer $JETON" | head -c 64 || true)
[ -n "$BATTEMENT" ] || echoue "aucun octet recu du flux SSE en 30 s"

etape "11. Le tamponnage est bien desactive sur le flux"
# L'etape 10 ne suffit PAS a le prouver : eprouvee contre un nginx.conf ou
# « proxy_buffering off » etait commente, elle restait verte. nginx transmet un flux lent
# meme buffering actif — il ne retient que jusqu'a remplir un buffer de plusieurs kilo-
# octets, ou un battement de quelques octets toutes les 20 s n'arrive jamais. Le defaut
# ne se verrait donc qu'a la premiere rafale d'evenements, en production. On asserte ici
# la configuration effective du conteneur, qui elle discrimine. Le motif est ancre sur
# le debut de ligne et exige le point-virgule : sans cela il matcherait la ligne
# COMMENTEE de la mutation, et l'assertion ne pourrait a nouveau pas echouer.
CONF=$($COMPOSE exec -T web nginx -T 2>/dev/null || true)
printf '%s' "$CONF" \
  | sed -n '/location = .api.stream.leads/,/}/p' \
  | grep -qE '^[[:space:]]*proxy_buffering off;' \
  || echoue "proxy_buffering off absent du bloc /api/stream/leads — le dashboard se figera"

printf '\n== Tout est vert.\n'
