# F6 — Monitoring et dashboard

Statut : validé en session de conception, prêt pour le plan d'implémentation.
Dépend de : F1 (livrée) pour `client`, `sales_rep` et les index de comptage ; F2 (livrée)
pour `raw_lead_event` et son cycle de vie ; F3 (livrée) pour `lead`, `intent_source` et la
file `leadflow.leads.qualified` ; F4 (livrée) pour l'attribution et `leadflow.leads.routed` ;
F5 (livrée) pour `crm_sync_attempt` et le registre de connecteurs.
Position dans le planning général : sixième feature exécutée, dernière avant F7.

---

## 1. Objectif

Rendre le pipeline observable et pilotable par l'agence.

À la fin de F6, un opérateur se connecte au dashboard, voit les leads de tous les clients
avec leurs statuts et leurs scores, lit pourquoi un lead a échoué, rejoue un message mort
d'un clic, constate qu'un connecteur ERP n'a plus rien synchronisé depuis deux heures, et
voit arriver les nouveaux leads sans recharger la page.

F6 est une **feature de lecture**. Le seul verbe d'écriture métier qu'elle introduit est le
rejeu d'un message mort, et il ne fait que remettre dans la file un message qui en venait.

---

## 2. Contexte et contradictions levées

### 2.1 Le pipeline fonctionne et personne ne le voit

Depuis F4, un lead traverse `QUALIFIED` → `ROUTED` → `SYNCED` sans intervention. Toutes les
traces existent en base — `raw_lead_event`, `lead`, `crm_sync_attempt` — et aucune n'est
lisible autrement qu'en `psql`. Les quatre composants Angular sont des coquilles vides, le
package `monitoring/` ne contient qu'un `package-info.java`, et la DLQ ne se rejoue qu'à la
main depuis la console RabbitMQ.

### 2.2 L'API est aujourd'hui fermée par accident, pas par décision

`SecurityConfig` déclare `anyRequest().authenticated()` avec `httpBasic` et **aucun**
`UserDetailsService`. Spring Boot génère donc au démarrage un utilisateur `user` avec un mot
de passe aléatoire imprimé dans les logs. Tout ce qui n'est ni webhook ni actuator est
inaccessible en pratique, et le sera par un mécanisme que personne n'a choisi. La spec
générale avait explicitement reporté ce choix à F6 ; il se tranche ici.

### 2.3 La cause d'un échec n'est nulle part

Le comportement d'échec actuel — trois tentatives, puis rejet, puis dead-lettering par le
broker — ne conserve **pas l'exception**. L'en-tête `x-death` posé par RabbitMQ contient la
file d'origine, le motif générique `rejected`, la routing key et un compteur ; jamais le
message d'erreur. Un écran qui liste des morts sans dire pourquoi ils sont morts n'oriente
personne : « ce lead a échoué » sans « aucun commercial actif pour ce client » ne permet
aucune action. F6 corrige le chemin d'échec avant d'écrire l'écran qui le lit.

### 2.4 Une file AMQP ne sait pas être une liste

Le critère de recette de F6 dans le plan général demande qu'un lead en échec **apparaisse**
dans l'interface et puisse y être rejoué. Cela suppose de lister, filtrer, paginer, trier et
afficher un motif — c'est-à-dire exactement ce qu'une file de messages est conçue pour ne
pas savoir faire. Le « peek » de l'API de management (`ackmode=ack_requeue_true`) donne
l'illusion de tenir le critère : il rejoue le message en tête de file, se comporte mal à
plusieurs lecteurs, ne pagine pas, et RabbitMQ le déconseille pour du parcours d'interface.

### 2.5 Deux dettes arrivent à échéance

F3 puis F4 ont reporté à F6 les **filets de republication** de `lead.qualified` et
`lead.routed`, avec l'argument — correct — qu'ils ne deviennent bornés qu'une fois qu'un lead
quitte réellement ces états, donc à partir de F4, et qu'ils n'ont de sens qu'accompagnés d'un
écran pour les observer. Elles sont dans le périmètre de F6.

---

## 3. Décisions

### 3.1 Le monitoring est un observateur, et le reste

`monitoring/` lit les tables des autres étapes, écoute une file qui lui est propre, et
**n'écrit jamais dans `lead`, `raw_lead_event` ni `crm_sync_attempt`**. C'est l'équivalent,
pour F6, de « un adaptateur ne lit jamais la base » pour F5 : la règle qu'on ne casse pas.

La seule table que `monitoring/` écrit est la sienne, `dead_letter`, et le seul message
qu'il publie est le rejeu d'un message déjà mort — republié à l'identique, jamais fabriqué.

Conséquence directe : les deux filets de republication de la section 3.11 **ne vivent pas
dans `monitoring/`**. Ils publient sur le pipeline, donc ils appartiennent aux packages qui
possèdent ces publications.

### 3.2 Le dashboard est une console d'agence

Un seul modèle d'utilisateur : des opérateurs de l'agence, qui voient **tous** les clients.
Le tenant n'est jamais implicite — il est un filtre de requête (`?clientId=…`), jamais une
donnée portée par le jeton.

