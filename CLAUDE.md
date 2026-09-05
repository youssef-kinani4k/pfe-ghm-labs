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
[notification]  CanalDeNotification (port)  -->  notification.smtp  |  ...
    |
    v
[monitoring]  API REST  -->  dashboard Angular
```

Le point structurant : **le webhook ne fait aucun travail metier**. Il valide la signature,
ecrit l'evenement brut et publie sur le broker. Tout ce qui peut echouer ou etre lent
(Dolibarr, NLP) vit derriere la file. Une modification qui ajoute du traitement synchrone
dans `capture` casse cette garantie de non-perte sous charge.

## Monorepo

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

Ce `docker-compose.yml` est celui du **developpement**. La pile complete — dashboard,
API, base et broker derriere un Nginx, un seul port publie — vit dans un fichier a part :

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
SMOKE_PASSWORD='...' ./scripts/smoke-prod.sh   # eprouve cette pile de bout en bout
```

Le `--env-file` n'est pas facultatif : `env_file:` alimente les variables du conteneur,
tandis que les `${...}` du fichier compose sont interpoles a sa lecture. Le geste complet,
les secrets a fabriquer et ce que ce deploiement n'est pas : `docs/deploiement.md`.

### Integration continue

Un seul fichier, `.github/workflows/ci.yml`, quatre jobs. `backend` (`./mvnw verify` en
Temurin 21), `frontend` (tests Karma puis `npm run build`) et `qualite` (ESLint puis
Prettier) partent sur **chaque push**, en parallele. `fumee` monte la pile de production
entiere et la traverse par `scripts/smoke-prod.sh`, et il depend des deux premiers.

**`fumee` ecoute `main`, les pull requests, et les branches `feature/**` et `fix/**`.**
Cette derniere condition n'est pas de la generosite : les fusions de ce projet se font
localement en `--no-ff`, donc une CI declenchee sur les seules pull requests ne parlerait
jamais. Le job fabrique son propre `.env.prod` a chaque execution — **aucun secret GitHub
n'est configure ni requis**.

**`qualite` bloque** depuis que la dette de lint et de format est videe : un constat ESLint
ou un fichier mal formate fait echouer l'execution. Il ne bloquait pas a sa creation — dix
ecrans avaient ete ecrits sans linter — et les deux `continue-on-error`, celui du job et
celui du pas ESLint, sont tombes ensemble avec la dette.

**La copie de travail est en LF, y compris sur Windows** (`* text=auto eol=lf` dans
`.gitattributes`). Ce n'est pas un detail de confort : Prettier compare avec
`endOfLine: lf`, donc une copie de travail en CRLF fait signaler des dizaines de fichiers
que la CI, qui extrait en LF, voit parfaitement formates. Sans cette regle, `npm run
format:check` ne veut rien dire sur un poste Windows.

Deux invariants qu'une modification casserait sans qu'on le voie :

- **`./mvnw verify` s'execute sans `-Perp-it`.** Ajouter le profil ferait echouer la CI sur
  des conteneurs Dolibarr et Odoo qu'elle ne sait pas preparer — leur mise en route est
  manuelle (`docs/erp-integration-setup.md`).
- **Les mots de passe fabriques par le job sont hexadecimaux**, pas base64. `docker compose`
  interpole les `${...}` a la lecture de `.env.prod` ; passer a base64 marcherait presque
  toujours et casserait le jour ou un `$` sortirait du tirage.

**Un crochet `pre-push` refuse un push vers `main` que la CI rejetterait.** Il vit dans
`.githooks/pre-push`, donc il est versionne, mais git ne le voit qu'une fois par copie de
travail :

```bash
git config core.hooksPath .githooks   # a faire apres chaque clone
```

Il ne joue que les trois controles frontend — ESLint, Prettier, les 44 tests — et jamais
`./mvnw verify` ni le script de fumee : ceux-la demandent Docker, prennent des minutes, et
un crochet qui echoue pour une raison d'environnement finit desactive. Il remplace une
protection de branche, **impossible ici** : le depot est prive sur un compte gratuit, ou
l'API rend `403` sur les regles de protection comme sur les rulesets. Et il s'interpose plus
tot qu'elle ne le ferait, puisque les fusions sont locales.

Ce que la CI couvre, ce qu'elle ne couvre pas et comment lire un echec :
`docs/integration-continue.md`.

### Backend

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # :8090, logs SQL + DEBUG
./mvnw test                                             # toute la suite
./mvnw test -Dtest=HmacSignatureVerifierTest            # une classe
./mvnw test -Dtest=HmacSignatureVerifierTest#rejectsExpiredTimestamp   # une methode
./mvnw verify                                           # tests + package
./mvnw spring-boot:test-run                             # Testcontainers, mais profil default
                                                        # et pipeline eteint : lire plus bas
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
Testcontainers : il evite d'avoir a monter l'infrastructure via `docker compose`. Les images
des conteneurs de test sont epinglees sur les memes versions que `docker-compose.yml` — les
garder alignees.

