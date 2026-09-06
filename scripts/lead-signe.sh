#!/usr/bin/env bash
# Envoie un lead de test signe en HMAC-SHA256 sur le webhook de capture.
#
# Sert la recette de F14 : le meme lead, signe tantot avec l ancien secret,
# tantot avec le nouveau, doit etre accepte tant que la fenetre de transition
# court, puis refuse des qu elle est fermee.
#
#   ./scripts/lead-signe.sh <cle-publique> <secret> [email]
#
# 202 = accepte, 401 = refuse. Les cinq causes de refus rendent le meme 401.
set -euo pipefail

CLE="${1:?usage: lead-signe.sh <cle-publique> <secret> [email]}"
SECRET="${2:?usage: lead-signe.sh <cle-publique> <secret> [email]}"
EMAIL="${3:-prospect.recette@example.test}"
HOTE="${LEADFLOW_HOTE:-http://localhost:8090}"

CORPS="{\"source\":\"recette-f14\",\"email\":\"$EMAIL\",\"nom\":\"Prospect Recette\",\"telephone\":\"0600000000\",\"message\":\"Je souhaite un devis rapidement pour 50 postes.\"}"
T=$(date +%s)
HEX=$(printf '%s' "$T.$CORPS" | openssl dgst -sha256 -hmac "$SECRET" -r | cut -d' ' -f1)

echo "-> POST $HOTE/api/webhooks/leads/$CLE"
curl -s -o /tmp/lead-signe-reponse.json -w "   HTTP %{http_code}\n" \
  -X POST "$HOTE/api/webhooks/leads/$CLE" \
  -H 'Content-Type: application/json' \
  -H "X-Leadflow-Signature: t=$T,v1=$HEX" \
  --data-raw "$CORPS"
cat /tmp/lead-signe-reponse.json 2>/dev/null; echo