Ce choix suit la nature du produit : les écrans que F6 doit livrer — état des files, DLQ avec
rejeu, santé des connecteurs — sont des écrans d'exploitation. Aucun client final ne veut
voir une DLQ. Il suit aussi ce que le projet fait déjà ailleurs : `GEMINI_API_KEY` est global
à l'instance, pas porté par le client.

Le modèle « un espace par client » n'est pas fermé : il ajouterait un `client_id` sur le
compte et un filtre imposé côté serveur, sans changer un seul contrat REST. Le construire
maintenant doublerait le coût de test de F6 — chaque endpoint devrait prouver son isolation —
pour un besoin qui n'existe pas.

Aucun rôle, donc, et aucune autorisation par endpoint : tous les opérateurs sont équivalents.

### 3.3 JWT, comptes en configuration, validation par Spring Security

`POST /api/auth/login` prend `{username, password}`, vérifie contre les comptes déclarés sous
`leadflow.dashboard.users` — identifiant et **hash BCrypt**, jamais un mot de passe en clair,
même en configuration — et rend `{token, expiresAt}`. Jeton HS256 signé par
`LEADFLOW_JWT_SECRET`, durée **8 heures**, **sans refresh** : à l'expiration, retour à
l'écran de connexion. Un mécanisme de rafraîchissement sans révocation ni stockage
n'ajouterait que de la surface d'attaque.

La validation n'est **pas** un filtre écrit à la main. Spring Security valide nativement un
Bearer JWT via `oauth2ResourceServer`, avec `NimbusJwtDecoder.withSecretKey(...)` pour un
secret partagé et `NimbusJwtEncoder` pour l'émission. Coût : la dépendance du starter
*resource server* — son nom d'artefact exact est à confirmer contre les starters renommés de
Boot 4.1, qui utilise déjà `spring-boot-starter-webmvc` au lieu de `-web`. Gain : aucun code
de parsing de jeton, donc aucun bug d'expiration, d'algorithme `none` ou de signature non
vérifiée.

Aucune table d'utilisateurs. Dans le modèle 3.2, les comptes sont deux ou trois opérateurs
provisionnés au déploiement, et F6 ne livre aucun écran de gestion de comptes : une table
dont le seul écrivain serait un `INSERT` manuel n'apporterait rien de plus qu'une variable
d'environnement. Le jour où le modèle « espace par client » arrive, seul le
`UserDetailsService` change.

`SecurityConfig` devient :

```
/api/webhooks/**                   permitAll   (inchange : authentifie par HMAC)
/api/auth/login                    permitAll
/actuator/health, /actuator/info   permitAll
tout le reste                      authenticated, Bearer JWT
```

`httpBasic` disparaît, et avec lui l'utilisateur généré à mot de passe aléatoire. Le refus
d'authentification rend un `ProblemDetail` et non la page d'erreur par défaut : le frontend
doit pouvoir distinguer un `401` (jeton expiré → écran de connexion) d'un `403`.

### 3.4 Le monitoring déclare ses propres repositories, et ne sérialise aucune entité

`LeadRepository` appartient à `qualification/` et porte les requêtes de la déduplication et
du tour de rôle ; y ajouter `JpaSpecificationExecutor` pour les besoins d'un écran
mélangerait deux responsabilités dans une interface que F3 et F4 lisent déjà. Spring Data
accepte plusieurs repositories pour une même entité : `monitoring/` déclare
`LeadQueryRepository extends Repository<Lead, UUID>, JpaSpecificationExecutor<Lead>` —
`Repository` nu, pas `JpaRepository`, donc aucune méthode d'écriture n'est même exposée.

Aucune entité JPA ne franchit la frontière HTTP. `Lead` porte `email`, `phone` et `message`
bruts ; surtout, `Client` porte `hmacSecret` et `crmConfig` que les `AttributeConverter`
**déchiffrent à la lecture**. Sérialiser l'entité `Client`, ne serait-ce qu'une fois par
inadvertance, publierait le secret HMAC d'un client sur HTTP. Le passage par des `record` de
sortie dans `monitoring/dto/` n'est pas de la cérémonie : c'est la barrière, et un test
l'assertera sur le corps JSON, pas sur le DTO.

`open-in-view` est à `false` : la conversion en DTO se fait dans le service, sous
`@Transactional(readOnly = true)`.

### 3.5 Un contrat de pagination qui nous appartient

Commun à toutes les listes :

```
?page=0&size=25&sort=createdAt,desc          size plafonne a 100
```

La réponse n'est pas un `Page` Spring sérialisé tel quel — sa forme JSON est instable d'une
version à l'autre, et Spring Boot avertit à ce sujet. `monitoring/dto/PageResponse<T>` :

```json
{ "content": [], "page": 0, "size": 25, "totalElements": 137, "totalPages": 6 }
```

Un `record` générique côté serveur, une interface générique côté TypeScript, écrits une fois.

### 3.6 Le journal des morts remplace la DLQ comme source de vérité

Un consommateur prend chaque message de `leadflow.leads.dlq`, écrit une ligne dans la table
`dead_letter` et acquitte. L'écran lit la table : filtres, tri, pagination, motif d'échec en
clair, trace de qui a rejoué et quand.

