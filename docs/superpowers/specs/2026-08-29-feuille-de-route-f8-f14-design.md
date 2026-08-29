# Feuille de route F8 - F14

_Document de vision, 29 aout 2026. Il fixe le perimetre restant, son ordre et les decisions
structurantes de chaque feature. Il ne remplace pas le brainstorming de chacune : chaque
feature garde son propre design et son propre plan d'implementation._

## Point de depart

Le pipeline est complet de bout en bout, observable et administrable : capture (F2),
qualification (F3), routage et synchronisation ERP (F4) sur le socle multi-tenant de F1, les
adaptateurs Dolibarr et Odoo (F5), le monitoring (F6), la gestion des boutiques (F7) et le
reglage de la cle d'analyse d'intention depuis l'interface (F7.2). Neuf ecrans Angular, cinq
migrations, `main` propre au commit `9d12dbf`.

## Le perimetre restant : sept features, et une seule chose ecartee

Sept features couvrent tout ce qui manque. **Hors perimetre, de maniere assumee : les comptes
multi-tenant avec roles.** LeadFlow est une console d'agence — un seul modele d'utilisateur,
aucun role, le tenant est un filtre de requete et non une donnee portee par le jeton. Tout ce
qui suppose des utilisateurs finaux tombe du meme cote de la ligne, et cela entraine une
consequence a assumer en soutenance : **aucune notification n'est envoyee au commercial**,
parce que le commercial n'a pas de compte. Une alerte n'a de valeur que si son destinataire
peut agir ; ici elle n'atterrirait que chez l'operateur, qui voit deja la liste des leads et
recoit le flux SSE en direct. Construire une demi-solution pour cocher la case aurait affaibli
le discours, pas le contraire.

## L'ordre retenu, et pourquoi

**F8 → F11 → F9 → F10 → F12 → F13 → F14.**

Deux principes le gouvernent.

**F11 tot, en deuxieme position.** Le deploiement et la CI sont le seul chantier sans ecran et
le plus lourd de la liste ; les placer tot signifie que les cinq features suivantes sont
testees automatiquement et deployables le jour meme, et que le durcissement se fait pendant
que la surface d'attaque est encore petite. Le prix — deux ou trois sessions sans rien de
visible a montrer, tot dans la serie — est accepte en connaissance de cause. F8 passe quand
meme devant parce que c'est une session courte qui leve une contradiction deja affichee a
l'ecran.

**Le reste suit les dependances, pas la seduction.** F9 avant F10, parce que la timeline donne
a la reattribution un endroit ou s'afficher. F10 avant F12, parce que la table d'audit de F10
devient une source d'agregats que `StatsView` ne sait pas produire aujourd'hui. F9 et F12
restent voisines : toutes deux vivent dans `monitoring/` en lecture seule et derivent des
donnees existantes, et le travail de jetons visuels de F9 sert directement F12. F13 et F14
ferment la marche : deux durcissements de rigueur, independants du reste, deplacables sans
rien casser.

**Deux ordres ont ete examines puis ecartes.** Par risque decroissant (F14 et F13 juste apres
F11) : defendable, mais F14 est une refonte du pivot et une migration de `crm_sync_attempt`,
un prix fort pour un trou qui ne s'ouvre que sur panne reseau **puis** rejeu, alors que le
contournement coute vingt lignes. Par valeur de demonstration (F12 en premier) : F12 sans F10
n'a que les compteurs actuels a mettre en courbe, et sans F9 le systeme n'a aucune notion de
duree — or les graphiques interessants d'un middleware sont des latences, pas des totaux.

---

## F8 — Le bareme reglable, et le badge « chaud »

_1 session courte. Touche `tenant/`, `monitoring/`, deux ecrans Angular. Aucune migration._

`ScoringConfig` fixe deja la forme du document (bareme additif a criteres fixes, lecture
tolerante), `LeadScorer` l'applique, et la colonne `client.scoring_config` est un
`JSONB NOT NULL DEFAULT '{}'` **non chiffre**, contrairement a `hmac_secret` et `crm_config`.
Il ne manque que l'exposition : `GET` et `PUT /api/admin/clients/{id}/scoring`, un
`ScoringForm` dans `tenant/dto/`, un onglet dans la fiche d'une boutique.

