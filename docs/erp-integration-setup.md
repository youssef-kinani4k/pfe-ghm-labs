# Mettre en route les ERP de test (Dolibarr et Odoo)

Procédure reproductible pour amener les deux conteneurs d'un état vierge à un état
interrogeable par leur API. Elle est la condition d'exécution des tests d'intégration de
F5 (`./mvnw verify -Perp-it`).

Toutes les commandes se lancent depuis la racine du dépôt, sous Git Bash.

---

## 1. Démarrer les conteneurs

```bash
docker compose --profile dolibarr --profile odoo up -d
```

Dolibarr s'auto-installe au premier démarrage et met **deux à trois minutes**. Attendre
que la page réponde :

```bash
until [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/)" = "200" ]; do sleep 10; done
```

Vérifier que l'installation est allée au bout (399 tables attendues) :

```bash
docker exec leadflow-dolibarr-db mariadb -udolibarr -pdolibarr dolibarr \
  -e "SELECT count(*) FROM information_schema.tables WHERE table_schema='dolibarr';"
```

---

## 2. Dolibarr : activer l'API et les modules métier

**L'installation par défaut n'active que deux modules** (`API` absent, `Societe` absent,
`Projet` absent). Sans eux, l'API répond `403 Forbidden` sur toute création — un message
qui n'indique pas la cause réelle.

### 2.1 Activer le module API et poser une clé (SQL)

```bash
docker exec leadflow-dolibarr-db mariadb -udolibarr -pdolibarr dolibarr -e \
  "INSERT INTO llx_const (name, entity, value, type, visible, note)
   VALUES ('MAIN_MODULE_API', 1, '1', 'chaine', 0, 'Active pour les tests LeadFlow')
   ON DUPLICATE KEY UPDATE value='1';
   UPDATE llx_user SET api_key='cle-dolibarr-de-demo', email='amina@demo.test' WHERE login='admin';"
```

Vérifier :

```bash
curl -s http://localhost:8081/api/index.php/status -H "DOLAPIKEY: cle-dolibarr-de-demo"
# {"success":{"code":200,"dolibarr_version":"23.0.2","access_locked":"0"}}
```

**La valeur de la cle n'est pas libre.** `cle-dolibarr-de-demo` est celle que porte le
`crm_config` chiffre du client de demonstration de
`backend/src/main/resources/db/dev/R__demo_data.sql`. En poser une autre fait echouer toute
verification manuelle du pipeline sous le profil `dev` : le backend s'authentifie avec la
valeur de la base, pas avec celle du bocal. Les tests `@Tag("erp")`, eux, lisent la cle dans
`LEADFLOW_DOLIBARR_API_KEY` (section 5) et acceptent donc n'importe quelle valeur, pourvu
qu'elle soit la meme des deux cotes.

### 2.2 Activer `Societe` et `Projet` (session web)

Ces deux modules ne s'activent pas par une simple constante : leur activation crée aussi
les permissions dans `llx_rights_def`, sans lesquelles même l'administrateur reçoit `403`.
Il faut donc passer par une session authentifiée.

```bash
# Jeton du formulaire de connexion, puis connexion
TOKEN=$(curl -s -c /tmp/doli.cookie http://localhost:8081/index.php \
  | grep -oE 'name="token" value="[^"]+"' | head -1 | sed 's/.*value="//;s/"//')

curl -s -b /tmp/doli.cookie -c /tmp/doli.cookie -o /dev/null -X POST http://localhost:8081/index.php \
  -d "actionlogin=login&loginfunction=loginfunction&username=admin&password=admin&token=$TOKEN"

# Jeton de la page des modules
TK=$(curl -s -b /tmp/doli.cookie -c /tmp/doli.cookie "http://localhost:8081/admin/modules.php?mode=common" \
  | grep -oE 'name="token" value="[^"]+"' | head -1 | sed 's/.*value="//;s/"//')

# Activation : modSociete (tiers et contacts) puis modProjet (opportunites)
curl -s -b /tmp/doli.cookie -c /tmp/doli.cookie -o /dev/null \
  "http://localhost:8081/admin/modules.php?id=1&token=$TK&action=set&token=$TK&value=modSociete&mode=common"
curl -s -b /tmp/doli.cookie -c /tmp/doli.cookie -o /dev/null \
  "http://localhost:8081/admin/modules.php?id=20&token=$TK&action=set&token=$TK&value=modProjet&mode=common"
```

Vérifier :

```bash
docker exec leadflow-dolibarr-db mariadb -udolibarr -pdolibarr dolibarr \
  -e "SELECT name FROM llx_const WHERE name LIKE 'MAIN_MODULE_%';"
# Attendu : MAIN_MODULE_API, MAIN_MODULE_SOCIETE, MAIN_MODULE_PROJET, MAIN_MODULE_USER
```

Équivalent par l'interface, si le script échoue : `http://localhost:8081`, `admin` / `admin`
→ Accueil → Configuration → Modules → activer **Tiers** et **Projets**, puis Utilisateurs →
`admin` → onglet Utilisateur → « Initialiser la clé d'API ».

### 2.3 Un 401 de Dolibarr ne ressemble pas a un 401