Cette décision **contredit une affirmation de `CLAUDE.md`** — « la DLQ est la source de
vérité des leads en échec, et l'écran File d'attente du dashboard doit s'appuyer dessus » —
et **casse une promesse tenue par F3 et F4**, celle que le schéma était complet depuis `V3`.
Les deux sont assumées et documentées en section 13. La promesse sur le schéma était une
prédiction, pas un invariant de conception comme « aucun ERP câblé en dur » ; et la section
2.4 explique pourquoi la file ne peut pas rendre le service que le critère de recette exige.

La DLQ RabbitMQ reste dans la topologie : elle est le tuyau par lequel les morts arrivent, et
sa profondeur doit rester nulle puisque le journal la vide.

### 3.7 `RepublishMessageRecoverer` : capturer la cause de l'échec

Un bean `MessageRecoverer` — `RepublishMessageRecoverer` visant `leadflow.leads.dlx` /
`lead.dead` — remplace le rejet par défaut. À l'épuisement des trois tentatives, Spring AMQP
republie lui-même le message vers la DLX en ajoutant `x-exception-message`,
`x-exception-stacktrace`, `x-original-exchange` et `x-original-routingKey`.

Deux gains : la **cause** de l'échec, sans laquelle l'écran ne sert à rien ; et la **routing
key d'origine explicite**, celle dont le rejeu a besoin, au lieu d'être déduite de `x-death`.

C'est une modification de `config/RabbitMQConfig`. Elle s'applique identiquement aux trois
étapes du pipeline et ne change aucune ligne de leur code.

### 3.8 Le consommateur de DLQ ne perd rien, et distingue deux échecs

`monitoring/deadletter/DeadLetterListener`, bean conditionnel
`leadflow.monitoring.deadletter.listener.enabled` — même schéma que les trois autres
listeners, retiré dans la suite de tests.

Il reçoit un **`Message` brut**, jamais un objet converti : la DLQ contient précisément ce
qui a échoué, y compris de l'illisible, et faire tourner Jackson dessus reproduirait l'échec.

Les deux façons de rater un message ne se traitent pas pareil :

- **Charge utile inexploitable** — `x-original-routingKey` absent, JSON tronqué, `clientId`
  illisible. Ce n'est pas une erreur du journal : la ligne est écrite avec ce qu'on a,
  `failure_reason` complété par ce qu'on n'a pas su lire, et le message acquitté.
- **Base indisponible.** Acquitter perdrait le message définitivement, la DLQ n'ayant
  elle-même aucune DLX. Le listener tourne donc sur sa **propre fabrique de conteneurs**,
  avec `defaultRequeueRejected = true` : le message retourne dans la DLQ et sera repris quand
  Postgres reviendra. Le risque de boucle chaude est assumé — si Postgres est à terre,
  l'application entière l'est.

### 3.9 Le rejeu est unitaire, tracé, et prévient de ce qu'il coûte

Le rejeu republie sur `leadflow.leads` la routing key mémorisée, **les octets d'origine**, le
`content-type` et le `__TypeId__` d'origine — sans ce dernier, le convertisseur ne saurait
pas dans quelle classe désérialiser, et il n'est honoré que si le paquet figure dans
`RabbitMQConfig.PAQUETS_DE_CONFIANCE`. La ligne passe `REPLAYED`, avec l'heure et **le nom de
l'opérateur tiré du jeton**. C'est la seule trace de qui a fait quoi dans tout F6, et elle
coûte une colonne.

Un rejeu qui échoue de nouveau produit une **nouvelle ligne** : l'historique se lit, il ne
s'écrase pas.

Les trois rejeux n'ont pas le même risque, et l'interface le dit :

| Message rejoué   | Risque            | Garde-fou                                        |
| ---------------- | ----------------- | ------------------------------------------------ |
| `lead.captured`  | aucun             | index unique `lead.raw_event_id`                 |
| `lead.routed`    | aucun             | `CrmSyncState` reconstruit, étapes faites sautées |
| `lead.qualified` | **réattribution** | aucun — le tour de rôle n'est pas idempotent     |

Rejouer un `lead.qualified` refait passer le lead par l'attribution : il peut changer de
commercial et la rotation se décale. C'est le motif même pour lequel F4 a séparé le routage
de la synchronisation. L'écran affiche un avertissement explicite sur ces lignes-là.

Pas de bouton « tout rejouer ». Un rejeu de masse sur un incident non compris multiplie
l'incident ; l'écran rejoue une ligne, ou une sélection explicite de lignes.

### 3.10 L'état des files se lit en AMQP, pas par l'API de management

`RabbitAdmin.getQueueInfo(nom)` donne profondeur et nombre de consommateurs par un
`queue.declare` passif. Aucun second jeu d'identifiants pour le broker, aucune dépendance au
plugin de management, et rien à résoudre du port 15672 dans les tests — que
`@ServiceConnection` ne câble pas.

Le nombre de consommateurs est la mesure la plus lisible d'un listener tombé : un zéro sur
`leadflow.leads.qualified` explique en une seconde pourquoi plus rien n'avance.

### 3.11 Les filets de republication, et le garde-fou qui les empêche d'inonder

