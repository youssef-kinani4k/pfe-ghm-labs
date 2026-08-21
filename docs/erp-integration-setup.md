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
   UPDATE llx_user SET api_key='cle-de-sonde-leadflow', email='amina@demo.test' WHERE login='admin';"
```

Vérifier :

```bash
curl -s http://localhost:8081/api/index.php/status -H "DOLAPIKEY: cle-de-sonde-leadflow"
# {"success":{"code":200,"dolibarr_version":"23.0.2","access_locked":"0"}}
```

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

## 4. Variables d'environnement des tests

```bash
export LEADFLOW_DOLIBARR_URL=http://localhost:8081/api/index.php
export LEADFLOW_DOLIBARR_API_KEY=cle-de-sonde-leadflow
export LEADFLOW_ODOO_URL=http://localhost:8069
export LEADFLOW_ODOO_DB=leadflow
export LEADFLOW_ODOO_USER=admin
export LEADFLOW_ODOO_PASSWORD=admin

cd backend && ./mvnw verify -Perp-it
```

Les tests marqués `@Tag("erp")` sont exclus de `./mvnw test` : sans ces variables et sans
les conteneurs, la suite ordinaire reste rapide et verte.

---

## 5. Repartir de zéro

```bash
docker compose --profile dolibarr --profile odoo down -v
```

Les volumes portent l'installation des deux ERP : les supprimer impose de rejouer
entièrement les sections 1 à 3.
