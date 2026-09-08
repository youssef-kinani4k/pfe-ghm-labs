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
| `chaud`        | `true`                  | Ne rend que les leads chauds                  |

Un paramètre absent n'ajoute aucun prédicat. Une taille supérieure à 100 est **plafonnée en
silence** plutôt que refusée : une taille excessive est une maladresse d'appelant, pas une
faute qui mérite de faire échouer l'écran.

**Le tri ne porte que sur les colonnes de la table `lead`** — `createdAt`, `companyName`,
`email`, `detectedIntent`, `score`, `status`. `clientName` et `salesRepName` sont résolus
après la requête, par une seconde lecture : les trier ferait rendre `500`.

**`chaud` n'a pas de négation.** Seul `chaud=true` pose un prédicat ; `chaud=false` est traité
comme l'absence du paramètre, parce que « pas seulement les chauds » veut dire « tous ». Le
prédicat n'est pas une comparaison de score : le seuil appartient à chaque boutique, donc la
clause est une disjonction de couples *(boutique, seuil)*, construite après lecture des
barèmes. `client.scoring_config` est chiffré au repos et illisible par Postgres — aucune
comparaison SQL directe n'est possible.

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
      "sector": "industrie",
      "chaud": true
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
de téléphone » sans ouvrir `psql`. La fiche porte le même `chaud` que la liste.

**`chaud` est calculé à la lecture, jamais stocké** : c'est `score >= seuilChaud` du barème de
la boutique du lead. Deux leads au même score peuvent donc différer, et un barème modifié
change le verdict des leads déjà écrits sans qu'aucun score ne bouge. Le figer en base à la
qualification ferait mentir la liste dès le lendemain.

```bash
curl -s "http://localhost:8090/api/leads/6f1c..." -H "Authorization: Bearer $JETON" | jq
```

```json
{
  "id": "6f1c...",
  "createdAt": "2026-08-24T09:12:44Z",
  "updatedAt": "2026-08-31T11:40:12Z",
  "clientId": "b21e...",
  "clientName": "Acme Maroc",
  "companyName": "Acme",
  "firstName": "Karim",
  "lastName": "Haddad",
  "email": "karim@acme.test",
  "phone": "+212600000000",
  "message": "Interesse par un devis",
  "detectedIntent": "DEVIS",
  "intentSource": "GEMINI",
  "score": 72,
  "status": "SYNCED",
  "countryCode": "MA",
  "sector": "industrie",
  "salesRep": { "id": "3a90...", "fullName": "Sara Bennani", "email": "sara@acme.test", "sector": null, "zone": null, "crmRef": "4" },
  "syncAttempts": [
    {
      "id": "9c02...",
      "providerId": "dolibarr",
      "status": "SUCCESS",
      "nature": "SYNCHRONISATION",
      "accountRef": "12",
      "contactRef": "7",
      "opportunityRef": "31",
      "assigneeRef": "4",
      "errorMessage": null,
      "attemptedAt": "2026-08-24T09:12:46Z"
    },
    {
      "id": "9c05...",
      "providerId": "dolibarr",
      "status": "SUCCESS",
      "nature": "REAFFECTATION",
      "accountRef": "12",
      "contactRef": "7",
      "opportunityRef": "31",
      "assigneeRef": "9",
      "errorMessage": null,
      "attemptedAt": "2026-08-31T11:40:14Z"
    }
  ],
  "rawEvent": { "...": "voir la charge utile brute plus haut" },
  "chaud": true
}
```

**Depuis F15, chaque ligne de `syncAttempts` porte `nature`** (`SYNCHRONISATION` ou
`REAFFECTATION`) **et `assigneeRef`** : une correction de responsable écrit une ligne de plus
sans toucher aux autres références, et `nature` dit laquelle des deux raisons a produit la
ligne. `taskRef` n'y figure plus — la colonne existe depuis `V2` mais aucun adaptateur ne l'a
jamais remplie, et l'écran affichait « tâche — » sur chaque tentative de chaque lead depuis
F5 tout en taisant `assigneeRef`, la seule référence qui ait bougé depuis.