**Mais il ne convient pas pour faire tourner le produit, et deux pieges le rendent
trompeur.** `TestBackendApplication` vit dans `src/test`, donc le lancement embarque tout le
classpath de test :

- **Il demarre en profil `default`, pas `dev`.** Le compte operateur et la cle de signature
  n'ont alors aucun repli — `password-hash` retombe sur la chaine vide — et **toute
  connexion au dashboard echoue par « Identifiants invalides »**. `R__demo_data.sql` n'est
  pas charge non plus, donc la base est vide. Ajouter `-Dspring-boot.run.profiles=dev`
  repare ces deux points.
- **`src/test/resources/application.properties` eteint les cinq consommateurs** —
  qualification, routage, CRM, journal des morts, flux temps reel. Le profil n'y change
  rien, et **le pipeline reste inerte** : un lead capture reste dans la file, aucune ligne
  `lead` n'est ecrite. Le symptome est un webhook qui rend `202` et un dashboard qui reste
  a zero.

**Pour lancer le produit — recette a l'ecran, verification manuelle du pipeline — c'est donc
`docker compose up -d` puis `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`**, qui ne
voit que le classpath principal. `test-run` reste utile pour eprouver le demarrage lui-meme
sans preparer d'infrastructure. Le controle qui tranche en une commande, une fois le backend
leve : `docker exec leadflow-rabbitmq rabbitmqctl list_queues name messages consumers` doit
montrer **cinq files avec un consommateur chacune**.

Corollaire : la base de `docker compose` est **persistante d'une session a l'autre**, la
qu'un lancement Testcontainers repart d'une base vierge. Une clef publique ou un secret HMAC
tourne depuis le dashboard y survit donc, et prend le pas sur les valeurs de
`R__demo_data.sql` — cette migration repetable ne se rejoue que si son contenu change.

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

**Depuis F14, une rotation de secret ouvre une fenetre pendant laquelle deux secrets sont
acceptes.** `HmacSignatureVerifier.verifie` recoit une `List<String> secrets` ordonnee, le
secret courant en premier : le cas normal ne calcule qu'un seul HMAC, et le second secret
n'est essaye que si le premier ne correspond pas. L'analyse de l'en-tete et le controle de
l'horodatage se font une seule fois, avant toute comparaison de secret. Le verificateur
ignore jusqu'au mot « transition » — il ne connait qu'une liste de secrets acceptables ;
c'est `LeadCaptureService` qui construit cette liste (le secret courant, plus le precedent
tant que sa date d'expiration est future) et pose le drapeau sur la ligne `raw_lead_event` :
c'est le seul endroit du projet qui connaisse la notion de fenetre. **Les cinq causes de
refus rendent toujours la meme reponse `401`** : un secret precedent expire ou revoque est
indiscernable d'une signature fausse, les deux echouent sur la meme exception, et l'index
d'idempotence `(client_id, signature)` de `V3` n'est pas affecte par la rotation.

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

