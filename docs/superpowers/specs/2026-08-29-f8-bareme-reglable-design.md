# F8 — Le bareme de scoring reglable, et le badge « chaud »

_Design valide le 29 aout 2026. Feuille de route :
`2026-08-29-feuille-de-route-f8-f14-design.md`, feature 1 sur 7._

## Le probleme

`client.scoring_config` gouverne la note de chaque lead, et **il ne se regle qu'en base**.
`ScoringConfig` fixe pourtant la forme du document depuis F3, `LeadScorer` l'applique, la
colonne existe : il ne manque que la porte d'entree. C'est la derniere operation courante du
produit qui demande un client Postgres, alors que la regle du projet est que tout se fasse
depuis l'interface.

Corollaire : `ScoringConfig.seuilChaud` est lu et porte depuis F3 **sans qu'aucun code ne
s'en serve**. F8 lui donne un consommateur visible — un badge — plutot qu'un systeme de
notification qui supposerait des comptes commerciaux que le produit n'a pas.

## Perimetre

Dans le perimetre :

- deux routes d'administration pour lire et ecrire le bareme d'une boutique ;
- un ecran dedie pour le saisir ;
- un badge « chaud » sur la liste des leads et sur la fiche d'un lead, plus un filtre.

Hors perimetre, decide explicitement :

- **aucun recalcul des leads deja notes** — un score enregistre est ce que le bareme disait
  au moment de la qualification, et il ne se reecrit pas ;
- **aucun apercu ni simulation** avant enregistrement ;
- **aucune notification** au commercial : il n'a pas de compte, et lui en donner un est hors
  perimetre du produit entier ;
- **aucun tri par temperature ni compteur de leads chauds sur le dashboard** — ce serait F12
  avant l'heure.

## Les decisions, et leurs raisons

### Le score est fige, le badge est vivant

Un lead garde le score calcule a sa qualification. Le badge, lui, compare **ce score fige au
seuil courant** : deplacer le seuil deplace les badges au rechargement suivant, sans qu'aucune
ligne de `lead` ne soit reecrite.

C'est ce qui rend le reglage observable sans rien detruire. Un recalcul automatique aurait
rendu l'historique reecrivable — un lead passant de 80 a 30 sans que rien ne le dise — et une
boutique a cent mille leads aurait bloque la requete HTTP. Un bouton « recalculer » aurait
demande d'ecrire dans `lead` depuis un ecran d'administration, de tracer qui a recalcule quoi,
et de decider si les leads deja synchronises doivent etre remis a jour dans l'ERP : une
feature a part entiere, pas un detail de F8.

### `LeadScorer` borne le total dans `[0, 100]`, et cela gouverne toutes les bornes

Le bornage n'est pas defensif : il rend le score comparable entre deux clients dont les
baremes different. Consequence directe pour F8 : **un poids superieur a 100 n'a aucun effet
observable, et un seuil superieur au maximum atteignable rend tout lead eternellement tiede**.
Les bornes de validation et l'avertissement de l'ecran en decoulent, ils ne sont pas
arbitraires.

### Le cycle de packages est assume, et verrouille par un test

`ScoringConfig` vit dans `qualification/` et `qualification/LeadQualificationService` importe
deja `tenant/`. Faire lire `ScoringConfig` par `tenant/` cree donc un cycle entre les deux
packages.

Deux alternatives ont ete pesees et ecartees : deplacer la classe — elle est chez elle la ou
le scoreur l'applique — et redeclarer les noms de cles dans le formulaire, ce qui garantit une
derive le jour ou une cle change. **Decision : assumer l'import, et poser un test
d'aller-retour** `ScoringForm` → `Map` → `ScoringConfig.depuis()` qui doit rendre exactement
les valeurs saisies. C'est ce test, et non la structure des packages, qui empeche la derive.

### Le badge et le filtre : seuils charges en Java, un predicat par boutique

`LeadQueryService` charge les boutiques concernees en une requete, en tire une
`Map<UUID, Integer>` de seuils via `ScoringConfig.depuis`, s'en sert pour poser le drapeau
`chaud` sur chaque ligne rendue, et pour batir le filtre :
`OR( clientId = X ET score >= seuil(X), ... )`. Quand une boutique est deja selectionnee — le
cas courant — le `OR` se reduit a un seul terme. Aucune requete par ligne.

Deux autres voies ont ete examinees :

- **une fonction JSON dans la requete Criteria** (`function('jsonb_extract_path_text', ...)`
  sur une sous-requete vers `Client`) : une seule requete, mais elle colle `monitoring/` au
  format du document **et** a Postgres, dans une classe dont la regle enoncee est « un
  predicat par filtre present, et rien pour les filtres absents ». `Lead` ne porte d'ailleurs
  pas d'association vers `Client`, seulement un `clientId`, et `scoringConfig` passe par
  `@JdbcTypeCode(SqlTypes.JSON)` : la clause n'est pas ecrivable telle quelle dans le Criteria
  existant ;
- **une colonne `lead.hot` ecrite a la qualification** : elle figerait le badge au bareme du
  jour de la capture, ce qui contredit la decision precedente.

## Le contrat backend

### `GET /api/admin/clients/{id}/scoring`

Rend un `ScoringView` portant **les valeurs effectives** — defauts compris. Un document vide
en base ne rend pas un formulaire vide : il rend le bareme par defaut, celui que le scoreur
applique reellement. L'ecran ne doit pas mentir sur ce qui est en vigueur.

`ScoringView` porte en plus trois elements calcules :