Un identifiant inconnu rend `404` en `ProblemDetail` (RFC 7807).

### Chronologie d'un lead

```
GET /api/leads/{id}/timeline
```

Rend la liste ordonnée de tout ce que le lead a vécu, **dérivée en lecture seule** de cinq
tables : `raw_lead_event` (la capture), `lead` (la qualification et l'attribution),
`crm_sync_attempt` (chaque tentative de synchronisation), `dead_letter` (la mort et son
éventuel rejeu) et `lead_action` (les gestes humains). Rien n'est stocké : il n'existe pas de
table d'événements.

Chaque entrée porte quatre champs — `type` parmi `CAPTURE`, `QUALIFICATION`, `ATTRIBUTION`,
`REATTRIBUTION`, `SYNC_ERP`, `REAFFECTATION_ERP`, `NOTIFICATION`, `MORT`, `REJEU`, `ECART` ;
`at` ; `outcome` parmi `SUCCES`, `ECHEC`, `NEUTRE` ; et un `details` de chaînes. **Aucune
phrase n'est composée côté serveur** : l'API rend des faits typés, le dashboard les met en
français.

**Les commerciaux sont nommes, pas identifies.** `commercial`, `ancienCommercial` et
`nouveauCommercial` portent le nom complet : c'est un fait au meme titre que l'identifiant, et
c'est celui qu'un ecran d'agence sait lire. Un commercial supprime fait exception — `lead_action`
ne porte aucune cle etrangere vers `sales_rep`, pour qu'une suppression n'efface pas l'histoire —
et son identifiant reste alors affiche, faute de mieux.

**Le `commercial` de la ligne `ATTRIBUTION` est celui de l'origine, pas le titulaire actuel.**
`lead.assigned_sales_rep_id` ne retient que le dernier en date : le rendre ici ferait dire à la
chronologie qu'un lead réattribué a toujours appartenu à son commercial actuel, et la ligne
`REATTRIBUTION` juste en dessous la contredirait. L'origine se dérive du journal — c'est le
`previous_sales_rep_id` de la **première** réattribution — et vaut le titulaire courant quand
il n'y en a eu aucune, ce qui est le cas courant.

**Une `NOTIFICATION` `IGNOREE` explique un silence, et n'est pas un échec.** Ses détails
portent `canal`, `statut`, `score`, `seuil`, le `destinataire` quand il est connu, et
`erreur` le cas échéant. Son `outcome` vaut `NEUTRE` pour un envoi comme pour un lead sous le
seuil, `ECHEC` pour un relais qui a refusé. **Le score et le seuil sont ceux figés au moment
de la décision**, jamais relus : régler le barème d'une boutique ne fait donc pas mentir
l'historique de ses notifications.

**`ECART` n'est pas un `REJEU`.** Les deux suivent la même mort, mais l'un abandonne le
message et l'autre le republie : les confondre annoncerait un traitement là où il y a eu
renoncement.

**Depuis F15, `REAFFECTATION_ERP` distingue trois cas sur la même colonne `outcome`.** Une
ligne `SUCCES` sans détail `raison` est une correction de responsable réussie chez l'ERP.
Une ligne `SUCCES` **avec** un détail `raison` est une propagation restée sans effet — un
lead jamais synchronisé, sans commercial, ou un commercial que l'ERP ne connaît pas : rien
ne s'est cassé, il n'y avait simplement rien à corriger, et aucune de ces trois causes ne
part en DLQ. Une ligne `ECHEC` est le seul vrai échec technique, celui d'un ERP injoignable
au moment de l'appel, avec l'erreur dans son détail `erreur`.

**Un rejeu n'apparaît qu'une fois.** Il est lisible à deux endroits — `dead_letter.replayed_at`
et la ligne `lead_action` qui porte son motif — et le service exclut la mort dont une action
porte déjà le `dead_letter_id`. Un rejeu antérieur à `V8` n'a pas de motif : il ressort de
`dead_letter` seul, avec son seul `par`.

**`at` peut être `null`, et l'absence est une information.** Une attribution antérieure à la
migration `V7` n'a pas de date : `lead.routed_at` n'existait pas, et `updated_at` ne la
remplace pas — il vaut la date d'attribution pour un lead resté `ROUTED` mais celle de la
synchronisation pour un lead `SYNCED`. Aucun remplissage rétroactif n'a été fait, parce qu'il
aurait inventé une date. Une entrée non datée garde malgré tout **sa place dans le pipeline**
plutôt que d'être rejetée en tête ou en queue de liste, l'ordre des étapes étant connu même
quand leur date ne l'est pas.

Il n'y a pas de pagination : le volume est borné par construction — une capture, une
qualification, une attribution, au plus trois tentatives avant la DLQ, une mort et un rejeu.

```bash
curl -s "http://localhost:8090/api/leads/6f1c.../timeline" -H "Authorization: Bearer $JETON" | jq
```

```json
[
  {
    "type": "CAPTURE",
    "at": "2026-08-31T09:12:04.118Z",
    "outcome": "NEUTRE",
    "details": { "source": "formulaire-devis", "statut": "PUBLISHED" }
  },
  {
    "type": "QUALIFICATION",
    "at": "2026-08-31T09:12:04.402Z",
    "outcome": "NEUTRE",
    "details": { "score": "80", "intention": "DEVIS", "sourceIntention": "GEMINI" }
  },
  {
    "type": "ATTRIBUTION",
    "at": null,
    "outcome": "NEUTRE",
    "details": { "commercial": "Karim Haddad" }
  },
  {
    "type": "SYNC_ERP",
    "at": "2026-08-31T09:12:06.771Z",
    "outcome": "ECHEC",
    "details": {
      "connecteur": "dolibarr",
      "statut": "FAILED",
      "erreur": "Connection refused"
    }
  },
  {
    "type": "MORT",
    "at": "2026-08-31T09:12:14.900Z",
    "outcome": "ECHEC",
    "details": { "file": "leadflow.leads.routed", "erreur": "Connection refused" }
  },
  {
    "type": "REJEU",
    "at": "2026-08-31T10:03:41.002Z",
    "outcome": "NEUTRE",
    "details": { "par": "admin", "motif": "Broker revenu" }
  },
  {
    "type": "REATTRIBUTION",
    "at": "2026-08-31T11:40:12.310Z",
    "outcome": "NEUTRE",
    "details": {
      "par": "admin",
      "motif": "Depart en conge de Karim",
      "ancienCommercial": "Karim Haddad",
      "nouveauCommercial": "Amina Bensalem"
    }
  },
  {
    "type": "REAFFECTATION_ERP",
    "at": "2026-08-31T11:40:14.552Z",
    "outcome": "SUCCES",
    "details": { "connecteur": "dolibarr", "responsable": "9" }
  }
]
```

Un identifiant inconnu rend `404` en `ProblemDetail`, comme le détail.

### Réattribuer un lead

```
POST /api/leads/{id}/reassign
```

Le seul endpoint de cette section qui **écrive**. Il vit dans `routing/` et non dans
`monitoring/` — le chemin suit la ressource, le package suit la responsabilité : le choix du
commercial appartient au routage depuis F4.

```bash
curl -s -X POST "http://localhost:8090/api/leads/6f1c.../reassign" \
  -H "Authorization: Bearer $JETON" -H 'Content-Type: application/json' \
  -d '{"salesRepId":"8b71-...","reason":"Départ en congé de Karim"}' | jq
```

Le corps porte deux champs, tous deux obligatoires : `salesRepId` et `reason` (500 caractères
au plus). **L'opérateur n'y figure pas** — il est lu dans le jeton. Un acteur transmis par le
client ferait un journal falsifiable.

La réponse est **la fiche complète rechargée**, le même `LeadDetail` que `GET /api/leads/{id}`.
C'est délibéré : l'écran affiche ainsi l'état réellement enregistré, sans un aller-retour de
plus qui pourrait montrer autre chose.

Ce que le geste fait, et surtout ce qu'il ne fait pas :

- il pose le nouveau commercial et écrit une ligne `lead_action` avec son motif ;
- il **ne change ni le statut ni `routed_at`** — un lead `SYNCED` réattribué reste `SYNCED`,
  et la date de sa première attribution reste vraie ;
- il **ne publie aucun message de routage** : le tour de rôle n'est pas idempotent, et
  republier sur `lead.routed` renverrait vers l'ERP un lead déjà synchronisé en entier ;
- **depuis F15, il propage en revanche le seul responsable vers l'ERP**, de façon
  asynchrone : une ligne `lead.reassigned` part après le journal, et une file dédiée
  corrige `assigneeRef` chez Dolibarr ou Odoo sans rien recréer. Sur un lead `SYNCED`, le
  dashboard annonce cette transmission ; sur un lead resté `ROUTED`, il ne promet rien,
  l'ERP n'ayant encore rien à corriger. Un lead jamais synchronisé, sans commercial, ou un
  commercial que l'ERP ne connaît pas n'écrit qu'une trace neutre, sans repartir en DLQ ;
  seul un ERP injoignable au moment de l'appel y part, et le rejeu répare une fois l'ERP
  relevé ;
- il **déplace le tour de rôle** en revanche, puisque celui-ci se lit dans `lead` : le nouveau
  titulaire vient d'être servi et passera en dernier.

Quatre réponses à connaître :

| Code  | Sens                                                                            |
| ----- | ------------------------------------------------------------------------------- |
| `400` | `salesRepId` absent, ou `reason` vide ou au-delà de 500 caractères               |
| `404` | Le lead n'existe pas                                                            |
| `409` | Réattribution impossible : lead sans commercial, commercial déjà en place, commercial désactivé, ou commercial d'une autre boutique |

Le `409` porte la phrase du refus dans le `detail` du `ProblemDetail`.

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
  "tauxDeConversion": 0.825,
  "leadsParIntention": { "DEVIS": 88, "INFORMATION": 41, "AUTRE": 14 },
  "leadsParSourceDIntention": { "GEMINI": 130, "RULES": 13 },
  "leadsParCommercial": { "3a90...": 74, "77bd...": 69 },
  "nomsDeCommercial": { "3a90...": "Sara Bennani", "77bd...": "Youssef Alami" }
}
```

**`tauxDeConversion` est un ratio dans `[0,1]`, pas un pourcentage** : `0.825` se lit 82,5 %.
La multiplication par cent appartient à l'affichage, et un client qui ajoute un signe `%` sans
convertir affichera une valeur cent fois trop petite.

`leadsParCommercial` a des identifiants pour clés ; `nomsDeCommercial` les accompagne, sans
quoi un écran afficherait des UUID. Chaque mesure est une requête d'agrégation : rien n'est
compté en mémoire.

### `GET /api/stats/series`

Les trois séries quotidiennes de l'écran d'analyse, en un appel.

| Paramètre | Type | Défaut | Rôle |
| --- | --- | --- | --- |
| `clientId` | UUID | absent | Restreint à une boutique. Absent : toutes. |
| `jours` | entier | `30` | Fenêtre. **7, 30 ou 90 uniquement** — `400` sinon. |

```bash
curl -s -H "Authorization: Bearer $JETON" \
  'http://localhost:8090/api/stats/series?jours=7' | jq
