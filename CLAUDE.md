# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Ce qu'est le projet

LeadFlow est un **middleware asynchrone** entre les canaux marketing (formulaires web) et
Dolibarr (ERP/CRM). Ce n'est pas un CRUD : c'est un pipeline de traitement de leads en
quatre etapes, et l'architecture des deux projets suit ce decoupage. Comprendre le pipeline
est le prerequis pour naviguer dans le code.

```
Formulaire client
    |  POST signe HMAC-SHA256
    v
[capture]  --persiste raw_lead_event-->  Postgres
    |  publie lead.captured
    v
RabbitMQ  leadflow.leads  --(3 echecs)-->  leadflow.leads.dlq
    |
    v
[qualification]  nettoyage . dedup . NLP intention . scoring
    |
    v
[routing]  choix du commercial  -->  [dolibarr]  Tiers + Contact + Opportunite + agenda
    |
    v
[monitoring]  API REST  -->  dashboard Angular
```

Le point structurant : **le webhook ne fait aucun travail metier**. Il valide la signature,
ecrit l'evenement brut et publie sur le broker. Tout ce qui peut echouer ou etre lent
(Dolibarr, NLP) vit derriere la file. Une modification qui ajoute du traitement synchrone
dans `capture` casse cette garantie de non-perte sous charge.

## Monorepo

| Chemin               | Role                                                |
| -------------------- | --------------------------------------------------- |
| `backend/`           | Spring Boot 4.1 / Java 21 / Maven — projet autonome  |
| `frontend/`          | Angular 20 standalone / npm — projet autonome        |
| `docker-compose.yml` | Postgres + RabbitMQ (+ Dolibarr sous profil)         |

Il n'y a pas d'outil de build racine : chaque projet se construit depuis son propre
repertoire, avec son propre gestionnaire de dependances.

## Commandes

Les commandes backend s'executent depuis `backend/`, les commandes frontend depuis
`frontend/`.

### Infrastructure

```bash
docker compose up -d                      # Postgres (5432) + RabbitMQ (5672, console 15672)
docker compose --profile dolibarr up -d   # ajoute Dolibarr sur :8081 + sa MariaDB
docker compose down -v                    # remet la base a zero (rejoue les migrations Flyway)
```

