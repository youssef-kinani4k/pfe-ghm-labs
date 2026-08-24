# API de monitoring

Ce document décrit l'API que consomme le dashboard : authentification, lecture du pipeline,
journal des messages morts, flux temps réel. Il est la référence pour qui veut interroger
LeadFlow sans passer par l'interface — un script d'exploitation, une sonde, une démonstration.

Toutes les routes vivent sous `/api` et sont servies par le backend sur `:8090`. Toutes,
**sauf le webhook de capture**, exigent un jeton.

---

## 1. Obtenir un jeton

Rien d'autre ne fonctionne sans lui, donc c'est le premier geste.

```
POST /api/auth/login
Content-Type: application/json
```

```bash
JETON=$(curl -s -X POST http://localhost:8090/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"mot-de-passe"}' | jq -r .token)

echo "$JETON"
```

Réponse :

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresAt": "2026-08-24T22:15:03Z"
}
```

Le jeton est un JWT **HS256** validé nativement par Spring Security. Il vaut huit heures et
**ne se rafraîchit pas** : à l'expiration, on se reconnecte. Toutes les requêtes suivantes le
portent en en-tête :

```bash
curl -s 'http://localhost:8090/api/leads?status=SYNCED' \
  -H "Authorization: Bearer $JETON" | jq
```

Un identifiant inconnu et un mot de passe faux rendent **le même `401`** : distinguer les
deux donnerait un oracle sur les comptes existants. Une requête sans jeton, ou avec un jeton
expiré, rend `401` également.

---

## 2. Configuration et comptes

Aucun secret n'est en dur dans `application.yml`. Trois variables d'environnement gouvernent
le dashboard :

| Variable                       | Rôle                                              | Défaut  |
| ------------------------------ | ------------------------------------------------- | ------- |
| `LEADFLOW_JWT_SECRET`          | Clé de signature HS256 des jetons de session      | *aucun* |
| `LEADFLOW_ADMIN_USER`          | Identifiant du compte opérateur                   | `admin` |
| `LEADFLOW_ADMIN_PASSWORD_HASH` | **Hash BCrypt** du mot de passe, jamais le mot de passe | *aucun* |

**Sous le profil `dev`, les trois ont un repli** : `spring-boot:run
-Dspring-boot.run.profiles=dev` démarre sans rien préparer, et le compte de démonstration est
`admin` / `leadflow-demo-2026`. Les variables restent prioritaires, donc lancer le profil `dev`
contre un serveur partagé ne signe pas avec une clé publiée dans le dépôt. **Hors `dev`**,
`LEADFLOW_JWT_SECRET` n'a **aucune valeur de repli**, exactement comme `LEADFLOW_MASTER_KEY` :
l'application refuse de démarrer plutôt que de signer avec un secret devinable. Le contrôle au
démarrage ne porte que sur l'absence ; **un secret de moins de 32 octets passe le démarrage
mais fait échouer la première connexion**, HS256 exigeant une clé d'au moins 256 bits. D'où la
commande ci-dessous plutôt qu'une chaîne inventée à la main.

```bash
export LEADFLOW_JWT_SECRET=$(openssl rand -base64 48)
```

### Produire un hash BCrypt

C'est le premier obstacle concret au déploiement : la configuration attend un hash, jamais un
mot de passe, **même en développement**.

```bash
# Le ':' de separation et le saut de ligne sont retires : il ne reste que le hash.
htpasswd -bnBC 10 "" 'mot-de-passe' | tr -d ':\n'
# $2y$10$Xk...
```

Sans `htpasswd` sous la main, n'importe quel `BCryptPasswordEncoder` fait l'affaire — un
`jshell` avec le jar de Spring Security au classpath, ou un test jetable. **Ne jamais
recopier un hash trouvé dans une documentation** : le vérifier avec `matches` avant de s'en
servir.

Le hash commence par `$2y$` ou `$2a$` ; dans un fichier `.env` ou un `docker-compose.yml`,
les `$` doivent être échappés (`$$`) sous peine d'être interprétés comme des variables.

---

## 3. Leads

### Liste paginée et filtrée

```
GET /api/leads
```

| Paramètre      | Type                    | Effet                                        |
| -------------- | ----------------------- | -------------------------------------------- |
| `page`         | entier, défaut `0`      | Index de page                                 |
| `size`         | entier, défaut `20`     | Taille de page, **plafonnée à 100**           |
| `sort`         | `champ,asc\|desc`       | Tri, p. ex. `createdAt,desc`                  |
| `clientId`     | UUID                    | Restreint à un client                         |
| `status`       | `LeadStatus`, répétable | `QUALIFIED`, `ROUTED`, `SYNCED`, `REJECTED`, `FAILED` |
| `intent`       | chaîne                  | Intention détectée                            |
| `intentSource` | `RULES` \| `GEMINI`     | Origine de l'analyse                          |
| `salesRepId`   | UUID                    | Commercial attribué                           |
| `minScore`     | entier                  | Score minimal                                 |
| `from`, `to`   | instant ISO-8601        | Fenêtre sur `createdAt`                       |
| `q`            | chaîne                  | Recherche libre (email, société)              |

Un paramètre absent n'ajoute aucun prédicat. Une taille supérieure à 100 est **plafonnée en
silence** plutôt que refusée : une taille excessive est une maladresse d'appelant, pas une
faute qui mérite de faire échouer l'écran.

**Le tri ne porte que sur les colonnes de la table `lead`** — `createdAt`, `companyName`,
`email`, `detectedIntent`, `score`, `status`. `clientName` et `salesRepName` sont résolus
après la requête, par une seconde lecture : les trier ferait rendre `500`.

```bash
curl -s 'http://localhost:8090/api/leads?status=FAILED&minScore=60&sort=createdAt,desc&size=25' \
  -H "Authorization: Bearer $JETON" | jq
