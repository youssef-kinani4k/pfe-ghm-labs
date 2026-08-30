# Déployer LeadFlow

Ce document décrit comment monter la pile complète — dashboard, API, base et broker — sur
une machine, et comment vérifier qu'elle fonctionne réellement. Il s'adresse à la personne
qui installe, pas à celle qui exploite : l'usage quotidien vit dans
`docs/boutique-onboarding.md`.

**Ce déploiement vise une pile locale reproductible, pas un serveur exposé sur Internet.**
La dernière section dit précisément ce qui manque pour cela, et ne pas la lire serait une
erreur.

---

## Ce que monte la pile

Quatre services, et **un seul port publié**.

```
                    :80  (ou WEB_PORT)
                        |
                   +----v----+
                   |   web   |   Nginx : sert le dashboard Angular,
                   |         |   relaie /api et /actuator/health
                   +----+----+
                        |  réseau interne du compose
                   +----v----+
                   | backend |   Spring Boot, :8090, non publié
                   +--+---+--+
                      |   |
          +-----------+   +-----------+
          |                           |
     +----v-----+              +------v---+
     | postgres |              | rabbitmq |   ni l'un ni l'autre n'est publié
     +----------+              +----------+
```

Le point qui compte : **le navigateur ne voit qu'une seule origine**. C'est ce qui fait
disparaître CORS — la liste `leadflow.security.cors.allowed-origins` est vide en production,
et ce n'est pas un oubli. Base et broker ne sont joignables que depuis le réseau du compose ;
la console de gestion de RabbitMQ existe dans l'image mais aucun port ne la publie.

---

## Prérequis

**Docker et Docker Compose. Rien d'autre.** Ni JDK, ni Maven, ni Node sur la machine
cible : les deux images se construisent en plusieurs étages et n'emportent que ce qui
s'exécute — un JRE 21 et le jar pour le backend, Nginx et les fichiers statiques pour le
dashboard.

`openssl` et `htpasswd` servent à fabriquer les secrets, une seule fois. Sur Windows, Git
Bash fournit `openssl` ; `htpasswd` vient avec Apache, et la section suivante donne une
solution de rechange si vous ne l'avez pas.

---

## 1. Préparer les secrets

```bash
cp .env.prod.example .env.prod
```

`.env.prod` est ignoré par git — c'est délibéré, et c'est vérifié : le motif `.env*` du
`.gitignore` couvre toutes les variantes, seul l'exemple restant suivi. Ne jamais forcer
son ajout.

Renseigner ensuite **chaque** valeur vide. Les trois secrets se fabriquent ainsi :

```bash
openssl rand -base64 32     # LEADFLOW_MASTER_KEY
openssl rand -base64 48     # LEADFLOW_JWT_SECRET
htpasswd -bnBC 10 "" 'mot-de-passe' | tr -d ':\n' | sed 's/[$]/$$/g'
```

**Chaque `$` du hash BCrypt doit être doublé**, et le `sed` ci-dessus s'en charge. Sans
cela, `docker compose` lit le `$` comme le début d'un nom de variable : le hash
`$2a$10$k1ZYa…` arrive dans le conteneur réduit à `$2a$10`. L'application démarre quand
même — le garde-fou vérifie que le hash est *présent*, pas qu'il est *valide* — et personne
ne peut se connecter au dashboard. Un hash correctement saisi commence par `$$2a$$10$$`.

Sans `htpasswd`, n'importe quel outil produisant un hash BCrypt en coût 10 convient ; il
faut alors doubler les `$` à la main.

Deux paires de mots de passe doivent coïncider, parce que chaque valeur est lue par deux
programmes différents :

| Le conteneur lit        | Le backend lit      |
| ----------------------- | ------------------- |
| `POSTGRES_PASSWORD`     | `DB_PASSWORD`       |
| `RABBITMQ_DEFAULT_PASS` | `RABBITMQ_PASSWORD` |

> **`LEADFLOW_MASTER_KEY` ne se remplace pas.** Elle chiffre les secrets HMAC et les
> configurations ERP stockés en base. La perdre rend ces données irrécupérables : aucune
> boutique déjà créée ne pourra plus recevoir de lead, et il faudra toutes les recréer. La
> sauvegarder ailleurs que sur la machine, dès maintenant.

`GEMINI_API_KEY` est la seule variable qui puisse rester vide : l'analyse d'intention
retombe alors sur son lexique. Elle n'est de toute façon qu'un repli, la clé enregistrée
depuis l'écran « Paramètres » l'emportant sur elle.

---

## 2. Monter la pile

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

**`--env-file .env.prod` n'est pas facultatif**, et il doit figurer sur *chaque* invocation
de ce fichier, `ps`, `logs` et `down` compris. La directive `env_file:` du compose alimente
les variables **du conteneur**, tandis que les `${…}` du fichier lui-même sont interpolés
**à sa lecture**, depuis le shell ou depuis `.env` — jamais depuis `.env.prod`. Sans lui, la
commande échoue dès `POSTGRES_PASSWORD`.