### Backend

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # :8080, logs SQL + DEBUG
./mvnw test                                             # toute la suite
./mvnw test -Dtest=HmacSignatureVerifierTest            # une classe
./mvnw test -Dtest=HmacSignatureVerifierTest#rejectsExpiredTimestamp   # une methode
./mvnw verify                                           # tests + package
./mvnw spring-boot:test-run                             # lance l'app avec Testcontainers
```

**Le daemon Docker doit tourner pour `./mvnw test`** : `BackendApplicationTests` importe
`TestcontainersConfiguration`, qui demarre Postgres et RabbitMQ en conteneurs. Sans Docker,
l'echec est `Could not find a valid Docker environment` — c'est un probleme d'environnement,
pas de code. `./mvnw package -DskipTests` reste utilisable dans ce cas.

`spring-boot:test-run` demarre `TestBackendApplication` sur la meme configuration
Testcontainers : c'est le moyen le plus rapide de lancer le backend sans avoir a monter
l'infrastructure via `docker compose`. Les images des conteneurs de test sont epinglees sur
les memes versions que `docker-compose.yml` — les garder alignees.

### Frontend

```bash
npm start                                     # ng serve sur :4200, proxy /api -> :8080
npm run build                                 # build production dans dist/
npm test                                      # Karma + Jasmine, mode watch
npm test -- --watch=false --browsers=ChromeHeadless   # une passe, pour CI ou verification
```

Il n'y a **pas de linter configure** : `ng lint` echouera tant qu'ESLint n'est pas ajoute.
Prettier est configure dans `package.json` (100 colonnes, guillemets simples).

## Backend — conventions

Package racine `com.leadflow`. Les sous-packages sont **des etapes du pipeline, pas des
couches techniques** : il n'y a volontairement pas de `controller/`, `service/` ou
`repository/` globaux. Chaque package porte son propre `package-info.java` qui documente sa
responsabilite — le lire avant d'ajouter du code dedans.

- `capture/` — endpoints webhook, verification HMAC, publication sur RabbitMQ.
- `qualification/` — consommateur de la file : validation, dedup, NLP, scoring.
- `routing/` — selection du commercial, notifications.
- `dolibarr/` — client de l'API REST Dolibarr. **Le format Dolibarr ne doit pas fuir hors de
  ce package** ; le reste du code manipule le modele du domaine.
- `monitoring/` — API REST du dashboard.
- `common/` — exceptions, gestion d'erreurs REST, types partages.
- `config/` — beans Spring et proprietes typees.

### Configuration

`application.yml` ne contient aucun secret en dur : tout passe par des variables
d'environnement avec valeur de repli pour le dev (`${DOLIBARR_API_KEY:}`). Les nouveaux
reglages metier vont sous le prefixe `leadflow.*` et se lisent via un `record`
`@ConfigurationProperties` place dans `config/` — `@ConfigurationPropertiesScan` est actif
sur `BackendApplication`, aucun enregistrement manuel n'est necessaire.

### Base de donnees

`ddl-auto: validate` : **Hibernate ne cree jamais de table**. Toute evolution de schema passe
par un nouveau fichier `src/main/resources/db/migration/V<n>__description.sql`. Modifier une
migration deja appliquee fait echouer Flyway au demarrage (checksum) — il faut soit ajouter
une migration, soit `docker compose down -v` en dev.

Seule `V1__raw_lead_event.sql` existe pour l'instant (journal de capture). Le schema metier
— lead qualifie, commercial, trace de synchronisation Dolibarr — reste a ecrire.

### Messaging

La topologie est declaree entierement dans `config/RabbitMQConfig.java` (exchange, queue,
DLX, DLQ, bindings) et creee au demarrage par Spring AMQP. Les constantes de noms de files
vivent dans cette classe — ne pas ecrire ces noms en dur ailleurs.

Le comportement d'echec est delibere : `default-requeue-rejected: false` plus 3 tentatives
avec backoff exponentiel, puis passage en DLQ. Un message ne reboucle donc jamais
indefiniment ; la DLQ est la source de verite des leads en echec, et l'ecran « File
d'attente » du dashboard doit s'appuyer dessus.

### Securite

`SecurityConfig` est **stateless** et laisse `/api/webhooks/**` en `permitAll` : ces requetes
viennent de serveurs tiers qui ne peuvent pas s'authentifier classiquement. Leur
authentification, c'est la signature HMAC, verifiee dans la couche `capture` et non dans la
chaine de filtres Spring Security. Ne pas « securiser » cette route avec un mecanisme Spring
sans retirer la verification HMAC, et inversement.

CORS n'autorise que `http://localhost:4200` : a elargir avant tout deploiement.

## Frontend — conventions

Angular 20 en mode **standalone** (aucun `NgModule`), avec signals et la nouvelle syntaxe de
template (`@if`, `@for`). Les fichiers suivent la convention de nommage Angular 20 sans
suffixe de type : `dashboard.ts` exporte `Dashboard`, et non `dashboard.component.ts`.

```
src/app/
├── core/          services singleton, modeles, intercepteurs HTTP
├── shared/        composants et pipes reutilisables
└── features/      un dossier par ecran, charge en lazy depuis app.routes.ts
```

Chaque route de `app.routes.ts` utilise `loadComponent` : les features sont des chunks
separes, verifiable dans la sortie de `npm run build`. Ajouter une feature = un dossier sous
`features/` plus une entree `loadComponent`.

### Appels API

`environment.apiBaseUrl` est **volontairement vide dans les deux environnements** : en dev
`proxy.conf.json` renvoie `/api` et `/actuator` vers `localhost:8080`, en production le
dashboard est servi derriere le meme domaine que l'API. Les services doivent donc appeler des
chemins relatifs (`/api/leads`), jamais une URL absolue. Le remplacement de fichier
d'environnement est cable dans `angular.json`, configuration `development`.

## Etat actuel

Le squelette compile de bout en bout, mais **la logique metier n'est pas implementee** : les
packages `capture`, `qualification`, `routing`, `dolibarr` et `monitoring` ne contiennent que
leur `package-info.java`, et les trois composants de `features/` sont des placeholders. Ne pas
supposer l'existence d'entites, de services ou d'endpoints — verifier avant de referencer.