`qualification/QualifiedLeadRelay` et `routing/RoutedLeadRelay`, dans leurs packages
d'origine (3.1), sur le schéma de `PendingEventRelay` : `@Scheduled`, âge minimal,
mono-instance assumé. Chacun balaye les leads restés dans l'état qui précède son étape et
republie. Le balayage est borné parce que, depuis F4, un lead quitte réellement `QUALIFIED`
puis `ROUTED`.

Sans garde-fou, un filet transforme un échec permanent en inondation : un client sans
commercial actif fait lever `AssignmentException`, le lead reste `QUALIFIED`, le message part
en DLQ — et le filet le republie toutes les 30 secondes, en fabriquant une ligne de journal à
chaque tour.

**Le filet ignore donc les leads qui ont déjà une ligne `dead_letter` en `PENDING`.** Un
échec déjà constaté et présenté à un humain n'est plus à rejouer automatiquement ; il
redevient éligible dès que l'opérateur l'a rejoué ou écarté. C'est la même distinction que
F2 a faite entre `FAILED` et `DISCARDED` — un échec en attente d'action humaine n'est pas un
échec transitoire — et elle rend le journal porteur plutôt que décoratif.

Le filet lit `dead_letter` : une lecture depuis `qualification/` et `routing/` vers une table
de `monitoring/`. C'est le seul couplage dans ce sens, et il est assumé (12.5).

### 3.12 Le temps réel : une file d'observation propre, et `lead.synced`

`monitoring/stream/` déclare **sa propre file**, `leadflow.monitoring.events`, liée à
l'exchange `leadflow.leads` sur les mêmes routing keys que le pipeline. Aucun vol de
message : un `DirectExchange` livre à **toutes** les files liées à la clé, et la concurrence
entre consommateurs ne joue qu'au sein d'une même file. Le monitoring observe donc sans
qu'aucune ligne du routage ou de la qualification ne bouge.

Une addition est nécessaire côté pipeline : il n'existe **rien après la synchronisation ERP**
— `CrmSyncListener` écrit `SYNCED` et se tait. Sans `lead.synced`, le flux montrerait un lead
figé en `ROUTED` jusqu'au prochain rechargement, c'est-à-dire raterait la fin de l'histoire
qu'il raconte. On ajoute une publication `lead.synced` dans `crm/`, sur le modèle de
`RoutedLeadPublisher`, **liée à la seule file du monitoring**. Le pipeline publie ; qui
écoute ne le regarde pas. Aucun consommateur métier ne s'y abonne, donc aucun risque de
rejeu.

La file du monitoring n'a **pas de DLX** : un échec d'affichage n'est pas un échec de lead et
n'a rien à faire dans le journal des morts. Son consommateur est un bean conditionnel
(`leadflow.monitoring.stream.listener.enabled`), comme les quatre autres listeners du
projet, et la suite de tests le retire sauf là où elle l'éprouve.

`GET /api/stream/leads` rend un `SseEmitter`. Registre `CopyOnWriteArrayList`, purgé sur
`onCompletion`, `onTimeout` et `onError` ; commentaire de maintien toutes les 20 secondes,
sans quoi un proxy coupe une connexion inactive et l'écran se fige sans le dire ; expiration
de l'émetteur à 30 minutes, le client se reconnecte. Le journal des morts alimente le même
diffuseur **en mémoire**, sans repasser par le broker : c'est le même processus.

Deux types d'événements nommés, à charge utile volontairement maigre — l'identifiant et
l'état suffisent à animer une ligne, le détail se charge au clic, et une connexion longue ne
diffuse pas en continu des e-mails et des messages de prospects :

```
event: lead          { leadId, clientId, status, score, salesRepId, occurredAt }
event: dead-letter   { id, originQueue, clientId, leadId, failureReason, deadAt }
```

Côté client, **pas d'`EventSource`** : il ne sait pas poser d'en-tête `Authorization`. Le
service Angular lit le flux par `fetch` + `ReadableStream` et découpe les trames lui-même.
Une trentaine de lignes, et aucun jeton en clair dans une URL — donc aucun jeton dans les
logs d'accès.

SSE plutôt que WebSocket : le flux est purement descendant, rien ne remonte du navigateur.
SSE plutôt qu'un rafraîchissement périodique : « flux temps réel » figure au contenu de F6
dans le plan général, et le surcoût reste modeste tant qu'on assume le mono-instance (12.1).

### 3.13 Angular Material, sans bibliothèque de graphiques

Une seule dépendance frontend ajoutée : `@angular/material`. Pas de Chart.js, pas de
`ngx-charts` — donc pas de `peerDependency` à faire correspondre à Angular 20.3.

Conséquence acceptée : l'écran de statistiques affiche des compteurs et des barres
(`mat-progress-bar`), pas de camembert ni de courbe. `GET /api/stats` ne rend donc **aucune
série temporelle** : sans graphique à dessiner, elle n'aurait pas de consommateur. La requête
d'agrégation par jour s'ajoutera le jour où un graphique existera.

Les tableaux utilisent `mat-table` + `mat-paginator` + `matSort` en **mode serveur** :
`[length]` alimenté par le total de l'API, `(page)` et `(sortChange)` déclenchant une nouvelle
requête. Il faut le dire explicitement parce que la plupart des exemples branchent un
`MatTableDataSource` sur un tableau complet : appliqué à une page déjà paginée par le serveur,
cela paginerait en mémoire une page et afficherait des totaux faux.