```

```json
{
  "content": [
    {
      "id": "6f1c...",
      "createdAt": "2026-08-24T09:12:44Z",
      "clientId": "b21e...",
      "clientName": "Acme Maroc",
      "companyName": "Acme",
      "email": "karim@acme.test",
      "detectedIntent": "DEVIS",
      "intentSource": "GEMINI",
      "score": 72,
      "status": "FAILED",
      "assignedSalesRepId": "3a90...",
      "salesRepName": "Sara Bennani",
      "countryCode": "MA",
      "sector": "industrie"
    }
  ],
  "page": 0,
  "size": 25,
  "totalElements": 1,
  "totalPages": 1
}
```

### Détail d'un lead

```
GET /api/leads/{id}
```

Rend le lead, son commercial, **tout l'historique `crm_sync_attempt`** et l'événement brut
d'origine avec sa charge utile. C'est ce dernier bloc qui répond à « pourquoi ce lead n'a pas
de téléphone » sans ouvrir `psql`.

```bash
curl -s "http://localhost:8090/api/leads/6f1c..." -H "Authorization: Bearer $JETON" | jq
```

Un identifiant inconnu rend `404` en `ProblemDetail` (RFC 7807).

---

## 4. Annuaire

```
GET /api/clients
GET /api/clients/{id}/sales-reps
```

```bash
curl -s http://localhost:8090/api/clients -H "Authorization: Bearer $JETON" | jq
```

```json
[
  {
    "id": "b21e...",
    "name": "Acme Maroc",
    "active": true,
    "crmProviderId": "dolibarr",
    "assignmentStrategy": "ROUND_ROBIN"
  }
]
```

**Aucun secret ne sort par là.** `client.hmac_secret` et `client.crm_config` sont déchiffrés
à la lecture par les `AttributeConverter` : sérialiser l'entité publierait le secret. La
réponse est un `record` dédié, et un test l'asserte sur le corps JSON.

---

## 5. Statistiques

```
GET /api/stats?clientId=&from=&to=
```

```bash
curl -s http://localhost:8090/api/stats -H "Authorization: Bearer $JETON" | jq
```

```json
{
  "total": 143,
  "leadsParStatut": { "QUALIFIED": 12, "ROUTED": 9, "SYNCED": 118, "FAILED": 4 },
  "evenementsParStatut": { "RECEIVED": 2, "PUBLISHED": 148, "DISCARDED": 5 },
  "tauxDeConversion": 82.5,
  "leadsParIntention": { "DEVIS": 88, "INFORMATION": 41, "AUTRE": 14 },
  "leadsParSourceDIntention": { "GEMINI": 130, "RULES": 13 },
  "leadsParCommercial": { "3a90...": 74, "77bd...": 69 },
  "nomsDeCommercial": { "3a90...": "Sara Bennani", "77bd...": "Youssef Alami" }
}
```

`leadsParCommercial` a des identifiants pour clés ; `nomsDeCommercial` les accompagne, sans
quoi un écran afficherait des UUID. Chaque mesure est une requête d'agrégation : rien n'est
compté en mémoire.

---

## 6. Files

```
GET /api/queues
```

Profondeur et consommateurs, lus **en AMQP** sur le broker.

```json
{
  "queues": [
    { "name": "leadflow.leads.captured", "reachable": true, "messageCount": 0, "consumerCount": 1 },
    { "name": "leadflow.leads.qualified", "reachable": true, "messageCount": 0, "consumerCount": 1 },
    { "name": "leadflow.leads.routed", "reachable": true, "messageCount": 0, "consumerCount": 1 },
    { "name": "leadflow.leads.dlq", "reachable": true, "messageCount": 0, "consumerCount": 1 }
  ],
  "pendingDeadLetters": 3
}
```

Deux lectures à faire tout de suite :

- **`consumerCount: 0` sur une file métier** est la mesure la plus lisible d'un listener
  tombé : rien ne dépile ce qui arrive.
- **`messageCount` non nul sur `leadflow.leads.dlq`** est anormal depuis F6 : le
  consommateur de la DLQ la vide au fil de l'eau vers la table `dead_letter`. Une profondeur
  qui monte veut dire que ce consommateur ne tourne pas.

Si le broker est injoignable, la file remonte avec `reachable: false` plutôt que de faire
échouer la requête entière.

---

## 7. Journal des messages morts

### Ce qu'il est, et pourquoi il remplace la DLQ

Une file de messages n'est pas une liste : elle ne sait ni paginer, ni filtrer, ni garder la
trace de ce qu'on a déjà traité. La DLQ reste le **tuyau** par lequel un message échoué
arrive ; la table `dead_letter` est la **source de vérité** de ce qui a échoué. Le
consommateur de la DLQ écrit une ligne par mort, avec le motif capté par le
`RepublishMessageRecoverer`, puis acquitte.

### Lister

```
GET /api/dead-letters?status=&originQueue=&clientId=&from=&to=&page=&size=
```

```bash
curl -s 'http://localhost:8090/api/dead-letters?status=PENDING' \
  -H "Authorization: Bearer $JETON" | jq
