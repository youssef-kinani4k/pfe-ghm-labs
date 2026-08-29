# F8 — barème réglable et badge « chaud » : état de fin de session

Session du 29 août 2026. La feature est **complète, fusionnée dans `main` et poussée**. Il
reste la recette manuelle à l'écran, que l'utilisateur fait lui-même — elle n'a pas eu lieu
avant la fusion, à sa demande.

**Fusion :** `deb0867`, `merge: F8 — bareme reglable et badge « chaud »`, 38 fichiers,
+3627 lignes. L'arbre fusionné était identique octet pour octet au sommet de la branche
éprouvée (`git diff feature/f8-bareme-reglable main` vide), donc la suite verte s'y applique
sans relance.

**Poussé sur GitHub :** `main` (62 commits, `ce5da3d..deb0867`) et les quatre branches de
feature qui n'existaient que localement — `f6`, `f7`, `f7.2`, `f8`. Les dix branches locales
ont désormais leur équivalent distant ; l'historique par feature ne dépend plus du poste.

**Branche conservée**, comme les huit précédentes.

## Ce qui a été livré

| Commit | Tâche |
| --- | --- |
| `6f8aa29` | design de F8 + correction de la feuille de route |
| `87cd4e6` | plan d'implémentation en sept tâches |
| `ca3b001` | 1 — `ScoringForm`, `ScoringView`, test d'aller-retour |
| `1e96156` | 2 — `ScoringAdminService`, routes `GET`/`PUT` |
| `89261fa` | 3 — bornes de validation `[0, 100]`, 404 sur boutique inconnue |
| `66d5227` | 3 bis — revue différée : contraintes de conteneur éprouvées, plancher du maximum |
| `b0d8ce5` | 4 — badge et filtre « chaud » côté lecture |
| `5c39865` | 5 — écran `boutiques/:id/bareme` |
| `a5df885` | 6 — pastille et case « chauds seulement » dans la liste |

## La dette de la session précédente, soldée

La tâche 3 avait été commitée **sans revue**, la session s'étant arrêtée. La revue a été faite
à la reprise (`.superpowers/sdd/2026-08-29-f8-bareme-reglable/review-task-3.md`) et a produit
deux corrections, toutes deux dans `66d5227` :

**Trois contraintes sur cinq n'étaient éprouvées par aucun test** — celles qui portent sur des
éléments de conteneur (`Map<LeadIntent, @Min(0) Integer>`, `Set<@Pattern String>`). C'est le
cas où une contrainte peut compiler sans jamais s'appliquer : `@Min` porte `TYPE_USE` dans son
`@Target`, donc l'écrire sur un argument de type est toujours accepté. `ScoringContraintesTest`
les éprouve directement sur le validateur, sans contexte Spring. **Vérification faite : elles
s'appliquent bien** — c'était une lacune de couverture, pas un bug vivant, mais le Javadoc
affirmait une garantie que rien ne tenait.

**`ScoringView.de` rendait un maximum négatif** pour un document à poids négatifs : les bornes
de `ScoringForm` ne tiennent qu'à l'écriture, et la lecture passe par `ScoringConfig.depuis`,
tolérante par construction. Le maximum est maintenant encadré des deux côtés comme
`LeadScorer` encadre le score qu'il rend. Sans le plancher, le test ajouté rend `-155`.

## Les décisions qui portent la feature

**Le badge est calculé à la lecture, jamais stocké.** Le figer à la qualification ferait mentir
la liste dès qu'une boutique déplace son seuil : les leads déjà écrits garderaient l'ancien
verdict. C'est la décision dont tout le reste découle.

**La comparaison ne peut pas se faire en SQL.** `client.scoring_config` est chiffré au repos,
donc illisible par Postgres, et `Lead` ne porte aucune association vers `Client`. Le filtre
`?chaud=true` est donc une disjonction de couples *(boutique, seuil)* et le drapeau se pose en
Java, sur les boutiques de la page — déjà chargées pour résoudre leur nom, d'où aucune requête
supplémentaire.

**Le filtre n'a pas de négation.** `LeadQuery.chaud` est typé `true` et non `boolean` côté
client, et la case décochée vaut `undefined` : la boucle de construction des paramètres ne
saute que `undefined`, `null` et la chaîne vide, donc un `false` serait parti dans l'URL et
aurait ajouté un prédicat. « Pas seulement les chauds » veut dire « tous ».

**Un seuil hors de portée est signalé, pas refusé.** C'est un état légitime le temps de monter
les poids ; le refuser empêcherait de régler le barème dans l'ordre qu'on veut. D'où le jeton
d'attente et non celui d'échec, à l'écran comme dans l'API.