### 3.14 Le détail d'un lead est une route, pas une boîte modale

`/leads/:id` plutôt qu'un `mat-dialog` : une URL partageable vaut mieux qu'une modale, en
exploitation comme en démonstration.

---

## 4. Organisation du code

### Backend

```
monitoring/
├── LeadQueryController.java        GET /api/leads, /api/leads/{id}
├── LeadQueryService.java           filtres -> Specification, projection en DTO
├── LeadQueryRepository.java        Repository nu + JpaSpecificationExecutor
├── StatsController.java            GET /api/stats
├── StatsService.java               agregats SQL
├── ConnectorHealthController.java  GET /api/connectors
├── ClientDirectoryController.java  GET /api/clients, /api/clients/{id}/sales-reps
├── QueueController.java            GET /api/queues
├── dto/                            records de sortie, PageResponse<T>
├── deadletter/
│   ├── DeadLetter.java             entite
│   ├── DeadLetterRepository.java
│   ├── DeadLetterStatus.java       PENDING | REPLAYED | DISCARDED
│   ├── DeadLetterListener.java     consommateur de leadflow.leads.dlq
│   ├── DeadLetterJournal.java      ecriture, en transaction propre
│   ├── DeadLetterReplayService.java republication + marquage
│   └── DeadLetterController.java   GET/POST /api/dead-letters
└── stream/
    ├── LeadStreamController.java   GET /api/stream/leads
    ├── LeadStreamBroadcaster.java  registre d'emetteurs SSE
    └── PipelineEventListener.java  consommateur de leadflow.monitoring.events

common/auth/
├── AuthController.java              POST /api/auth/login
├── DashboardUserDetailsService.java comptes en configuration
└── JwtIssuer.java                   emission HS256
```

### Fichiers touchés hors de `monitoring/`

| Fichier                                | Modification                                                                                                            |
| -------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `config/SecurityConfig.java`           | JWT à la place de `httpBasic`, `/api/auth/login` en `permitAll`                                                           |
| `config/RabbitMQConfig.java`           | `RepublishMessageRecoverer`, file `leadflow.monitoring.events` et ses bindings, routing key `lead.synced`, fabrique de conteneurs dédiée à la DLQ |
| `config/DashboardProperties.java`      | **nouveau** : comptes, secret JWT, durée du jeton                                                                         |
| `config/MonitoringProperties.java`     | **nouveau** : réglages des filets, du flux, des listeners                                                                 |
| `common/ApiExceptionHandler.java`      | `404` sur ressource inconnue, `409` sur rejeu déjà traité, `401` en `ProblemDetail`                                       |
| `crm/SyncedLeadPublisher.java`         | **nouveau** : publication `lead.synced`                                                                                   |
| `qualification/QualifiedLeadRelay.java`| **nouveau** : filet de republication                                                                                      |
| `routing/RoutedLeadRelay.java`         | **nouveau** : filet de republication                                                                                      |
| `pom.xml`                              | starter *resource server* (nom exact à confirmer sous Boot 4.1)                                                           |

### Frontend

```
src/app/
├── core/
│   ├── auth/      AuthService (signal du jeton), authInterceptor, authGuard
│   ├── api/       LeadApi, StatsApi, QueueApi, DeadLetterApi, ConnectorApi, ClientApi
│   ├── models/    interfaces calquees sur les DTO, PageResponse<T>
│   └── stream/    LeadStream : lecture SSE par fetch, expose un signal
├── shared/        badge de statut, formatage de dates
└── features/
    ├── login/      nouveau
    ├── dashboard/  compteurs, repartitions, flux temps reel
    ├── leads/      table serveur + filtres, et lead-detail sur /leads/:id
    ├── queue/      profondeurs + journal des morts + rejeu
    └── connectors/ etat par fournisseur et par client
```

`app.routes.ts` gagne `/login` (libre) et `/leads/:id` ; les quatre autres routes passent
sous `authGuard`. `app.config.ts` gagne `provideAnimationsAsync()` et l'intercepteur.
Le squelette `app.html` devient `mat-toolbar` + `mat-sidenav`, thème Material 3 défini une
fois dans `styles.scss`.

Le jeton va en `localStorage` : il doit survivre au rechargement, ce qu'une session de 8
heures rend nécessaire. Le choix expose le jeton à une XSS ; la contrepartie est qu'il n'y a
ni cookie ni CSRF à gérer sur une API `STATELESS`. L'intercepteur pose le `Bearer`, et sur
`401` il vide le jeton et renvoie à `/login`.

---

## 5. Contrats REST

Toutes les réponses d'erreur sont des `ProblemDetail`, comme F2. Tous les corps sont en
camelCase anglais, comme `CaptureAccepted`.

### `POST /api/auth/login`

```
{ "username": "...", "password": "..." }   ->   { "token": "...", "expiresAt": "..." }
```

`401` en cas d'échec, sans distinguer identifiant inconnu et mot de passe faux.

### `GET /api/leads`

