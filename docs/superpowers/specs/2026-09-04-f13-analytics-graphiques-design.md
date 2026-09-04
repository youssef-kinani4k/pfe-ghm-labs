# F13 — Analytics et graphiques

_Design valide le 4 septembre 2026. Succede a F12 (notification du commercial)._

## Ce que cette feature livre

Le monitoring ne sait dire que le present. `StatsView` sert tout l'ecran d'accueil en un
appel — total, leads par statut, evenements par statut, taux de conversion, repartitions par
intention, par source d'intention et par commercial — et ce sont de vraies requetes de
comptage. **Ce qui manque n'est pas le comptage, c'est le temps.**

F13 ajoute un ecran « Analyse » portant trois series quotidiennes, et leve le choix documente
de n'avoir aucune bibliotheque de graphiques.

## Note de numerotation

La feuille de route `2026-08-29-feuille-de-route-f8-f14-design.md` appelait cette feature
**F12**. Le creneau F12 a ete pris par la notification du commercial, livree le 4 septembre.
Les trois features restantes de la feuille de route se decalent donc d'un cran : **F13**
analytics, **F14** rotation HMAC, **F15** pivot ERP en carte de references. C'est le meme
decalage qui avait fait passer la migration de F10 de `V6` a `V7`.

## Les trois figures, et ce qu'elles repondent

Le garde-fou de la feuille de route est tenu : **aucune figure ne redit un compteur deja
affiche**, et chacune repond a une question qu'aucun chiffre de la console ne repond.

| Carte | Forme | Question |
| --- | --- | --- |
| Volume capture | aire empilee : leads produits / ecartes | « en perd-on plus qu'avant ? » |
| Delai capture -> ERP | deux lignes : mediane et p95, en secondes | « le pipeline tient-il la charge ? » |
| Analyse d'intention | aire empilee a 100 % : Gemini / lexique | « le mode degrade s'est-il installe ? » |

**Le taux d'echec ERP par connecteur est ecarte**, bien que la feuille de route le listat :
`ConnectorView` le sert deja a l'ecran des connecteurs. L'ajouter aurait viole le garde-fou.

**Le p95 est une ligne distincte, pas une bande autour de la mediane** : une bande se lit
comme un intervalle de confiance, ce que ce n'est pas.

## Le contrat

```
GET /api/stats/series?clientId=<uuid>&jours=7|30|90
```

`jours` est **borne cote serveur** a ces trois valeurs, `400` sinon. Sans borne, un appel a
100 000 jours ferait balayer la table entiere ; c'est le reflexe qui a donne sa pagination a
la liste des leads.

`clientId` reste un **filtre de requete**, jamais une donnee portee par le jeton. Le
dashboard est une console d'agence : un seul modele d'utilisateur, aucun role. Ouvrir la
console aux clients finaux demanderait d'abord de porter le tenant dans le jeton, et ce n'est
pas une extension de l'existant.

`SeriesView` porte trois listes, dans `monitoring/dto/` comme toute reponse de ce package —
aucune entite JPA ne franchit la frontiere HTTP, et le test l'asserte sur le corps JSON.

| Record | Champs | Source |
| --- | --- | --- |
| `PointVolume` | `jour`, `captures`, `ecartes` | `raw_lead_event` groupe par jour de `received_at` ; `ecartes` = les `DISCARDED` |
| `PointDelai` | `jour`, `medianeSecondes`, `p95Secondes` | requete native, ancree sur le jour de la **synchronisation** |
| `PointIntention` | `jour`, `gemini`, `lexique` | `lead.intent_source` groupe par jour de `created_at` |

`jour` est une `LocalDate`, serialisee en `YYYY-MM-DD` — une date, pas un instant : le point
represente une journee entiere du fuseau de regroupement, et rendre un `Instant` laisserait
croire a une precision qui n'existe pas. Les deux mesures de `PointDelai` sont des `Double`
et non des `double`, seuls champs des trois records dans ce cas : `null` y est une valeur
signifiante, et le type primitif la lierait a zero.

## Le calcul