```

```json
{
  "content": [
    {
      "id": "9c02...",
      "originQueue": "leadflow.leads.routed",
      "routingKey": "lead.routed",
      "clientId": "b21e...",
      "clientName": "Acme Maroc",
      "leadId": "6f1c...",
      "failureReason": "CrmSyncException: 401 Unauthorized sur /api/index.php/thirdparties",
      "deadAt": "2026-08-24T09:13:02Z",
      "status": "PENDING",
      "replayedAt": null,
      "replayedBy": null,
      "payload": "{\"leadId\":\"6f1c...\"}",
      "replayWarning": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### Rejouer et écarter

```
POST /api/dead-letters/{id}/replay
POST /api/dead-letters/{id}/discard
```

```bash
curl -s -X POST "http://localhost:8090/api/dead-letters/9c02.../replay" \
  -H "Authorization: Bearer $JETON" -i
```

Le rejeu republie la charge d'origine sur la file d'où elle venait, marque la ligne `REPLAYED`
et **retient qui l'a demandé** (`replayedBy`, tiré du jeton). `discard` marque `DISCARDED`,
définitivement.

Trois réponses à connaître :

| Code  | Sens                                                                       |
| ----- | -------------------------------------------------------------------------- |
| `409` | La ligne n'est plus `PENDING` — elle a été traitée ailleurs, sans doute par un autre onglet |
| `503` | Le broker est injoignable. **La ligne reste `PENDING`** et le geste réussira plus tard |
| `404` | La ligne n'existe pas                                                       |

**Les gestes sont unitaires, et il n'y a pas d'endpoint de masse.** Ce n'est pas un oubli :
rejouer en bloc un incident qu'on n'a pas compris le multiplie. Le dashboard propose une
sélection multiple, mais elle boucle sur l'appel unitaire.

### L'avertissement de rejeu

Quand la ligne vient de `lead.qualified`, la réponse porte un `replayWarning`. La raison est
une décision de F4 : **le tour de rôle n'est pas idempotent**. Il se lit dans la table `lead`
— le commercial dont la dernière attribution est la plus ancienne prend le suivant — donc
rejouer une qualification refait passer le lead par l'attribution, il peut changer de
commercial, et la rotation des autres se décale.

L'avertissement est calculé par le serveur, pas deviné par l'écran. Un client qui appelle
l'API directement doit le lire et décider en connaissance de cause.

---

## 8. Connecteurs

```
GET /api/connectors
```

État de chaque connecteur ERP, **dérivé des traces de `crm_sync_attempt`** : aucun ERP n'est
contacté pour produire cette vue. Un écran de supervision qui interrogerait les ERP à chaque
affichage ferait dépendre son propre temps de réponse de la disponibilité de chacun d'eux.

```json
[
  {
    "providerId": "dolibarr",
    "implemented": true,
    "enabled": true,
    "successCount": 118,
    "failureCount": 4,
    "lastSuccessAt": "2026-08-24T09:40:11Z",
    "lastFailureAt": "2026-08-24T09:13:02Z",
    "lastFailureMessage": "401 Unauthorized sur /api/index.php/thirdparties",
    "parClient": [
      {
        "clientId": "b21e...",
        "clientName": "Acme Maroc",
        "successCount": 118,
        "failureCount": 4,
        "lastAttemptAt": "2026-08-24T09:40:11Z"
      }
    ]
  }
]
```

Quatre situations à ne pas confondre :

- **`enabled: false`** — désactivé par configuration. Ce n'est pas une panne.
- **compteurs à zéro** — actif, mais aucune synchronisation à ce jour.
- **`lastFailureAt` postérieur à `lastSuccessAt`** — le **dernier** appel a échoué : c'est le
  seul cas qui appelle une intervention. Se fier au seul `failureCount` peindrait en rouge un
  connecteur qui a échoué hier et réussi depuis.
- **`enabled: true` avec `implemented: false`** — anomalie de déploiement : le fournisseur est
  configuré mais aucun adaptateur ne porte cet identifiant, et toute synchronisation qui le
  vise échouera.

`parClient` sert à voir qu'**un seul client casse pendant que les autres passent** : le
problème est alors dans son `crm_config`, pas dans l'adaptateur.

---

## 9. Flux temps réel

```
GET /api/stream/leads
Accept: text/event-stream
```

Un flux SSE alimenté par `leadflow.monitoring.events`, une file d'observation liée aux mêmes
routing keys que les files métier. Elle ne vole aucun message : un `DirectExchange` livre à
**toutes** les files liées à une clé.

```bash
curl -N http://localhost:8090/api/stream/leads -H "Authorization: Bearer $JETON"
```

```
event: lead
data: {"leadId":"6f1c...","clientId":"b21e...","status":"SYNCED","score":72,"salesRepId":"3a90...","occurredAt":"2026-08-24T09:40:11Z"}

: keep-alive

event: dead-letter
data: {"id":"9c02...","originQueue":"leadflow.leads.routed","status":"PENDING",...}
```

Deux noms d'événement : `lead` et `dead-letter`. Les commentaires `: keep-alive` existent pour
empêcher un proxy de couper une connexion silencieuse — un client les ignore.

La charge de `lead` est volontairement maigre : le flux dit qu'un lead a bougé et où il en
est, pas tout ce qu'il contient. Le détail reste sur `/api/leads/{id}`, et une charge légère
permet de diffuser à tous les abonnés sans relire la base.

**L'émetteur expire au bout de trente minutes** (`leadflow.monitoring.stream.emitter-timeout`).
Une reconnexion est donc attendue, pas exceptionnelle : un client doit la prévoir.

`EventSource` du navigateur **ne convient pas** ici — il ne sait pas poser d'en-tête
`Authorization`, et passer le jeton en paramètre d'URL le ferait apparaître dans tous les
journaux d'accès. Le dashboard lit le flux par `fetch` + `ReadableStream` et découpe les
trames lui-même.

---

## 10. Erreurs

Toutes les erreurs sont des `ProblemDetail` (RFC 7807) :

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Aucun lead avec cet identifiant"
}
```

Les corps JSON sont en **camelCase anglais** (`eventId`, `clientKey`), y compris pour les
champs dont le nom Java est en français.