**Le barème s'applique au prochain lead, jamais rétroactivement.** `lead.score` est figé à la
qualification. Ce qui bouge immédiatement, c'est le badge. C'est voulu, et c'est exactement ce
que la recette doit montrer.

## Choix visuels, et par où ils sont passés

Les skills `ui-ux-pro-max:ui-styling` et `:design-system` ont été invoqués avant chaque
template, comme l'exige la règle du projet. Ils sont orientés React/Tailwind/shadcn ; ce qui
s'est transposé, c'est la discipline de jetons et l'accessibilité. **Aucun jeton nouveau n'a
été créé** : l'avertissement réutilise la paire `--lf-attente` / `--lf-attente-fond` et
l'idiome `bandeau` de l'écran Paramètres.

Deux décisions méritent d'être retenues. La pastille **n'est pas un `app-status-badge`** :
ce composant associe des valeurs d'énumération du backend à un ton, et y glisser une clé
inventée pour un booléen dérivé l'aurait fait dériver de son rôle. Et elle vit **dans la
cellule du score** plutôt que dans une colonne à elle : « chaud » est exactement
« score ≥ seuil », les séparer obligerait à relier deux colonnes du regard, et la table en
porte déjà huit.

Les listes cibles sont des `mat-chip-grid` — premier usage de ce module dans le projet. Chaque
valeur devient un objet distinct et retirable, ce qui correspond au `Set` du backend et rend
visible la règle de correspondance exacte. `addOnBlur` est actif : sans lui, une étiquette
tapée mais non validée par Entrée disparaissait au moment d'enregistrer.

## Vérifications

- Suite backend complète en fin de session : **375 tests, 0 échec, 0 erreur**, 70 classes,
  15 min 09. Les six tests de plus qu'au tour précédent (369) sont ceux de `LeadChaudTest`.
- Frontend : **44 tests, 0 échec**, `npm run build` vert, `bareme` en chunk lazy séparé.
- Les deux tests neufs ont été éprouvés contre une implémentation naïve : un seuil global fait
  échouer 3 des 6 tests de `LeadChaudTest`, et le test du plancher rend `-155` sans le
  correctif.

## Un détail d'environnement, à connaître

L'Angular CLI écrit un identifiant de télémétrie dans `frontend/angular.json` (`"analytics":
"…"`) dès qu'on lance `npm test` ou `npm run build`. Ce n'est pas une modification voulue et
elle ne doit pas entrer dans un commit : `git checkout -- frontend/angular.json` avant de
committer. Le cas s'est présenté juste avant la fusion.

## La prochaine session

L'ordre de la feuille de route est **F11 → F9 → F10 → F12 → F13 → F14**. La suivante est donc
**F11 — déploiement, CI, durcissement**, estimée à 2 ou 3 sessions, sans migration. C'est le
seul chantier sans écran : il n'y a ni intégration continue, ni image de production, et CORS
n'autorise toujours que `http://localhost:4200`. Elle porte aussi le contournement de
`lieResponsable`, le trou de perte silencieuse de l'adaptateur Dolibarr.

`V6` reste la prochaine migration à écrire, mais elle appartient à F10.

## Ce qui reste ouvert

**La recette manuelle à l'écran est à faire par l'utilisateur**, en fin de feature. Ce qu'elle
doit montrer, et que rien d'automatisé ne prouve à sa place : régler le barème de la boutique
de démonstration, descendre le seuil, recharger la liste des leads, et **voir les pastilles se
déplacer sans qu'aucun score n'ait changé**. C'est la démonstration de la feature.

Pour la suite :

- **Le badge reste passif.** `seuilChaud` a enfin un consommateur, mais personne n'est prévenu :
  un lead chaud se voit si quelqu'un regarde l'écran. Aucune feature de F8 à F14 ne couvre la
  notification : la feuille de route l'assume explicitement comme une conséquence à porter en
  soutenance.
- **Aucun recalcul rétroactif des scores.** Changer un barème ne touche pas les leads déjà
  qualifiés. C'est voulu, mais cela surprend — à dire dans l'aide de l'écran si la question
  revient.
- **La cardinalité des listes cibles n'est pas bornée.** `@Size(max = 80)` borne la longueur
  d'un secteur, rien ne borne leur nombre. Sans gravité à l'échelle d'une agence, relevé par la
  revue de la tâche 3.
- **`seuils()` charge toutes les boutiques** quand le filtre `chaud` est posé sans `clientId`.
  Acceptable à l'échelle d'une console d'agence, et c'est le seul moyen : le seuil n'est pas
  requêtable en SQL.