### Le monitoring reste un observateur

`SeriesRepository` etend `Repository` nu, comme `StatsRepository` et `LeadQueryRepository` :
aucune methode d'ecriture n'est meme exposee. F13 ne touche ni `lead`, ni `raw_lead_event`,
ni `crm_sync_attempt` autrement qu'en lecture. C'est la propriete qui rend cet observateur
sur, et elle ne doit pas bouger.

### Trois requetes natives, et pourquoi

`SeriesRepository` est le **premier repository natif du projet**. Le reste du monitoring est
en JPQL, et ce n'est pas une preference : deux contraintes independantes ferment cette porte.

**Le regroupement par jour dans un fuseau ne s'exprime pas en JPQL.** Il n'y a ni `date_trunc`
ni `AT TIME ZONE` dans le langage, et la seule alternative — poser
`hibernate.jdbc.time_zone` — est globale : elle changerait la facon dont tout le projet lit et
ecrit ses `Instant`, pour resoudre un besoin de trois requetes. Ce reglage n'est pas touche.

**`percentile_cont` n'existe pas non plus en JPQL**, ce qui condamnait deja la requete des
delais a elle seule. Ses deux alternatives ont ete pesees et refusees :

- `avg`/`min`/`max` en JPQL resterait dans le style existant, mais **la moyenne ment sur une
  latence** : un seul ERP en timeout a 30 s ecrase la lecture d'une journee normale a 2 s.
  C'est precisement la figure qu'on veut, mal mesuree.
- Calculer les percentiles en Java serait portable, mais ferait payer a l'ecran le volume de
  la table — ce que le Javadoc de `StatsRepository` interdit explicitement.

La non-portabilite hors Postgres est deja acquise ailleurs (`jsonb`, index partiels).

Le prix reel du natif est ailleurs : **Hibernate ne valide plus la requete au demarrage**, et
une colonne renommee ne se verra qu'a l'execution. C'est ce qui rend les tests de F13
obligatoirement des `@SpringBootTest` contre un vrai Postgres, et non un choix de confort.

### Le chemin du delai

`raw_lead_event.received_at` -> `lead.raw_event_id` -> `crm_sync_attempt.lead_id` avec
`status = 'SUCCESS'`.

La jointure se fait **par identifiant, pas par association** : `CrmSyncAttempt` n'en porte
aucune vers `Lead`, choix de F5 qui garde l'adaptateur ignorant du pipeline.
`SyncActivityRepository` fait deja exactement cela.

**Le premier succes par lead, jamais le dernier.** `min(attempted_at)` sur les succes : un
rejeu ajoute un succes tardif, et le lire ferait afficher trois jours de delai pour un lead
synchronise en deux secondes.

### L'ancrage du point du jour J

Le point agrege les leads **synchronises** ce jour-la, pas ceux captures. Deux raisons : le
point est definitif des que le jour est passe, tout ce qui y est compte etant deja arrive au
bout ; et l'ancrage sur la capture ferait **s'ameliorer la courbe quand ca va mal**, puisqu'un
lead jamais synchronise n'y apparaitrait jamais.

Consequence assumee : cette courbe et celle du volume n'ont pas le meme ancrage, et un pic de
capture ne se retrouve pas le meme jour sur la courbe des delais.

### Le fuseau de regroupement

`received_at` et `attempted_at` sont des `TIMESTAMPTZ`, et `date_trunc('day', ...)` decoupe
selon le fuseau de la session — UTC dans le conteneur. Le regroupement se fait donc
explicitement en **`Europe/Paris`**, fuseau pose en constante dans `config/` et non ecrit en
dur dans trois requetes.

Sans cela, un lead recu a 00 h 30 heure locale tomberait dans la journee de la veille : un
decalage silencieux que personne ne rattache jamais a sa cause. Le prix assume est que le
fuseau est **global a l'instance**, pas porte par la boutique — comme les reglages du relais
SMTP de F12.

### Les trous sont combles par le service, pas par SQL