```

```json
{
  "volume": [{ "jour": "2026-02-26", "captures": 12, "ecartes": 1 }],
  "delais": [{ "jour": "2026-02-26", "medianeSecondes": 4.2, "p95Secondes": 31.7 }],
  "intentions": [{ "jour": "2026-02-26", "gemini": 9, "lexique": 3 }]
}
```

Trois choses à savoir en lisant cette réponse.

**Les trois listes portent exactement les mêmes jours, dans le même ordre**, et il y en a
toujours `jours` — les journées sans donnée sont comblées par le serveur. Une journée absente
laisserait le client tracer une droite par-dessus.

**`medianeSecondes` et `p95Secondes` peuvent être `null`**, et cela veut dire « aucun lead
synchronisé ce jour-là ». Ce n'est pas un délai de zéro seconde : afficher zéro dessinerait une
chute vers le bas, soit l'inverse du sens.

**Le découpage en journées se fait dans le fuseau de l'instance** (`leadflow.analytics.fuseau`,
`Europe/Paris` par défaut), et non en UTC. Le point du jour J de `delais` agrège les leads
**synchronisés** ce jour-là, pas ceux capturés.

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

**Les deux exigent un corps depuis F10** : `{"reason": "…"}`, non vide, 500 caractères au
plus. Un corps vide rend `400`. Le motif n'est pas une formalité — il est écrit dans
`lead_action` et ressort dans la chronologie du lead, et un journal dont la moitié des lignes
n'ont pas de motif ne sert à rien six mois plus tard.

```bash
curl -s -X POST "http://localhost:8090/api/dead-letters/9c02.../replay" \
  -H "Authorization: Bearer $JETON" -H 'Content-Type: application/json' \
  -d '{"reason":"Broker revenu"}' -i
