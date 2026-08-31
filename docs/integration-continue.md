# Intégration continue

LeadFlow a une CI GitHub Actions depuis F11.3. Elle tient dans **un seul fichier**,
`.github/workflows/ci.yml`, et porte **quatre jobs**. Ce document dit quand chacun part, ce
qu'il faut configurer (rien), comment lire un échec, et — le plus important — **ce que la CI
ne couvre pas**.

## Les quatre jobs

| Job | Ce qu'il fait | Quand il part | Durée observée |
| --- | --- | --- | --- |
| `backend` | `./mvnw --batch-mode verify` en Temurin 21, cache Maven | chaque push, chaque PR | ~5 min |
| `frontend` | `npm ci`, tests Karma en ChromeHeadless, `npm run build` | chaque push, chaque PR | ~50 s |
| `qualite` | `npm run lint` puis `npm run format:check` | chaque push, chaque PR | ~25 s |
| `fumee` | monte la pile de production entière et la traverse par `scripts/smoke-prod.sh` | voir ci-dessous | ~2 min 30 |

Les trois premiers sont indépendants et tournent en parallèle.

### Pourquoi `fumee` écoute `feature/**` et `fix/**`

`fumee` ne part que sur `main`, sur une pull request, **ou sur une branche `feature/**` ou
`fix/**`**. Cette dernière condition n'est pas de la générosité : **les fusions de ce projet
se font localement en `--no-ff`**, jamais par une pull request. Une CI déclenchée sur les
seules pull requests ne parlerait donc jamais, et une CI déclenchée sur `main` seulement
n'apprendrait la panne qu'**après** la fusion.

Il dépend de `backend` et `frontend` (`needs`) : il reconstruit les images depuis les
sources, donc il recompile le backend de toute façon, et il serait absurde de payer ces
minutes pour apprendre ce qu'un test unitaire vient de dire. **`qualite` n'y figure pas**,
meme depuis qu'il bloque : un defaut de format ne dit rien de la sante de la pile, et le
faire attendre allongerait le retour pour rien.

Une exécution est annulée si une seconde poussée arrive sur la même branche
(`concurrency` + `cancel-in-progress`).

## Il n'y a rien à configurer dans les réglages du dépôt

**Aucun secret GitHub n'est configuré, et aucun n'est requis.** Le job `fumee` fabrique
`.env.prod` à chaque exécution, à partir de valeurs qu'il tire lui-même :

| Variable | Origine |
| --- | --- |
| `POSTGRES_PASSWORD` = `DB_PASSWORD` | `openssl rand -hex 24` |
| `RABBITMQ_DEFAULT_PASS` = `RABBITMQ_PASSWORD` | `openssl rand -hex 24` |
| `LEADFLOW_MASTER_KEY` | `openssl rand -base64 32` |
| `LEADFLOW_JWT_SECRET` | `openssl rand -base64 48` |
| `SMOKE_PASSWORD` | `openssl rand -hex 16`, gardé en variable de job |
| `LEADFLOW_ADMIN_PASSWORD_HASH` | `htpasswd -bnBC 10` sur ce mot de passe, `$` doublés |
| `WEB_PORT` | `8088` |
| `GEMINI_API_KEY` | vide — l'analyse d'intention retombe sur le lexique |

**Les mots de passe de service sont hexadécimaux, jamais base64.** `docker compose`
interpole les `${...}` à la lecture de `.env.prod`, et un `$` dans une valeur y serait lu
comme le début d'un nom de variable. L'alphabet base64 n'en contient pas, mais
l'hexadécimal ferme la question sans avoir à le démontrer. Le hash BCrypt, lui, en est
plein — d'où le doublage par `sed 's/[$]/$$/g'`, exactement comme le documente
`.env.prod.example`.

Chaque valeur passe par `::add-mask::` **avant** d'être écrite, si bien qu'aucune n'apparaît
dans le journal même via une commande verbeuse. `.env.prod` n'est jamais téléversé en
artefact, et il reste ignoré par git (`.gitignore:5`).

## Comment lire un échec

`gh run view --log-failed` donne le journal du seul pas qui a échoué ; à défaut, l'onglet
Actions du dépôt.

Le job `fumee` téléverse un artefact **`journaux-docker`**, mais **seulement en cas
d'échec** : c'est la sortie de `docker compose logs` des quatre services, capturée avant le
démontage. Le script tourne avec `--keep` pour cette raison précise — sans lui, son `trap`
démonterait la pile avant qu'on puisse lire quoi que ce soit.

Les quatre causes d'échec les plus probables de `fumee`, dans l'ordre :

