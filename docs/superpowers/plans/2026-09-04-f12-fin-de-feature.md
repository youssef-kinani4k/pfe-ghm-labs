# F12 livree et fusionnee — etat pour la reprise

Session du 4 septembre 2026. F12 est **complete, recettee a l'ecran, fusionnee dans `main` en
`--no-ff` et poussee**. Le depot est propre et synchronise avec `origin`.

## L'etat du depot

| | |
| --- | --- |
| Branche courante | `main`, synchronisee avec `origin/main` |
| Dernier commit | `e3b0bd6` — la fusion de F12 |
| Arbre de travail | **propre** |
| Migrations | **neuf** — `V9__notification_attempt.sql` est la derniere |
| Suite backend | **503/503**, `BUILD SUCCESS` (455 en debut de session) |
| Suite frontend | **62/62**, ESLint et Prettier verts |
| Branche de la feature | `feature/f12-notification-commercial`, **conservee**, 17 commits |

## Ce que la session a produit

Trois travaux, dans cet ordre.

**La dette de F7.2 n'existait pas.** Le secret HMAC du jeu de demonstration etait correct
depuis F1 ; trois preuves concordantes l'etablissent, dont un webhook signe rendant `202`.
Livre a la place : `DonneesDeDemoTest`, qui empeche la divergence de revenir. Detail dans
`2026-09-04-f7.2-dette-inexistante.md`.

**La ligne « Attribution » de la chronologie** nomme desormais le titulaire d'origine, derive
du `previous_sales_rep_id` de la premiere reattribution — **sans la neuvieme migration** que
le constat de F10 annoncait. Detail dans `2026-09-04-attribution-d-origine.md`.

**F12, la notification du commercial**, en douze taches, chacune en rouge puis vert puis
commit.

## F12 — ce qu'il faut savoir avant d'y toucher

**L'accroche n'a rien coute au pipeline.** `leadflow.leads.notify` se lie a la cle
`lead.synced` que `SyncedLeadPublisher` publiait deja depuis F6. Un `DirectExchange` livrant a
toutes les files liees a une cle, le monitoring continue de recevoir ce qu'il recevait, et ni
`CrmSyncService` ni son publieur n'ont ete modifies. Un test l'asserte sur les deux files.

**On previent apres la synchronisation, jamais apres le routage.** Le prix assume : un lead
dont la synchronisation ERP echoue ne declenche aucune alerte, et aucune trace non plus.

**Le seuil de notification est distinct de `seuilChaud`** et vit dans le meme document JSON,
donc sans migration. `ScoringForm.seuilNotification` est un `Integer` et non un `int`, seul
champ du record dans ce cas : le `PUT` remplacant le document entier, un client qui ne
l'envoie pas le lierait a zero, et la boutique notifierait tous ses leads.

**Trois causes ne partent jamais en DLQ** — score sous le seuil, lead sans commercial,
commercial sans adresse. Seul un echec technique y va, et **sa trace s'ecrit avant que
l'exception ne parte**, en `REQUIRES_NEW`.

**`IGNOREE` est un statut, pas une absence de ligne**, et la chronologie l'affiche : c'est ce
qui repond « score 75, seuil 95 » a « pourquoi n'ai-je pas ete prevenu ? ».

## Les quatre defauts trouves, et par quoi

Aucun n'aurait ete vu par la seule lecture du code.

**La suite complete, apres la tache 7.** `purgeQueue(nom, noWait)` avec `noWait` a vrai rend
la main avant que le broker ait fini : en isolation la file est vide et cela ne se voit pas,
dans la suite complete la purge emportait le message que le test venait de publier. C'est la
raison d'avoir lance la suite entiere apres avoir touche `RabbitMQConfig`.

**La lecture du `pom.xml`, a la tache 11.** Le profil `erp-it` ne disait pas « joue les tests
ERP » mais « n'exclus plus rien » : ajouter un second groupe sous ce schema aurait fait partir
de vrais e-mails a chaque execution ERP. La propriete liste desormais `erp,notification` et
chaque profil n'ouvre que le sien.

**La recette, sur le contenu du message.** L'alerte citait un lien vers le dashboard, ou le
commercial **n'a aucun compte** — la console est une console d'agence, un seul modele
d'utilisateur, aucun role. Le lien menait a un ecran de connexion infranchissable. Il est
retire, et le message porte a la place le telephone du prospect et ce qu'il a ecrit, tous deux
absents jusque-la. Un test verrouille l'absence de lien.

**La recette, sur l'interface.** Suivant la consigne « monter le seuil de notification a 95 »,
c'est le seuil de chaleur qui a ete regle. Les liaisons etaient correctes ; ce sont les
libelles qui ne l'etaient pas — deux champs commencant tous deux par « Seuil », l'un sous
l'autre, le texte d'aide ne se lisant qu'apres avoir clique. Ils se nomment desormais par leur
action, sans mot commun : « Badge chaud a partir de » et « Alerter le commercial a partir de »,
avec une icone chacun.

## L'etat de la machine

**Tout tourne encore** : `leadflow-postgres`, `leadflow-rabbitmq`, `leadflow-dolibarr` et sa
base, le conteneur `mailpit` (SMTP sur `:1025`, console sur `:8025`), le backend sur `:8090`
en profil `dev` avec `LEADFLOW_SMTP_HOST=localhost LEADFLOW_SMTP_PORT=1025`, et le dashboard
sur `:4200`.

Pour eteindre : arreter les deux processus Java/Node, puis `docker compose --profile dolibarr
down` sans `-v`, et `docker rm -f mailpit`.

**Dolibarr est installe et sa cle d'API repond** — le volume a survecu aux sessions
precedentes, ce qui evite la procedure manuelle de `docs/erp-integration-setup.md`.

La base porte les traces de la recette : quatre lignes de `notification_attempt`, dont une
`IGNOREE`, et les leads `recette-f12*` et `sara.benali@atlas-industries.test`. Le seuil de
notification de la boutique de demonstration est reste a **70**, son seuil de chaleur a **90**.

## La suite

Ce que `CLAUDE.md` liste encore comme absent, par ordre de valeur apparente :

- **Aucun graphique** dans le dashboard — forte valeur de soutenance, effort modere, et le
  plugin `ui-ux-pro-max` plus le skill `dataviz` sont la pour ca.
- **Le TLS**, prerequis de toute mise en ligne reelle.
- **Une reattribution ne remonte pas jusqu'a l'ERP** (F14) : la plus couteuse, elle touche le
  pivot et les deux adaptateurs. Elle apporterait aussi de quoi citer l'ERP dans l'alerte,
  a la place du lien retire.
- **Aucun recalcul retroactif des scores**, et **le nonce CSP**, et le passage **multi-instance**
  de `PendingEventRelay` et du limiteur de debit.

Un canal de notification supplementaire — tache d'agenda ERP, webhook sortant — est desormais
**additif** : une classe `@Component` implementant `CanalDeNotification`, sans toucher au
reste.