Une journee sans donnee n'a pas de ligne, et un graphique tracerait une droite par-dessus. Le
service produit exactement `jours` points, et **la valeur de comblement differe selon la
figure** :

- volume et intentions : **zero** — « aucun lead capture ce jour-la » est un fait ;
- delais : **`null`** — « aucun lead synchronise ce jour-la » ne veut pas dire « delai de zero
  seconde ». Chart.js interrompt la ligne sur un `null` ; la mettre a zero dessinerait une
  chute vers le bas, soit l'inverse du sens.

C'est le point le plus facile a « simplifier » plus tard en mettant zero partout, et il a son
test.

## La migration `V10`

**Un seul des deux index annonces par la feuille de route est necessaire**, et un autre qu'elle
ne prevoyait pas l'est.

`idx_raw_lead_event_client_received (client_id, received_at DESC)` existe depuis `V2` et sert
deja la courbe de volume. Il n'y a rien a creer de ce cote.

```sql
-- La serie de volume et celle des intentions balaient `lead` par date. L'index existant
-- est (client_id, email, created_at) : la colonne `email` au milieu le rend inutilisable
-- pour un regroupement par jour.
CREATE INDEX idx_lead_client_created ON lead (client_id, created_at DESC);

-- La serie des delais balaie les succes sur une plage de dates, toutes boutiques
-- confondues. L'index existant est (lead_id, attempted_at) : il sert la fiche d'un lead,
-- pas un balayage par date. Partiel, parce que la requete ne lit que les succes.
CREATE INDEX idx_crm_sync_attempt_success_at ON crm_sync_attempt (attempted_at DESC)
    WHERE status = 'SUCCESS';
```

L'index partiel suit le motif de `V8` (`uq_dead_letter_lead_pending`) et de `V4`.

**Aucune colonne, aucune table.** F13 ne fait que lire ce qui existe, comme la chronologie de
F9 qui recompose six tables sans en creer aucune.

## L'ecran

### Une route de plus, un chunk de plus

`/analyse` en `loadComponent`, comme les onze autres routes. Chart.js vit donc dans le chunk
de la feature et **n'entre jamais dans celui de l'accueil**, verifiable dans la sortie de
`npm run build`.

```
features/analyse/
├── analyse.ts        etat, periode, boutique, appel API
├── analyse.html      trois cartes, une par figure
├── analyse.scss
└── graphique-ligne/  le seul composant qui connaisse Chart.js
```

### Un seul composant connait la bibliotheque

`graphique-ligne` recoit des libelles, des series et une palette, et rend un `<canvas>` ;
`analyse.ts` n'importe jamais Chart.js. C'est ce qui rend la bibliotheque remplacable et le
reste de l'ecran testable sans elle — meme geste que `CrmConnector` derriere son port, a une
autre echelle.

Il **detruit son instance Chart au `ngOnDestroy`** : sans cela, chaque navigation vers l'ecran
en laisse une vivante, avec son ecouteur de redimensionnement.

### Chart.js, et sans wrapper Angular

Recommandation de la feuille de route et bibliotheque de reference du plugin
`ui-ux-pro-max`, qui sait donc la styler. Elle est **empaquetee par le build, jamais chargee
d'un CDN** : la CSP de production dit `script-src 'self'`, exactement la contrainte qui avait
fait rapatrier les polices en F11.2.

**Pas de `ng2-charts`.** Un wrapper ajouterait une peerDependency a faire correspondre a
chaque montee d'Angular — precisement la crainte inscrite dans le Javadoc du dashboard, qui
justifiait l'absence de bibliotheque.

### L'etat vide est un vrai etat

Une instance neuve, ou une boutique sans lead sur la periode, affiche un message qui le dit.
Un canvas plat se lit comme une panne. Meme chose quand aucun lead n'a ete synchronise : la
ligne des delais s'interrompt, et une legende explique pourquoi.

### Le visuel passe par le plugin