Filtres facultatifs et combinables : `clientId`, `status` (répétable), `intent`,
`intentSource`, `salesRepId`, `minScore`, `from`, `to`, `q` (email ou raison sociale).
Traduits en `Specification` composée, un prédicat par filtre présent.

Chaque ligne : `id`, `createdAt`, `clientId`, `clientName`, `companyName`, `email`,
`detectedIntent`, `intentSource`, `score`, `status`, `assignedSalesRepId`, `salesRepName`,
`countryCode`, `sector`.

`Lead` ne porte **pas d'associations JPA** — `clientId` et `assignedSalesRepId` sont des
`UUID` nus, choix de F1 qui garde les étapes découplées. Aucun `join fetch` n'est donc
possible : la résolution des noms se fait en deux requêtes supplémentaires par page
(identifiants distincts collectés, un `findAllById` sur `client`, un sur `sales_rep`,
assemblage en mémoire). Trois requêtes bornées par page au lieu d'un `N+1`, sans introduire
d'association dans les entités du pipeline pour le confort d'un écran.

### `GET /api/leads/{id}`

Le lead complet, `phone` et `message` compris, plus :

- le commercial attribué : nom, e-mail, secteur, zone, `crmRef` ;
- l'historique `crm_sync_attempt`, du plus récent au plus ancien : statut, `providerId`, les
  quatre références, message d'erreur ;
- l'événement brut : `source`, `receivedAt`, `status`, `failureReason`, et **la charge utile
  JSON telle que reçue**.

La charge utile brute est incluse délibérément : c'est l'écran où l'on répond à « pourquoi ce
lead n'a pas de téléphone » ou « pourquoi cet événement est `DISCARDED` », et sans elle la
réponse demande un accès `psql`. Elle ne contient aucun secret — les secrets sont sur la
ligne `client`, chiffrés, et ne sortent jamais.

`404` si l'identifiant est inconnu.

### `GET /api/stats`

Paramètres facultatifs `clientId`, `from`, `to`. Rend en un appel :

- volumes de leads par statut, les cinq valeurs, zéros compris ;
- volumes d'événements bruts par statut — `RECEIVED`, `PUBLISHED`, `FAILED`, `DISCARDED` :
  la santé de la capture, et la seule mesure des formulaires mal branchés chez un client ;
- taux de conversion : `SYNCED` sur total ;
- répartition des intentions détectées ;
- **part `GEMINI` contre `RULES`** — raison d'être de la colonne `intent_source`, ajoutée en
  F3 pour rendre le mode dégradé mesurable et jamais lue depuis. Si Gemini tombe en panne de
  quota, le pipeline continue en silence ; c'est ici que ça se voit ;
- volume par commercial, pour juger de l'équité du tour de rôle.