1. **`SMOKE_PASSWORD non defini`** — le pas de fabrication a échoué avant d'écrire dans
   `$GITHUB_ENV`.
2. **`aucun jeton rendu` à l'étape 5** — le hash BCrypt est mal formé. Un hash correct
   commence par `$$2y$$10$$` dans `.env.prod`.
3. **Attente de santé dépassée** — RabbitMQ met une trentaine de secondes, le backend migre
   ensuite ; le script attend jusqu'à 240 s. L'artefact dit lequel des quatre services n'est
   pas monté.
4. **Port 8088 occupé** — improbable sur un runner neuf ; le message serait un refus de
   connexion sur `http://localhost:8088`.

**Cet étage sait échouer, et cela a été prouvé** : une police Google réinjectée dans
`index.html` a fait rougir l'étape `3c` avec le message *« une police est encore chargée
depuis Google : la CSP la bloquera »*, et l'artefact a été téléversé. Le commit de preuve a
été annulé aussitôt.

## `qualite` bloque, depuis que la dette est vidée

Le job a porté `continue-on-error: true` le temps d'une feature : il rendait un **compte**,
pas un verdict. Au moment de son installation, la dette valait :

- **ESLint : 2 constats** — un `no-empty-function` dans `auth.spec.ts`, un `no-autofocus`
  dans `login.html`.
- **Prettier : 83 fichiers** mal formatés — dix écrans écrits sans linter, la configuration
  Prettier vivant dans `package.json` depuis F1 sans avoir jamais eu d'outil pour
  l'appliquer.

**Le compte Prettier était faux, et il l'était pour une raison qui vaut d'être retenue.** Il
avait été relevé sur le poste de développement, en Windows, où la copie de travail est en
CRLF ; Prettier compare avec `endOfLine: lf`, donc il signalait aussi les fichiers dont seule
la fin de ligne différait. Le vrai compte — celui que la CI voyait, en extrayant en LF —
était de **11 fichiers**. La règle `* text=auto eol=lf` de `.gitattributes` aligne désormais
les deux, et un `format:check` local dit la même chose qu'un `format:check` distant.

Les 11 fichiers sont formatés, les 2 constats ESLint corrigés, et **les deux
`continue-on-error` — celui du job et celui du pas ESLint — sont tombés ensemble**. Le second
n'existait que pour que le compte porte sur les deux outils : le drapeau du job dit ce que son
échec fait à l'exécution, il ne dit pas ce qu'un pas raté fait aux pas suivants, si bien qu'un
ESLint rouge sautait l'étape Prettier. Maintenant qu'un seul constat suffit à refuser la
fusion, arrêter le job au premier est le comportement voulu.

L'`autofocus` de l'écran de connexion a été **retiré**, pas désactivé par annotation : la
règle dit qu'un focus volé désoriente qui ne voit pas la page, et l'argument tient même sur un
formulaire seul à l'écran.

`angular-eslint` est **épinglé en `^20`**. `ng add` résout la dernière version publiée, qui
vise Angular 22 et émet un avertissement de décalage à chaque exécution ; le workspace est en
Angular 20, les deux vont ensemble.

## Ce que la CI ne couvre pas

Cinq manques, nommés plutôt que dissimulés.

- **Les tests `@Tag("erp")`.** `./mvnw verify` s'exécute **sans `-Perp-it`** : ces tests
  demandent de vrais Dolibarr et Odoo, dont la mise en route est manuelle
  (`docs/erp-integration-setup.md`) — une clé d'API à créer à la main, une base Odoo à
  initialiser. L'étage **contractuel** des adaptateurs, lui, tourne bien en CI : il asserte
  les corps envoyés contre `MockRestServiceServer`.
- **Aucune publication d'image.** Les images de `docker-compose.prod.yml` sont construites
  par le job de fumée puis jetées ; rien n'est poussé vers un registre.
- **Aucun déploiement.** La CI éprouve la pile, elle ne la met nulle part en service.
- **Aucune protection de branche.** Exiger que `backend` et `frontend` soient verts avant
  une fusion est un réglage GitHub, que seul le propriétaire du dépôt peut activer. En
  l'état, rien n'empêche de fusionner sur du rouge.
- **Aucune vérification du rendu visuel.** Le script de fumée éprouve que la feuille de
  style s'applique et que la police d'icônes est servie par l'origine (étapes `3c` et `3d`),
  ce qui aurait suffi à voir les deux pannes de F11.2. Il ne regarde aucune capture d'écran :
  la recette visuelle reste humaine.