**Le piege d'architecture.** `ScoringConfig` vit dans `qualification/`, et
`qualification/LeadQualificationService` importe deja `tenant/` : faire lire `ScoringConfig`
par `tenant/` cree un cycle entre les deux packages. Trois sorties ont ete pesees — deplacer
la classe (elle est chez elle dans `qualification/`, c'est le bareme que le scoreur applique),
dupliquer les noms de cles dans le formulaire (derive garantie le jour ou une cle bouge), ou
assumer l'import. **Decision : assumer l'import, et le verrouiller par un test d'aller-retour**
formulaire → `Map` → `ScoringConfig.depuis()`, qui doit rendre exactement les valeurs saisies.
Le cycle de packages est un fait ; le test est la garantie qui compte.

**Le badge chaud se calcule a la lecture, jamais stocke.** C'est la seule forme qui donne
l'effet promis : on bouge le seuil, on recharge, les badges se deplacent. `LeadSummary` et
`LeadDetail` portent deja `score`. Comme `scoring_config` est en clair, le filtre « leads
chauds » est une vraie clause SQL —
`score >= COALESCE((c.scoring_config->>'seuilChaud')::int, 70)` — donc ni N+1, ni calcul cote
navigateur.

**Ce que la feature referme** : le bareme d'une boutique ne se reglera plus en base, et
`seuilChaud`, lu et porte depuis F3 sans consommateur, en retrouve un qui se voit.

## F11 — Deploiement, CI, durcissement

_2 a 3 sessions. Touche la racine, `config/`, `capture/`, `SecurityConfig`, le frontend._

Sept chantiers qui n'ont de sens qu'ensemble.

1. **Dockerfile multi-stage backend** (JRE 21 au runtime, ni JDK ni Maven) et **frontend servi
   par Nginx**. La moitie du travail est deja faite sans que cela se voie :
   `environment.apiBaseUrl` est vide dans les deux environnements et tous les services
   appellent des chemins relatifs — l'application est deja ecrite pour vivre sous un domaine
   unique.
2. **Nginx en reverse proxy** : le probleme CORS disparait au lieu d'etre configure. Piege
   connu : `proxy_buffering off` sur `/api/stream/leads`, sinon le flux SSE se fige.
3. **Un profil `prod`** explicite, et le `docker-compose` complet pour la recette.
4. **Rate limiting sur `POST /api/webhooks/leads/{clePublique}`**, le dernier vrai trou de
   securite. Contrainte non negociable : les cinq causes de refus rendent le meme `401` pour ne
   pas donner d'oracle sur les cles publiques existantes ; un `429` qui ne se declencherait que
   sur cle valide **reconstituerait cet oracle**. Le quota se compte sur la cle de l'URL,
   valide ou non.
5. **En-tetes HTTP** (HSTS, CSP, `X-Content-Type-Options`, `Referrer-Policy`) : ici et pas
   avant, HSTS n'ayant aucun objet sans HTTPS. Verifier que la CSP ne casse ni le flux SSE lu
   en `fetch` + `ReadableStream`, ni les chunks lazy d'Angular.
6. **CI GitHub Actions.** Deux pieges : `./mvnw test` exige un daemon Docker (Testcontainers
   Postgres et RabbitMQ), et l'etape « qualite » n'a rien a appeler cote frontend — aucun
   ESLint n'est configure, `ng lint` echoue. Soit ESLint est ajoute dans cette session, soit
   l'etape est retiree ; elle ne doit pas rester rouge.
7. **SSRF** : `POST /api/admin/crm/test` et `POST /api/admin/intent/test` font emettre au
   serveur un appel vers une destination fournie par l'operateur, sans restriction. Acceptable
   sur une console interne a compte unique ; cette session est le dernier moment ou c'est
   encore un detail.

**Et le contournement de `lieResponsable`, deplace ici depuis F14.** Sortir l'appel du bloc
garde par `opportunite == null` et le rendre naturellement idempotent : demander a Dolibarr si
le projet a deja ce chef de projet avant de l'attacher. Vingt lignes, meme logique que
`chercheOpportuniteParRef` qui a leve l'autre limitation en F3. Le trou de perte silencieuse
est bouche ici ; F14 garde la refonte du pivot. **Boucher le trou et prouver l'architecture
sont deux travaux distincts** — les separer evite de payer le prix du second pour obtenir le
premier.

## F9 — La timeline derivee du lead

_1 session. Touche `monitoring/` en lecture seule. Aucune migration._

**Decision structurante : timeline derivee, pas table d'evenements.** Une table
`lead_timeline` alimentee par ecriture explicite obligerait `capture`, `qualification`,
`routing` et `crm` a ecrire dans une table transverse, et ferait ecrire `monitoring/` — ce qui
detruirait la seule propriete qui rend cet observateur sur : `LeadQueryRepository` etend
`Repository` nu, aucune methode d'ecriture n'est meme exposee. Une projection calculee a la
lecture donne le meme ecran sans toucher au pipeline.

La matiere existe deja, repartie sur quatre tables :

| Source | Ce qu'elle date |
| --- | --- |
| `raw_lead_event` | `received_at`, `published_at`, le statut (`RECEIVED` / `PUBLISHED` / `FAILED` / `DISCARDED`) et `failure_reason` |
| `lead` | `created_at` — la qualification, avec l'intention, sa **source** (`GEMINI` ou `RULES`) et le score |
| `crm_sync_attempt` | une ligne append-only par tentative, avec les references obtenues et le motif d'echec |
| `dead_letter` | `dead_at`, la cause, puis `replayed_at` et `replayed_by` |

**Trois trous a afficher honnetement, pas a combler par de l'invention.**

1. **L'attribution n'a pas d'horodatage propre.** `lead.updated_at` est ecrase a chaque
   changement de statut : il ne date pas le passage en `ROUTED`. La premiere ligne de
   `crm_sync_attempt` en donne une borne superieure ; la timeline affiche l'attribution sans
   heure exacte, jamais avec une heure fausse.
2. **Les trois tentatives de Spring AMQP ne sont pas persistees.** L'ecran dit « 3 tentatives
   puis DLQ », pas leur horodatage individuel.
3. **`LeadDetail` n'expose pas les morts.** Il porte `syncAttempts` et `rawEvent`, rien de
   `dead_letter`. F9 les ajoute — c'est ce qui rend le chemin vers la DLQ visible.

**La forme** : une `List<TimelineEntry>` ajoutee a `LeadDetail`, un seul appel servant l'ecran.
Chaque entree porte **un type enumere et ses donnees**, jamais une phrase pre-composee cote
serveur : le libelle se compose dans le template Angular, sinon la presentation traverse la
frontiere HTTP en meme temps que la donnee.

Conception visuelle par les skills du plugin `ui-ux-pro-max`, invoques avant d'ecrire le code.
Le travail de jetons fait ici sert directement F12.

## F10 — Reattribution manuelle et journal d'actions

_1 session. Touche `routing/` en ecriture, une migration `V6`, l'ecran de detail._

**Ou cela vit.** Pas dans `monitoring/`, qui n'ecrit que `dead_letter`. Pas dans `tenant/`, qui
porte les donnees de reference — client et commerciaux — et non les leads. **Dans `routing/`** :
le choix du commercial est sa responsabilite depuis F4, et la reattribution est ce meme choix
repris a la main.

**Ne jamais republier un message de routage.** Le tour de role n'est pas idempotent — rejouer
une attribution decale la rotation — et `LeadRoutingService` refuse deja un lead qui porte un
commercial (`LeadRoutingService.java:74`). Une reattribution manuelle est une **ecriture
directe assumee** par l'operateur, tracee ; pas un rejeu de file.

**La migration `V6` sert deux besoins d'un coup.** Une table `lead_action` (`lead_id`, `actor`,
`action`, l'ancien et le nouveau commercial, `reason`, `created_at`), ou `actor` est le sujet
du jeton JWT — l'operateur unique. Elle absorbe aussi le manque releve sur les rejeux :
`dead_letter` ne retient que `replayed_by` et `replayed_at`, jamais le resultat ni le motif. Un
rejeu ecrit desormais sa ligne dans le meme journal. La timeline de F9 a deja l'endroit ou les
afficher — c'est la raison de l'ordre.

**La limite a dire, pas a masquer.** Un lead `SYNCED` existe deja dans l'ERP avec l'ancien
commercial ; le reattribuer dans LeadFlow ne le corrige pas chez Dolibarr. Pousser le
changement demanderait une methode de plus sur le port `CrmConnector`, donc une extension du
contrat que tous les adaptateurs devraient honorer, et elle retomberait droit sur le trou
d'etape que F14 traite. F10 reattribue dans LeadFlow, **l'ecran le dit explicitement**, et la
propagation vers l'ERP reste un candidat nomme pour plus tard.

## F12 — Analytics et graphiques

_1 a 2 sessions. Touche `monitoring/`, une migration d'index, le frontend._

Le socle est solide : `StatsView` sert tout l'ecran en un appel — total, leads par statut,
evenements par statut, taux de conversion, repartitions par intention, par source d'intention
et par commercial — et ce sont de vraies requetes de comptage, pas un chargement d'entites.

**Ce qui manque n'est pas le comptage, c'est le temps.** Tous les agregats actuels sont des
instantanes ; les figures qui valent quelque chose sur un middleware asynchrone sont des series
et des latences :

- le volume capture par jour, et la part qui n'a jamais produit de lead (`DISCARDED`) ;
- le **delai capture → synchronisation ERP**, calculable des aujourd'hui entre
  `raw_lead_event.received_at` et le `crm_sync_attempt` reussi : c'est la metrique d'un
  middleware, et personne ne la voit ;
- la part Gemini / lexique dans le temps — le bandeau de F7.2 la donne en cumul, une courbe
  rendrait le mode degrade lisible d'un coup d'oeil ;
- le taux d'echec ERP par connecteur, deja servi par `ConnectorView`.

**La migration, la seule de cette feature** : les index existants sont
`(client_id, email, created_at)` et `(client_id, status)`, aucun ne sert un regroupement par
jour. Un index dedie sur `lead (client_id, created_at)` et son equivalent sur
`raw_lead_event`.

**Le choix de bibliotheque se fait ici et s'assume.** L'absence actuelle est un choix
documente ; F12 le leve. Recommandation : **Chart.js**, charge dans le seul chunk de la feature
pour ne pas alourdir le bundle initial, et bibliotheque de reference du plugin
`ui-ux-pro-max`. Toute la conception visuelle passe par ce plugin, le skill `dataviz` en
complement pour l'accessibilite des couleurs et la coherence clair/sombre.

**Le garde-fou** : pas de camembert a trois parts, pas de graphique qui redit un compteur deja
affiche. Chaque figure doit repondre a une question qu'aucun chiffre de l'ecran ne repond.

## F13 — Rotation HMAC a fenetre de transition

_1 session courte. Touche `tenant/`, `capture/`, une migration._

**Le defaut, concretement.** `ClientAdminService.tourneLeSecret` fait
`client.setHmacSecret(secret)` : le nouveau secret prend effet a l'instant du commit, et
`HmacSignatureVerifier.verifie(String secret, ...)` n'en connait qu'un seul. Or le site du
client continue de signer avec l'ancien jusqu'a ce que son developpeur redeploie. Entre les
deux, **chaque lead est refuse** — en `401`, indiscernable d'une attaque, et la boutique ne
s'en apercoit qu'en constatant que plus rien n'arrive.

**Le travail** : deux colonnes chiffrees de plus (`previous_hmac_secret`,
`previous_secret_expires_at`), un verificateur qui accepte l'un ou l'autre tant que la fenetre
court, et l'ecran de la boutique qui affiche « ancien secret valide jusqu'au ... » avec un
bouton de revocation immediate.

**Trois contraintes a tenir.** La reponse reste un `401` uniforme pour les cinq causes de
refus : la fenetre n'ajoute pas un sixieme message. La cle d'idempotence
`(client_id, signature)` n'est pas affectee — le HMAC calcule differe, mais la cle stockee
reste la forme canonique de l'en-tete. Et l'ancien secret **doit** expirer : une fenetre sans
fin, ce sont deux secrets permanents, soit le double de surface pour la meme porte.

## F14 — La limitation du pivot ERP

_1 session. Touche `crm/model`, `crm/`, les deux adaptateurs, une migration, et
`monitoring/dto`._

Le trou de perte silencieuse aura ete bouche en F11 par le contournement idempotent. **F14
traite la cause, pas le symptome** : `CrmSyncState` est un triplet de champs nommes, or
Dolibarr a quatre etapes et la quatrieme n'a nulle part ou se ranger. Au rejeu, le bloc garde
par `opportunite == null` est saute en entier, rattachement du responsable compris.

La tentation — un quatrieme champ — est precisement ce qu'il faut refuser : « rattachement du
responsable au projet » est une notion Dolibarr, Odoo posant `user_id` directement sur le
`crm.lead` a la creation. Faire remonter la forme d'un ERP dans le contrat commun est
exactement ce que l'invariant d'agnosticisme interdit. Le Javadoc de `DolibarrConnector` a deja
tranche : une **carte de references par etape**, dont **l'adaptateur** nomme les cles.

**Le vrai cout n'est pas le record, c'est la trace.** `crm_sync_attempt` a quatre colonnes
plates (`account_ref`, `contact_ref`, `opportunity_ref`, `task_ref`) et
`CrmSyncService.etatAnterieur()` (`CrmSyncService.java:97-109`) reconstruit l'etat champ par
champ, en remontant les tentatives et en prenant la valeur non nulle la plus recente. Passer a
une carte, c'est une colonne `jsonb`, la reprise de l'historique existant, et une
reconstruction qui devient une fusion de cartes.

**Une onde de choc a ne pas decouvrir en cours de route** : `SyncAttemptView` expose les quatre
references au dashboard et l'ecran de detail les affiche nommement. F14 touche donc aussi
`monitoring/dto` et le frontend. Detail revelateur : `task_ref` existe depuis `V2` et **aucun
adaptateur ne le remplit** — la place etait deja reservee a une etape qui n'existe pas encore.
Le nombre d'etapes n'a jamais ete trois.

---

## Recapitulatif

| Ordre | Feature | Taille | Migration | Package principal |
| --- | --- | --- | --- | --- |
| 1 | F8 — bareme reglable + badge chaud | 1 session courte | aucune | `tenant/`, `monitoring/` |
| 2 | F11 — deploiement, CI, durcissement | 2 a 3 sessions | aucune | racine, `config/`, `capture/` |
| 3 | F9 — timeline derivee | 1 session | aucune | `monitoring/` |
| 4 | F10 — reattribution + journal d'actions | 1 session | `V6` | `routing/` |
| 5 | F12 — analytics et graphiques | 1 a 2 sessions | index | `monitoring/`, frontend |
| 6 | F13 — rotation HMAC a fenetre | 1 session courte | une migration | `tenant/`, `capture/` |
| 7 | F14 — pivot ERP en carte de references | 1 session | `jsonb` | `crm/` |

`V6` est la prochaine migration a ecrire, celle de F10. Les suivantes se numerotent dans
l'ordre reel des sessions, F12 en comptant une pour ses index.

## Invariants que ces sept features ne doivent pas casser

Ils sont enonces dans `CLAUDE.md` et rappeles ici parce que chacune des sept les frole :

- **Le webhook ne fait aucun travail metier**, et les cinq causes de refus rendent le meme
  `401` (F11 avec le `429`, F13 avec la fenetre de transition).
- **`monitoring/` observe et n'ecrit que `dead_letter`** (F9, F12 — et c'est pourquoi F10 vit
  dans `routing/`).