- `scoreMaximum` : `min(100, poids de presence + meilleur poids d'intention + bonus)` ;
- `seuilInatteignable` : vrai quand `seuilChaud > scoreMaximum` ;
- `defauts` : le bareme par defaut, pour que le bouton « revenir aux valeurs par defaut » soit
  un remplissage local et non une route de plus.

### `PUT /api/admin/clients/{id}/scoring`

Prend un `ScoringForm` valide et **remplace** le document. Pas de `PATCH` : le bareme est un
petit objet complet, et une fusion partielle rendrait indecidable la difference entre « poids
absent » et « poids remis a zero ». Rend le `ScoringView` recalcule.

Validation : les quatre poids de presence, les cinq poids d'intention et le bonus dans
`[0, 100]` ; `seuilChaud` dans `[0, 100]` ; codes pays sur deux lettres. Un seuil superieur au
maximum atteignable **n'est pas une erreur** — c'est un choix legitime en attendant de monter
les poids — mais il est signale par `seuilInatteignable`.

La lecture reste tolerante des deux cotes : un document deja en base malforme, partiel ou
portant des cles inconnues donne les defauts a l'ecran, jamais une erreur.

### Ce que les DTO deviennent

`LeadSummary` et `LeadDetail` gagnent un `boolean chaud`, calcule a la lecture. `LeadFilter`
gagne un `Boolean chaud`, et `LeadSpecifications` le predicat correspondant — construit a
partir de la carte de seuils, pas d'une clause JSON.

## L'ecran

Route `/boutiques/:id/bareme`, entree `loadComponent` dans `app.routes.ts` — donc un chunk
separe, verifiable dans la sortie de `npm run build` — et un bouton depuis la fiche de la
boutique. La fiche porte deja quatre sujets sur 359 lignes de template : le bareme n'y est pas
ajoute.

Quatre blocs :

1. **Poids de presence** : telephone, societe, nom, message.
2. **Poids par intention** : un champ par valeur de `LeadIntent`, `AUTRE` comprise. Les cinq
   sont affiches meme a zero — un bareme dont une intention manque a l'ecran serait un bareme
   qu'on croit connaitre.
3. **Ciblage** : secteurs et pays en saisie de tags, plus le bonus. L'ecran **montre la
   normalisation** au lieu de la subir — secteurs en minuscules, pays en majuscules a deux
   lettres — et rappelle les deux regles qui surprennent : le bonus **ne se cumule pas** (bon
   secteur *et* bon pays donnent un seul bonus) et la correspondance de secteur est
   **exacte**, jamais par prefixe.
4. **Seuil « chaud »** : a cote, le score maximum atteignable et, quand le seuil le depasse,
   l'avertissement « aucun lead ne pourra etre chaud ».

Le maximum **se recalcule en direct a la saisie**, cote client. C'est une somme bornee a 100,
pas une reimplementation du bareme : ni normalisation, ni logique de ciblage, aucune des
regles qui font `LeadScorer`. La valeur rendue par le serveur reste la reference et reprend la
main apres enregistrement.

Le badge se montre a deux endroits, et deux seulement : une pastille dans la liste des leads,
la meme sur la fiche d'un lead, plus une case « chauds seulement » dans la barre de filtres
existante.

**Toute la conception visuelle** — pastille, blocs du formulaire, saisie de tags, ton de
l'avertissement — passe par les skills du plugin `ui-ux-pro-max`, invoques **avant** d'ecrire
le template.

## Les tests

Backend, aux conventions du projet : `@SpringBootTest` avec `MockMvc` et Testcontainers, et
les assertions portent **sur le corps JSON**, jamais sur le DTO.

| Test | Ce qu'il verrouille |
| --- | --- |
| `ScoringAllerRetourTest` | `ScoringForm` → `Map` → `ScoringConfig.depuis()` rend exactement les valeurs saisies. Le garde-fou du cycle de packages. |
| `ScoringAdminTest` | `GET` sur un document vide rend les defauts et non un objet vide ; `PUT` puis `GET` rend ce qui a ete ecrit ; un document malforme deja en base rend les defauts sans erreur 500. |
| `ScoringValidationTest` | Poids et seuil hors `[0, 100]` rendent `400` ; boutique inconnue rend `404` ; un seuil inatteignable est accepte et signale. |
| `LeadChaudTest` | Deux boutiques de seuils 50 et 90, un lead a 60 : chaud chez l'une, tiede chez l'autre. Badge et filtre doivent tous deux le refleter, **y compris sans `clientId` dans la requete** — c'est le cas qui casse une implementation naive. |

Frontend, Karma et Jasmine : chargement du formulaire, avertissement quand le seuil depasse le
maximum, enregistrement, et affichage de la pastille dans la liste.

Ce qui n'est **pas** teste ici : que `LeadScorer` applique correctement le bareme. F3 le couvre
deja. F8 n'ajoute aucune regle de calcul, elle ajoute une porte d'entree.

## Ce que F8 ne casse pas

- **Aucune entite JPA ne franchit la frontiere HTTP** : `ScoringView` et `ScoringForm` sont des
  `record`, et l'assertion des tests porte sur le JSON.
- **`monitoring/` reste un observateur** : il lit `client` pour connaitre les seuils, il
  n'ecrit rien. L'ecriture du bareme vit dans `tenant/`, qui gouverne deja les boutiques.
- **Aucune migration** : la colonne `scoring_config` existe depuis `V2`, et elle n'est pas
  chiffree — contrairement a `hmac_secret` et `crm_config`.
- **`ddl-auto: validate`** n'a rien a valider de nouveau.
