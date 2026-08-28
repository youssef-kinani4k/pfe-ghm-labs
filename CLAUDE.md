# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Ce qu'est le projet

LeadFlow est un **middleware asynchrone** entre les canaux marketing (formulaires web) et
les ERP/CRM du commerce (Dolibarr, Odoo, et d'autres a venir). Ce n'est pas un CRUD : c'est
un pipeline de traitement de leads en quatre etapes, et l'architecture des deux projets suit
ce decoupage. Comprendre le pipeline est le prerequis pour naviguer dans le code.

Deuxieme invariant, aussi structurant que le premier : **aucun ERP n'est cable en dur**. Le
pipeline parle a un port `CrmConnector` et a un modele pivot ; chaque ERP vit dans son
propre adaptateur.

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
[routing]  choix du commercial
    |
    v
[crm]  CrmConnector (port)  -->  crm.dolibarr  |  crm.odoo  |  ...
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
| `docker-compose.yml` | Postgres + RabbitMQ (+ Dolibarr / Odoo sous profils) |

Il n'y a pas d'outil de build racine : chaque projet se construit depuis son propre
repertoire, avec son propre gestionnaire de dependances.

## Commandes

Les commandes backend s'executent depuis `backend/`, les commandes frontend depuis
`frontend/`.

### Infrastructure

```bash
docker compose up -d                      # Postgres (5432) + RabbitMQ (5672, console 15672)
docker compose --profile dolibarr up -d   # ajoute Dolibarr sur :8081 + sa MariaDB
docker compose --profile odoo up -d       # ajoute Odoo sur :8069 + sa Postgres dediee
docker compose down -v                    # remet la base a zero (rejoue les migrations Flyway)
```

### Backend

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # :8090, logs SQL + DEBUG
./mvnw test                                             # toute la suite
./mvnw test -Dtest=HmacSignatureVerifierTest            # une classe
./mvnw test -Dtest=HmacSignatureVerifierTest#rejectsExpiredTimestamp   # une methode
./mvnw verify                                           # tests + package
./mvnw spring-boot:test-run                             # lance l'app avec Testcontainers
```

**Le backend ecoute sur `:8090`, pas sur `:8080`.** Le port par defaut de Tomcat est occupe
en permanence sur le poste de developpement du projet ; le choisir explicitement evite un
demarrage qui echoue une fois sur deux. Il se surcharge par `SERVER_PORT`, et
`frontend/proxy.conf.json` vise ce meme port — les deux se changent ensemble.

**Le daemon Docker doit tourner pour `./mvnw test`** : `BackendApplicationTests` importe
`TestcontainersConfiguration`, qui demarre Postgres et RabbitMQ en conteneurs. Sans Docker,
l'echec est `Could not find a valid Docker environment` — c'est un probleme d'environnement,
pas de code. `./mvnw package -DskipTests` reste utilisable dans ce cas.

`spring-boot:test-run` demarre `TestBackendApplication` sur la meme configuration
Testcontainers : c'est le moyen le plus rapide de lancer le backend sans avoir a monter
l'infrastructure via `docker compose`. Les images des conteneurs de test sont epinglees sur
les memes versions que `docker-compose.yml` — les garder alignees.

Les tests des adaptateurs ERP ont deux etages. L'etage contractuel tourne a chaque
`./mvnw test` contre `MockRestServiceServer` — il asserte les corps envoyes, pas seulement
les codes retour. L'etage d'integration, marque `@Tag("erp")`, est exclu par defaut et
demande de vrais conteneurs :

```bash
docker compose --profile dolibarr --profile odoo up -d
# puis docs/erp-integration-setup.md pour la cle d'API et la base Odoo
./mvnw verify -Perp-it
```

### Capture — le contrat d'entree

Le webhook est `POST /api/webhooks/leads/{clePublique}`, authentifie par l'en-tete
`X-Leadflow-Signature: t=<epoch>,v1=<hex>` ou l'hexadecimal est
`HMAC-SHA256(client.hmac_secret, t + "." + corps brut)`. Le contrat complet, avec exemples
PHP, JS et curl, vit dans `docs/webhook-integration.md`.

**Le corps n'est jamais deserialise avant authentification.** Le controleur le recoit en
`String` : il faut les octets exacts pour recalculer le HMAC, et faire tourner Jackson sur
une entree non authentifiee reviendrait a traiter une donnee dont on n'a pas verifie
l'origine. Un test le verrouille — corps JSON invalide plus signature invalide doit rendre
`401`, jamais `400`.

**Les cinq causes de refus rendent la meme reponse `401`** : cle publique inconnue, client
desactive, en-tete absent, signature fausse, horodatage hors fenetre. Distinguer les codes
donnerait un oracle sur les cles publiques existantes. Le detail n'existe que dans les logs.

**L'idempotence est tranchee par la base**, par l'index unique `(client_id, signature)` de
`V3` : l'insertion est tentee dans une transaction a part, et la violation de contrainte
est rattrapee pour rendre le `eventId` deja attribue. Il n'y a volontairement aucun
« existe-t-il deja ? » prealable, que deux requetes concurrentes passeraient toutes les deux.

La cle stockee est la **forme canonique** de la signature rendue par `HmacSignatureVerifier`,
jamais le texte recu : l'analyse de l'en-tete tolere les espaces et les parametres inconnus,
donc plusieurs textes valides decrivent la meme soumission et doivent partager une seule cle.

**La publication est at-least-once.** Elle part apres le commit
(`@TransactionalEventListener(AFTER_COMMIT)`), et `PendingEventRelay` reprend
periodiquement ce qui est reste non publie. Si l'envoi reussit mais que le passage a
`PUBLISHED` echoue, le message est renvoye — le consommateur de F3 absorbe ce cas par la
contrainte unique `lead.raw_event_id`. Le filet est mono-instance ; deux instances
demanderaient un `SELECT ... FOR UPDATE SKIP LOCKED`.

**Le convertisseur de messages ne fait confiance qu'a une liste blanche de paquets**
(`RabbitMQConfig.PAQUETS_DE_CONFIANCE`), la correspondance etant exacte : ni prefixe, ni
joker. Une feature qui ajoute un contrat de file dans un autre paquet doit l'y declarer,
sans quoi le consommateur refusera de deserialiser le message.

### Qualification — ce qui sort de la file

Le consommateur est `qualification/LeadQualificationListener`, et il ne fait que traduire le
protocole : tout le metier vit dans `LeadQualificationService`, testable sans broker. Le
bean est conditionnel (`leadflow.qualification.listener.enabled`) et la suite de tests le
retire — un consommateur actif volerait aux tests de capture le message qu'ils viennent de
publier. Ne pas le remplacer par `spring.rabbitmq.listener.simple.auto-startup=false` : le
cache de contextes de test met un contexte en pause puis le redemarre, et `start()` reveille
les beans `Lifecycle` en ignorant `auto-startup`.

**Le service n'est pas transactionnel, et c'est delibere.** L'appel a Gemini peut durer
plusieurs secondes ; a l'interieur d'une transaction JPA il tiendrait une connexion Postgres
ouverte pendant tout ce temps, et le pool s'epuiserait avant le broker. L'ecriture a sa
propre transaction, portee par `LeadWriter` en `REQUIRES_NEW`.

**L'ordre des etapes est porteur de sens.** Normalisation, puis deduplication, puis analyse
d'intention : la deduplication compare des emails normalises, et un doublon ne doit pas
couter un appel au modele.

**Seul l'email peut faire echouer la qualification.** Les autres champs illisibles passent a
`null`. Un evenement sans email exploitable n'ecrit aucun lead et marque `raw_lead_event` en
`DISCARDED` : une erreur deterministe ne part jamais en DLQ. Ce statut est **terminal**, et
c'est pour cela qu'il ne reutilise pas le `FAILED` de la capture, que `PendingEventRelay`
rebalaye — un echec deterministe range sous `FAILED` serait republie a chaque tour de filet.

**L'analyse d'intention ne peut pas echouer.** `GeminiIntentAnalyzer` est `@Primary` sous
`leadflow.intent.gemini.enabled` et decore `RuleBasedIntentAnalyzer` ; toute defaillance —
delai depasse, quota, reponse hors vocabulaire — retombe sur le lexique avec
`IntentSource.RULES`. La cle d'API est **globale a l'instance**, pas portee par le client.
La reponse du modele n'est acceptee que si elle appartient a
l'enumeration `LeadIntent` : c'est la parade a une injection de prompt glissee dans le
message du prospect.

**La cle d'API se regle depuis l'ecran « Parametres », plus seulement au demarrage.** Elle
vit chiffree dans la ligne unique de `intent_setting` (`V5`), et **la base l'emporte sur
`GEMINI_API_KEY`**, qui reste un repli pour les instances deja deployees. L'analyseur ne
connait ni la base ni les proprietes : il interroge le port `ReglageIntent` **a chaque
analyse**, ce qui fait qu'un changement dans la console prend effet au lead suivant, sans
redemarrage. `SondeIntent` eprouve une cle sans l'enregistrer et nomme la cause de l'echec,
la ou l'analyseur avale tout ; les deux partagent `GeminiClient`, seul endroit qui connaisse
le prompt et la forme de la reponse. La cle ne ressort jamais de l'API : `EtatIntent` n'en
porte que les quatre derniers caracteres.

**`client.scoring_config` a desormais une forme**, fixee par `ScoringConfig` : bareme additif
a criteres fixes, seuls les poids et les listes cibles sont configurables. La lecture est
tolerante — un document malforme donne les defauts, jamais une erreur.

**La sortie est `leadflow.leads.qualified`.** La publication est un appel direct apres le
retour de `LeadWriter.insere`, donc apres le commit — et non un
`@TransactionalEventListener` comme en F2, qui serait silencieusement ignore hors
transaction. Un doublon `REJECTED` n'est pas publie. **Il n'y a pas de filet de
republication** : voir le Javadoc de `QualifiedLeadPublisher`. F4 n'a pas repris cette
dette et a produit la meme sur `lead.routed` ; les deux filets sont reportes a F6.

### Routage — le dernier maillon

Deux etapes, deux files : `routing` consomme `leadflow.leads.qualified`, attribue et publie
sur `leadflow.leads.routed` ; `crm/CrmSyncListener` consomme cette file et appelle
`CrmSyncService`. Les deux consommateurs sont des beans conditionnels
(`leadflow.routing.listener.enabled`, `leadflow.crm.listener.enabled`), retires dans la
suite de tests.

**Le decoupage en deux etapes n'est pas cosmetique.** Le tour de role n'est pas idempotent :
rejouer une attribution decale la rotation. Un ERP injoignable — le cas le plus frequent —
ne doit donc jamais renvoyer l'attribution au consommateur. Avec deux files, la DLQ ne
contient que ce qui a reellement echoue.

**Aucun `switch` sur la strategie.** `AssignmentStrategyRegistry` collecte les
implementations par injection de `List<AssignmentStrategy>` et refuse de demarrer si une
valeur de `AssignmentStrategyType` n'a pas de titulaire, ou si deux la revendiquent.

**Le tour de role se lit dans la table `lead`**, pas dans un compteur : le commercial dont
`max(created_at)` est le plus ancien prend le lead suivant, et celui qui n'a jamais rien recu
passe devant. Aucun etat a maintenir, donc rien qui puisse diverger de la realite apres un
redemarrage ou une desactivation.

**Les strategies geographique et sectorielle filtrent puis retombent sur le tour de role**
quand leur critere ne trouve personne, et le repli est logue. Un prospect qui attend coute
plus cher qu'une attribution imparfaite ; le log existe pour que la configuration incomplete
du client se voie.

**Un client sans aucun commercial actif fait lever `AssignmentException`** — trois tentatives
puis DLQ. C'est le seul echec du routage qui merite la DLQ, parce qu'un humain peut le
reparer : activer un commercial, puis rejouer.

### Monitoring — l'observateur

Le contrat complet de l'API, avec exemples `curl` et reponses, vit dans
`docs/monitoring-api.md`.

**Le monitoring est un observateur.** Il lit les tables des autres etapes par ses propres
repositories en lecture seule, n'ecrit que `dead_letter`, et ne publie qu'un rejeu de message
mort. `LeadQueryRepository` etend `Repository` nu et non `JpaRepository` : aucune methode
d'ecriture n'est meme exposee. Une modification qui ferait ecrire `monitoring/` dans `lead`,
`raw_lead_event` ou `crm_sync_attempt` casse cette separation.

**Aucune entite JPA ne franchit la frontiere HTTP.** `Client` porte `hmacSecret` et
`crmConfig` **dechiffres a la lecture** par les `AttributeConverter` : serialiser l'entite
publierait le secret en clair. Toute reponse passe par un `record` de `monitoring/dto/`, et
un test l'asserte sur le corps JSON — pas sur le DTO, qui ne prouverait rien.

**La file d'observation ne vole aucun message.** `leadflow.monitoring.events` est liee aux
memes routing keys que les files metier ; un `DirectExchange` livre a **toutes** les files
liees a une cle, donc le pipeline continue de recevoir ce qu'il recevait. Elle n'a pas de DLX :
un echec d'affichage n'est pas un echec de lead.

**Les filets de republication vivent dans `qualification/` et `routing/`**, pas dans
`monitoring/` : ils publient sur le pipeline, ils ne l'observent pas. `QualifiedLeadRelay` et
`RoutedLeadRelay` ignorent les leads portant une mort `PENDING`, sans quoi un echec permanent
deviendrait une inondation — le filet republierait a chaque tour ce que la DLQ vient de tuer.

**Le dashboard est une console d'agence.** Un seul modele d'utilisateur, aucun role, et le
tenant est un **filtre de requete** (`?clientId=`), jamais une donnee portee par le jeton.
Ouvrir le dashboard aux clients finaux demanderait d'abord de porter le tenant dans le jeton
et de filtrer cote serveur — ce n'est pas une extension de l'existant.

**Le flux temps reel ne passe pas par `EventSource`** : il ne sait pas poser d'en-tete
`Authorization`, et mettre le jeton en parametre d'URL le ferait apparaitre dans tous les
journaux d'acces. Le frontend lit `/api/stream/leads` par `fetch` + `ReadableStream`.

**Le CRUD des boutiques vit dans `tenant/`, pas ici.** Depuis F7, l'API d'administration
(`/api/admin/clients`, `/api/admin/sales-reps`, `/api/admin/crm`) ecrit : elle cree des
boutiques, tourne des secrets, active des commerciaux. La placer dans `monitoring/` aurait
casse la seule propriete qui rend cet observateur sur : il lit et n'ecrit que `dead_letter`.
Les deux packages parlent des memes tables et se consomment depuis le meme ecran, mais l'un
observe et l'autre gouverne. La suppression n'est exposee nulle part — `lead` et
`raw_lead_event` referencent `client` sans cascade, donc Postgres la refuserait des la
premiere boutique ayant recu un lead ; la desactivation la remplace.

**Ajouter un endpoint de monitoring** = un `record` dans `monitoring/dto/`, une methode de
service `@Transactional(readOnly = true)`, un controleur. Jamais d'entite en sortie.

### Frontend

```bash
npm start                                     # ng serve sur :4200, proxy /api -> :8090
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
- `crm/` — port de sortie vers les ERP/CRM (voir la section dediee plus bas).
- `monitoring/` — API REST du dashboard.
- `tenant/` — donnees de reference multi-tenant : client et commerciaux. Seul package qui
  ne soit pas une etape du pipeline ; il porte ce qui parametre toutes les etapes.
- `common/` — exceptions, gestion d'erreurs REST, chiffrement des secrets, types partages.
- `config/` — beans Spring et proprietes typees.

### Configuration

`application.yml` ne contient aucun secret en dur : tout passe par des variables
d'environnement (`${LEADFLOW_MASTER_KEY:}`). Les nouveaux reglages metier vont sous le
prefixe `leadflow.*` et se lisent via un `record`
`@ConfigurationProperties` place dans `config/` — `@ConfigurationPropertiesScan` est actif
sur `BackendApplication`, aucun enregistrement manuel n'est necessaire.

Cinq variables gouvernent l'instance :

| Variable                       | Role                                                    |
| ------------------------------ | ------------------------------------------------------- |
| `LEADFLOW_MASTER_KEY`          | Cle AES-256 (base64, 32 octets) des secrets au repos     |
| `LEADFLOW_JWT_SECRET`          | Cle de signature HS256 des jetons du dashboard           |
| `LEADFLOW_ADMIN_USER`          | Identifiant de l'operateur (defaut `admin`)              |
| `LEADFLOW_ADMIN_PASSWORD_HASH` | **Hash BCrypt** du mot de passe, jamais le mot de passe  |
| `GEMINI_API_KEY`               | Repli de la cle d'analyse d'intention ; l'ecran prime    |

**`LEADFLOW_JWT_SECRET` n'a aucune valeur de repli en production, comme `LEADFLOW_MASTER_KEY`** :
l'application refuse de demarrer plutot que de signer avec un secret devinable. Le controle
ne porte que sur l'absence — un secret de moins de 32 octets demarre mais fait echouer la
premiere connexion, HS256 exigeant 256 bits.

**Le profil `dev` porte un repli** pour ces trois-la, secret de signature et compte
operateur compris (`admin` / `leadflow-demo-2026`), afin que `spring-boot:run` demarre sans
preparer d'environnement. Les variables restent prioritaires : lancer le profil `dev` contre
un serveur partage ne signe donc pas avec une cle publiee dans le depot. `GEMINI_API_KEY` est
la seule des cinq qui puisse manquer sans consequence en production : l'analyse d'intention
retombe alors sur le lexique.

La production d'un hash BCrypt est documentee dans `docs/monitoring-api.md` — c'est le
premier obstacle concret au deploiement.

### Base de donnees

`ddl-auto: validate` : **Hibernate ne cree jamais de table**. Toute evolution de schema passe
par un nouveau fichier `src/main/resources/db/migration/V<n>__description.sql`. Modifier une
migration deja appliquee fait echouer Flyway au demarrage (checksum) — il faut soit ajouter
une migration, soit `docker compose down -v` en dev.

Cinq migrations existent : `V1__raw_lead_event.sql` (journal de capture),
`V2__multi_tenant_schema.sql` (schema metier complet — `client`, `sales_rep`, `lead`,
`crm_sync_attempt`, et l'ajout de `client_id` sur `raw_lead_event`),
`V3__raw_lead_event_idempotence.sql` (index unique `(client_id, signature)`),
`V4__dead_letter.sql` (journal des messages morts) et `V5__intent_setting.sql` (cle d'API de
l'analyse d'intention, ligne unique, chiffree au repos).

`V4` est la seule table que F6 ait ajoutee, et la raison tient en une phrase : **une file de
messages ne sait pas etre une liste paginee et filtrable**, ni retenir qui a rejoue quoi. Le
schema metier, lui, n'a pas bouge depuis `V2` — F3, F4 et F5 n'ont rien eu a y ajouter.

Sous le profil `dev`, `spring.flyway.locations` inclut en plus `classpath:db/dev`, qui
contient `R__demo_data.sql` — un client de demonstration et ses commerciaux. La production
ne charge jamais ce dossier.

### Multi-tenant et secrets

Chaque client (table `client`) porte son secret HMAC, l'identifiant de son connecteur ERP
et les parametres de connexion a **son** instance ERP. Rien de tout cela n'est dans
`application.yml` : ajouter un client est une insertion en base, pas un redeploiement.

Le webhook identifie le client par une cle publique placee dans l'URL
(`/api/webhooks/leads/{clientKey}`, colonne `client.public_key`). Cette cle n'est pas
secrete — c'est la signature qui authentifie — et elle est distincte de la cle primaire
pour pouvoir etre revoquee sans recreer la ligne.

`client.hmac_secret` et `client.crm_config` sont **chiffres au repos** en AES-256-GCM par
`common/SecretCipher` et deux `AttributeConverter` (`EncryptedStringConverter`,
`EncryptedJsonConverter`). La cle maitre vient de `LEADFLOW_MASTER_KEY` et n'est jamais en
base ; sa perte rend les secrets irrecuperables. Les converters sont des `@Component` :
ils recoivent `SecretCipher` par injection grace au `SpringBeanContainer` que Spring Boot
installe dans Hibernate. En consequence, **les tests de persistance utilisent
`@SpringBootTest` et non `@DataJpaTest`**, dont la tranche n'inclut pas les `@Component`.

`client.crm_config` est un document JSON chiffre plutot que des colonnes plates : ajouter
un ERP reclamant un reglage inedit ne doit demander ni migration ni modification d'entite.
Le prix assume est qu'il n'est pas requetable en SQL.

### Messaging

La topologie est declaree entierement dans `config/RabbitMQConfig.java` (exchange, queue,
DLX, DLQ, bindings) et creee au demarrage par Spring AMQP. Les constantes de noms de files
vivent dans cette classe — ne pas ecrire ces noms en dur ailleurs.

Le comportement d'echec est delibere : `default-requeue-rejected: false` plus 3 tentatives
avec backoff exponentiel, puis passage en DLQ. Un message ne reboucle donc jamais
indefiniment.

**La DLQ est le tuyau, pas le registre.** Depuis F6, la source de verite des leads en echec
est la table `dead_letter` : une file de messages ne sait ni paginer, ni filtrer, ni retenir
ce qu'on a deja traite. Le consommateur de la DLQ ecrit une ligne par mort puis acquitte,
donc **la profondeur de `leadflow.leads.dlq` doit rester nulle** — une profondeur qui monte
veut dire que ce consommateur ne tourne pas.

Le motif de l'echec vient du `RepublishMessageRecoverer` : il republie le message vers la DLX
en ajoutant dans ses en-tetes la cause et la trace de l'exception d'origine. Sans lui, un
message mort arriverait sans rien dire de ce qui l'a tue, et le journal n'aurait qu'une date
a montrer.

### Connecteurs ERP/CRM — la regle a ne pas casser

```
crm/
├── CrmConnector.java          port : providerId(), sync(...), resolveAssignee, reglagesAttendus, verifieAcces
├── CrmConnectorRegistry.java  resout l'adaptateur par providerId, applique `enabled`
├── CrmSyncService.java        orchestration : cible, etat anterieur, trace
├── CrmSyncTraceWriter.java    ecriture de la trace en transaction propre
├── CrmHttpConfig.java         builderPour(providerId) : un RestClient.Builder par ERP, avec ses delais
├── CrmSyncAttempt.java        trace append-only des synchronisations
├── model/                     modele pivot : CrmLead, CrmTarget, CrmSyncState, CrmAssignee, ...
├── dolibarr/                  adaptateur REST : DolibarrConnector + DolibarrClient
└── odoo/                      adaptateur JSON-RPC : OdooConnector + OdooClient
```

**Le modele pivot de `crm/model` ne doit contenir aucun terme propre a un fournisseur** —
ni « thirdparty » (Dolibarr), ni « res.partner » (Odoo). Toute la traduction se fait dans
l'adaptateur. C'est ce qui rend le pipeline independant de l'ERP, et c'est l'invariant le
plus facile a casser par inadvertance : des qu'un champ specifique remonte dans `CrmLead`,
la generalisation est perdue.

Les ERP ne se correspondent pas un pour un, et c'est normal : Dolibarr separe Tiers et
Contact en deux endpoints, alors qu'Odoo met les deux dans `res.partner` distingues par
`is_company`. Ces divergences se resolvent dans l'adaptateur, jamais en amont.

**Ajouter un ERP** se fait en trois gestes, sans toucher au reste du code :

1. un sous-package `crm/<provider>/` ;
2. une classe `@Component` implementant `CrmConnector`, dont `providerId()` renvoie la cle
   utilisee dans la configuration ;
3. une entree sous `leadflow.crm.providers.<provider>` dans `application.yml`.

`CrmConnectorRegistry` collecte les connecteurs par injection de `List<CrmConnector>` : il
n'y a aucune liste de fournisseurs a maintenir a la main, et aucun `switch` sur le nom de
l'ERP a ajouter quelque part.

**Le port porte deux methodes que F7 a ajoutees, et aucune n'a d'implementation par defaut.**
`reglagesAttendus()` declare les cles que l'adaptateur attend dans `crm_config`, avec leur
libelle et leur caractere secret : le formulaire d'administration se genere a partir de la,
donc ajouter un ERP ne touche ni `tenant/` ni le frontend. `verifieAcces(CrmTarget)` eprouve
une cible sans rien creer et rend un `CrmCheck`, dont la cause appartient a une enumeration
sans terme propre a un fournisseur. Un `default` rendant « non verifiable » aurait laisse un
futur adaptateur degrader silencieusement la promesse faite a l'ecran de creation — d'ou
l'obligation. La sonde ne lit pas la base, comme le reste de l'adaptateur : elle n'eprouve
que les reglages qu'on lui passe.

`sync` prend un `CrmTarget(providerId, settings)` decrivant **l'instance** ERP visee, car
deux clients sur le meme type d'ERP ont chacun leur serveur. Les cles de `settings`
viennent de `client.crm_config` et sont interpretees par l'adaptateur seul.

**Un adaptateur ne lit jamais la base.** Il recoit `CrmSyncState` — les references deja
obtenues lors des tentatives precedentes — et saute toute etape dont la reference est
connue. C'est la, et nulle part ailleurs, que se joue l'absence de doublon au rejeu. En cas
d'echec partiel, il leve une `CrmSyncException` enrichie de ce qu'il avait obtenu, sans
quoi le rejeu recreerait ce qui existe deja.

Deux limitations connues vivent dans cette modelisation, documentees dans le Javadoc de
`DolibarrConnector` : le rattachement du responsable Dolibarr n'a pas de logement dans
`CrmSyncState` — s'il echoue apres la creation de l'opportunite, le rejeu saute l'etape sans
le signaler — et la `ref` d'opportunite est tiree au hasard faute de reference de lead dans
le pivot. Les deux appellent la meme decision : elargir le pivot, ou passer d'un triplet de
references a une carte par etape. Elle se prendra avec F3, quand le consommateur de file
dira ce qu'il peut fournir comme identifiant.

Les cles attendues dans `crm_config` pour chaque fournisseur sont documentees dans
`docs/erp-integration-setup.md`.

`CrmSyncService` porte tout ce qui est propre a LeadFlow : resolution de `CrmTarget` depuis
`client.crm_config` dechiffre, reconstruction de l'etat anterieur (valeur non nulle la plus
recente, champ par champ), resolution et memorisation de `sales_rep.crm_ref`, ecriture de la
trace. Il ne choisit aucun commercial (F4), ne consomme aucune file (F3) et ne reessaie pas :
la reprise appartient a la DLQ. La trace s'ecrit en `REQUIRES_NEW` pour survivre au rollback
d'un appelant transactionnel.

Il n'y a **pas de connecteur par defaut** : tout lead appartient a un client, et tout
client nomme son fournisseur. `CrmConnectorRegistry.defaultConnector()` et la propriete
`leadflow.crm.default-provider` ont ete supprimees.

`leadflow.crm.providers.<provider>` ne porte donc que des reglages **techniques** communs a
toutes les instances d'un meme fournisseur (`enabled`, `connect-timeout`, `read-timeout`).
Tout ce qui depend du client — URL, cle d'API, base, utilisateur — vit dans `crm_config`
sur la ligne `client`, chiffre.

### Securite

`SecurityConfig` est **stateless** et laisse `/api/webhooks/**` en `permitAll` : ces requetes
viennent de serveurs tiers qui ne peuvent pas s'authentifier classiquement. Leur
authentification, c'est la signature HMAC, verifiee dans la couche `capture` et non dans la
chaine de filtres Spring Security. Ne pas « securiser » cette route avec un mecanisme Spring
sans retirer la verification HMAC, et inversement.

CORS n'autorise que `http://localhost:4200` : a elargir avant tout deploiement.

## Frontend — conventions

### Le visuel passe par le plugin `ui-ux-pro-max`

**Règle permanente** : toute décision visuelle — écran, composant, palette, typographie,
graphique, identité — se prend à travers les skills du plugin `ui-ux-pro-max`, invoqués
**avant** d'écrire le code, jamais en relecture après coup.

| Skill                        | Quand                                                       |
| ---------------------------- | ----------------------------------------------------------- |
| `ui-ux-pro-max:ui-ux-pro-max`| point d'entrée : styles, palettes produit, polices, graphiques |
| `ui-ux-pro-max:ui-styling`   | composants concrets et mise en page                          |
| `ui-ux-pro-max:design-system`| jetons de design (primitive → sémantique → composant)        |
| `ui-ux-pro-max:design`, `:brand` | identité visuelle, logo, charte                          |
| `ui-ux-pro-max:slides`       | présentations HTML avec Chart.js (soutenance)                |

Cela concerne au premier chef les quatre écrans du dashboard (F6) : les compteurs, les
répartitions et toute représentation graphique doivent être conçus, pas improvisés. Le skill
`dataviz` reste complémentaire pour la rigueur des graphiques (accessibilité des couleurs,
cohérence clair/sombre), mais le plugin prime sur les décisions visuelles.

### Angular

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
`proxy.conf.json` renvoie `/api` et `/actuator` vers `localhost:8090`, en production le
dashboard est servi derriere le meme domaine que l'API. Les services doivent donc appeler des
chemins relatifs (`/api/leads`), jamais une URL absolue. Le remplacement de fichier
d'environnement est cable dans `angular.json`, configuration `development`.

## Etat actuel

Le pipeline est **complet de bout en bout, observable et administrable** : capture (F2),
qualification (F3), routage et synchronisation ERP (F4), sur le socle multi-tenant de F1, les
adaptateurs de F5, le monitoring de F6 et la gestion des boutiques de F7.

Ce qui existe : la configuration, le chiffrement des secrets, les six entites et leurs
repositories, les migrations `V1` a `V4`, le port `CrmConnector` et son registre, les
adaptateurs Dolibarr et Odoo, `CrmSyncService`, les couches `capture`, `qualification` et
`routing` — trois strategies d'attribution, et la synchronisation ERP declenchee par la file.
Un lead traverse `QUALIFIED` -> `ROUTED` -> `SYNCED` sans intervention.

Et desormais la couche `monitoring` : authentification JWT, API de lecture du pipeline
(`/api/leads`, `/api/stats`, `/api/clients`, `/api/connectors`, `/api/queues`), journal des
messages morts rejouable un par un, flux SSE, filets de republication de `lead.qualified` et
`lead.routed`.

Et depuis F7, l'administration des boutiques dans `tenant/` : creation, mise a jour,
activation, rotation du secret HMAC et de la cle publique, gestion des commerciaux, et une
sonde d'acces sur le port `CrmConnector` qui eprouve une cible ERP sans rien y creer.
**Ajouter une boutique ne demande plus la base** : c'est un formulaire, et le secret n'a
jamais a etre chiffre a la main.

Et l'ecran « Parametres » : la cle d'API de l'analyse d'intention s'y colle, s'y eprouve
avant d'etre enregistree, et l'analyse s'y coupe sans perdre la cle. Le bandeau d'etat
compte les leads classes par le modele et par le lexique, pour que le mode degrade se voie.
Neuf ecrans Angular en tout : connexion, dashboard, leads et detail, file d'attente,
connecteurs, boutiques, fiche d'une boutique, creation, parametres.

Ce qui n'existe pas :

- **Aucune notification n'est envoyee au commercial** : ni tache d'agenda dans l'ERP, ni
  alerte pour les leads chauds. `ScoringConfig.seuilChaud` est lu et porte depuis F3 pour
  figer la forme du document, mais **rien ne s'en sert encore**.
- **Aucune reattribution manuelle** : un lead attribue au mauvais commercial ne se corrige
  que par un rejeu depuis le journal des morts, ou en base.
- **Aucun graphique** : les repartitions sont des compteurs et des barres de progression.
  Aucune bibliotheque de graphiques n'est installee, et c'est un choix.
- **Aucun deploiement** : pas d'integration continue, pas d'image de production, et CORS
  n'autorise toujours que `http://localhost:4200`. C'est desormais le dernier chantier avant
  une mise en service.
- **Aucune restriction sur les destinations de `POST /api/admin/crm/test`** : le serveur
  appelle l'URL que l'operateur lui donne. Acceptable sur une console interne a compte
  unique ; a restreindre si le dashboard s'ouvre a des utilisateurs moins fiables.

Ne pas supposer l'existence d'un service ou d'un endpoint : verifier avant de referencer.