Tout en agrégats SQL (`count` + `group by`, projections d'interface), jamais en chargeant les
lignes. Les index `idx_lead_client_status` et `idx_raw_lead_event_client_received` posés en
F1 servent exactement ici.

### `GET /api/clients` et `GET /api/clients/{id}/sales-reps`

Le strict nécessaire pour alimenter les filtres. Client : `id`, `name`, `active`,
`crmProviderId`, `assignmentStrategy`. Commercial : `id`, `fullName`, `email`, `sector`,
`zone`, `active`, `crmRef`. Deux `record` écrits à la main — jamais l'entité `Client` (3.4).

### `GET /api/connectors`

Deux sources fusionnées : les fournisseurs **déclarés** (`CrmConnectorRegistry` pour les
connecteurs présents, `leadflow.crm.providers.*` pour `enabled` et les délais) et leur
**activité réelle**, agrégée depuis `crm_sync_attempt` : succès, échecs, date du dernier
succès, date du dernier échec et son message, par `providerId` puis par client.

**Aucun appel vers l'ERP.** Trois raisons : il faudrait déchiffrer le `crm_config` de chaque
client pour construire l'appel ; un ERP lent bloquerait le chargement de l'écran ; et le port
`CrmConnector` n'a pas d'opération de santé — lui en ajouter une obligerait **chaque futur
adaptateur** à l'implémenter, pour un écran. L'état affiché est dérivé des traces, et
« dernier succès il y a 3 minutes » est plus honnête qu'un voyant vert sur une instance qui
refuse les écritures.

### `GET /api/queues`

Profondeur et nombre de consommateurs des quatre files, plus le nombre de morts `PENDING` en
base. La profondeur de `leadflow.leads.dlq` doit rester nulle ; une valeur qui persiste
signale que le journal ne suit pas.

### Journal des morts

```
GET  /api/dead-letters                 filtres : status, originQueue, clientId, from, to
POST /api/dead-letters/{id}/replay
POST /api/dead-letters/{id}/discard
```

### `GET /api/stream/leads`

`text/event-stream`, événements `lead` et `dead-letter` (3.12).

---

## 6. Configuration

```yaml
leadflow:
  dashboard:
    jwt-secret: ${LEADFLOW_JWT_SECRET:} # aucune valeur de repli, comme la cle maitre
    token-ttl: 8h
    users:
      - username: ${LEADFLOW_ADMIN_USER:}
        password-hash: ${LEADFLOW_ADMIN_PASSWORD_HASH:} # BCrypt
  monitoring:
    deadletter:
      listener:
        enabled: true # retire dans la suite de tests
    stream:
      listener:
        enabled: true # retire dans la suite de tests
      emitter-timeout: 30m
      heartbeat-interval: 20s
    relay:
      qualified-after: 2m
      routed-after: 2m
      interval: 30s
```

Deux `record` `@ConfigurationProperties` dans `config/`, lus par
`@ConfigurationPropertiesScan` déjà actif. Aucun secret en dur, comme `LEADFLOW_MASTER_KEY`.

Sous le profil `dev`, `db/dev/R__demo_data.sql` peut recevoir un client de démonstration
supplémentaire, mais **pas de compte opérateur** : le hash BCrypt reste en variable
d'environnement, les comptes n'étant pas une donnée du schéma (3.3).

---

## 7. Migration `V4__dead_letter.sql`

```sql
CREATE TABLE dead_letter (
    id              UUID        PRIMARY KEY,
    origin_queue    VARCHAR(80) NOT NULL,
    routing_key     VARCHAR(80) NOT NULL,
    payload         TEXT        NOT NULL,
    content_type    VARCHAR(80),
    type_id         VARCHAR(255),
    client_id       UUID REFERENCES client (id),
    lead_id         UUID,
    failure_reason  TEXT,
    dead_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    status          VARCHAR(32) NOT NULL
        CONSTRAINT ck_dead_letter_status CHECK (status IN ('PENDING','REPLAYED','DISCARDED')),
    replayed_at     TIMESTAMPTZ,
    replayed_by     VARCHAR(120)
);

CREATE INDEX idx_dead_letter_status_dead_at ON dead_letter (status, dead_at DESC);
CREATE INDEX idx_dead_letter_lead ON dead_letter (lead_id) WHERE lead_id IS NOT NULL;
```

Quatre décisions dedans :

- `payload` est stocké **en texte brut, tel qu'arrivé**, jamais désérialisé puis
  re-sérialisé : un message illisible doit être journalisé quand même, et un rejeu doit
  renvoyer exactement les octets d'origine.
- `type_id` garde l'en-tête `__TypeId__`, sans lequel le rejeu ne peut pas être désérialisé
  à l'arrivée.
- `lead_id` n'a **pas** de clé étrangère : le message peut être corrompu, et une contrainte
  ferait échouer l'écriture du journal exactement quand on en a le plus besoin. L'index
  partiel qui l'accompagne sert le garde-fou des filets (3.11).
- **Aucune contrainte d'unicité.** Un même message peut produire deux lignes si le
  consommateur meurt entre le commit et l'acquittement : c'est un doublon visible dans un
  journal, écartable d'un clic, et le prix à payer pour ne rien perdre.

---

## 8. Gestion d'erreurs

| Situation                                       | Réponse                                       |
| ----------------------------------------------- | --------------------------------------------- |
| Identifiants de connexion faux                  | `401` `ProblemDetail`, sans distinguer la cause |
| Jeton absent, expiré, mal signé                 | `401` `ProblemDetail`                          |
| Lead ou mort inconnu                            | `404` `ProblemDetail`                          |
| `size` au-delà de 100                           | plafonné en silence, pas d'erreur              |
| Rejeu d'une ligne déjà `REPLAYED` / `DISCARDED` | `409` `ProblemDetail`                          |
| Broker injoignable au rejeu                     | `503` `ProblemDetail`, ligne laissée `PENDING` |
| Charge utile morte illisible                    | ligne écrite quand même, motif renseigné (3.8) |
| Postgres injoignable au journal                 | message remis en file (3.8)                    |

---

## 9. Tests

Étage unitaire, sans Spring ni base : composition des `Specification` selon les filtres
présents, extraction des en-têtes d'un message mort (avec et sans `x-original-routingKey`),
découpage des trames SSE côté Angular.

Étage intégration, `@SpringBootTest` + Testcontainers — `@DataJpaTest` reste exclu, sa
tranche n'incluant pas les `@Component` que sont les converters chiffrés :

- **Authentification** : connexion valide ; mot de passe faux ; jeton expiré ; jeton signé
  avec une autre clé ; endpoint protégé sans jeton → `401` en `ProblemDetail`.
- **Non-fuite de secret** : la réponse JSON de `/api/clients` ne contient ni `hmacSecret` ni
  `crmConfig`. Assertion sur le corps, pas sur le DTO : c'est la sérialisation qu'on
  verrouille.
- **Requêtes** : chaque filtre isolément, deux filtres combinés, pagination, plafond de
  `size`, et résolution des noms de client et de commercial sans `N+1`.
- **Statistiques** : agrégats confrontés à un jeu de lignes inséré par le test, `DISCARDED`
  et part `RULES` compris.
- **Journal** : une charge utile illisible produit quand même une ligne ; le rejeu republie
  les octets d'origine avec le même `__TypeId__` ; la ligne passe `REPLAYED` avec le nom de
  l'opérateur ; un second rejeu rend `409`.
- **Chemin d'échec complet** : listener qui lève, trois tentatives, et une ligne
  `dead_letter` portant **le message de l'exception**. C'est le test qui prouve que le
  `RepublishMessageRecoverer` sert à quelque chose.
- **Filets** : un lead `QUALIFIED` assez vieux est republié ; le même, avec une mort
  `PENDING`, ne l'est pas.
- **Flux** : un message sur `leadflow.monitoring.events` atteint un émetteur abonné, et le
  consommateur du pipeline reçoit toujours le sien — la preuve qu'aucun message n'est volé.

Frontend, Karma/Jasmine, ciblé : le guard redirige sans jeton, l'intercepteur pose l'en-tête
et vide le jeton sur `401`, un service mappe correctement `PageResponse`.

---

## 10. Critères de recette

1. Un opérateur se connecte, obtient un jeton, et toute requête sans jeton est refusée en
   `401`.
2. Un lead en échec **apparaît** dans l'écran « File d'attente » avec **le motif de son
   échec**, et peut être rejoué depuis l'interface.
3. Les compteurs de conversion sont cohérents avec le contenu de la base.
4. Un nouveau lead capté apparaît dans le flux du dashboard **sans rechargement**, et son
   passage `QUALIFIED` → `ROUTED` → `SYNCED` s'y lit.
5. Aucune réponse de l'API ne contient de secret client.
6. L'écran « Connecteurs » distingue un fournisseur désactivé d'un fournisseur actif dont la
   dernière synchronisation a échoué.
7. Un client sans commercial actif produit une mort journalisée, et le filet de republication
   **ne** la republie **pas** en boucle.

---

## 11. Hors périmètre

- **Réattribution manuelle d'un lead.** Ce n'est pas de la lecture : réattribuer veut dire
  réécrire `assigned_sales_rep_id` puis refaire une synchronisation ERP, donc republier sur
  `lead.routed`, ce qui rouvre la question de l'idempotence du connecteur sur un lead déjà
  créé dans l'ERP. Une feature d'écriture déguisée en bouton.
- **Alerte des leads chauds et tâche d'agenda du commercial.** Le `seuilChaud` de F3 reste
  inutilisé ; la tâche d'agenda demande une opération nouvelle sur le port `CrmConnector` et
  une quatrième étape dans `CrmSyncState`. Ce sont des écritures vers l'ERP, pas de
  l'observabilité.
- **Gestion des clients et des commerciaux depuis l'interface.** CRUD multi-tenant, sans
  rapport avec l'observabilité.
- **Série temporelle et graphiques** (3.13).
- **Rôles et espace par client** (3.2).
- **Ping de santé des ERP** (section 5).
- **Élargissement de CORS au-delà de `http://localhost:4200`** : F7.

---

## 12. Risques et dettes assumées

### 12.1 Mono-instance

Le registre SSE est en mémoire et la file d'observation est unique : à deux instances, un
client abonné à l'une ne verrait pas ce que traite l'autre. Même hypothèse que
`PendingEventRelay` et que le tour de rôle de F4. La réponse serait un exchange fanout et une
file exclusive par instance — pas un correctif, une évolution.

### 12.2 Un jeton volé vaut huit heures

Pas de révocation : une liste de jetons révoqués demanderait un état partagé, que `STATELESS`
exclut. Le stockage en `localStorage` l'expose à une XSS. C'est le compromis d'une console
interne ; il serait à revoir avant toute exposition publique.

### 12.3 Doublons possibles dans le journal

Sans contrainte d'unicité (section 7), un redémarrage malheureux du consommateur peut créer
deux lignes pour un même message. Visibles, écartables, préférables à une perte.

### 12.4 Rejouer un `lead.qualified` décale la rotation

Signalé dans l'interface, pas empêché (3.9). L'empêcher demanderait de rendre l'attribution
idempotente, c'est-à-dire de mémoriser l'attribution précédente — une décision de F4 qu'on ne
rouvre pas ici.

### 12.5 Le filet dépend d'une table du monitoring

`qualification/` et `routing/` lisent `dead_letter` (3.11). C'est un couplage du pipeline
vers l'observateur, à contre-sens de la règle 3.1. Il est assumé parce que l'alternative —
un marqueur d'échec définitif sur la ligne `lead` — demanderait une colonne de plus et une
transition d'état que personne n'écrit aujourd'hui.

### 12.6 La promesse « le schéma est complet » est cassée

Elle avait tenu pour F3 et F4. `V4` la rompt. La justification tient en une phrase : une file
de messages ne sait pas être une liste paginée et filtrable (2.4), et le critère de recette
en exige une.

---

## 13. Documentation à corriger

`CLAUDE.md` porte deux affirmations que F6 rend fausses :

- « la DLQ est la source de vérité des leads en échec, et l'écran File d'attente du dashboard
  doit s'appuyer dessus » → ce sera la table `dead_letter` ;
- « le schéma est complet : F3 n'a rien eu à y ajouter, et les features suivantes ne devraient
  pas non plus » → `V4` existe.

La section « État actuel » devra dire que l'observabilité existe, et une section
« Monitoring » décrire les règles 3.1, 3.4 et 3.12. Un `docs/monitoring-api.md` décrira les
endpoints, comme `docs/webhook-integration.md` décrit la capture.

---

## 14. Prochaine étape

Plan d'implémentation, puis F7 — durcissement, intégration continue, déploiement et mémoire
de soutenance.