- **Aucune entite JPA ne franchit la frontiere HTTP** ; toute reponse passe par un `record` de
  DTO (F8, F9, F10, F12).
- **Le modele pivot de `crm/model` ne contient aucun terme propre a un fournisseur** (F14, et
  la propagation d'assignation ecartee en F10).
- **Aucun ERP n'est cable en dur**, et ajouter un ERP reste trois gestes (F14).
- **Hibernate ne cree jamais de table** : toute evolution de schema est une migration Flyway
  numerotee (F10, F12, F13, F14).
- **Toute operation courante passe par l'interface**, jamais par du SQL (F8 en est la raison
  d'etre).

## Dettes connues que cette feuille de route ne traite pas

Elles restent assumees et documentees, et aucune des sept features ne les aggrave :

- **Mono-instance a trois endroits** : `PendingEventRelay`, le tour de role de F4 et le
  registre d'emetteurs SSE de `LeadStreamBroadcaster`.
- **`dead_letter` n'a aucune contrainte d'unicite** : la livraison etant at-least-once, une meme
  mort livree deux fois ecrirait deux lignes.
- **`DolibarrConnector` ne cherche pas de tiers existant par email** avant d'en creer un : deux
  leads du meme prospect separes par plus que la fenetre de deduplication produisent deux tiers
  dans l'ERP.
- **Le secret HMAC de la boutique de demonstration a ete tourne deux fois** pendant la recette
  de F7.2 : la valeur en clair documentee dans `R__demo_data.sql` n'est plus valide.

## Ce que ce document ne fait pas

Il ne remplace pas le brainstorming de chaque feature. Chaque ligne du recapitulatif ouvre sa
propre session : questions, design, spec, plan, puis implementation. Les decisions ecrites ici
sont celles qui engagent l'ordre ou un invariant — pas le detail de conception, qui se prend
avec le code sous les yeux.