Dolibarr repond a une cle refusee par un en-tete `WWW-Authenticate:` **vide**. Le parseur du
JDK (`sun.net.www.HeaderParser`) ne sait pas lire cet en-tete et leve
`IllegalArgumentException: invalid start or end` avant que Spring n'ait pu transformer la
reponse en erreur HTTP. Comme `DolibarrClient` ne rattrape que `RestClientException`, cette
exception lui echappe : la trace remonte sans jamais mentionner « 401 », et l'erreur
ressemble a un defaut de construction d'URL.

Reflexe en cas de trace opaque contenant `invalid start or end` : verifier la cle d'API, puis
regarder le journal d'acces du conteneur, qui donne le vrai code retour.

```bash
docker logs leadflow-dolibarr 2>&1 | grep "api/index.php" | tail -5
```

---

## 3. Odoo : créer la base et installer le module `crm`

Le mot de passe maître de l'image est `admin` (`admin_passwd` laissé commenté dans
`/etc/odoo/odoo.conf`, donc valeur par défaut).

```bash
curl -s -o /dev/null -X POST http://localhost:8069/web/database/create \
  -F 'master_pwd=admin' -F 'name=leadflow' -F 'login=admin' -F 'password=admin' \
  -F 'lang=fr_FR' -F 'country_code=ma' -F 'phone=' --max-time 600
```

Compter **environ une minute**. La base ne contient alors que `base` : le modèle
`crm.lead` n'existe pas encore.

**Installer `crm` par la ligne de commande**, et non par `button_immediate_install` en
JSON-RPC : l'installation redémarre le registre, la requête HTTP tombe et la base reste
avec des modules `to install` dans un état incohérent.

```bash
MSYS_NO_PATHCONV=1 docker exec leadflow-odoo \
  odoo -d leadflow -i crm --db_host odoo-db -r odoo -w odoo --stop-after-init --no-http
docker restart leadflow-odoo
```

Vérifier :

```bash
curl -s -X POST http://localhost:8069/jsonrpc -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","method":"call","id":1,
  "params":{"service":"object","method":"execute_kw",
            "args":["leadflow",2,"admin","ir.module.module","search_read",
                    [[["name","=","crm"]],["name","state"]]]}}'
# {"result":[{"id":53,"name":"crm","state":"installed"}]}
```

---

## 4. Clés attendues dans `client.crm_config`

`crm_config` est le document JSON chiffré porté par la ligne `client` : c'est lui, et non
`application.yml`, qui décrit **l'instance** ERP du client. Les clés sont interprétées par
l'adaptateur seul, qui refuse la synchronisation avec un message nommant la clé manquante.

| Fournisseur | Clé | Rôle | Exemple |
| --- | --- | --- | --- |
| `dolibarr` | `baseUrl` | racine de l'API REST, `/api/index.php` compris | `http://localhost:8081/api/index.php` |
| `dolibarr` | `apiKey` | valeur envoyée en en-tête `DOLAPIKEY` | `cle-dolibarr-de-demo` |
| `odoo` | `baseUrl` | racine du serveur, sans `/jsonrpc` | `http://localhost:8069` |
| `odoo` | `database` | base Odoo visée | `leadflow` |
| `odoo` | `username` | login du compte de service | `admin` |
| `odoo` | `apiKey` | mot de passe ou clé d'API de ce compte | `admin` |

Les réglages **techniques** (`enabled`, `connect-timeout`, `read-timeout`) ne sont pas ici :
ils vivent sous `leadflow.crm.providers.<fournisseur>` dans `application.yml`, car ils sont
communs à toutes les instances d'un même ERP.

---

## 5. Variables d'environnement des tests

```bash
export LEADFLOW_DOLIBARR_URL=http://localhost:8081/api/index.php
export LEADFLOW_DOLIBARR_API_KEY=cle-dolibarr-de-demo
export LEADFLOW_ODOO_URL=http://localhost:8069
export LEADFLOW_ODOO_DB=leadflow
export LEADFLOW_ODOO_USER=admin
export LEADFLOW_ODOO_PASSWORD=admin

cd backend && ./mvnw verify -Perp-it
```

Les tests marqués `@Tag("erp")` sont exclus de `./mvnw test` : sans ces variables et sans
les conteneurs, la suite ordinaire reste rapide et verte. L'exclusion vient du plugin
Surefire, configuré sur `<excludedGroups>${erp.excludedGroups}</excludedGroups>` ; la
propriété vaut `erp` par défaut et le profil `erp-it` la remplace par un nom de groupe
inexistant, ce qui laisse tout passer.

Chaque test est en plus conditionné par une variable d'environnement
(`LEADFLOW_DOLIBARR_API_KEY` pour Dolibarr, `LEADFLOW_ODOO_DB` pour Odoo) : sous
`-Perp-it` sans ces variables, le test concerné est ignoré au lieu d'échouer.

Les deux tests créent un tiers, un contact et une opportunité portant un suffixe aléatoire,
puis rejouent la même synchronisation avec les références obtenues : le rejeu ne doit rien
recréer. Les objets sont laissés en place dans les ERP — la section 5 remet à zéro.

Relevé de la dernière exécution : `./mvnw verify -Perp-it` → **78 tests, 0 échec**, dont les
2 de `ErpIntegrationTest` contre Dolibarr 23.0.2 et Odoo (module `crm` installé).

---

## 6. Repartir de zéro

```bash
docker compose --profile dolibarr --profile odoo down -v
```

Les volumes portent l'installation des deux ERP : les supprimer impose de rejouer
entièrement les sections 1 à 3.