```

Le rejeu republie la charge d'origine sur la file d'où elle venait, marque la ligne `REPLAYED`
et **retient qui l'a demandé** (`replayedBy`, tiré du jeton). `discard` marque `DISCARDED`,
définitivement.

**Un rejeu qui échoue laisse quand même sa trace.** La ligne `lead_action` est écrite en
`REQUIRES_NEW` avant que l'erreur ne remonte : elle survit au rollback, avec `outcome`
`ECHEC` et la cause dans son `detail`. Sans cela, la seule trace d'un rejeu raté
disparaîtrait avec lui.

Quatre réponses à connaître :

| Code  | Sens                                                                       |
| ----- | -------------------------------------------------------------------------- |
| `400` | Le motif est absent, vide, ou dépasse 500 caractères                        |
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

## 10. Administration des boutiques

Ces routes **écrivent**, contrairement à toutes les précédentes. Elles vivent dans le package
`tenant/` et non dans `monitoring/`, qui reste un observateur en lecture seule ; elles sont
regroupées ici parce que c'est la même console qui les consomme, avec le même jeton.

La suppression n'est exposée nulle part. `lead` et `raw_lead_event` référencent `client` sans
cascade : Postgres refuserait d'effacer la première boutique ayant reçu un lead. La
désactivation la remplace.

### Lister et lire

```bash
curl -s http://localhost:8090/api/admin/clients   -H "Authorization: Bearer $JETON"
```

```json
[
  {
    "id": "3f2a...",
    "name": "Boutique du Nord",
    "crmProviderId": "dolibarr",
    "assignmentStrategy": "ROUND_ROBIN",
    "active": true,
    "activeSalesReps": 3
  }
]
```

`GET /api/admin/clients/{id}` rend la fiche : identité, `publicKey`, `webhookPath`,
`crmSettings` **non secrets uniquement**, et la liste des commerciaux. Le secret HMAC n'y
figure à aucun titre, et la clé d'API de l'ERP non plus — les deux sont chiffrés au repos et
ne sont jamais rendus en lecture.

Depuis F14, la fiche porte aussi `transition`, **nul hors transition** :

```json
{
  "id": "3f2a...",
  "name": "Boutique du Nord",
  "publicKey": "pk_live_...",
  "webhookPath": "/api/webhooks/leads/pk_live_...",
  "...": "...",
  "transition": {
    "expireLe": "2026-09-06T10:15:00Z",
    "dernierLeadAncienSecret": "2026-09-05T18:42:03Z"
  }
}
```

`expireLe` est la fin de la fenêtre pendant laquelle l'ancien secret reste accepté.
`dernierLeadAncienSecret` est la date du dernier lead encore signé avec cet ancien secret,
**nulle** tant qu'aucun n'est arrivé — c'est ce qui dit à l'opérateur qu'une révocation est
sans risque. Aucun des deux secrets n'y figure jamais.

### Créer

```bash
curl -s -X POST http://localhost:8090/api/admin/clients   -H "Authorization: Bearer $JETON" -H 'Content-Type: application/json'   -d '{"name":"Boutique du Nord",
       "crmProviderId":"dolibarr",
       "assignmentStrategy":"ROUND_ROBIN",
       "crmSettings":{"baseUrl":"http://localhost:8081/api/index.php","apiKey":"..."},
       "firstSalesRep":{"fullName":"Amine Idrissi","email":"amine@boutique.fr"}}'