**Le bareme se regle depuis l'ecran « Bareme » d'une boutique**, plus seulement en base
(`GET`/`PUT /api/admin/clients/{id}/scoring`, ecrits dans `tenant/` comme le reste de
l'administration). Les bornes `[0, 100]` des poids viennent du bornage du score par
`LeadScorer` : au-dela, un poids n'aurait aucun effet observable. Le `PUT` remplace le
document entier — une fusion partielle rendrait indecidable la difference entre « poids
absent » et « poids remis a zero ».

**Le bareme s'applique au prochain lead, jamais aux scores deja ecrits.** `lead.score` est
fige a la qualification, et rien ne le recalcule. Ce qui bouge immediatement, c'est le badge
« chaud » du monitoring, calcule a la lecture : c'est la seule chose que `seuilChaud`
alimente aujourd'hui.

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

**La reattribution manuelle vit ici, et elle ne publie aucun message.** `ReattributionService`
ecrit directement — le tour de role n'etant pas idempotent, republier sur `lead.routed`
decalerait la rotation et renverrait vers l'ERP un lead deja synchronise. Elle **ne touche ni
le statut ni `routed_at`** : un lead `SYNCED` reattribue reste `SYNCED`, et la date d'origine
de son attribution reste vraie. Elle **deplace en revanche le tour de role**, puisque celui-ci
se lit dans `lead` et non dans un compteur — le nouveau titulaire vient d'etre servi.

Quatre refus, tous en `409` (`ReattributionImpossibleException`) : lead sans commercial,
commercial deja en place, commercial desactive, commercial d'une autre boutique. Le dernier
n'est pas theorique — l'identifiant vient du client, et rien d'autre n'empecherait d'attribuer
le lead d'une boutique au commercial d'une autre.

Le service **n'est pas transactionnel**, comme `LeadRoutingService` : l'ecriture porte la
sienne (`RoutedLeadWriter`) et le journal la sienne, ce qui permet de journaliser **apres** le
commit. L'ordre inverse laisserait une ligne affirmant un changement qui n'a pas eu lieu ; le
risque assume est symetrique et moindre — une panne entre les deux donne un lead reattribue
sans trace.

**`LeadActionJournal` est en `REQUIRES_NEW`**, et c'est ce qui fait qu'une ligne de journal
survit au rollback de ce qu'elle raconte. C'est ce dont depend le rejeu en echec : il
journalise **avant** de laisser partir l'exception, sans quoi la seule trace d'un rejeu rate
disparaitrait avec lui.

### Notification — prevenir le commercial

**Le point d'accroche n'a rien coute au pipeline.** La file `leadflow.leads.notify` est liee
a la cle `lead.synced` que `SyncedLeadPublisher` publie deja depuis F6. Un `DirectExchange`
livrant a **toutes** les files liees a une cle, le monitoring continue de recevoir ce qu'il
recevait, et ni `CrmSyncService` ni son publieur n'ont eu a changer. Un test l'asserte sur
les deux files a la fois.

**On previent apres la synchronisation, jamais apres le routage.** Alerter sur `lead.routed`
enverrait le commercial chercher une fiche qui n'existe pas encore dans son ERP, et l'alerte
perdrait sa credibilite des la premiere fois. Le prix assume : un lead dont la
synchronisation echoue ne declenche aucune alerte — cet echec a deja son canal, le journal
des morts.

**Le seuil de notification est distinct de `seuilChaud`.** Les deux vivent dans
`client.scoring_config` et repondent a des questions differentes : colorer une pastille, et
deranger quelqu'un. On tolere un badge genereux, pas une boite mail saturee. Aucune migration
n'a ete necessaire — c'est precisement ce pour quoi ce reglage est un document JSON — et la
lecture etant tolerante, toute boutique configuree avant F12 prend le defaut.
`ScoringForm.seuilNotification` est un `Integer` et non un `int`, seul champ du record dans ce
cas : le `PUT` remplacant le document entier, un client qui n'envoie pas le champ le lierait
sinon a **zero**, et la boutique se mettrait a notifier tous ses leads.

**Trois causes ne partent jamais en DLQ** : un score sous le seuil, un lead sans commercial,
un commercial sans adresse. Aucune repetition ne les repare, donc elles ecrivent une trace
explicite et acquittent — meme parti que `DISCARDED` en qualification. Seul un echec technique
merite les trois tentatives puis la DLQ, et **sa trace s'ecrit avant que l'exception ne
parte**, en `REQUIRES_NEW` : sans cela elle disparaitrait avec le rollback, comme le
`LeadActionJournal` d'un rejeu rate.

**`IGNOREE` est un statut, pas une absence de ligne.** Un lead sous le seuil ecrit quand meme
sa trace, et la chronologie l'affiche : c'est ce qui repond « score 55, seuil 70 » a la
question « pourquoi n'ai-je pas ete prevenu ? ». Le score et le seuil sont figes dans la
ligne, jamais relus, pour qu'un reglage de bareme ne fasse pas mentir l'historique.

**Le canal est inerte sans hote de relais**, et l'application demarre quand meme : c'est le
parti de `GEMINI_API_KEY`. L'alerte est un confort, le pipeline ne l'est pas. Le profil `dev`
ne porte donc aucun repli — un repli vers un serveur imaginaire ferait mourir chaque lead
chaud en developpement.

**Les reglages du relais sont globaux a l'instance**, pas portes par le client : l'agence
exploite un seul relais pour toutes ses boutiques. Ce qui se regle par boutique, c'est le
seuil. `SondeNotification` eprouve la configuration depuis l'ecran « Parametres » en envoyant
un **vrai** message d'essai — un diagnostic qui ne prouverait que l'ouverture du port ne
prouverait rien — et n'ecrit aucune ligne de `notification_attempt`.

**L'alerte ne cite aucun lien vers le dashboard**, et porte a la place le telephone du
prospect et son message. Le commercial n'a **aucun compte** sur la console — un seul modele
d'utilisateur, aucun role — donc un lien l'enverrait sur un ecran de connexion qu'il ne peut
pas franchir, et un lien mort dans une alerte apprend surtout a ignorer les suivantes. Un
test le verrouille. Pointer vers l'ERP serait la bonne reponse a terme, le lead y etant deja
et le commercial y ayant un vrai compte, mais construire cette URL demanderait au port
`CrmConnector` de la fournir.

**Ajouter un canal** = une classe `@Component` implementant `CanalDeNotification`, dans son
propre sous-package. Aucun `switch`, aucun autre package a modifier — et **aucun terme propre
a un canal dans `NotificationLead`**, sans quoi la generalisation est perdue exactement comme
elle le serait dans `crm/model`.

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

**Le badge « chaud » est calcule a la lecture**, jamais stocke : c'est
`score >= seuilChaud` du bareme de **la boutique du lead**, donc deux leads au meme score
peuvent differer. Le figer en base a la qualification ferait mentir la liste des qu'une
boutique deplace son seuil. La comparaison ne peut pas se faire en SQL — `client.scoring_config`
est chiffre au repos, illisible par Postgres, et `Lead` ne porte aucune association vers
`Client` — donc `?chaud=true` construit une disjonction de couples (boutique, seuil), et le
drapeau se pose en Java sur les boutiques de la page, deja chargees pour leur nom. Le filtre
n'a pas de negation : `chaud=false` vaut l'absence du parametre.

**Les series quotidiennes de F13** (`GET /api/stats/series`) sortent de `SeriesRepository`, le
**premier repository natif du projet**, et deux contraintes independantes l'imposent : JPQL n'a
ni `date_trunc` ni `AT TIME ZONE` pour regrouper par jour dans un fuseau, et il n'a pas non plus
`percentile_cont` pour les delais. Le seul contournement global, poser
`hibernate.jdbc.time_zone`, changerait la lecture de tous les `Instant` du projet pour resoudre
le besoin de trois requetes — non touche. Le prix du natif : Hibernate ne valide plus ces
requetes au demarrage, ce qui rend les tests contre un vrai Postgres obligatoires et non
facultatifs. **Le fuseau de regroupement** est `leadflow.analytics.fuseau`, defaut
`Europe/Paris`, **global a l'instance** et non porte par la boutique, comme les reglages du
relais SMTP de F12. **Les delais s'ancrent sur le jour de la synchronisation**, pas de la
capture : le point est definitif des que le jour est passe, et l'ancrage inverse ferait
s'ameliorer la courbe quand ca va mal, un lead jamais synchronise n'y apparaissant jamais — le
delai se compte jusqu'au premier succes, un rejeu ajoutant un succes tardif. **Une journee sans
donnee n'a pas de ligne en base**, et le service la comble : volume et intentions a zero,
« aucun lead capture ce jour-la » etant un fait ; delais a `null`, « aucun lead synchronise » ne
voulant pas dire « delai de zero seconde ». Le dashboard compte desormais **onze ecrans**.

**La chronologie d'un lead est derivee, jamais stockee.** `GET /api/leads/{id}/timeline`
recompose en lecture seule ce que le lead a vecu depuis six tables existantes —
`raw_lead_event`, `lead`, `crm_sync_attempt`, `dead_letter`, `lead_action` depuis F10 et
`notification_attempt` depuis F12 —
sans qu'aucune table d'evenements n'existe. Quatre consequences a ne pas casser. **L'endpoint
est separe du detail** parce que la fiche rafraichit la seule chronologie apres une
reattribution, sans refaire tout le detail. **Une entree non datee garde sa place dans le
pipeline** au lieu d'etre rejetee en tete ou en queue : c'est le cas de toute attribution
anterieure a `V7`, et l'ordre des etapes est connu meme quand leur date ne l'est pas. Et **un
rejeu n'est compte qu'une fois** : il est lisible dans `dead_letter.replayed_at` comme dans
`lead_action`, et le service exclut la mort dont une action porte deja le `dead_letter_id`.
Enfin, **la ligne « Attribution » nomme le titulaire d'origine, pas le titulaire courant** :
`lead.assigned_sales_rep_id` ne retient que le dernier en date, si bien que le lire ferait
dire a la chronologie qu'un lead reattribue a toujours appartenu a son commercial actuel — et
la ligne « Reattribution » juste en dessous la contredirait. L'origine se derive du journal,
c'est le `previous_sales_rep_id` de la **premiere** reattribution. La derivation est exacte et
non approchee, parce que F10 a introduit ensemble la reattribution et sa trace : il n'existe
aucune reattribution non journalisee, et un lead sans commercial est refuse en `409`, donc
cette colonne ne peut pas etre nulle sur une ligne `REATTRIBUTION`. Aucune migration n'a donc
ete necessaire, la ou stocker l'attribution d'origine en colonne aurait rendu « inconnu » pour
tout l'historique anterieur.

Le service rend des faits typés, jamais des phrases — la mise en francais appartient au
template Angular, qui tient la table des huit libelles dans `lead-timeline.ts`. Ajouter une
valeur a `LeadActionType` ne compile plus tant qu'elle n'a pas sa place dans `typeDe(...)`,
un `switch` d'expression sans branche par defaut.

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
- `notification/` — port de sortie vers les canaux d'alerte du commercial.
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

Dix variables gouvernent l'instance :

| Variable                       | Role                                                    |
| ------------------------------ | ------------------------------------------------------- |
| `LEADFLOW_MASTER_KEY`          | Cle AES-256 (base64, 32 octets) des secrets au repos     |
| `LEADFLOW_JWT_SECRET`          | Cle de signature HS256 des jetons du dashboard           |
| `LEADFLOW_ADMIN_USER`          | Identifiant de l'operateur (defaut `admin`)              |
| `LEADFLOW_ADMIN_PASSWORD_HASH` | **Hash BCrypt** du mot de passe, jamais le mot de passe  |
| `GEMINI_API_KEY`               | Repli de la cle d'analyse d'intention ; l'ecran prime    |
| `LEADFLOW_SMTP_HOST`           | Relais d'envoi des alertes. **Absent : canal inerte**    |
| `LEADFLOW_SMTP_USERNAME`       | Identifiant du relais, si celui-ci en demande un         |
| `LEADFLOW_SMTP_PASSWORD`       | Son mot de passe                                         |
| `LEADFLOW_SMTP_FROM`           | Adresse d'expedition des alertes                         |
| `LEADFLOW_ANALYTICS_FUSEAU`    | Facultative, fuseau des series quotidiennes, defaut `Europe/Paris` |

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

**`leadflow.webhook.transition-secret` ne figure pas dans ce tableau**, contrairement a
`LEADFLOW_ANALYTICS_FUSEAU` : ce reglage n'a aucune variable d'environnement associee, c'est
une valeur d'`application.yml`, par defaut `24h` — la duree pendant laquelle le secret
precedent d'une boutique reste accepte apres une rotation. **Global a l'instance**, comme le
fuseau des series de F13 et le relais SMTP de F12 : ce n'est pas une caracteristique du
client, mais un parametre d'exploitation de l'agence.

La production d'un hash BCrypt est documentee dans `docs/monitoring-api.md` — c'est le
premier obstacle concret au deploiement.

### Base de donnees

`ddl-auto: validate` : **Hibernate ne cree jamais de table**. Toute evolution de schema passe
par un nouveau fichier `src/main/resources/db/migration/V<n>__description.sql`. Modifier une
migration deja appliquee fait echouer Flyway au demarrage (checksum) — il faut soit ajouter
une migration, soit `docker compose down -v` en dev.

Onze migrations existent : `V1__raw_lead_event.sql` (journal de capture),
`V2__multi_tenant_schema.sql` (schema metier complet — `client`, `sales_rep`, `lead`,
`crm_sync_attempt`, et l'ajout de `client_id` sur `raw_lead_event`),
`V3__raw_lead_event_idempotence.sql` (index unique `(client_id, signature)`),
`V4__dead_letter.sql` (journal des messages morts), `V5__intent_setting.sql` (cle d'API de
l'analyse d'intention, ligne unique, chiffree au repos) et `V6__crm_sync_attempt_assignee.sql`
(quatrieme reference de synchronisation, `assignee_ref` : elle memorise le responsable deja
lie chez Dolibarr, pour que le rejeu repare une attribution manquante au lieu de la sauter
en silence), `V7__lead_routed_at.sql` (date d'attribution au commercial),
`V8__lead_action.sql` (journal des gestes humains portes par un lead),
`V9__notification_attempt.sql` (trace des alertes envoyees au commercial),
`V10__analytics_index.sql` (deux index pour les series quotidiennes de F13) et
`V11__hmac_secret_transition.sql` (fenetre de transition du secret HMAC de F14).

**`V11` ajoute deux colonnes nullables sur `client`** — `previous_hmac_secret` (chiffree au
repos comme `hmac_secret`) et `previous_secret_expires_at` — **et un booleen sur
`raw_lead_event`**, `signed_with_previous_secret NOT NULL DEFAULT false`. Aucun index,
aucune table : comme `V7`, les deux colonnes de `client` sont sans remplissage retroactif,
et vont toujours ensemble — un secret precedent sans date d'expiration serait un secret
permanent, l'inverse de la feature. Le booleen se pose a l'insertion de la ligne, qui a de
toute facon lieu : le chemin chaud de la capture ne paie aucune ecriture de plus, et c'est
ce qui permet a la fiche de la boutique de dire quand le dernier lead a l'ancien secret est
passe, donc quand une revocation est sans risque.

**`V10` n'ajoute ni colonne ni table** : F13 ne fait que lire ce qui existe, comme la
chronologie de F9 qui recompose six tables sans en creer aucune. `idx_lead_client_created`
sur `lead (client_id, created_at DESC)` sert le regroupement par jour des series de volume et
d'intention — l'index existant sur `lead` porte `email` en deuxieme colonne, ce qui le rend
inutilisable des qu'on saute cette colonne pour grouper par date. `idx_crm_sync_attempt_success_at`
sur `crm_sync_attempt (attempted_at DESC)` est **partiel**, `WHERE status = 'SUCCESS'`, comme
`uq_dead_letter_lead_pending` de `V8` : un index global ferait payer les echecs, qui sont
l'essentiel du volume quand un ERP tombe. **Il ne sert cependant pas la requete des delais** :
sa CTE `premier_succes` fait un `group by lead_id`, qu'un index trie par date n'accelere pas,
et elle agrege **sans aucun filtre de date** — le predicat `depuis`/`jusqu` porte sur le
resultat de l'agregat, apres coup, pas sur les lignes lues. Le cout de cette requete est donc
proportionnel a tous les succes depuis toujours, pas a `jours` ; le vrai bornage demande un
`where` dans la CTE plus un `not exists` de succes anterieur, dette documentee dans le Javadoc
de `SeriesRepository` et volontairement non corrigee. Un troisieme index avait ete envisage
pour la courbe de volume, mais `idx_raw_lead_event_client_received` sur
`raw_lead_event (client_id, received_at DESC)` existe deja depuis `V2` et la sert.

**`V8` porte deux choses.** La table `lead_action` d'abord — qui a change quoi, quand, et
**pourquoi** : `dead_letter` ne retenait d'un rejeu que `replayed_by` et `replayed_at`, jamais
son motif ni son resultat. `lead_id` y est une vraie cle etrangere, contrairement a
`dead_letter.lead_id`, parce qu'une action part toujours d'un lead qu'on vient de lire ;
`previous_sales_rep_id` et `new_sales_rep_id` n'en sont pas, deliberement, pour qu'un
commercial supprime n'efface pas l'histoire. Et l'index unique partiel
`uq_dead_letter_lead_pending` ensuite — **la dette de F6, payee ici**. Il est partiel et non
global : la livraison etant at-least-once, une meme mort livree deux fois ecrirait deux
lignes, mais une seconde mort **apres** un rejeu qui a de nouveau echoue est un fait reel
qu'il faut garder. Le restreindre aux lignes `PENDING` distingue ces deux cas, et aligne le
schema sur le garde-fou deja ecrit en Java.

**`V7` est nullable et sans remplissage retroactif**, deliberement. L'attribution n'etait
datee nulle part avant F9, et `updated_at` ne la remplace pas : il vaut la date
d'attribution pour un lead reste `ROUTED`, mais celle de la synchronisation pour un lead
`SYNCED`. Remplir l'historique depuis cette colonne aurait donc invente une date pour tout
lead deja synchronise. La chronologie affiche « date inconnue » pour les leads attribues
avant la migration, et c'est la seule chose vraie qu'on puisse en dire. La date est posee
par `RoutedLeadWriter` dans la meme transaction que le statut et le commercial : les trois
sont un seul fait, et un `routed_at` sans commercial serait un etat incoherent.

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

**Le modele pivot de `crm/model` ne doit contenir aucun terme propre a un fournisseur** — ni
« thirdparty » (Dolibarr), ni « res.partner » (Odoo). Toute la traduction se fait dans
l'adaptateur. C'est ce qui rend le pipeline independant de l'ERP, et c'est l'invariant le plus
facile a casser par inadvertance : des qu'un champ specifique remonte dans `CrmLead`, la
generalisation est perdue.

Le detail — le port et ses methodes, la resolution par registre, les trois gestes pour ajouter
un ERP, les limitations connues du pivot — vit dans
`backend/src/main/java/com/leadflow/crm/CLAUDE.md`, qui se charge quand on travaille dans ce
repertoire.
### Securite

`SecurityConfig` est **stateless** et laisse `/api/webhooks/**` en `permitAll` : ces requetes
viennent de serveurs tiers qui ne peuvent pas s'authentifier classiquement. Leur
authentification, c'est la signature HMAC, verifiee dans la couche `capture` et non dans la
chaine de filtres Spring Security. Ne pas « securiser » cette route avec un mecanisme Spring
sans retirer la verification HMAC, et inversement.

Les origines CORS viennent de `leadflow.security.cors.allowed-origins`, et **la liste est
vide en production** : Nginx sert le dashboard et relaie l'API sous une origine unique,
donc il n'y a aucune requete cross-origin a autoriser. Seul le profil `dev` la peuple, ou
`ng serve` tient le `:4200` face au backend sur `:8090`. Une liste vide ne se contente pas
de tout refuser : aucun `CorsFilter` n'est enregistre, et un test le verrouille.

**`LimiteurDeDebit` plafonne le volume du webhook, hors de la chaine Spring Security.**
L'authentification de `/api/webhooks/**` reste la signature HMAC, verifiee dans `capture` ;
le limiteur ne l'authentifie pas, il compte. Il ne consulte jamais la base — la cle est le
dernier segment de l'URL, **decode** (sans quoi `abc`, `%61bc` et `a%62c` ouvriraient trois
seaux pour une seule boutique), sans savoir si une boutique lui correspond — ce qui garde
intacte la regle des cinq `401` uniformes : un `429` dit « trop d'appels », jamais « cette cle
existe ». Il est **mono-instance**, comme `PendingEventRelay` : deux exemplaires de
l'application offriraient deux fois le plafond, et le lever demanderait un compteur partage.
Le plafond protege le quota d'une boutique et la file d'un formulaire qui s'emballe ; il ne
protege pas l'instance d'une inondation qui varie la cle a chaque appel — chaque cle inventee
coute quand meme une consultation client, et peut faire tourner les 10 000 entrees du registre
LRU. Une parade a cette inondation demanderait une cle sur l'IP, ou un plafond pose au niveau
du reverse-proxy.

**`CrmHttpConfig` ancre un garde de destination sur chaque appel sortant vers un ERP.**
`PolitiqueDeDestination` refuse les adresses internes (boucle locale, plages privees,
lien-local dont les metadonnees d'instance cloud) et n'autorise que les hotes d'une liste
d'exceptions **par profil** (`leadflow.security.crm.hotes-autorises` — conteneurs `dolibarr`/
`odoo` en prod, `localhost`/`host.docker.internal` en dev). `CrmHttpConfig` construit son
`HttpClient` avec `Redirect.NEVER` : le garde ne voit la requete qu'une fois, et une
redirection suivie a l'interieur d'un `send()` y echapperait sinon. Le garde ne ferme pas
la fenetre de DNS-rebinding : il resout le nom, puis le client HTTP le resout a son tour, et
un serveur DNS hostile peut repondre differemment aux deux.

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

### Chart.js — depuis F13, empaquete et confine

**Chart.js existe**, dans le seul chunk de la feature qui l'a introduit : l'ecran `analyse`,
charge en `loadComponent` comme les autres. **Un seul fichier l'importe**, `graphique-ligne.ts`
— aucun autre ecran ne connait la bibliotheque, et l'ecran d'accueil (`dashboard.ts`) reste
sans elle pour que son chunk, charge a chaque connexion, ne s'alourdisse pas. Pas de
`ng2-charts` : un enrobage Angular de plus n'aurait rien resolu qu'un composant seul ne fasse,
et aurait ajoute une dependance a suivre a chaque montee de version.

### Polices — servies par l'origine, jamais par un CDN

**Le dashboard ne charge aucune ressource tierce a l'execution.** Les polices (Fira Sans,
Roboto, Fira Code) et surtout **Material Symbols Outlined**, la police d'icones, vivent dans
`frontend/src/fonts/` et sont versionnees. Ce n'est pas une preference : la CSP de production
dit `font-src 'self'`, donc tout ce qui vient de `fonts.gstatic.com` est bloque. Material
Symbols est une police **a ligatures** — `mat-icon` ecrit le nom de l'icone en texte et laisse
la police y substituer le glyphe — si bien qu'une police bloquee ne degrade pas les icones :
elle les fait disparaitre.

`src/fonts.css` est **genere**, pas ecrit a la main : `python build-fonts.py` depuis
`frontend/` rapatrie les sous-ensembles `latin` et `latin-ext` et reconstruit la feuille. Il
en emet aussi la classe `.material-symbols-outlined`, que Google servait avec son CSS et dont
`MAT_ICON_DEFAULT_OPTIONS` depend : sans elle, les fichiers sont servis et aucune icone
n'apparait. Le script deduplique par empreinte — Roboto et Fira Code sont des polices
variables, un seul fichier porte toutes leurs graisses sous une plage `font-weight`.

Les polices sont sous `src/` et non `public/` **pour que le build les hache**. Le bloc Nginx
des ressources statiques pose `Cache-Control: immutable` sur un an ; sous `public/` les noms
seraient stables, et une police regeneree resterait figee un an dans les navigateurs.

**`inlineCritical` est desactive dans la configuration `production`**, et ce n'est pas un
reglage de performance. Pour differer la feuille principale, l'inlining du CSS critique emet
`<link rel="stylesheet" media="print" onload="this.media='all'">`. Sous `script-src 'self'`,
la CSP refuse les gestionnaires d'evenements en attribut : le `onload` ne part jamais, le
`media` reste `print`, et **toute la feuille n'est jamais appliquee a l'ecran**. L'echec est
muet — la page rend `200`, le CSS rend `200`, et la classe des icones ne s'applique pas.
L'etape `3d` du script de fumee refuse desormais tout attribut `on*=` dans `index.html`.

L'etape `3c` de `scripts/smoke-prod.sh` verrouille l'ensemble : aucune reference a Google dans
la page ni dans sa feuille, et la police d'icones servie en `font/woff2`.

### Appels API

`environment.apiBaseUrl` est **volontairement vide dans les deux environnements** : en dev
`proxy.conf.json` renvoie `/api` et `/actuator` vers `localhost:8090`, en production le
dashboard est servi derriere le meme domaine que l'API. Les services doivent donc appeler des
chemins relatifs (`/api/leads`), jamais une URL absolue. Le remplacement de fichier
d'environnement est cable dans `angular.json`, configuration `development`.

## Etat actuel

Le pipeline est **complet de bout en bout, observable et administrable** : capture (F2),
qualification (F3), routage et synchronisation ERP (F4), notification du commercial (F12), sur
le socle multi-tenant de F1, les adaptateurs de F5, le monitoring de F6 et la gestion des
boutiques de F7. F13 ajoute l'ecran d'analyse et ses trois series quotidiennes. **F14 repare
la rotation du secret HMAC** : regenerer le secret d'une boutique n'interrompt plus sa
capture — une fenetre de transition, `leadflow.webhook.transition-secret` (defaut `24h`),
laisse l'ancien secret valoir le temps que le site de la boutique redeploie, et l'ecran des
boutiques montre l'etat de cette migration jusqu'a sa revocation.

Un lead traverse `QUALIFIED` -> `ROUTED` -> `SYNCED` sans intervention, et onze ecrans Angular
couvrent l'exploitation comme l'administration.

Trois capacites meritent d'etre connues avant d'ouvrir le code, parce qu'elles ont ete des
manques longtemps : **ajouter une boutique ne demande plus la base** — c'est un formulaire, et
le secret n'a jamais a etre chiffre a la main —, **regler un bareme non plus**, et depuis F10
**un lead se reattribue depuis sa fiche**, motif obligatoire, sans passer par un rejeu ni par
`psql`. Le meme motif est desormais exige du rejeu et de l'ecart d'un message mort : les trois
gestes humains laissent une trace dans `lead_action`, et la chronologie du lead les montre.

Ce qui n'existe pas :

- **L'alerte du commercial ne passe que par l'e-mail** : depuis F12 un lead au-dessus du
  seuil de notification de sa boutique declenche un envoi SMTP, mais **aucune tache d'agenda
  n'est creee dans l'ERP**. Ce second canal demanderait un adaptateur de plus derriere le
  port `CanalDeNotification` — additif, contrairement a la propagation d'une reattribution.
  Et rien ne previent l'operateur qu'une alerte a echoue, sinon le journal des morts.
- **Aucun recalcul retroactif des scores** : changer un bareme ne touche pas les leads deja
  qualifies. Le badge se deplace, le score non — c'est voulu, mais cela surprend.
- **Une reattribution ne remonte pas jusqu'a l'ERP** : elle corrige LeadFlow, pas Dolibarr
  ni Odoo. Un lead deja `SYNCED` garde son ancien responsable chez l'ERP, et le dialogue de
  reattribution le dit a l'operateur plutot que de le laisser croire a une correction qui
  n'a pas lieu. Propager demanderait une methode de mise a jour sur le port `CrmConnector`,
  que le pivot n'expose pas.
- **Le modele de l'analyse d'intention est un reglage, pas une constante** : Google retire
  des modeles au fil du temps, et l'ancien nom se met a rendre `404`. Le libelle vit sous
  `leadflow.intent.gemini.model` pour que ce retrait se repare sans toucher au code. La
  forme du corps de requete, elle, se verrouille par un test contractuel — `thinkingBudget`
  a ete remplace par `thinkingLevel`, l'ancienne forme faisant rendre un `400` muet.
- **Le deploiement existe, mais il n'est pas une mise en service** : depuis F11.1, deux
  images multi-etages, une pile de production a quatre services derriere un Nginx qui ne
  publie qu'un port, un profil `prod`, et un script de fumee qui traverse le pipeline
  entier — `docs/deploiement.md`. Depuis F11.2, Nginx pose les en-tetes de securite (CSP,
  HSTS inerte, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`), le webhook
  porte un plafond de debit mono-instance et chaque appel sortant vers un ERP passe par un
  garde de destination. Depuis F11.3, une CI GitHub Actions eprouve les deux etages de test
  et la pile de production entiere a chaque push. Manquent encore le TLS, le nonce CSP
  (donc `'unsafe-inline'` reste sur `style-src`) et un limiteur partage entre instances.

Ne pas supposer l'existence d'un service ou d'un endpoint : verifier avant de referencer.