Palette, typographie et mise en page se decident via `ui-ux-pro-max`, invoque **avant**
d'ecrire le code et non en relecture, avec le skill `dataviz` en complement pour
l'accessibilite des couleurs. Les couleurs des series se derivent des jetons `--lf-*`
existants ; choisies a part, l'ecran d'analyse ne ressemblerait pas au reste de la console.
Il n'y a pas de theme sombre dans le dashboard — une seule palette a tenir.

### Les commandes

Le selecteur de boutique est celui du dashboard, reutilise tel quel (`ClientApi`,
`?clientId=`). La periode est un groupe de trois boutons, **30 jours par defaut** : assez pour
voir une tendance, assez court pour qu'une instance de demonstration ait des donnees.

## Les tests

`SeriesServiceTest` est un `@SpringBootTest` avec `TestcontainersConfiguration`, comme
`StatsServiceTest`. Ce n'est pas un choix de confort : **une requete native a `percentile_cont`
ne se teste pas contre un mock**.

| Ce qui est verrouille | Pourquoi ce test existe |
| --- | --- |
| Les trous combles, **volume a zero mais delai a `null`** | la regle la plus facile a « simplifier » en mettant zero partout |
| Le **premier** succes par lead | sans `min(attempted_at)`, un rejeu ferait afficher trois jours pour un lead synchronise en deux secondes |
| Le decoupage tombe en `Europe/Paris` | un lead a 00 h 30 locale appartient a la bonne journee |
| Exactement `jours` points, et `400` hors {7, 30, 90} | la borne serveur, et la forme de la serie |
| Le filtre `clientId` isole les boutiques | meme exigence multi-tenant que partout ailleurs |

`SeriesControllerTest` asserte **sur le corps JSON**, pas sur le DTO : un test sur le record ne
prouverait pas qu'aucune entite ne franchit la frontiere HTTP.

Cote frontend, `analyse.spec.ts` avec `HttpTestingController` verifie que periode et boutique
se traduisent en parametres, et que **l'etat vide s'affiche au lieu d'un graphique plat**.
`graphique-ligne` est teste sur ce qu'il recoit ; Chart.js n'est pas exerce dans Karma — on ne
teste pas la bibliotheque d'un tiers.

Le rendu du canvas releve de la recette a l'ecran, qui revient a l'utilisateur.

## Les documents a mettre a jour

Sans eux, la feature est a moitie livree.

- `docs/monitoring-api.md` — le contrat de `/api/stats/series`, avec un `curl` et une reponse,
  comme les autres endpoints.
- `CLAUDE.md` — retirer « **Aucun graphique** » de la liste « ce qui n'existe pas », et
  documenter le fuseau de regroupement ainsi que la dixieme migration.
- Le Javadoc de `StatsView`, qui affirme aujourd'hui « aucune serie temporelle : sans
  bibliotheque de graphiques, elle n'aurait aucun consommateur ». Cette phrase devient fausse
  et doit dire ou les series vivent desormais.

## Invariants que F13 ne doit pas casser

- **Le monitoring lit et n'ecrit que `dead_letter`.** `SeriesRepository` etend `Repository` nu.
- **Aucune entite JPA ne franchit la frontiere HTTP.** Tout passe par `monitoring/dto/`.
- **Le tenant est un filtre de requete**, jamais une donnee du jeton.
- **Aucune ressource tierce a l'execution.** Chart.js est empaquete, la CSP dit
  `script-src 'self'`.
- **Le chunk d'accueil ne s'alourdit pas.** La feature est en `loadComponent`.

## Ce que F13 ne fait pas

- **Aucun export** (CSV, PNG) des figures. Personne ne l'a demande.
- **Aucun grain autre que le jour.** Un grain automatique heure/jour/semaine ferait du grain un
  parametre du contrat, avec trois formes a tester par requete et des trous a combler
  differents selon le grain.
- **Aucune plage de dates libre.** Trois boutons ; rien dans la console ne demande aujourd'hui
  de dates a l'operateur.
- **Aucun recalcul retroactif des scores.** C'est un chantier a part, toujours ouvert.
- **Aucun graphique au dashboard d'accueil**, qui garde son role : l'etat instantane plus le
  flux temps reel.