```

```json
{
  "id": "3f2a...",
  "publicKey": "pk_live_...",
  "hmacSecret": "le-secret-en-clair",
  "webhookPath": "/api/webhooks/leads/pk_live_..."
}
```

**Le secret n'est rendu qu'ici, et une seule fois.** Il est chiffré en AES-256-GCM au repos ;
aucune autre réponse de l'API ne le contient, et il n'existe aucun endpoint pour le relire.
Perdu, il ne peut qu'être régénéré.

`firstSalesRep` est **obligatoire** à la création : une boutique sans commercial actif
capterait des leads que le routage ne pourrait attribuer à personne, et qui finiraient en
DLQ dès le premier formulaire.

### Mettre à jour, activer, désactiver

```bash
curl -s -X PUT  http://localhost:8090/api/admin/clients/$ID -H "Authorization: Bearer $JETON" ...
curl -s -X POST http://localhost:8090/api/admin/clients/$ID/deactivate -H "Authorization: Bearer $JETON"
curl -s -X POST http://localhost:8090/api/admin/clients/$ID/activate   -H "Authorization: Bearer $JETON"
```

Dans le `PUT`, un réglage **secret laissé vide vaut « inchangé »** : la fusion précède la
validation, sans quoi tout formulaire dont la clé d'API reste vide serait refusé. Un réglage
non secret vide, lui, est bien une valeur vide et sera refusé s'il est requis.

L'activation est une sous-ressource et non un champ du `PUT` : elle coupe ou rétablit la
capture, une conséquence qui mérite un geste distinct de l'enregistrement d'un formulaire.

### Rotations

```bash
curl -s -X POST http://localhost:8090/api/admin/clients/$ID/rotate-secret     -H "Authorization: Bearer $JETON"
curl -s -X POST http://localhost:8090/api/admin/clients/$ID/rotate-public-key -H "Authorization: Bearer $JETON"
```

La première rend :

```json
{
  "hmacSecret": "le-nouveau-secret-en-clair",
  "ancienSecretValideJusquA": "2026-09-06T10:15:00Z"
}
```

`hmacSecret` est, encore une fois, la seule et dernière occasion de le lire. Depuis F14,
l'ancien secret **ne cesse plus immédiatement de signer** : il reste accepté jusqu'à
`ancienSecretValideJusquA`, une fenêtre de transition dont la durée est
`leadflow.webhook.transition-secret` (24h par défaut, globale à l'instance) — le temps que
le site de la boutique redéploie avec le nouveau secret sans perdre de leads entre-temps.
Une seconde rotation pendant la fenêtre remplace l'ancien secret par celui qu'on vient de
retirer : il n'y a jamais plus de deux secrets vivants, et le secret d'origine cesse alors
immédiatement de valoir.

La seconde change l'**URL** du webhook ; l'ancienne n'est plus reconnue, sans fenêtre de
transition — c'est une clé publique, pas un secret de signature.

```bash
curl -s -X POST http://localhost:8090/api/admin/clients/$ID/revoke-previous-secret -H "Authorization: Bearer $JETON"
```

Ferme la fenêtre de transition avant son terme — le geste d'une fuite avérée sur l'ancien
secret. Rend la fiche à jour, `transition` étant alors `null`. Révoquer une boutique qui
n'est pas en transition est un succès sans effet : l'état visé est déjà atteint.

### Commerciaux

```
GET  /api/admin/clients/{id}/sales-reps
POST /api/admin/clients/{id}/sales-reps
PUT  /api/admin/sales-reps/{id}
POST /api/admin/sales-reps/{id}/activate
POST /api/admin/sales-reps/{id}/deactivate
```

Désactiver le **dernier commercial actif** d'une boutique est refusé en `409` : le routage
lèverait `AssignmentException` au premier lead suivant, et trois tentatives plus tard le lead
serait en DLQ. Le refus porte la raison.

### Barème de scoring

```
GET /api/admin/clients/{id}/scoring
PUT /api/admin/clients/{id}/scoring
```

Le barème d'une boutique — les poids, les listes cibles et le seuil de chaleur. Il vit dans
`client.scoring_config`, chiffré au repos. **Le jeu de critères est fermé** : seuls les poids
et les listes se règlent, il n'y a pas de moteur de règles.

`GET` rend les valeurs **effectives**, défauts compris, car c'est le barème que `LeadScorer`
applique réellement — un document vide ne rend donc pas des zéros. Il rend en plus
`scoreMaximum`, borné à `[0, 100]` comme le score lui-même, et `seuilInatteignable`.

```bash
curl -s http://localhost:8090/api/admin/clients/$ID/scoring -H "Authorization: Bearer $JETON" | jq
```

```json
{
  "valeurs": {
    "telephonePresent": 15,
    "societePresente": 10,
    "nomPresent": 5,
    "messagePresent": 10,
    "intention": { "DEVIS": 40, "ACHAT": 40, "INFORMATION": 15, "SUPPORT": 5, "AUTRE": 0 },
    "secteursCibles": ["industrie"],
    "paysCibles": ["MA"],
    "bonusCible": 10,
    "seuilChaud": 70
  },
  "scoreMaximum": 90,
  "seuilInatteignable": false,
  "defauts": { "...": "le barème par défaut, pour le bouton de remise à zéro" }
}
```

`PUT` **remplace le document entier** : pas de fusion partielle, qui rendrait indécidable la
différence entre « poids absent » et « poids remis à zéro ». Le corps est un `ScoringForm`,
c'est-à-dire le contenu de `valeurs` ci-dessus.

| Refus                                                    | Code  |
| -------------------------------------------------------- | ----- |
| Un poids, le bonus ou le seuil hors de `[0, 100]`         | `400` |
| Un code pays qui n'est pas deux lettres                   | `400` |
| Un secteur de plus de 80 caractères                       | `400` |
| Boutique inconnue                                         | `404` |

Les bornes `[0, 100]` ne sont pas arbitraires : `LeadScorer` plafonne le total à 100, donc un
poids au-delà serait sans effet observable.

**Un seuil au-dessus du maximum atteignable est accepté**, et signalé par
`seuilInatteignable: true`. Ce n'est pas une erreur de saisie mais un état légitime, le temps
de monter les poids — le refuser empêcherait de régler le barème dans l'ordre qu'on veut.

Les listes sont **normalisées** à la relecture : les secteurs en minuscules, les pays en
majuscules. La réponse du `PUT` montre donc la forme retenue, qui peut différer de ce qui a
été envoyé.

Le barème s'applique **au prochain lead reçu** : les scores déjà écrits ne sont pas recalculés.
Le badge « chaud », lui, est calculé à la lecture — il se déplace immédiatement.

### Fournisseurs ERP et test de connexion

```bash
curl -s http://localhost:8090/api/admin/crm/providers -H "Authorization: Bearer $JETON"
```

```json
[
  {
    "providerId": "dolibarr",
    "settings": [
      { "cle": "baseUrl", "libelle": "Adresse de l'API, /api/index.php compris", "secret": false },
      { "cle": "apiKey", "libelle": "Cle d'API de l'utilisateur de service", "secret": true }
    ]
  }
]
```

Les fournisseurs **implémentés et activés**, chacun avec les réglages qu'il déclare. Le
formulaire du dashboard se génère à partir de cette réponse : ajouter un ERP ne demande
aucune modification du frontend, ce qui prolonge la promesse des trois gestes jusqu'à
l'écran.

```bash
curl -s -X POST http://localhost:8090/api/admin/crm/test   -H "Authorization: Bearer $JETON" -H 'Content-Type: application/json'   -d '{"crmProviderId":"dolibarr","crmSettings":{"baseUrl":"http://127.0.0.1:9","apiKey":"x"}}'
```

```json
{ "ok": false, "cause": "INJOIGNABLE", "detail": "I/O error on GET request..." }
```

Les réglages sont **éprouvés sans être enregistrés** : tout l'intérêt est de vérifier avant
de sauver. Un test qui échoue rend `200` — c'est un résultat de diagnostic, pas une panne du
serveur ; le traiter en `502` ferait passer l'intercepteur du dashboard pour un incident. Un
fournisseur inconnu, lui, rend bien `400`.

`cause` appartient à `{JOIGNABLE, INJOIGNABLE, IDENTIFIANTS_REFUSES, CIBLE_INCONNUE,
REPONSE_INATTENDUE}`. C'est un nom d'énumération et non une phrase : l'écran phrase en
français, et un libellé construit ici obligerait à redéployer le backend pour le corriger.

La sonde **ne lit pas la base**, comme tout adaptateur : elle éprouve les réglages qu'on lui
passe, jamais ceux qui sont enregistrés. Tester une boutique existante sans retaper sa clé
d'API teste donc une clé vide.

> **Réserve de sécurité.** `POST /api/admin/crm/test` fait émettre au serveur un appel HTTP
> vers une URL fournie par l'opérateur — une requête sortante déclenchée depuis l'extérieur.
> C'est acceptable sur une console interne authentifiée par un compte unique d'agence, qui
> est le modèle actuel du dashboard. Le jour où celui-ci s'ouvrirait à des utilisateurs moins
> fiables — les boutiques elles-mêmes, par exemple — il faudrait restreindre les
> destinations : liste blanche d'hôtes, refus des adresses privées et de `localhost`, et
> plafonnement du nombre d'appels.

### Analyse d'intention : clé d'API et interrupteur

L'écran « Paramètres » du dashboard se sert de trois routes. Le réglage est **global à
l'instance** : l'analyse d'intention est un service que l'agence rend à ses boutiques, pas
un paramètre de tenant.

```bash
curl -s http://localhost:8090/api/admin/intent -H "Authorization: Bearer $JETON"
```

```json
{
  "actif": true,
  "cleDefinie": true,
  "apercu": "rete",
  "source": "BASE",
  "modele": "gemini-3.6-flash"
}
```

`apercu` ne porte que les **quatre derniers caractères** de la clé, et rien de plus ne sort
jamais de cette API : assez pour reconnaître la clé en place, trop peu pour s'en servir. Un
test asserte l'absence de la clé entière dans le corps JSON, et non dans le DTO — c'est le
corps qui part sur le réseau.

`source` vaut `BASE` (clé saisie dans la console), `ENV` (repli sur `GEMINI_API_KEY`) ou
`AUCUNE`. **La base l'emporte sur l'environnement** : une instance déjà déployée continue de
fonctionner sans qu'on y touche, et la console devient le chemin normal.

```bash
curl -s -X PUT http://localhost:8090/api/admin/intent   -H "Authorization: Bearer $JETON" -H 'Content-Type: application/json'   -d '{"apiKey":"AIza...","actif":true}'
```

`apiKey` **absente ou vide conserve la clé enregistrée** : l'opérateur ne voit jamais la clé
en clair, donc l'obliger à la ressaisir pour actionner l'interrupteur reviendrait à lui
demander l'impossible. Le changement prend effet **au lead suivant**, sans redémarrage : la
clé et l'interrupteur sont relus à chaque analyse.

```bash
curl -s -X POST http://localhost:8090/api/admin/intent/test   -H "Authorization: Bearer $JETON" -H 'Content-Type: application/json'   -d '{"apiKey":"AIza..."}'
```

```json
{ "ok": true, "cause": "OK", "detail": "Cle valide", "intention": "DEVIS" }
```

Le diagnostic classe un message d'exemple et **n'enregistre rien** : éprouver une clé n'est
pas la mettre en service. Sans `apiKey`, c'est la clé en service qui est éprouvée. Comme
pour le test de connexion ERP, un échec rend `200` avec sa cause, prise dans
`{OK, CLE_ABSENTE, CLE_REFUSEE, QUOTA_DEPASSE, INJOIGNABLE, ERREUR_SERVEUR,
REPONSE_INATTENDUE}` — l'écran phrase en français ce que l'opérateur doit faire.

La sonde ne réutilise pas `GeminiIntentAnalyzer` : celui-ci avale toute défaillance pour ne
perdre aucun lead, ce qui est l'inverse de ce qu'un diagnostic doit faire. Les deux partagent
`GeminiClient`, donc le même appel et le même prompt.

---

## 11. Erreurs

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