Le premier passage construit les deux images et prend plusieurs minutes. Ensuite :

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml ps
docker compose --env-file .env.prod -f docker-compose.prod.yml logs -f backend
```

`ps` doit montrer les quatre services `running`, et `healthy` pour les trois qui portent une
sonde. RabbitMQ met une trentaine de secondes à devenir sain ; le backend l'attend, puis
applique ses migrations Flyway. Compter deux bonnes minutes avant de conclure à un échec.

La colonne des ports de `ps` est trompeuse : elle liste aussi les ports **exposés** par les
images, que rien ne publie. Seule une ligne portant une adresse d'hôte — `0.0.0.0:80->80` —
est réellement joignable de l'extérieur.

**Si le port 80 est déjà pris sur la machine**, poser `WEB_PORT` dans `.env.prod` (par
exemple `WEB_PORT=8088`). Ce cas mérite d'être vérifié plutôt que supposé : quand un autre
serveur tient déjà le 80, Docker s'y lie **sans erreur** et le trafic continue d'aller à
l'occupant — la pile paraît répondre alors qu'on lit l'autre serveur.

---

## 3. Vérifier

```bash
SMOKE_PASSWORD='le-mot-de-passe-choisi' ./scripts/smoke-prod.sh
```

Le script monte la pile, la traverse de bout en bout, puis la démonte. Il rend `0` si tout
passe, et un code non nul en nommant l'étape échouée. `--keep` la laisse debout pour
inspection.

Ce que ses onze étapes établissent :

| Étape | Ce qu'elle prouve                                                                |
| ----- | -------------------------------------------------------------------------------- |
| 0–1   | `.env.prod` est là, la pile monte                                                |
| 2     | les services deviennent sains — l'attente porte sur un état, jamais sur une durée |
| 3     | Nginx sert le dashboard, et `/leads/42` rend l'index : le routage client marche   |
| 4     | l'API est atteinte **et** fermée — `401` sans jeton                              |
| 5     | le compte opérateur fonctionne : c'est ce qui attrape un hash mal saisi           |
| 6     | une boutique se crée par l'API sur une base vide                                 |
| 7     | une soumission signée en HMAC est acceptée (`202`)                               |
| 8     | rejouer la même signature rend le même `eventId` — l'idempotence tient            |
| 9     | le lead traverse capture, qualification et routage, et apparaît dans la liste     |
| 10    | le flux SSE traverse le proxy                                                    |
| 11    | `proxy_buffering off` est bien actif sur le flux                                 |

Les étapes 10 et 11 sont distinctes à dessein. La 10 seule ne prouverait pas la 11 :
éprouvée contre une configuration où `proxy_buffering off` était retiré, elle restait verte,
Nginx transmettant un flux lent de toute façon. Le défaut ne se serait vu qu'à la première
rafale d'événements — en production.

Le script **ne va pas** jusqu'à `SYNCED` : la boutique qu'il crée pointe vers un ERP
injoignable, donc la synchronisation échoue et une ligne apparaît dans le journal des morts.
C'est le comportement correct, pas un échec du test.

---

## 4. Premier usage

La base de production démarre **vide** : le jeu de démonstration vit sous `db/dev`, que le
profil `prod` ne charge jamais.

1. Ouvrir `http://localhost/` (ou le `WEB_PORT` choisi) et se connecter avec
   `LEADFLOW_ADMIN_USER` et le mot de passe dont vous avez posé le hash.
2. Créer la première boutique **par l'écran** — jamais en SQL. La marche à suivre, écrite
   pour quelqu'un qui n'est pas développeur, est dans `docs/boutique-onboarding.md`.
3. La création rend une clé publique et un secret HMAC. Le chemin de webhook qui les
   accompagne est ce qu'il faut donner à l'intégrateur du formulaire ; le contrat complet,
   avec des exemples en PHP, JS et curl, vit dans `docs/webhook-integration.md`.

Le secret HMAC n'est montré qu'à la création. S'il est perdu, il se tourne depuis la fiche
de la boutique — l'ancien cesse alors d'être accepté.

---

## 5. Mettre à jour

```bash
git pull
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

Les images sont reconstruites et les conteneurs remplacés ; **les volumes sont conservés**,
donc les données restent et les nouvelles migrations Flyway s'appliquent au redémarrage du
backend. Une migration qui échoue empêche le backend de démarrer — c'est voulu : mieux vaut
un service arrêté qu'un schéma à moitié appliqué. Les journaux du backend nomment alors la
migration fautive.

`down` arrête la pile en gardant les données. **`down -v` détruit les volumes**, donc la
base : ne l'utiliser que pour repartir de zéro délibérément.

---

## Ce que ce déploiement n'est pas

Cette pile est reproductible et cohérente, mais **elle n'est pas une mise en service
complète**. Quatre manques, tous connus et tous planifiés :

- **Pas de TLS.** Tout circule en clair, jeton de session compris. Hors périmètre tant que
  la pile reste locale ; indispensable dès qu'elle est exposée.
- **Pas d'en-têtes de sécurité** — ni HSTS, ni CSP, ni `X-Content-Type-Options`. Leur place
  est dans `frontend/nginx.conf`, et c'est **F11.2** qui les y met.
- **Pas de limitation de débit sur le webhook.** Une adresse peut soumettre autant qu'elle
  veut ; la signature protège l'authenticité, pas le volume. **F11.2** également, avec la
  restriction des destinations de `POST /api/admin/crm/test`, aujourd'hui libres.
- **Pas d'intégration continue.** Rien ne construit ni n'éprouve automatiquement à chaque
  commit ; `./mvnw test` et ce script de fumée se lancent à la main. C'est **F11.3**.

Tant que ces quatre points ne sont pas traités, cette pile a sa place sur un poste ou un
réseau de confiance, pas sur une adresse publique.
