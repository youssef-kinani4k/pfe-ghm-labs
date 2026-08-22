# F5 — Connecteurs ERP : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implémenter les deux adaptateurs derrière `CrmConnector` — Dolibarr en REST, Odoo en JSON-RPC — et l'orchestration minimale qui permet de pousser un lead vers l'instance ERP de son client sans créer de doublon au rejeu.

**Architecture:** Le port reçoit désormais l'état des tentatives précédentes (`CrmSyncState`) au lieu de lire notre base, ce qui garde chaque adaptateur ignorant de notre schéma et testable sans PostgreSQL. Un orchestrateur `CrmSyncService` porte tout ce qui est propre à LeadFlow : résolution de la cible depuis `client.crm_config` déchiffré, reconstruction de l'état antérieur, résolution et mémorisation de `sales_rep.crm_ref`, écriture de la trace append-only. Chaque adaptateur est en deux couches — une traduction sans réseau au-dessus d'un transport sans vocabulaire métier.

**Tech Stack:** Java 21, Spring Boot 4.1, `RestClient` (Spring Framework 7), Jackson 3 (`tools.jackson`), Spring Data JPA / Hibernate 7, PostgreSQL 16, Lombok, JUnit 5, AssertJ, `MockRestServiceServer`, Testcontainers, Docker Compose (profils `dolibarr` et `odoo`).

**Spec:** `docs/superpowers/specs/2026-08-21-f5-connecteurs-erp-design.md`

## Global Constraints

- Branche de travail : `feature/f5-connecteurs-erp`. Ne jamais commiter sur `main`.
- Toutes les commandes Maven s'exécutent depuis `backend/`. Sous Git Bash : `./mvnw`. Sous PowerShell : `.\mvnw.cmd`.
- **Le daemon Docker doit tourner** : les tests de persistance démarrent des conteneurs Testcontainers, et les tâches 1 et 8 utilisent les profils ERP de `docker-compose.yml`.
- **F5 n'ajoute aucune migration Flyway.** Le schéma est complet depuis F1. Si une tâche croit avoir besoin d'une colonne, c'est le signe qu'elle déborde de son périmètre — s'arrêter et le signaler.
- **Ne jamais modifier `V1__raw_lead_event.sql` ni `V2__multi_tenant_schema.sql`.** Flyway échouerait sur une somme de contrôle divergente.
- **Le modèle pivot `crm/model` ne doit contenir aucun terme propre à un fournisseur** : ni `thirdparty`, ni `socid`, ni `res.partner`, ni `crm.lead`. Toute la traduction vit dans `crm/dolibarr/` et `crm/odoo/`.
- **La tâche 1 a été exécutée** : son relevé est le §15 de la spec, et les écarts qu'elle a trouvés sont déjà répercutés dans les §6 et §7 de la spec et dans le code des tâches 4 à 7 (`ref` obligatoire sur les projets Dolibarr, `fk_user_resp` inopérant remplacé par un appel dédié, recherche Odoo sur `login` ou `email`). Si un écart subsiste à l'exécution, la même règle vaut : l'ERP réel fait foi, on corrige la spec puis le code.
- Les images ERP sont épinglées (`dolibarr/dolibarr:23.0.2`, `odoo:17.0`). La mise en route des conteneurs suit `docs/erp-integration-setup.md` — sans l'activation préalable des modules `Societe` et `Projet`, Dolibarr répond `403` à toute création.
- `crm_sync_attempt` est **append-only** : aucun `UPDATE`, jamais. Une tentative = une ligne.
- Jackson est en version 3 dans ce projet : importer `tools.jackson.databind.ObjectMapper`, jamais `com.fasterxml.jackson`.
- Clé maître de chiffrement utilisée par les tests (déjà dans `src/test/resources/application.properties`) : `gh/SD1Jp/HfWc/sB/BybtvbUqehzXmv0YIZ8uMwutME=`
- Convention de commit : `feat:`, `fix:`, `test:`, `docs:`, `refactor:`.
- Suite de référence au démarrage de F5 : **32 tests, 0 échec**. Toute tâche qui la fait baisser a cassé quelque chose.

---

## Structure des fichiers

**À créer — code principal**

| Fichier | Responsabilité |
| --- | --- |
| `crm/model/CrmSyncState.java` | Références déjà obtenues lors des tentatives précédentes |
| `crm/model/CrmAssignee.java` | Commercial à traduire en identifiant ERP |
| `crm/CrmSyncService.java` | Orchestration : cible, état antérieur, appel, trace |
| `crm/CrmSyncTraceWriter.java` | Écriture de la trace en transaction propre |
| `crm/CrmHttpConfig.java` | `RestClient.Builder` par fournisseur, avec ses timeouts |
| `crm/dolibarr/DolibarrClient.java` | Transport REST, en-tête `DOLAPIKEY`, mapping des erreurs |
| `crm/dolibarr/DolibarrConnector.java` | Traduction pivot ↔ Dolibarr |
| `crm/odoo/OdooClient.java` | Transport JSON-RPC, `authenticate` + `execute_kw` |
| `crm/odoo/OdooConnector.java` | Traduction pivot ↔ Odoo |

**À modifier — code principal**

| Fichier | Modification |
| --- | --- |
| `crm/model/CrmSyncException.java` | Porte l'état partiel obtenu avant l'échec |
| `crm/CrmConnector.java` | `sync(lead, target, previous)` + `resolveAssignee(...)` |
| `crm/CrmConnectorRegistry.java` | Applique `enabled`, distingue trois refus |
| `crm/CrmSyncAttemptRepository.java` | Historique filtré par fournisseur |
| `src/main/resources/application.yml` | `odoo.enabled: true` (tâche 7) |
| `pom.xml` | Exclusion du groupe `erp` + profil `erp-it` (tâche 8) |
| `docker-compose.yml` | Images ERP épinglées (tâche 1) |
| `CLAUDE.md` | Adaptateurs, port, tests à deux étages (tâche 9) |

**À créer — tests**

| Fichier | Ce qu'il prouve |
| --- | --- |
| `crm/model/CrmSyncExceptionTest.java` | L'état partiel voyage avec l'exception |
| `crm/CrmSyncServiceTest.java` | Orchestration complète, succès et échec |
| `crm/dolibarr/DolibarrClientTest.java` | Transport : en-tête, corps, erreurs |
| `crm/dolibarr/DolibarrConnectorTest.java` | Traduction et idempotence |
| `crm/odoo/OdooClientTest.java` | JSON-RPC, dont le piège du `200` en erreur |
| `crm/odoo/OdooConnectorTest.java` | Traduction et idempotence |
| `crm/ErpIntegrationTest.java` | Étage 2, `@Tag("erp")`, contre les vrais ERP |

**À modifier — tests**

| Fichier | Modification |
| --- | --- |
| `crm/CrmConnectorRegistryTest.java` | Nouvelle signature, `enabled`, refus `null` |

**Documentation**

| Fichier | Contenu |
| --- | --- |
| `docs/erp-integration-setup.md` | Procédure reproductible de mise en route des deux ERP |
| Spec F5, §15 | Relevé de la sonde (tâche 1) |

---

## Task 1: Sonde contre les conteneurs ERP réels

Cette tâche ne produit **aucun code de production**. Elle produit de l'information : la forme réelle des requêtes et des réponses des deux ERP. C'est la raison d'être de la position de F5 dans le planning — écrire les adaptateurs d'après la documentation seule reviendrait à découvrir les écarts en écrivant les tests, c'est-à-dire trop tard.

Elle est jetable, comme l'était `DemoSecretGenerator` en F1 : le fichier de sonde est supprimé à la fin de la tâche, son résultat vit dans la spec.

**Files:**
- Create (temporaire, supprimé à l'étape 8) : `backend/src/test/java/com/leadflow/crm/ErpProbe.java`
- Create : `docs/erp-integration-setup.md`
- Modify : `docs/superpowers/specs/2026-08-21-f5-connecteurs-erp-design.md` (§6, §7, nouveau §15)
- Modify : `docker-compose.yml` (épinglage des images)

**Interfaces:**
- Consumes : rien.
- Produces : le §15 de la spec, dont les tâches 4 à 7 tirent les noms de champs exacts ; `docs/erp-integration-setup.md`, dont la tâche 8 tire sa procédure.

- [x] **Step 1: Monter les deux ERP**

```bash
docker compose --profile dolibarr --profile odoo up -d
docker compose ps
```

Attendu : `leadflow-dolibarr` sur `:8081`, `leadflow-odoo` sur `:8069`, plus leurs bases. Dolibarr s'auto-installe au premier démarrage et peut prendre plusieurs minutes ; suivre `docker logs -f leadflow-dolibarr` jusqu'à ce que l'installation se termine.

- [x] **Step 2: Relever les versions réelles et épingler les images**

```bash
docker exec leadflow-dolibarr sh -c 'cat /var/www/html/filefunc.inc.php | grep -i version' || true
docker inspect --format '{{index .Config.Image}} {{index .Config.Labels "org.opencontainers.image.version"}}' leadflow-dolibarr leadflow-odoo
```

Remplacer dans `docker-compose.yml` :

```yaml
    image: dolibarr/dolibarr:latest
```

par la version effectivement observée, par exemple :

```yaml
    # Version epinglee : F5 teste la conformite d'une API, un tag flottant rendrait
    # les tests d'integration non reproductibles.
    image: dolibarr/dolibarr:21.0.1
```

Faire de même pour `odoo:17` si l'image expose une version plus précise (`odoo:17.0`).

- [x] **Step 3: Obtenir une clé d'API Dolibarr**

Deux voies. Essayer l'interface d'abord ; si elle résiste, la voie SQL.

Voie interface (`http://localhost:8081`, `admin` / `admin`) :
1. Accueil → Configuration → Modules → onglet « Interfaces avec systèmes externes » → activer **API REST**.
2. Accueil → Utilisateurs & Groupes → `admin` → onglet « Cartes utilisateur » → bouton « Initialiser la clé d'API ».
3. Relever la clé.

Voie SQL, si l'interface ne coopère pas :

```bash
docker exec leadflow-dolibarr-db mariadb -udolibarr -pdolibarr dolibarr -e \
  "INSERT INTO llx_const (name, entity, value, type, visible) \
   VALUES ('MAIN_MODULE_API', 1, '1', 'chaine', 0) \
   ON DUPLICATE KEY UPDATE value='1';"

docker exec leadflow-dolibarr-db mariadb -udolibarr -pdolibarr dolibarr -e \
  "UPDATE llx_user SET api_key='cle-de-sonde-leadflow' WHERE login='admin';"
```

**Consigner dans le §15 de la spec laquelle des deux voies a fonctionné**, et sous quelle forme exacte. C'est cette procédure que la tâche 8 automatisera ou documentera.

- [x] **Step 4: Sonder l'API Dolibarr**

```bash
CLE="<la cle relevee>"
BASE="http://localhost:8081/api/index.php"

# Le tiers
curl -s -X POST "$BASE/thirdparties" -H "DOLAPIKEY: $CLE" -H 'Content-Type: application/json' \
  -d '{"name":"Sonde LeadFlow","client":"2","email":"sonde@exemple.test"}' ; echo

# Le contact, rattache par socid au tiers cree
curl -s -X POST "$BASE/contacts" -H "DOLAPIKEY: $CLE" -H 'Content-Type: application/json' \
  -d '{"lastname":"Bensalem","firstname":"Amina","email":"sonde@exemple.test","socid":"<id du tiers>"}' ; echo

# L'opportunite
curl -s -X POST "$BASE/projects" -H "DOLAPIKEY: $CLE" -H 'Content-Type: application/json' \
  -d '{"ref":"SONDE-1","title":"Demande de devis","socid":"<id du tiers>","usage_opportunity":"1","opp_status":"1","opp_amount":"1000"}' ; echo

# L'utilisateur, pour resolveAssignee
curl -s "$BASE/users?sqlfilters=(t.email%3A%3D%3A'admin%40exemple.test')" -H "DOLAPIKEY: $CLE" ; echo
```

Consigner **la forme exacte des réponses** : un identifiant nu (`42`) ou un objet ? Un code HTTP `200` ou `201` ? Quelle forme prend une erreur de validation ? Le champ d'opportunité s'appelle-t-il bien `opp_status`, et faut-il `usage_opportunity` ? La recherche d'utilisateur accepte-t-elle `sqlfilters` sous cette forme ?

- [x] **Step 5: Préparer Odoo**

Créer la base et installer le module `crm` :

```bash
# Base 'leadflow', mot de passe maitre par defaut de l'image
curl -s -X POST http://localhost:8069/web/database/create \
  -F 'master_pwd=admin' -F 'name=leadflow' -F 'login=admin' -F 'password=admin' \
  -F 'lang=fr_FR' -F 'country_code=ma' -F 'phone=' ; echo
```

Puis, dans l'interface (`http://localhost:8069`, `admin` / `admin`) : Applications → installer **CRM**. Sans ce module, le modèle `crm.lead` n'existe pas et l'adaptateur échouera sur un message d'Odoo peu explicite.

Consigner la procédure exacte qui a fonctionné, mot de passe maître inclus.

- [x] **Step 6: Sonder l'API Odoo**

```bash
# 1. Authentification : renvoie l'uid
curl -s -X POST http://localhost:8069/jsonrpc -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","method":"call","id":1,
  "params":{"service":"common","method":"authenticate",
            "args":["leadflow","admin","admin",{}]}}' ; echo

# 2. Creation d'une societe
curl -s -X POST http://localhost:8069/jsonrpc -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","method":"call","id":2,
  "params":{"service":"object","method":"execute_kw",
            "args":["leadflow",<uid>,"admin","res.partner","create",
                    [{"name":"Sonde LeadFlow","is_company":true,"email":"sonde@exemple.test"}]]}}' ; echo

# 3. Creation d'une opportunite
curl -s -X POST http://localhost:8069/jsonrpc -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","method":"call","id":3,
  "params":{"service":"object","method":"execute_kw",
            "args":["leadflow",<uid>,"admin","crm.lead","create",
                    [{"name":"Demande de devis","type":"opportunity","partner_id":<id>,
                      "email_from":"sonde@exemple.test","priority":"2"}]]}}' ; echo

# 4. Erreur volontaire : champ inexistant, pour observer la forme de l'echec
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8069/jsonrpc \
  -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","method":"call","id":4,
  "params":{"service":"object","method":"execute_kw",
            "args":["leadflow",<uid>,"admin","res.partner","create",
                    [{"champ_inexistant":"x"}]]}}'
```

**Le point capital de cette étape est le n°4** : confirmer qu'Odoo renvoie bien `200` avec un objet `error` dans le corps, et relever le chemin exact du message (`error.data.message` ou `error.message`). Tout l'adaptateur Odoo repose là-dessus.

- [x] **Step 7: Consigner le relevé dans la spec**

Ajouter à `docs/superpowers/specs/2026-08-21-f5-connecteurs-erp-design.md` une section :

```markdown
## 15. Relevé de la sonde

Réalisé le <date>, contre `dolibarr/dolibarr:<version>` et `odoo:<version>`.

### Dolibarr
- Obtention de la clé d'API : <voie qui a fonctionné, verbatim>
- `POST /thirdparties` → HTTP <code>, corps de la réponse : <verbatim>
- `POST /contacts` → <verbatim> ; champ de rattachement : <socid ou autre>
- Opportunité : <objet retenu et champs exacts>
- Recherche d'utilisateur : <requête qui fonctionne>
- Forme d'une erreur : <verbatim>

### Odoo
- Création de base et module `crm` : <procédure exacte>
- `common.authenticate` → <verbatim>
- `res.partner` / `crm.lead` : champs acceptés, valeurs de `priority`
- **Erreur** : code HTTP <code>, chemin du message : <verbatim>

### Écarts avec les §6 et §7
<liste des différences, ou « aucun »>
```

Si des écarts existent, **corriger les tableaux des §6 et §7 de la spec en conséquence**.

- [x] **Step 8: Nettoyer et commiter**

```bash
rm -f backend/src/test/java/com/leadflow/crm/ErpProbe.java
cd backend && ./mvnw test
```

Attendu : 32 tests, 0 échec — la sonde n'a touché à aucun code de production.

Écrire `docs/erp-integration-setup.md` : la suite de commandes minimale, dans l'ordre, pour amener les deux conteneurs d'un `docker compose down -v` à un état interrogeable par l'API. C'est ce document que suivra la tâche 8.

```bash
git add docker-compose.yml docs/erp-integration-setup.md \
        docs/superpowers/specs/2026-08-21-f5-connecteurs-erp-design.md
git commit -m "docs: releve de sonde des API Dolibarr et Odoo, images epinglees"
```

---

## Task 2: Modèle pivot élargi, port et registre

Le port change de forme avant qu'un seul adaptateur existe : c'est le moment le moins cher pour le faire, exactement comme en F1.

Cette tâche apporte une **précision au §8 de la spec** : pour qu'une ligne `FAILED` porte les références déjà obtenues, l'adaptateur doit pouvoir les transmettre au moment où il échoue. `CrmSyncException` gagne donc un état partiel. Sans lui, un échec sur l'opportunité ferait recréer le tiers au rejeu — précisément ce que le critère de recette n°3 interdit.

**Files:**
- Create : `backend/src/main/java/com/leadflow/crm/model/CrmSyncState.java`
- Create : `backend/src/main/java/com/leadflow/crm/model/CrmAssignee.java`
- Modify : `backend/src/main/java/com/leadflow/crm/model/CrmSyncException.java`
- Modify : `backend/src/main/java/com/leadflow/crm/CrmConnector.java`
- Modify : `backend/src/main/java/com/leadflow/crm/CrmConnectorRegistry.java`
- Test (create) : `backend/src/test/java/com/leadflow/crm/model/CrmSyncExceptionTest.java`
- Test (modify) : `backend/src/test/java/com/leadflow/crm/CrmConnectorRegistryTest.java`

**Interfaces:**
- Consumes : `CrmProperties` (`config/CrmProperties.java`), record `CrmProperties.Provider(boolean enabled, Duration connectTimeout, Duration readTimeout)`.
- Produces :
  - `CrmSyncState(String accountRef, String contactRef, String opportunityRef)` avec la constante `CrmSyncState.VIERGE`.
  - `CrmAssignee(String fullName, String email)`.
  - `CrmSyncException.partialState()` et `CrmSyncException.avecEtat(CrmSyncState)`.
  - `CrmConnector.sync(CrmLead, CrmTarget, CrmSyncState)` et `CrmConnector.resolveAssignee(CrmAssignee, CrmTarget)`.
  - `CrmConnectorRegistry(List<CrmConnector>, CrmProperties)`.

- [x] **Step 1: Écrire le test de l'état partiel porté par l'exception**

Créer `backend/src/test/java/com/leadflow/crm/model/CrmSyncExceptionTest.java` :

```java
package com.leadflow.crm.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CrmSyncExceptionTest {

    @Test
    void sansEtatExpliciteLExceptionPorteUnEtatVierge() {
        CrmSyncException echec = new CrmSyncException("dolibarr", "instance injoignable", null);

        assertThat(echec.partialState()).isEqualTo(CrmSyncState.VIERGE);
    }

    @Test
    void avecEtatConserveLeMessageLeProviderEtLaCause() {
        Throwable cause = new IllegalStateException("socket fermee");
        CrmSyncException initiale = new CrmSyncException("dolibarr", "echec sur l'opportunite", cause);

        CrmSyncException enrichie = initiale.avecEtat(new CrmSyncState("42", "77", null));

        assertThat(enrichie.providerId()).isEqualTo("dolibarr");
        assertThat(enrichie.getMessage()).isEqualTo("echec sur l'opportunite");
        assertThat(enrichie.getCause()).isSameAs(cause);
        assertThat(enrichie.partialState().accountRef()).isEqualTo("42");
        assertThat(enrichie.partialState().contactRef()).isEqualTo("77");
        assertThat(enrichie.partialState().opportunityRef()).isNull();
    }
}
```

- [x] **Step 2: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=CrmSyncExceptionTest
```

Attendu : échec de compilation — `CrmSyncState` et `avecEtat` n'existent pas.

- [x] **Step 3: Créer les deux records du modèle pivot**

`backend/src/main/java/com/leadflow/crm/model/CrmSyncState.java` :

```java
package com.leadflow.crm.model;

/**
 * References deja obtenues dans l'ERP lors des tentatives precedentes.
 *
 * <p>C'est l'appelant qui reconstruit cet etat depuis la trace et le passe a l'adaptateur :
 * un adaptateur ne connait pas notre schema et ne lit jamais notre base. Chaque champ non
 * nul dispense l'adaptateur de recreer l'objet correspondant — c'est tout le mecanisme
 * d'idempotence au rejeu.
 */
public record CrmSyncState(String accountRef, String contactRef, String opportunityRef) {

    /** Aucune tentative anterieure exploitable : tout est a creer. */
    public static final CrmSyncState VIERGE = new CrmSyncState(null, null, null);
}
```

`backend/src/main/java/com/leadflow/crm/model/CrmAssignee.java` :

```java
package com.leadflow.crm.model;

/**
 * Commercial a traduire en identifiant utilisateur dans l'ERP cible.
 *
 * <p>Volontairement reduit a ce qui permet de le retrouver : les ERP n'exposent pas la
 * meme fiche utilisateur, et le pivot n'a pas a porter leurs differences.
 */
public record CrmAssignee(String fullName, String email) {
}
```

- [x] **Step 4: Enrichir `CrmSyncException`**

Remplacer le contenu de `backend/src/main/java/com/leadflow/crm/model/CrmSyncException.java` :

```java
package com.leadflow.crm.model;

/**
 * Echec de synchronisation vers un ERP. Levee par un adaptateur, elle laisse le message
 * repartir en retry puis en DLQ plutot que d'etre avalee silencieusement.
 *
 * <p>Elle transporte l'etat partiel obtenu avant l'echec : si le tiers a ete cree mais que
 * l'opportunite a echoue, l'appelant doit pouvoir tracer la reference du tiers, sans quoi
 * le rejeu le recreerait. Ne jamais y placer de secret : le message finit en base et dans
 * les journaux.
 */
public class CrmSyncException extends RuntimeException {

    private final String providerId;
    private final CrmSyncState partialState;

    public CrmSyncException(String providerId, String message, Throwable cause) {
        this(providerId, message, cause, CrmSyncState.VIERGE);
    }

    private CrmSyncException(
            String providerId, String message, Throwable cause, CrmSyncState partialState) {
        super(message, cause);
        this.providerId = providerId;
        this.partialState = partialState;
    }

    public String providerId() {
        return providerId;
    }

    /** References obtenues avant l'echec. Jamais nul : {@link CrmSyncState#VIERGE} par defaut. */
    public CrmSyncState partialState() {
        return partialState;
    }

    /** Meme echec, enrichi de ce qui avait deja ete cree. */
    public CrmSyncException avecEtat(CrmSyncState etat) {
        return new CrmSyncException(providerId, getMessage(), getCause(), etat);
    }
}
```

- [x] **Step 5: Lancer le test et vérifier qu'il passe**

```bash
./mvnw test -Dtest=CrmSyncExceptionTest
```

Attendu : 2 tests verts.

- [x] **Step 6: Écrire les tests du registre**

Remplacer `backend/src/test/java/com/leadflow/crm/CrmConnectorRegistryTest.java` :

```java
package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.config.CrmProperties;
import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrmConnectorRegistryTest {

    /** Connecteur factice : le registre ne doit dependre d'aucun adaptateur reel. */
    private static final class ConnecteurFactice implements CrmConnector {

        private final String providerId;

        private CrmLead leadRecu;
        private CrmTarget cibleRecue;
        private CrmSyncState etatRecu;

        private ConnecteurFactice(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
            this.leadRecu = lead;
            this.cibleRecue = target;
            this.etatRecu = previous;
            return new CrmSyncResult(providerId, "1", "2", "3", null, Instant.now());
        }

        @Override
        public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
            return "u-" + assignee.email();
        }
    }

    private static CrmProperties proprietes(boolean odooActive) {
        CrmProperties.Provider actif =
                new CrmProperties.Provider(true, Duration.ofSeconds(5), Duration.ofSeconds(15));
        CrmProperties.Provider odoo =
                new CrmProperties.Provider(odooActive, Duration.ofSeconds(5), Duration.ofSeconds(15));
        return new CrmProperties(Map.of("dolibarr", actif, "odoo", odoo));
    }

    private final ConnecteurFactice dolibarr = new ConnecteurFactice("dolibarr");

    private final ConnecteurFactice odoo = new ConnecteurFactice("odoo");

    private final CrmConnectorRegistry registry =
            new CrmConnectorRegistry(List.of(dolibarr, odoo), proprietes(true));

    @Test
    void resoutUnConnecteurParSonIdentifiant() {
        assertThat(registry.forProvider("odoo")).isSameAs(odoo);
    }

    @Test
    void listeLesFournisseursActives() {
        CrmConnectorRegistry avecOdooDesactive =
                new CrmConnectorRegistry(List.of(dolibarr, odoo), proprietes(false));

        assertThat(avecOdooDesactive.availableProviders()).containsExactly("dolibarr");
    }

    @Test
    void refuseUnFournisseurInconnuEnNommantLesDisponibles() {
        assertThatThrownBy(() -> registry.forProvider("salesforce"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("salesforce")
                .hasMessageContaining("dolibarr");
    }

    @Test
    void refuseUnFournisseurDesactiveAvecUnMessageDistinct() {
        CrmConnectorRegistry avecOdooDesactive =
                new CrmConnectorRegistry(List.of(dolibarr, odoo), proprietes(false));

        assertThatThrownBy(() -> avecOdooDesactive.forProvider("odoo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desactive")
                .hasMessageContaining("leadflow.crm.providers.odoo.enabled");
    }

    @Test
    void refuseUnIdentifiantNulSansLeverDeNullPointerException() {
        assertThatThrownBy(() -> registry.forProvider(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void transmetLeLeadLaCibleEtLEtatAnterieurAuConnecteur() {
        CrmTarget cible = new CrmTarget("dolibarr", Map.of("baseUrl", "http://client-a:8081"));
        CrmLead lead = new CrmLead("Acme", "Amina", "Bensalem", "amina@exemple.test",
                "+212600000000", "Demande de devis", "DEMANDE_DEVIS", 72, "MA", "industrie", "7");
        CrmSyncState etat = new CrmSyncState("42", null, null);

        registry.forProvider("dolibarr").sync(lead, cible, etat);

        assertThat(dolibarr.leadRecu).isSameAs(lead);
        assertThat(dolibarr.cibleRecue).isSameAs(cible);
        assertThat(dolibarr.etatRecu).isSameAs(etat);
        assertThat(odoo.cibleRecue).isNull();
    }
}
```

- [x] **Step 7: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=CrmConnectorRegistryTest
```

Attendu : échec de compilation — le constructeur du registre ne prend pas `CrmProperties`, et le port n'a ni le troisième argument ni `resolveAssignee`.

- [x] **Step 8: Élargir le port**

Remplacer les deux déclarations de méthode de `backend/src/main/java/com/leadflow/crm/CrmConnector.java` (garder l'en-tête de classe et son Javadoc) :

```java
    /**
     * Identifiant stable du fournisseur, tel qu'il apparait dans
     * {@code leadflow.crm.providers.<id>} de la configuration.
     */
    String providerId();

    /**
     * Cree le compte, le contact et l'opportunite dans l'instance ERP designee par
     * {@code target}.
     *
     * <p>{@code previous} porte ce qui a deja ete cree lors des tentatives precedentes :
     * toute reference non nulle dispense de recreer l'objet correspondant. En cas d'echec
     * partiel, l'implementation leve une {@link com.leadflow.crm.model.CrmSyncException}
     * enrichie de ce qu'elle a obtenu avant de tomber.
     *
     * @throws com.leadflow.crm.model.CrmSyncException si l'ERP refuse ou est injoignable
     */
    CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous);

    /**
     * Traduit un commercial en identifiant utilisateur dans l'ERP cible.
     *
     * @return l'identifiant, ou {@code null} si l'ERP ne connait pas ce commercial
     * @throws com.leadflow.crm.model.CrmSyncException si l'ERP est injoignable
     */
    String resolveAssignee(CrmAssignee assignee, CrmTarget target);
```

Ajouter les imports `com.leadflow.crm.model.CrmAssignee` et `com.leadflow.crm.model.CrmSyncState`.

- [x] **Step 9: Réécrire le registre**

Remplacer le corps de `backend/src/main/java/com/leadflow/crm/CrmConnectorRegistry.java` :

```java
package com.leadflow.crm;

import com.leadflow.config.CrmProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resout l'adaptateur a utiliser pour un lead donne.
 *
 * <p>Le pipeline appelle {@link #forProvider(String)} avec le fournisseur configure sur le
 * client concerne. Il n'y a pas de connecteur par defaut : tout lead appartient a un
 * client, et tout client nomme son fournisseur.
 *
 * <p>Un adaptateur present dans le classpath mais desactive par configuration est refuse
 * avec un message distinct de celui d'un fournisseur inconnu : les deux situations se
 * corrigent a des endroits differents.
 */
@Component
public class CrmConnectorRegistry {

    private final Map<String, CrmConnector> connectors;
    private final Set<String> enabled;

    public CrmConnectorRegistry(List<CrmConnector> connectors, CrmProperties properties) {
        this.connectors = connectors.stream()
                .collect(Collectors.toUnmodifiableMap(CrmConnector::providerId, Function.identity()));
        Map<String, CrmProperties.Provider> declares =
                properties.providers() == null ? Map.of() : properties.providers();
        this.enabled = declares.entrySet().stream()
                .filter(entree -> entree.getValue().enabled())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    public CrmConnector forProvider(String providerId) {
        if (providerId == null) {
            throw new IllegalArgumentException(
                    "Aucun fournisseur CRM demande : providerId est null. Disponibles : "
                            + availableProviders());
        }
        CrmConnector connector = connectors.get(providerId);
        if (connector == null) {
            throw new IllegalArgumentException(
                    "Aucun connecteur CRM pour '" + providerId + "'. Disponibles : "
                            + availableProviders());
        }
        if (!enabled.contains(providerId)) {
            throw new IllegalArgumentException(
                    "Le connecteur CRM '" + providerId + "' est desactive : "
                            + "leadflow.crm.providers." + providerId + ".enabled=false");
        }
        return connector;
    }

    /** Fournisseurs implementes ET actives, pour l'ecran Connecteurs du dashboard. */
    public Set<String> availableProviders() {
        return connectors.keySet().stream()
                .filter(enabled::contains)
                .collect(Collectors.toUnmodifiableSet());
    }
}
```

- [x] **Step 10: Lancer la suite complète**

```bash
./mvnw test
```

Attendu : 32 tests de base moins les 4 anciens du registre, plus 6 nouveaux du registre et 2 de l'exception — **36 tests, 0 échec**. Vérifier le total dans la sortie ; s'il est inférieur, une classe ne compile plus.

- [x] **Step 11: Commit**

```bash
git add backend/src/main/java/com/leadflow/crm backend/src/test/java/com/leadflow/crm
git commit -m "feat: le port CrmConnector recoit l'etat anterieur et resout le commercial"
```

---

## Task 3: Orchestrateur `CrmSyncService`

C'est la pièce qui rend F5 testable sans F2, F3 ni F4. Elle porte tout ce qui est propre à LeadFlow, pour que les adaptateurs n'en portent rien.

Deux points de conception à ne pas rater :

- **La trace s'écrit dans une transaction propre** (`REQUIRES_NEW`), dans un bean distinct. Sinon, quand `CrmSyncService` relaie l'exception à un appelant transactionnel — ce que fera le consommateur RabbitMQ en F3 — le `ROLLBACK` emporterait la ligne `FAILED`, et on perdrait à la fois le diagnostic et les références déjà obtenues. Un bean distinct est nécessaire : Spring ne proxie pas un appel de méthode interne.
- **La reconstruction de l'état prend la valeur non nulle la plus récente champ par champ**, et non les champs de la dernière ligne (spec §8).

**Files:**
- Create : `backend/src/main/java/com/leadflow/crm/CrmSyncService.java`
- Create : `backend/src/main/java/com/leadflow/crm/CrmSyncTraceWriter.java`
- Modify : `backend/src/main/java/com/leadflow/crm/CrmSyncAttemptRepository.java`
- Test (create) : `backend/src/test/java/com/leadflow/crm/CrmSyncServiceTest.java`

**Interfaces:**
- Consumes : `CrmConnectorRegistry.forProvider(String)`, `CrmSyncException.partialState()`, `CrmSyncState`, `CrmAssignee`, `ClientRepository`, `SalesRepRepository`, `LeadRepository`, `CrmSyncAttemptRepository`, `Lead`, `LeadStatus`, `CrmSyncAttemptStatus`.
- Produces : `CrmSyncService.synchronise(UUID leadId)` → `CrmSyncResult`, appelé par F3/F4 plus tard.

- [x] **Step 1: Ajouter le dérivé manquant au repository**

Dans `backend/src/main/java/com/leadflow/crm/CrmSyncAttemptRepository.java`, ajouter :

```java
    /**
     * Historique d'un lead pour UN fournisseur, du plus recent au plus ancien. C'est la
     * source de l'etat anterieur : un meme lead peut partir vers des fournisseurs
     * differents, et des references Dolibarr ne doivent jamais servir d'etat de depart
     * a Odoo.
     */
    List<CrmSyncAttempt> findByLeadIdAndProviderIdOrderByAttemptedAtDesc(
            UUID leadId, String providerId);
```

- [x] **Step 2: Écrire le test d'orchestration**

Créer `backend/src/test/java/com/leadflow/crm/CrmSyncServiceTest.java` :

```java
package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Le connecteur est factice : ce test prouve l'orchestration, pas la traduction ERP.
 * {@code @SpringBootTest} et non {@code @DataJpaTest} — les converters de chiffrement
 * sont des {@code @Component}.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, CrmSyncServiceTest.ConnecteurDeTest.class})
class CrmSyncServiceTest {

    static final class ConnecteurEspion implements CrmConnector {

        CrmLead leadRecu;
        CrmTarget cibleRecue;
        CrmSyncState etatRecu;
        CrmAssignee assigneeRecu;
        boolean echoue;

        @Override
        public String providerId() {
            return "dolibarr";
        }

        @Override
        public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
            this.leadRecu = lead;
            this.cibleRecue = target;
            this.etatRecu = previous;
            if (echoue) {
                throw new CrmSyncException("dolibarr", "opportunite refusee", null)
                        .avecEtat(new CrmSyncState("A-1", "C-1", null));
            }
            return new CrmSyncResult("dolibarr", "A-1", "C-1", "O-1", null, Instant.now());
        }

        @Override
        public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
            this.assigneeRecu = assignee;
            return "U-9";
        }
    }

    @TestConfiguration
    static class ConnecteurDeTest {
        @Bean
        ConnecteurEspion connecteurEspion() {
            return new ConnecteurEspion();
        }
    }

    @Autowired private CrmSyncService service;
    @Autowired private ConnecteurEspion connecteur;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private CrmSyncAttemptRepository attemptRepository;

    private UUID leadId;
    private UUID salesRepId;

    @BeforeEach
    void preparerUnLeadComplet() {
        connecteur.echoue = false;

        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Boutique de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-erp"));
        client = clientRepository.saveAndFlush(client);

        SalesRep commercial = new SalesRep();
        commercial.setClient(client);
        commercial.setFullName("Amina Bensalem");
        commercial.setEmail("amina-" + UUID.randomUUID() + "@demo.test");
        salesRepId = salesRepRepository.saveAndFlush(commercial).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(client.getId());
        evenement.setSource("test");
        evenement.setPayload(Map.of("email", "karim@acme.test"));
        evenement.setSignature("sig");
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = rawLeadEventRepository.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(client.getId());
        lead.setRawEventId(rawEventId);
        lead.setCompanyName("Acme");
        lead.setFirstName("Karim");
        lead.setLastName("Haddad");
        lead.setEmail("karim@acme.test");
        lead.setScore(72);
        lead.setStatus(LeadStatus.ROUTED);
        lead.setAssignedSalesRepId(salesRepId);
        leadId = leadRepository.saveAndFlush(lead).getId();
    }

    @Test
    void construitLaCibleDepuisLaConfigurationDechiffreeDuClient() {
        service.synchronise(leadId);

        assertThat(connecteur.cibleRecue.providerId()).isEqualTo("dolibarr");
        assertThat(connecteur.cibleRecue.settings()).containsEntry("baseUrl", "http://erp.test");
        assertThat(connecteur.cibleRecue.settings()).containsEntry("apiKey", "cle-erp");
    }

    @Test
    void traduitLeLeadVersLePivot() {
        service.synchronise(leadId);

        assertThat(connecteur.leadRecu.companyName()).isEqualTo("Acme");
        assertThat(connecteur.leadRecu.email()).isEqualTo("karim@acme.test");
        assertThat(connecteur.leadRecu.score()).isEqualTo(72);
        assertThat(connecteur.leadRecu.assigneeRef()).isEqualTo("U-9");
    }

    @Test
    void resoutPuisMemoriseLaReferenceDuCommercial() {
        service.synchronise(leadId);

        assertThat(connecteur.assigneeRecu.email())
                .isEqualTo(salesRepRepository.findById(salesRepId).orElseThrow().getEmail());
        assertThat(salesRepRepository.findById(salesRepId).orElseThrow().getCrmRef())
                .isEqualTo("U-9");
    }

    @Test
    void neResoutPasDeuxFoisLeMemeCommercial() {
        service.synchronise(leadId);
        connecteur.assigneeRecu = null;

        service.synchronise(leadId);

        assertThat(connecteur.assigneeRecu).isNull();
    }

    @Test
    void traceLeSuccesEtPasseLeLeadEnSynced() {
        CrmSyncResult resultat = service.synchronise(leadId);

        assertThat(resultat.opportunityRef()).isEqualTo("O-1");
        assertThat(leadRepository.findById(leadId).orElseThrow().getStatus())
                .isEqualTo(LeadStatus.SYNCED);
        List<CrmSyncAttempt> tentatives =
                attemptRepository.findByLeadIdAndProviderIdOrderByAttemptedAtDesc(leadId, "dolibarr");
        assertThat(tentatives).hasSize(1);
        assertThat(tentatives.getFirst().getStatus()).isEqualTo(CrmSyncAttemptStatus.SUCCESS);
        assertThat(tentatives.getFirst().getAccountRef()).isEqualTo("A-1");
    }

    @Test
    void traceLEchecAvecLesReferencesDejaObtenuesPuisRelaieLException() {
        connecteur.echoue = true;

        assertThatThrownBy(() -> service.synchronise(leadId))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("opportunite refusee");

        List<CrmSyncAttempt> tentatives =
                attemptRepository.findByLeadIdAndProviderIdOrderByAttemptedAtDesc(leadId, "dolibarr");
        assertThat(tentatives).hasSize(1);
        assertThat(tentatives.getFirst().getStatus()).isEqualTo(CrmSyncAttemptStatus.FAILED);
        assertThat(tentatives.getFirst().getAccountRef()).isEqualTo("A-1");
        assertThat(tentatives.getFirst().getContactRef()).isEqualTo("C-1");
        assertThat(tentatives.getFirst().getOpportunityRef()).isNull();
        assertThat(tentatives.getFirst().getErrorMessage()).contains("opportunite refusee");
        assertThat(leadRepository.findById(leadId).orElseThrow().getStatus())
                .isEqualTo(LeadStatus.FAILED);
    }

    @Test
    void reconstruitLEtatChampParChampSurToutesLesTentatives() {
        connecteur.echoue = true;
        assertThatThrownBy(() -> service.synchronise(leadId)).isInstanceOf(CrmSyncException.class);

        connecteur.echoue = false;
        service.synchronise(leadId);

        assertThat(connecteur.etatRecu.accountRef()).isEqualTo("A-1");
        assertThat(connecteur.etatRecu.contactRef()).isEqualTo("C-1");
        assertThat(connecteur.etatRecu.opportunityRef()).isNull();
    }
}
```

- [x] **Step 3: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=CrmSyncServiceTest
```

Attendu : échec de compilation — `CrmSyncService` n'existe pas.

- [x] **Step 4: Écrire l'écrivain de trace**

Créer `backend/src/main/java/com/leadflow/crm/CrmSyncTraceWriter.java` :

```java
package com.leadflow.crm;

import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecrit la trace d'une tentative dans une transaction qui lui est propre.
 *
 * <p>Bean distinct de {@link CrmSyncService} a dessein : Spring ne proxie pas un appel de
 * methode interne, et {@code REQUIRES_NEW} est indispensable ici. Quand l'echec remonte a
 * un appelant transactionnel — le consommateur RabbitMQ de F3 — son rollback emporterait
 * sinon la ligne {@code FAILED}, donc le diagnostic et les references deja obtenues.
 */
@Component
public class CrmSyncTraceWriter {

    private final CrmSyncAttemptRepository attemptRepository;
    private final LeadRepository leadRepository;

    public CrmSyncTraceWriter(
            CrmSyncAttemptRepository attemptRepository, LeadRepository leadRepository) {
        this.attemptRepository = attemptRepository;
        this.leadRepository = leadRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succes(UUID leadId, CrmSyncResult resultat) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId(resultat.providerId());
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setAccountRef(resultat.accountRef());
        tentative.setContactRef(resultat.contactRef());
        tentative.setOpportunityRef(resultat.opportunityRef());
        tentative.setTaskRef(resultat.taskRef());
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.save(tentative);
        changeStatut(leadId, LeadStatus.SYNCED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void echec(UUID leadId, String providerId, CrmSyncState partiel, String message) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId(providerId);
        tentative.setStatus(CrmSyncAttemptStatus.FAILED);
        tentative.setAccountRef(partiel.accountRef());
        tentative.setContactRef(partiel.contactRef());
        tentative.setOpportunityRef(partiel.opportunityRef());
        tentative.setErrorMessage(message);
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.save(tentative);
        changeStatut(leadId, LeadStatus.FAILED);
    }

    private void changeStatut(UUID leadId, LeadStatus statut) {
        Lead lead = leadRepository.findById(leadId).orElseThrow();
        lead.setStatus(statut);
        leadRepository.save(lead);
    }
}
```

- [x] **Step 5: Écrire l'orchestrateur**

Créer `backend/src/main/java/com/leadflow/crm/CrmSyncService.java` :

```java
package com.leadflow.crm;

import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Pousse un lead vers l'instance ERP de son client.
 *
 * <p>Porte tout ce qui est propre a LeadFlow — resolution de la cible, etat anterieur,
 * reference du commercial, trace — pour que les adaptateurs n'en portent rien. Ne choisit
 * aucun commercial (F4), ne consomme aucune file (F3), ne reessaie pas : la reprise sur
 * echec appartient a la file, 3 tentatives puis DLQ.
 *
 * <p>Volontairement non transactionnel : la trace s'ecrit via {@link CrmSyncTraceWriter},
 * en transaction propre, pour survivre a la remontee de l'exception.
 */
@Service
public class CrmSyncService {

    private final LeadRepository leadRepository;
    private final ClientRepository clientRepository;
    private final SalesRepRepository salesRepRepository;
    private final CrmSyncAttemptRepository attemptRepository;
    private final CrmConnectorRegistry registry;
    private final CrmSyncTraceWriter trace;

    public CrmSyncService(
            LeadRepository leadRepository,
            ClientRepository clientRepository,
            SalesRepRepository salesRepRepository,
            CrmSyncAttemptRepository attemptRepository,
            CrmConnectorRegistry registry,
            CrmSyncTraceWriter trace) {
        this.leadRepository = leadRepository;
        this.clientRepository = clientRepository;
        this.salesRepRepository = salesRepRepository;
        this.attemptRepository = attemptRepository;
        this.registry = registry;
        this.trace = trace;
    }

    public CrmSyncResult synchronise(UUID leadId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Lead inconnu : " + leadId));
        Client client = clientRepository.findById(lead.getClientId())
                .orElseThrow(() -> new IllegalStateException(
                        "Le lead " + leadId + " reference un client inexistant"));

        CrmConnector connector = registry.forProvider(client.getCrmProviderId());
        CrmTarget cible = new CrmTarget(client.getCrmProviderId(), client.getCrmConfig());
        CrmSyncState anterieur = etatAnterieur(leadId, client.getCrmProviderId());
        String assigneeRef = referenceDuCommercial(lead, connector, cible);

        try {
            CrmSyncResult resultat = connector.sync(versPivot(lead, assigneeRef), cible, anterieur);
            trace.succes(leadId, resultat);
            return resultat;
        } catch (CrmSyncException echec) {
            trace.echec(leadId, client.getCrmProviderId(), fusionne(anterieur, echec.partialState()),
                    echec.getMessage());
            throw echec;
        }
    }

    /**
     * Prend, champ par champ, la valeur non nulle la plus recente. On ne peut pas se
     * contenter de la derniere ligne : une tentative echouee tot n'a que le compte, alors
     * qu'une tentative plus ancienne avait deja obtenu le contact.
     */
    private CrmSyncState etatAnterieur(UUID leadId, String providerId) {
        List<CrmSyncAttempt> tentatives =
                attemptRepository.findByLeadIdAndProviderIdOrderByAttemptedAtDesc(leadId, providerId);
        String compte = null;
        String contact = null;
        String opportunite = null;
        for (CrmSyncAttempt tentative : tentatives) {
            compte = compte != null ? compte : tentative.getAccountRef();
            contact = contact != null ? contact : tentative.getContactRef();
            opportunite = opportunite != null ? opportunite : tentative.getOpportunityRef();
        }
        return new CrmSyncState(compte, contact, opportunite);
    }

    /** L'etat partiel de l'echec prime, mais ne doit rien perdre de ce qu'on savait deja. */
    private CrmSyncState fusionne(CrmSyncState anterieur, CrmSyncState partiel) {
        return new CrmSyncState(
                partiel.accountRef() != null ? partiel.accountRef() : anterieur.accountRef(),
                partiel.contactRef() != null ? partiel.contactRef() : anterieur.contactRef(),
                partiel.opportunityRef() != null
                        ? partiel.opportunityRef()
                        : anterieur.opportunityRef());
    }

    /** Resolu une seule fois par commercial et par instance : le resultat est memorise. */
    private String referenceDuCommercial(Lead lead, CrmConnector connector, CrmTarget cible) {
        if (lead.getAssignedSalesRepId() == null) {
            return null;
        }
        SalesRep commercial = salesRepRepository.findById(lead.getAssignedSalesRepId())
                .orElseThrow(() -> new IllegalStateException(
                        "Le lead " + lead.getId() + " reference un commercial inexistant"));
        if (commercial.getCrmRef() != null) {
            return commercial.getCrmRef();
        }
        String reference = connector.resolveAssignee(
                new CrmAssignee(commercial.getFullName(), commercial.getEmail()), cible);
        if (reference != null) {
            commercial.setCrmRef(reference);
            salesRepRepository.save(commercial);
        }
        return reference;
    }

    private CrmLead versPivot(Lead lead, String assigneeRef) {
        return new CrmLead(
                lead.getCompanyName(),
                lead.getFirstName(),
                lead.getLastName(),
                lead.getEmail(),
                lead.getPhone(),
                lead.getMessage(),
                lead.getDetectedIntent(),
                lead.getScore(),
                lead.getCountryCode(),
                lead.getSector(),
                assigneeRef);
    }
}
```

- [x] **Step 6: Lancer le test et vérifier qu'il passe**

```bash
./mvnw test -Dtest=CrmSyncServiceTest
```

Attendu : 7 tests verts. La fixture doit satisfaire les colonnes `NOT NULL` de `V1` : `source`, `payload` (une `Map`, sérialisée en `jsonb`), `signature` et `received_at`. En cas d'échec de persistance, ouvrir `backend/src/main/java/com/leadflow/capture/RawLeadEvent.java` et aligner les setters sur les champs réels.

- [x] **Step 7: Lancer la suite complète et commiter**

```bash
./mvnw test
```

Attendu : **43 tests, 0 échec**.

```bash
git add backend/src/main/java/com/leadflow/crm backend/src/test/java/com/leadflow/crm
git commit -m "feat: orchestrateur de synchronisation ERP avec trace en transaction propre"
```

---

## Task 4: Transport Dolibarr

Deux couches, et cette tâche ne fait que la couche basse : parler HTTP à une instance Dolibarr, sans rien savoir du modèle pivot. Le `RestClient.Builder` est fourni par un bean dédié pour que les timeouts de `leadflow.crm.providers.dolibarr` soient réellement appliqués — aujourd'hui ces réglages ne sont lus par personne — tout en restant remplaçable par `MockRestServiceServer` dans les tests.

**Files:**
- Create : `backend/src/main/java/com/leadflow/crm/CrmHttpConfig.java`
- Create : `backend/src/main/java/com/leadflow/crm/dolibarr/DolibarrClient.java`
- Test (create) : `backend/src/test/java/com/leadflow/crm/dolibarr/DolibarrClientTest.java`

**Interfaces:**
- Consumes : `CrmProperties`, `CrmTarget`, `CrmSyncException`.
- Produces :
  - `CrmHttpConfig.requestFactory(CrmProperties.Provider)` (package-private, statique).
  - Beans `RestClient.Builder` nommés `dolibarrRestClientBuilder` et `odooRestClientBuilder`.
  - `DolibarrClient.creeTiers(CrmTarget, Map<String,Object>) → String`
  - `DolibarrClient.creeContact(CrmTarget, Map<String,Object>) → String`
  - `DolibarrClient.creeOpportunite(CrmTarget, Map<String,Object>) → String`
  - `DolibarrClient.lieResponsable(CrmTarget, String opportuniteRef, String utilisateurRef)`
  - `DolibarrClient.chercheUtilisateurParEmail(CrmTarget, String) → String` (nul si absent)

- [x] **Step 1: Écrire le test du transport**

Créer `backend/src/test/java/com/leadflow/crm/dolibarr/DolibarrClientTest.java` :

```java
package com.leadflow.crm.dolibarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmTarget;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DolibarrClientTest {

    private static final CrmTarget CIBLE = new CrmTarget(
            "dolibarr",
            Map.of("baseUrl", "http://erp.test/api/index.php", "apiKey", "cle-de-test"));

    private MockRestServiceServer serveur;
    private DolibarrClient client;

    @BeforeEach
    void preparer() {
        RestClient.Builder builder = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(builder).build();
        client = new DolibarrClient(builder);
    }

    @Test
    void creeUnTiersEtRenvoieSonIdentifiant() {
        serveur.expect(requestTo("http://erp.test/api/index.php/thirdparties"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("DOLAPIKEY", "cle-de-test"))
                .andExpect(jsonPath("$.name").value("Acme"))
                .andRespond(withSuccess("42", MediaType.APPLICATION_JSON));

        String reference = client.creeTiers(CIBLE, Map.of("name", "Acme"));

        assertThat(reference).isEqualTo("42");
        serveur.verify();
    }

    @Test
    void chercheUnUtilisateurParEmail() {
        serveur.expect(requestTo(org.hamcrest.Matchers.containsString("/users")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("DOLAPIKEY", "cle-de-test"))
                .andRespond(withSuccess(
                        "[{\"id\":\"9\",\"email\":\"amina@demo.test\"}]", MediaType.APPLICATION_JSON));

        assertThat(client.chercheUtilisateurParEmail(CIBLE, "amina@demo.test")).isEqualTo("9");
        serveur.verify();
    }

    @Test
    void renvoieNulQuandAucunUtilisateurNeCorrespond() {
        serveur.expect(requestTo(org.hamcrest.Matchers.containsString("/users")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.chercheUtilisateurParEmail(CIBLE, "inconnu@demo.test")).isNull();
    }

    @Test
    void traduitUneErreurHttpEnCrmSyncExceptionSansDivulguerLaCle() {
        serveur.expect(requestTo("http://erp.test/api/index.php/thirdparties"))
                .andRespond(withServerError().body("{\"error\":{\"message\":\"champ manquant\"}}"));

        assertThatThrownBy(() -> client.creeTiers(CIBLE, Map.of("name", "Acme")))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("thirdparties")
                .hasMessageNotContaining("cle-de-test");
    }

    @Test
    void refuseUneCibleSansUrlNiCle() {
        CrmTarget incomplete = new CrmTarget("dolibarr", Map.of("baseUrl", "http://erp.test"));

        assertThatThrownBy(() -> client.creeTiers(incomplete, Map.of("name", "Acme")))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void lieLeResponsableParUnAppelDedie() {
        // Releve de la sonde : fk_user_resp est ignore a la creation comme en PUT.
        serveur.expect(requestTo(org.hamcrest.Matchers.containsString("/projects/99/contacts")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(requestTo(org.hamcrest.Matchers.containsString("fk_socpeople=9")))
                .andExpect(requestTo(org.hamcrest.Matchers.containsString("PROJECTLEADER")))
                .andRespond(withSuccess("{\"id\":\"99\"}", MediaType.APPLICATION_JSON));

        client.lieResponsable(CIBLE, "99", "9");

        serveur.verify();
    }

    @Test
    void envoieUnCorpsJson() {
        serveur.expect(requestTo("http://erp.test/api/index.php/contacts"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.socid").value("42"))
                .andRespond(withSuccess("77", MediaType.APPLICATION_JSON));

        assertThat(client.creeContact(CIBLE, Map.of("socid", "42"))).isEqualTo("77");
    }
}
```

- [x] **Step 2: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=DolibarrClientTest
```

Attendu : échec de compilation — `DolibarrClient` n'existe pas.

- [x] **Step 3: Écrire la configuration HTTP**

Créer `backend/src/main/java/com/leadflow/crm/CrmHttpConfig.java` :

```java
package com.leadflow.crm;

import com.leadflow.config.CrmProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Un {@code RestClient.Builder} par fournisseur, portant ses propres delais.
 *
 * <p>Les timeouts sont poses ici et non dans l'adaptateur : un test peut ainsi injecter un
 * builder nu branche sur {@code MockRestServiceServer} sans que la fabrique de requetes de
 * production interfere. L'URL de l'instance n'est pas connue a ce stade — elle vient de la
 * ligne client — donc aucun {@code baseUrl} n'est fixe ici.
 */
@Configuration
public class CrmHttpConfig {

    @Bean
    RestClient.Builder dolibarrRestClientBuilder(CrmProperties properties) {
        return RestClient.builder().requestFactory(requestFactory(properties, "dolibarr"));
    }

    @Bean
    RestClient.Builder odooRestClientBuilder(CrmProperties properties) {
        return RestClient.builder().requestFactory(requestFactory(properties, "odoo"));
    }

    static ClientHttpRequestFactory requestFactory(CrmProperties properties, String providerId) {
        CrmProperties.Provider provider =
                properties.providers() == null ? null : properties.providers().get(providerId);
        if (provider == null) {
            throw new IllegalStateException(
                    "leadflow.crm.providers." + providerId + " est absente de la configuration");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(provider.connectTimeout());
        factory.setReadTimeout(provider.readTimeout());
        return factory;
    }
}
```

- [x] **Step 4: Écrire le transport Dolibarr**

Créer `backend/src/main/java/com/leadflow/crm/dolibarr/DolibarrClient.java` :

```java
package com.leadflow.crm.dolibarr;

import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmTarget;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Transport REST vers une instance Dolibarr. Ne connait pas le modele pivot : il recoit
 * des cartes deja traduites par {@link DolibarrConnector} et rend des identifiants.
 *
 * <p>Dolibarr renvoie l'identifiant d'un objet cree sous forme de nombre nu dans le corps
 * de la reponse ; il est normalise en chaine ici, parce que le pivot manipule des
 * references opaques.
 */
@Component
public class DolibarrClient {

    static final String PROVIDER_ID = "dolibarr";

    private final RestClient.Builder builder;

    public DolibarrClient(@Qualifier("dolibarrRestClientBuilder") RestClient.Builder builder) {
        this.builder = builder;
    }

    public String creeTiers(CrmTarget target, Map<String, Object> corps) {
        return cree(target, "/thirdparties", corps);
    }

    public String creeContact(CrmTarget target, Map<String, Object> corps) {
        return cree(target, "/contacts", corps);
    }

    public String creeOpportunite(CrmTarget target, Map<String, Object> corps) {
        return cree(target, "/projects", corps);
    }

    /**
     * Assigne un utilisateur interne a une opportunite.
     *
     * <p>Appel distinct parce que Dolibarr ignore {@code fk_user_resp}, a la creation comme
     * en modification : la sonde de la tache 1 l'a verifie dans les deux sens. C'est le seul
     * endroit de F5 ou une etape de {@code sync} compte deux appels.
     */
    public void lieResponsable(CrmTarget target, String opportuniteRef, String utilisateurRef) {
        try {
            restClient(target)
                    .post()
                    .uri(uri -> uri.path("/projects/" + opportuniteRef + "/contacts")
                            .queryParam("fk_socpeople", utilisateurRef)
                            .queryParam("type_contact", "PROJECTLEADER")
                            .queryParam("source", "internal")
                            .build())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw echec("/projects/" + opportuniteRef + "/contacts", e);
        }
    }

    /** @return l'identifiant de l'utilisateur, ou {@code null} si l'ERP n'en connait aucun. */
    @SuppressWarnings("unchecked")
    public String chercheUtilisateurParEmail(CrmTarget target, String email) {
        String filtre = "(t.email:=:'" + email.replace("'", "") + "')";
        try {
            List<Map<String, Object>> reponse = restClient(target)
                    .get()
                    .uri(uri -> uri.path("/users").queryParam("sqlfilters", filtre).build())
                    .retrieve()
                    .body(List.class);
            if (reponse == null || reponse.isEmpty()) {
                return null;
            }
            Object id = reponse.getFirst().get("id");
            return id == null ? null : String.valueOf(id);
        } catch (RestClientException e) {
            throw echec("/users", e);
        }
    }

    private String cree(CrmTarget target, String chemin, Map<String, Object> corps) {
        try {
            Object identifiant = restClient(target)
                    .post()
                    .uri(chemin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corps)
                    .retrieve()
                    .body(Object.class);
            if (identifiant == null) {
                throw new CrmSyncException(
                        PROVIDER_ID, "Dolibarr n'a renvoye aucun identifiant sur " + chemin, null);
            }
            return String.valueOf(identifiant);
        } catch (RestClientException e) {
            throw echec(chemin, e);
        }
    }

    private RestClient restClient(CrmTarget target) {
        return builder.clone()
                .baseUrl(reglage(target, "baseUrl"))
                .defaultHeader("DOLAPIKEY", reglage(target, "apiKey"))
                .build();
    }

    /** La valeur n'apparait jamais dans le message : {@code crm_config} contient des secrets. */
    private String reglage(CrmTarget target, String cle) {
        String valeur = target.settings() == null ? null : target.settings().get(cle);
        if (valeur == null || valeur.isBlank()) {
            throw new CrmSyncException(
                    PROVIDER_ID, "Reglage '" + cle + "' absent de la configuration du client", null);
        }
        return valeur;
    }

    private CrmSyncException echec(String chemin, Exception cause) {
        return new CrmSyncException(
                PROVIDER_ID, "Appel Dolibarr en echec sur " + chemin, cause);
    }
}
```

- [x] **Step 5: Lancer le test et vérifier qu'il passe**

```bash
./mvnw test -Dtest=DolibarrClientTest
```

Attendu : 7 tests verts. La forme `sqlfilters=(t.email:=:'…')` a été vérifiée par la sonde, tout comme la réponse `[]` en `200` quand aucun utilisateur ne correspond.

- [x] **Step 6: Lancer la suite complète et commiter**

```bash
./mvnw test
```

Attendu : **50 tests, 0 échec**.

```bash
git add backend/src/main/java/com/leadflow/crm backend/src/test/java/com/leadflow/crm
git commit -m "feat: transport REST vers Dolibarr avec delais configures"
```

---

## Task 5: Adaptateur Dolibarr

La couche de traduction. Elle ne fait pas de HTTP : elle transforme le pivot en cartes de champs Dolibarr, saute ce qui existe déjà, et enrichit l'exception de ce qu'elle a obtenu avant de tomber.

**Files:**
- Create : `backend/src/main/java/com/leadflow/crm/dolibarr/DolibarrConnector.java`
- Test (create) : `backend/src/test/java/com/leadflow/crm/dolibarr/DolibarrConnectorTest.java`

**Interfaces:**
- Consumes : `DolibarrClient` (tâche 4), `CrmConnector` (tâche 2), `CrmLead`, `CrmTarget`, `CrmSyncState`, `CrmSyncResult`, `CrmAssignee`.
- Produces : bean `DolibarrConnector` avec `providerId() == "dolibarr"`, collecté automatiquement par `CrmConnectorRegistry`.

- [x] **Step 1: Écrire le test de traduction**

Créer `backend/src/test/java/com/leadflow/crm/dolibarr/DolibarrConnectorTest.java` :

```java
package com.leadflow.crm.dolibarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DolibarrConnectorTest {

    private static final CrmTarget CIBLE = new CrmTarget(
            "dolibarr", Map.of("baseUrl", "http://erp.test", "apiKey", "cle"));

    /** Faux transport : ce test porte sur la traduction, pas sur HTTP. */
    private static final class TransportFactice extends DolibarrClient {

        private final List<String> appels = new ArrayList<>();
        private Map<String, Object> corpsTiers;
        private Map<String, Object> corpsContact;
        private Map<String, Object> corpsOpportunite;
        private String responsableLie;
        private boolean echoueSurOpportunite;

        private TransportFactice() {
            super(org.springframework.web.client.RestClient.builder());
        }

        @Override
        public String creeTiers(CrmTarget target, Map<String, Object> corps) {
            appels.add("tiers");
            corpsTiers = corps;
            return "42";
        }

        @Override
        public String creeContact(CrmTarget target, Map<String, Object> corps) {
            appels.add("contact");
            corpsContact = corps;
            return "77";
        }

        @Override
        public String creeOpportunite(CrmTarget target, Map<String, Object> corps) {
            appels.add("opportunite");
            corpsOpportunite = corps;
            if (echoueSurOpportunite) {
                throw new CrmSyncException("dolibarr", "projet refuse", null);
            }
            return "99";
        }

        @Override
        public void lieResponsable(CrmTarget target, String opportuniteRef, String utilisateurRef) {
            appels.add("responsable");
            responsableLie = utilisateurRef;
        }

        @Override
        public String chercheUtilisateurParEmail(CrmTarget target, String email) {
            appels.add("utilisateur");
            return "9";
        }
    }

    private static CrmLead lead(String companyName) {
        return new CrmLead(companyName, "Amina", "Bensalem", "amina@acme.test", "+212600000000",
                "Je veux un devis", "DEMANDE_DEVIS", 72, "MA", "industrie", "9");
    }

    private final TransportFactice transport = new TransportFactice();

    private final DolibarrConnector connecteur = new DolibarrConnector(transport);

    @Test
    void seDeclareSousLIdentifiantDolibarr() {
        assertThat(connecteur.providerId()).isEqualTo("dolibarr");
    }

    @Test
    void creeLeTiersLeContactPuisLOpportunite() {
        CrmSyncResult resultat = connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.appels).containsExactly("tiers", "contact", "opportunite", "responsable");
        assertThat(resultat.accountRef()).isEqualTo("42");
        assertThat(resultat.contactRef()).isEqualTo("77");
        assertThat(resultat.opportunityRef()).isEqualTo("99");
        assertThat(resultat.taskRef()).isNull();
        assertThat(resultat.providerId()).isEqualTo("dolibarr");
    }

    @Test
    void rattacheLeContactAuTiersEtMarqueLeTiersProspect() {
        connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.corpsTiers).containsEntry("name", "Acme");
        assertThat(transport.corpsTiers).containsEntry("client", "2");
        assertThat(transport.corpsContact).containsEntry("socid", "42");
        assertThat(transport.corpsContact).containsEntry("lastname", "Bensalem");
        assertThat(transport.corpsOpportunite).containsEntry("socid", "42");
    }

    @Test
    void donneUneReferenceUniqueALOpportunite() {
        // Releve de la sonde : sans `ref`, Dolibarr repond 400 ; en doublon, 500.
        connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);
        Object premiere = transport.corpsOpportunite.get("ref");

        TransportFactice second = new TransportFactice();
        new DolibarrConnector(second).sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(premiere).asString().startsWith("LF-");
        assertThat(second.corpsOpportunite.get("ref")).isNotEqualTo(premiere);
    }

    @Test
    void assigneLeCommercialALOpportuniteParUnAppelDedie() {
        connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.responsableLie).isEqualTo("9");
    }

    @Test
    void nommeLeTiersDApresLaPersonneQuandAucuneSocieteNEstFournie() {
        connecteur.sync(lead(null), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.corpsTiers).containsEntry("name", "Amina Bensalem");
    }

    @Test
    void neRecreeRienDeCeQuiExisteDeja() {
        CrmSyncResult resultat =
                connecteur.sync(lead("Acme"), CIBLE, new CrmSyncState("42", "77", null));

        assertThat(transport.appels).containsExactly("opportunite", "responsable");
        assertThat(resultat.accountRef()).isEqualTo("42");
        assertThat(resultat.contactRef()).isEqualTo("77");
    }

    @Test
    void neFaitAucunAppelQuandToutExisteDeja() {
        CrmSyncResult resultat =
                connecteur.sync(lead("Acme"), CIBLE, new CrmSyncState("42", "77", "99"));

        assertThat(transport.appels).isEmpty();
        assertThat(resultat.opportunityRef()).isEqualTo("99");
    }

    @Test
    void enrichitLExceptionDeCeQuiAvaitDejaEteCree() {
        transport.echoueSurOpportunite = true;

        assertThatThrownBy(() -> connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE))
                .isInstanceOf(CrmSyncException.class)
                .satisfies(echec -> {
                    CrmSyncState partiel = ((CrmSyncException) echec).partialState();
                    assertThat(partiel.accountRef()).isEqualTo("42");
                    assertThat(partiel.contactRef()).isEqualTo("77");
                    assertThat(partiel.opportunityRef()).isNull();
                });
    }

    @Test
    void resoutLeCommercialParSonEmail() {
        String reference = connecteur.resolveAssignee(
                new CrmAssignee("Amina Bensalem", "amina@demo.test"), CIBLE);

        assertThat(reference).isEqualTo("9");
        assertThat(transport.appels).containsExactly("utilisateur");
    }
}
```

- [x] **Step 2: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=DolibarrConnectorTest
```

Attendu : échec de compilation — `DolibarrConnector` n'existe pas.

- [x] **Step 3: Écrire l'adaptateur**

Créer `backend/src/main/java/com/leadflow/crm/dolibarr/DolibarrConnector.java` :

```java
package com.leadflow.crm.dolibarr;

import com.leadflow.crm.CrmConnector;
import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Traduction du modele pivot vers le vocabulaire de Dolibarr.
 *
 * <p>Dolibarr separe le Tiers et le Contact en deux endpoints, et n'a pas d'objet
 * « opportunite » de plein droit : ce qui s'en rapproche est le projet dote des champs
 * d'opportunite. Ces divergences se resolvent ici, jamais en amont.
 */
@Component
public class DolibarrConnector implements CrmConnector {

    /** Statut « prospect » d'un tiers Dolibarr. */
    private static final String TIERS_PROSPECT = "2";

    private final DolibarrClient client;

    public DolibarrConnector(DolibarrClient client) {
        this.client = client;
    }

    @Override
    public String providerId() {
        return DolibarrClient.PROVIDER_ID;
    }

    @Override
    public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
        String compte = previous.accountRef();
        String contact = previous.contactRef();
        String opportunite = previous.opportunityRef();
        try {
            if (compte == null) {
                compte = client.creeTiers(target, corpsTiers(lead));
            }
            if (contact == null) {
                contact = client.creeContact(target, corpsContact(lead, compte));
            }
            if (opportunite == null) {
                opportunite = client.creeOpportunite(target, corpsOpportunite(lead, compte));
                if (lead.assigneeRef() != null) {
                    client.lieResponsable(target, opportunite, lead.assigneeRef());
                }
            }
        } catch (CrmSyncException echec) {
            throw echec.avecEtat(new CrmSyncState(compte, contact, opportunite));
        }
        return new CrmSyncResult(
                providerId(), compte, contact, opportunite, null, Instant.now());
    }

    @Override
    public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
        return client.chercheUtilisateurParEmail(target, assignee.email());
    }

    private Map<String, Object> corpsTiers(CrmLead lead) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("name", nomDuTiers(lead));
        corps.put("client", TIERS_PROSPECT);
        corps.put("email", lead.email());
        if (lead.phone() != null) {
            corps.put("phone", lead.phone());
        }
        if (lead.countryCode() != null) {
            corps.put("country_code", lead.countryCode());
        }
        corps.put("note_private", note(lead));
        return corps;
    }

    /**
     * Dolibarr exige un tiers pour rattacher un contact. Un formulaire B2C n'en fournit
     * pas : on nomme alors le tiers d'apres la personne. Le pivot n'a pas a connaitre
     * cette contrainte.
     */
    private String nomDuTiers(CrmLead lead) {
        if (lead.companyName() != null && !lead.companyName().isBlank()) {
            return lead.companyName();
        }
        String nom = (valeur(lead.firstName()) + " " + valeur(lead.lastName())).trim();
        return nom.isBlank() ? lead.email() : nom;
    }

    private Map<String, Object> corpsContact(CrmLead lead, String compte) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("socid", compte);
        corps.put("lastname", valeur(lead.lastName()));
        corps.put("firstname", valeur(lead.firstName()));
        corps.put("email", lead.email());
        if (lead.phone() != null) {
            corps.put("phone_pro", lead.phone());
        }
        return corps;
    }

    private Map<String, Object> corpsOpportunite(CrmLead lead, String compte) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("ref", reference());
        corps.put("socid", compte);
        corps.put("title", lead.detectedIntent() == null ? "Lead LeadFlow" : lead.detectedIntent());
        corps.put("usage_opportunity", "1");
        corps.put("opp_status", "1");
        corps.put("note_private", note(lead));
        return corps;
    }

    /**
     * Dolibarr exige une {@code ref} sur un projet, et la refuse en doublon (contrainte
     * {@code uk_projet_ref}). Elle est tiree au hasard plutot que derivee du lead : le
     * pivot ne porte pas d'identifiant, et la non-recreation au rejeu est deja garantie en
     * amont par {@code CrmSyncState}. Une opportunite ne peut donc etre creee deux fois que
     * si la reponse de Dolibarr s'est perdue apres coup — cas ou une ref stable aurait, elle,
     * fait echouer le rejeu au lieu de le laisser passer.
     */
    private String reference() {
        return "LF-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** Le score n'a pas d'equivalent chez Dolibarr : il finit en texte, pas en champ. */
    private String note(CrmLead lead) {
        return "LeadFlow — intention : " + valeur(lead.detectedIntent())
                + " | score : " + lead.score()
                + (lead.sector() == null ? "" : " | secteur : " + lead.sector())
                + "\n" + valeur(lead.message());
    }

    private String valeur(String texte) {
        return texte == null ? "" : texte;
    }
}
```

- [x] **Step 4: Lancer le test et vérifier qu'il passe**

```bash
./mvnw test -Dtest=DolibarrConnectorTest
```

Attendu : 10 tests verts.

- [x] **Step 5: Vérifier que le registre voit l'adaptateur**

```bash
./mvnw test -Dtest=CrmConnectorRegistryTest
./mvnw test
```

Attendu : suite complète à **60 tests, 0 échec**. `BackendApplicationTests` prouve au passage que le contexte démarre avec un vrai adaptateur enregistré.

- [x] **Step 6: Commit**

```bash
git add backend/src/main/java/com/leadflow/crm/dolibarr backend/src/test/java/com/leadflow/crm/dolibarr
git commit -m "feat: adaptateur Dolibarr, traduction du pivot et idempotence au rejeu"
```

---

## Task 6: Transport Odoo

Le JSON-RPC d'Odoo demande deux appels : `common.authenticate` pour obtenir un `uid`, puis `object.execute_kw` pour chaque opération. Le piège central de la feature est ici : **Odoo répond `HTTP 200` avec un objet `error` dans le corps**. Un transport qui se fie au code de statut avalerait les erreurs.

**Files:**
- Create : `backend/src/main/java/com/leadflow/crm/odoo/OdooClient.java`
- Test (create) : `backend/src/test/java/com/leadflow/crm/odoo/OdooClientTest.java`

**Interfaces:**
- Consumes : bean `odooRestClientBuilder` (tâche 4), `CrmTarget`, `CrmSyncException`.
- Produces :
  - `OdooClient.authentifie(CrmTarget) → int`
  - `OdooClient.cree(CrmTarget, int uid, String modele, Map<String,Object> champs) → String`
  - `OdooClient.chercheUtilisateurParEmail(CrmTarget, int uid, String email) → String` (nul si absent)

- [x] **Step 1: Écrire le test du transport**

Créer `backend/src/test/java/com/leadflow/crm/odoo/OdooClientTest.java` :

```java
package com.leadflow.crm.odoo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmTarget;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OdooClientTest {

    private static final CrmTarget CIBLE = new CrmTarget("odoo", Map.of(
            "baseUrl", "http://odoo.test",
            "database", "leadflow",
            "username", "admin",
            "apiKey", "cle-odoo"));

    private MockRestServiceServer serveur;
    private OdooClient client;

    @BeforeEach
    void preparer() {
        RestClient.Builder builder = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(builder).build();
        client = new OdooClient(builder);
    }

    @Test
    void authentifieEtRenvoieLUid() {
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.params.service").value("common"))
                .andExpect(jsonPath("$.params.method").value("authenticate"))
                .andExpect(jsonPath("$.params.args[0]").value("leadflow"))
                .andExpect(jsonPath("$.params.args[1]").value("admin"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":7}", MediaType.APPLICATION_JSON));

        assertThat(client.authentifie(CIBLE)).isEqualTo(7);
        serveur.verify();
    }

    @Test
    void refuseUneAuthentificationSansUid() {
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":false}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.authentifie(CIBLE))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("authentification")
                .hasMessageNotContaining("cle-odoo");
    }

    @Test
    void creeUnEnregistrementEtRenvoieSonIdentifiant() {
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andExpect(jsonPath("$.params.method").value("execute_kw"))
                .andExpect(jsonPath("$.params.args[3]").value("res.partner"))
                .andExpect(jsonPath("$.params.args[4]").value("create"))
                .andExpect(jsonPath("$.params.args[5][0].name").value("Acme"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":31}", MediaType.APPLICATION_JSON));

        assertThat(client.cree(CIBLE, 7, "res.partner", Map.of("name", "Acme"))).isEqualTo("31");
        serveur.verify();
    }

    @Test
    void traiteUnStatut200PorteurDUneErreurCommeUnEchec() {
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"Odoo Server Error\","
                                + "\"data\":{\"message\":\"Invalid field 'champ_inexistant'\"}}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.cree(CIBLE, 7, "res.partner", Map.of("x", "y")))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("Invalid field");
    }

    @Test
    void chercheUnUtilisateurParEmailEtRenvoieNulSiAucun() {
        // Releve de la sonde : res.users porte login ET email, d'ou le OU explicite.
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andExpect(jsonPath("$.params.args[3]").value("res.users"))
                .andExpect(jsonPath("$.params.args[5][0][0]").value("|"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.chercheUtilisateurParEmail(CIBLE, 7, "inconnu@demo.test")).isNull();
    }

    @Test
    void chercheUnUtilisateurParEmailEtRenvoieSonIdentifiant() {
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[9]}", MediaType.APPLICATION_JSON));

        assertThat(client.chercheUtilisateurParEmail(CIBLE, 7, "amina@demo.test")).isEqualTo("9");
    }

    @Test
    void refuseUneCibleIncomplete() {
        CrmTarget sansBase = new CrmTarget("odoo", Map.of("baseUrl", "http://odoo.test"));

        assertThatThrownBy(() -> client.authentifie(sansBase))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("database");
    }
}
```

- [x] **Step 2: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=OdooClientTest
```

Attendu : échec de compilation — `OdooClient` n'existe pas.

- [x] **Step 3: Écrire le transport**

Créer `backend/src/main/java/com/leadflow/crm/odoo/OdooClient.java` :

```java
package com.leadflow.crm.odoo;

import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmTarget;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Transport JSON-RPC vers une instance Odoo.
 *
 * <p>Odoo repond {@code HTTP 200} meme quand l'appel echoue : l'erreur est dans le corps,
 * sous la cle {@code error}. Toute reponse passe donc par {@link #resultat(Map, String)},
 * qui inspecte le corps avant de rendre quoi que ce soit — s'en remettre au code de statut
 * ferait passer un echec pour un succes.
 */
@Component
public class OdooClient {

    static final String PROVIDER_ID = "odoo";

    private final RestClient.Builder builder;

    public OdooClient(@Qualifier("odooRestClientBuilder") RestClient.Builder builder) {
        this.builder = builder;
    }

    public int authentifie(CrmTarget target) {
        Map<String, Object> reponse = appelle(target, "common", "authenticate", List.of(
                reglage(target, "database"),
                reglage(target, "username"),
                reglage(target, "apiKey"),
                Map.of()));
        Object uid = resultat(reponse, "authenticate");
        if (!(uid instanceof Number nombre) || nombre.intValue() <= 0) {
            throw new CrmSyncException(
                    PROVIDER_ID, "Odoo a refuse l'authentification de l'utilisateur configure", null);
        }
        return nombre.intValue();
    }

    public String cree(CrmTarget target, int uid, String modele, Map<String, Object> champs) {
        Map<String, Object> reponse = executeKw(target, uid, modele, "create", List.of(List.of(champs)));
        Object identifiant = resultat(reponse, modele + ".create");
        return identifiant == null ? null : String.valueOf(identifiant);
    }

    /** @return l'identifiant de l'utilisateur, ou {@code null} si Odoo n'en connait aucun. */
    public String chercheUtilisateurParEmail(CrmTarget target, int uid, String email) {
        Map<String, Object> reponse = executeKw(target, uid, "res.users", "search",
                List.of(List.of(List.of("|", List.of("login", "=", email),
                        List.of("email", "=", email)))));
        Object trouves = resultat(reponse, "res.users.search");
        if (!(trouves instanceof List<?> liste) || liste.isEmpty()) {
            return null;
        }
        return String.valueOf(liste.getFirst());
    }

    private Map<String, Object> executeKw(
            CrmTarget target, int uid, String modele, String methode, List<Object> arguments) {
        List<Object> args = new java.util.ArrayList<>(List.of(
                reglage(target, "database"), uid, reglage(target, "apiKey"), modele, methode));
        args.addAll(arguments);
        return appelle(target, "object", "execute_kw", args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> appelle(
            CrmTarget target, String service, String methode, List<Object> args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("service", service);
        params.put("method", methode);
        params.put("args", args);

        Map<String, Object> enveloppe = new LinkedHashMap<>();
        enveloppe.put("jsonrpc", "2.0");
        enveloppe.put("method", "call");
        enveloppe.put("id", 1);
        enveloppe.put("params", params);

        try {
            return builder.clone()
                    .baseUrl(reglage(target, "baseUrl"))
                    .build()
                    .post()
                    .uri("/jsonrpc")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(enveloppe)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new CrmSyncException(PROVIDER_ID, "Appel Odoo en echec sur " + methode, e);
        }
    }

    /** Extrait {@code result} apres avoir verifie l'absence de {@code error} dans le corps. */
    @SuppressWarnings("unchecked")
    private Object resultat(Map<String, Object> reponse, String operation) {
        if (reponse == null) {
            throw new CrmSyncException(PROVIDER_ID, "Reponse Odoo vide sur " + operation, null);
        }
        Object erreur = reponse.get("error");
        if (erreur instanceof Map<?, ?> details) {
            Object donnees = details.get("data");
            Object message = donnees instanceof Map<?, ?> carte && carte.get("message") != null
                    ? carte.get("message")
                    : details.get("message");
            throw new CrmSyncException(
                    PROVIDER_ID, "Odoo a rejete " + operation + " : " + message, null);
        }
        return reponse.get("result");
    }

    /** La valeur n'apparait jamais dans le message : {@code crm_config} contient des secrets. */
    private String reglage(CrmTarget target, String cle) {
        String valeur = target.settings() == null ? null : target.settings().get(cle);
        if (valeur == null || valeur.isBlank()) {
            throw new CrmSyncException(
                    PROVIDER_ID, "Reglage '" + cle + "' absent de la configuration du client", null);
        }
        return valeur;
    }
}
```

- [x] **Step 4: Lancer le test et vérifier qu'il passe**

```bash
./mvnw test -Dtest=OdooClientTest
```

Attendu : 7 tests verts. Le chemin `error.data.message` et le `HTTP 200` porteur d'erreur ont été confirmés par la sonde ; `error.data.debug` porte la trace Python complète, à ne jamais recopier dans `crm_sync_attempt.error_message`.

- [x] **Step 5: Lancer la suite complète et commiter**

```bash
./mvnw test
```

Attendu : **67 tests, 0 échec**.

```bash
git add backend/src/main/java/com/leadflow/crm/odoo backend/src/test/java/com/leadflow/crm/odoo
git commit -m "feat: transport JSON-RPC vers Odoo, erreur detectee dans le corps"
```

---

## Task 7: Adaptateur Odoo

Odoo place la société et le contact dans le même modèle `res.partner`, distingués par `is_company` et reliés par `parent_id` — là où Dolibarr a deux endpoints. Cette asymétrie se résout ici.

**Files:**
- Create : `backend/src/main/java/com/leadflow/crm/odoo/OdooConnector.java`
- Modify : `backend/src/main/resources/application.yml` (`odoo.enabled: true`)
- Test (create) : `backend/src/test/java/com/leadflow/crm/odoo/OdooConnectorTest.java`

**Interfaces:**
- Consumes : `OdooClient` (tâche 6), `CrmConnector` (tâche 2).
- Produces : bean `OdooConnector` avec `providerId() == "odoo"`.

- [x] **Step 1: Écrire le test de traduction**

Créer `backend/src/test/java/com/leadflow/crm/odoo/OdooConnectorTest.java` :

```java
package com.leadflow.crm.odoo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OdooConnectorTest {

    private static final CrmTarget CIBLE = new CrmTarget("odoo", Map.of(
            "baseUrl", "http://odoo.test",
            "database", "leadflow",
            "username", "admin",
            "apiKey", "cle-odoo"));

    /** Faux transport : ce test porte sur la traduction, pas sur le JSON-RPC. */
    private static final class TransportFactice extends OdooClient {

        private final List<String> appels = new ArrayList<>();
        private final Map<String, Map<String, Object>> corpsParModele = new LinkedHashMap<>();
        private boolean echoueSurOpportunite;
        private int authentifications;

        private TransportFactice() {
            super(org.springframework.web.client.RestClient.builder());
        }

        @Override
        public int authentifie(CrmTarget target) {
            authentifications++;
            return 7;
        }

        @Override
        public String cree(CrmTarget target, int uid, String modele, Map<String, Object> champs) {
            corpsParModele.put(modele + "#" + appels.size(), champs);
            appels.add(modele);
            if ("crm.lead".equals(modele)) {
                if (echoueSurOpportunite) {
                    throw new CrmSyncException("odoo", "champ refuse", null);
                }
                return "99";
            }
            return Boolean.TRUE.equals(champs.get("is_company")) ? "31" : "32";
        }

        @Override
        public String chercheUtilisateurParEmail(CrmTarget target, int uid, String email) {
            appels.add("res.users");
            return "9";
        }
    }

    private static CrmLead lead(String companyName) {
        return new CrmLead(companyName, "Amina", "Bensalem", "amina@acme.test", "+212600000000",
                "Je veux un devis", "DEMANDE_DEVIS", 72, "MA", "industrie", "9");
    }

    private final TransportFactice transport = new TransportFactice();

    private final OdooConnector connecteur = new OdooConnector(transport);

    @Test
    void seDeclareSousLIdentifiantOdoo() {
        assertThat(connecteur.providerId()).isEqualTo("odoo");
    }

    @Test
    void creeLaSocietePuisLeContactPuisLOpportunite() {
        CrmSyncResult resultat = connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.appels).containsExactly("res.partner", "res.partner", "crm.lead");
        assertThat(resultat.accountRef()).isEqualTo("31");
        assertThat(resultat.contactRef()).isEqualTo("32");
        assertThat(resultat.opportunityRef()).isEqualTo("99");
        assertThat(transport.authentifications).isEqualTo(1);
    }

    @Test
    void rattacheLeContactALaSocieteEtLOpportuniteAuPartenaire() {
        connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.corpsParModele.get("res.partner#0")).containsEntry("is_company", true);
        assertThat(transport.corpsParModele.get("res.partner#1")).containsEntry("parent_id", "31");
        assertThat(transport.corpsParModele.get("crm.lead#2")).containsEntry("partner_id", "31");
        assertThat(transport.corpsParModele.get("crm.lead#2")).containsEntry("type", "opportunity");
        assertThat(transport.corpsParModele.get("crm.lead#2")).containsEntry("user_id", "9");
    }

    @Test
    void traduitLeScoreEnPriorite() {
        connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.corpsParModele.get("crm.lead#2")).containsEntry("priority", "2");
    }

    @Test
    void creeUnContactSansSocieteQuandAucuneNEstFournie() {
        CrmSyncResult resultat = connecteur.sync(lead(null), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.appels).containsExactly("res.partner", "crm.lead");
        assertThat(resultat.accountRef()).isNull();
        assertThat(resultat.contactRef()).isEqualTo("32");
    }

    @Test
    void neRecreeRienDeCeQuiExisteDeja() {
        CrmSyncResult resultat =
                connecteur.sync(lead("Acme"), CIBLE, new CrmSyncState("31", "32", null));

        assertThat(transport.appels).containsExactly("crm.lead");
        assertThat(resultat.accountRef()).isEqualTo("31");
    }

    @Test
    void neSAuthentifieMemePasQuandToutExisteDeja() {
        connecteur.sync(lead("Acme"), CIBLE, new CrmSyncState("31", "32", "99"));

        assertThat(transport.appels).isEmpty();
        assertThat(transport.authentifications).isZero();
    }

    @Test
    void enrichitLExceptionDeCeQuiAvaitDejaEteCree() {
        transport.echoueSurOpportunite = true;

        assertThatThrownBy(() -> connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE))
                .isInstanceOf(CrmSyncException.class)
                .satisfies(echec -> {
                    CrmSyncState partiel = ((CrmSyncException) echec).partialState();
                    assertThat(partiel.accountRef()).isEqualTo("31");
                    assertThat(partiel.contactRef()).isEqualTo("32");
                    assertThat(partiel.opportunityRef()).isNull();
                });
    }

    @Test
    void resoutLeCommercialParSonEmail() {
        assertThat(connecteur.resolveAssignee(
                new CrmAssignee("Amina Bensalem", "amina@demo.test"), CIBLE)).isEqualTo("9");
    }
}
```

- [x] **Step 2: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=OdooConnectorTest
```

Attendu : échec de compilation — `OdooConnector` n'existe pas.

- [x] **Step 3: Écrire l'adaptateur**

Créer `backend/src/main/java/com/leadflow/crm/odoo/OdooConnector.java` :

```java
package com.leadflow.crm.odoo;

import com.leadflow.crm.CrmConnector;
import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Traduction du modele pivot vers le vocabulaire d'Odoo.
 *
 * <p>Odoo place la societe et le contact dans le meme modele {@code res.partner},
 * distingues par {@code is_company} et relies par {@code parent_id} — la ou Dolibarr a
 * deux endpoints. L'opportunite est un {@code crm.lead}, qui exige le module {@code crm}.
 */
@Component
public class OdooConnector implements CrmConnector {

    private static final String PARTENAIRE = "res.partner";
    private static final String OPPORTUNITE = "crm.lead";

    private final OdooClient client;

    public OdooConnector(OdooClient client) {
        this.client = client;
    }

    @Override
    public String providerId() {
        return OdooClient.PROVIDER_ID;
    }

    @Override
    public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
        String societe = previous.accountRef();
        String contact = previous.contactRef();
        String opportunite = previous.opportunityRef();

        boolean societeAttendue = lead.companyName() != null && !lead.companyName().isBlank();
        boolean toutExiste = contact != null && opportunite != null
                && (!societeAttendue || societe != null);
        if (toutExiste) {
            return new CrmSyncResult(providerId(), societe, contact, opportunite, null, Instant.now());
        }

        int uid = client.authentifie(target);
        try {
            if (societeAttendue && societe == null) {
                societe = client.cree(target, uid, PARTENAIRE, champsSociete(lead));
            }
            if (contact == null) {
                contact = client.cree(target, uid, PARTENAIRE, champsContact(lead, societe));
            }
            if (opportunite == null) {
                opportunite = client.cree(
                        target, uid, OPPORTUNITE, champsOpportunite(lead, societe, contact));
            }
        } catch (CrmSyncException echec) {
            throw echec.avecEtat(new CrmSyncState(societe, contact, opportunite));
        }
        return new CrmSyncResult(providerId(), societe, contact, opportunite, null, Instant.now());
    }

    @Override
    public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
        return client.chercheUtilisateurParEmail(target, client.authentifie(target), assignee.email());
    }

    private Map<String, Object> champsSociete(CrmLead lead) {
        Map<String, Object> champs = new LinkedHashMap<>();
        champs.put("name", lead.companyName());
        champs.put("is_company", true);
        champs.put("email", lead.email());
        if (lead.phone() != null) {
            champs.put("phone", lead.phone());
        }
        return champs;
    }

    private Map<String, Object> champsContact(CrmLead lead, String societe) {
        Map<String, Object> champs = new LinkedHashMap<>();
        champs.put("name", nomDeLaPersonne(lead));
        champs.put("is_company", false);
        champs.put("email", lead.email());
        if (lead.phone() != null) {
            champs.put("phone", lead.phone());
        }
        if (societe != null) {
            champs.put("parent_id", societe);
        }
        return champs;
    }

    private Map<String, Object> champsOpportunite(CrmLead lead, String societe, String contact) {
        Map<String, Object> champs = new LinkedHashMap<>();
        champs.put("name", lead.detectedIntent() == null ? "Lead LeadFlow" : lead.detectedIntent());
        champs.put("type", "opportunity");
        champs.put("partner_id", societe != null ? societe : contact);
        champs.put("email_from", lead.email());
        champs.put("description", lead.message() == null ? "" : lead.message());
        champs.put("priority", priorite(lead.score()));
        if (lead.phone() != null) {
            champs.put("phone", lead.phone());
        }
        if (lead.assigneeRef() != null) {
            champs.put("user_id", lead.assigneeRef());
        }
        return champs;
    }

    /**
     * Le score de LeadFlow va de 0 a 100, la priorite d'Odoo de 0 a 3. La conversion vit
     * ici : aucune des deux echelles n'a a remonter dans le pivot.
     */
    private String priorite(int score) {
        if (score >= 90) {
            return "3";
        }
        if (score >= 70) {
            return "2";
        }
        if (score >= 40) {
            return "1";
        }
        return "0";
    }

    private String nomDeLaPersonne(CrmLead lead) {
        String prenom = lead.firstName() == null ? "" : lead.firstName();
        String nom = lead.lastName() == null ? "" : lead.lastName();
        String complet = (prenom + " " + nom).trim();
        return complet.isBlank() ? lead.email() : complet;
    }
}
```

- [x] **Step 4: Lancer le test et vérifier qu'il passe**

```bash
./mvnw test -Dtest=OdooConnectorTest
```

Attendu : 9 tests verts.

- [x] **Step 5: Activer Odoo dans la configuration**

Dans `backend/src/main/resources/application.yml`, sous `leadflow.crm.providers.odoo` :

```yaml
      odoo:
        # Adaptateur ecrit en F5 : le connecteur est desormais utilisable.
        enabled: true
```

- [x] **Step 6: Lancer la suite complète et commiter**

```bash
./mvnw test
```

Attendu : **76 tests, 0 échec**.

```bash
git add backend/src/main/java/com/leadflow/crm/odoo backend/src/test/java/com/leadflow/crm/odoo \
        backend/src/main/resources/application.yml
git commit -m "feat: adaptateur Odoo et activation du fournisseur"
```

---

## Task 8: Tests d'intégration contre les ERP réels

L'étage 2 de la stratégie de test. Il ne tourne pas à chaque `./mvnw test` : Dolibarr s'installe en minutes et réclame une clé d'API. Il tourne à la recette, contre les conteneurs montés par les profils Docker, en suivant `docs/erp-integration-setup.md` produit par la tâche 1.

**Files:**
- Create : `backend/src/test/java/com/leadflow/crm/ErpIntegrationTest.java`
- Modify : `backend/pom.xml`
- Modify : `docs/erp-integration-setup.md`

**Interfaces:**
- Consumes : `DolibarrConnector`, `OdooConnector`, `CrmLead`, `CrmSyncState`, `CrmTarget`, et la procédure de la tâche 1.
- Produces : le profil Maven `erp-it`.

- [x] **Step 1: Exclure le groupe `erp` par défaut et ajouter le profil**

Dans `backend/pom.xml`, ajouter la propriété dans le bloc `<properties>` existant :

```xml
		<!-- Les tests contre de vrais ERP ne tournent que sous -Perp-it. -->
		<erp.excludedGroups>erp</erp.excludedGroups>
```

Ajouter le plugin Surefire dans `<build><plugins>` :

```xml
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-surefire-plugin</artifactId>
				<configuration>
					<excludedGroups>${erp.excludedGroups}</excludedGroups>
				</configuration>
			</plugin>
```

Et le profil, juste avant `</project>` :

```xml
	<profiles>
		<profile>
			<id>erp-it</id>
			<properties>
				<!-- Groupe inexistant : plus rien n'est exclu, les tests @Tag("erp") tournent. -->
				<erp.excludedGroups>aucun-groupe-exclu</erp.excludedGroups>
			</properties>
		</profile>
	</profiles>
```

- [x] **Step 2: Écrire le test d'intégration**

Créer `backend/src/test/java/com/leadflow/crm/ErpIntegrationTest.java` :

```java
package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.crm.dolibarr.DolibarrClient;
import com.leadflow.crm.dolibarr.DolibarrConnector;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import com.leadflow.crm.odoo.OdooClient;
import com.leadflow.crm.odoo.OdooConnector;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * Etage 2 de la strategie de test : contre de vrais conteneurs Dolibarr et Odoo.
 *
 * <p>Exclu de la suite par defaut ({@code @Tag("erp")}). Pour le lancer :
 * <pre>
 * docker compose --profile dolibarr --profile odoo up -d
 * # puis la procedure de docs/erp-integration-setup.md
 * export LEADFLOW_DOLIBARR_API_KEY=...
 * ./mvnw verify -Perp-it
 * </pre>
 */
@Tag("erp")
class ErpIntegrationTest {

    private static CrmTarget cibleDolibarr() {
        return new CrmTarget("dolibarr", Map.of(
                "baseUrl", System.getenv().getOrDefault(
                        "LEADFLOW_DOLIBARR_URL", "http://localhost:8081/api/index.php"),
                "apiKey", System.getenv("LEADFLOW_DOLIBARR_API_KEY")));
    }

    private static CrmTarget cibleOdoo() {
        return new CrmTarget("odoo", Map.of(
                "baseUrl", System.getenv().getOrDefault("LEADFLOW_ODOO_URL", "http://localhost:8069"),
                "database", System.getenv().getOrDefault("LEADFLOW_ODOO_DB", "leadflow"),
                "username", System.getenv().getOrDefault("LEADFLOW_ODOO_USER", "admin"),
                "apiKey", System.getenv().getOrDefault("LEADFLOW_ODOO_PASSWORD", "admin")));
    }

    private static CrmLead leadUnique() {
        String marque = UUID.randomUUID().toString().substring(0, 8);
        return new CrmLead("Acme " + marque, "Amina", "Bensalem",
                "amina+" + marque + "@exemple.test", "+212600000000",
                "Je veux un devis pour 50 unites", "DEMANDE_DEVIS", 72, "MA", "industrie", null);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LEADFLOW_DOLIBARR_API_KEY", matches = ".+")
    void dolibarrCreeLesTroisObjetsPuisNeLesRecreePasAuRejeu() {
        DolibarrConnector connecteur =
                new DolibarrConnector(new DolibarrClient(RestClient.builder()));
        CrmLead lead = leadUnique();

        CrmSyncResult premier = connecteur.sync(lead, cibleDolibarr(), CrmSyncState.VIERGE);

        assertThat(premier.accountRef()).isNotBlank();
        assertThat(premier.contactRef()).isNotBlank();
        assertThat(premier.opportunityRef()).isNotBlank();

        CrmSyncResult rejeu = connecteur.sync(lead, cibleDolibarr(), new CrmSyncState(
                premier.accountRef(), premier.contactRef(), premier.opportunityRef()));

        assertThat(rejeu.accountRef()).isEqualTo(premier.accountRef());
        assertThat(rejeu.contactRef()).isEqualTo(premier.contactRef());
        assertThat(rejeu.opportunityRef()).isEqualTo(premier.opportunityRef());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LEADFLOW_ODOO_DB", matches = ".+")
    void odooCreeLesTroisObjetsPuisNeLesRecreePasAuRejeu() {
        OdooConnector connecteur = new OdooConnector(new OdooClient(RestClient.builder()));
        CrmLead lead = leadUnique();

        CrmSyncResult premier = connecteur.sync(lead, cibleOdoo(), CrmSyncState.VIERGE);

        assertThat(premier.accountRef()).isNotBlank();
        assertThat(premier.contactRef()).isNotBlank();
        assertThat(premier.opportunityRef()).isNotBlank();

        CrmSyncResult rejeu = connecteur.sync(lead, cibleOdoo(), new CrmSyncState(
                premier.accountRef(), premier.contactRef(), premier.opportunityRef()));

        assertThat(rejeu.opportunityRef()).isEqualTo(premier.opportunityRef());
    }
}
```

- [x] **Step 3: Vérifier que la suite par défaut ignore bien ces tests**

```bash
./mvnw test
```

Attendu : **76 tests**, inchangé. `ErpIntegrationTest` ne doit pas apparaître dans `target/surefire-reports/`.

- [x] **Step 4: Lancer l'étage 2 pour de vrai**

```bash
docker compose --profile dolibarr --profile odoo up -d
# suivre docs/erp-integration-setup.md
export LEADFLOW_DOLIBARR_API_KEY="<la cle>"
export LEADFLOW_ODOO_DB=leadflow
./mvnw verify -Perp-it
```

Attendu : `BUILD SUCCESS`, avec `ErpIntegrationTest` exécuté et vert.

**Si l'obtention de la clé Dolibarr résiste à toute automatisation raisonnable** (risque identifié au §13 de la spec) : ne pas s'acharner. Consigner dans `docs/erp-integration-setup.md` la procédure manuelle exacte, laisser le test en place derrière sa variable d'environnement, et le signaler dans le rapport de tâche. L'étage 1 reste intact et la feature reste livrable.

- [x] **Step 5: Compléter la documentation et commiter**

Compléter `docs/erp-integration-setup.md` avec les variables d'environnement attendues et la commande exacte, puis :

```bash
git add backend/pom.xml backend/src/test/java/com/leadflow/crm/ErpIntegrationTest.java \
        docs/erp-integration-setup.md
git commit -m "test: integration contre les ERP reels sous le profil erp-it"
```

---

## Task 9: Mise à jour de `CLAUDE.md` et recette

`CLAUDE.md` est la mémoire du projet entre deux sessions. Après F1, il décrivait un port qui n'existait plus ; il ne doit pas recommencer. C'est le critère de recette n°8.

**Files:**
- Modify : `CLAUDE.md`

**Interfaces:**
- Consumes : tout ce qui précède.
- Produces : rien de logiciel.

- [x] **Step 1: Mettre à jour l'arborescence `crm/`**

Remplacer le bloc d'arborescence de la section « Connecteurs ERP/CRM » par :

```markdown
crm/
├── CrmConnector.java          port : providerId(), sync(CrmLead, CrmTarget, CrmSyncState), resolveAssignee
├── CrmConnectorRegistry.java  resout l'adaptateur par providerId, applique `enabled`
├── CrmSyncService.java        orchestration : cible, etat anterieur, trace
├── CrmSyncTraceWriter.java    ecriture de la trace en transaction propre
├── CrmHttpConfig.java         un RestClient.Builder par fournisseur, avec ses delais
├── CrmSyncAttempt.java        trace append-only des synchronisations
├── model/                     modele pivot : CrmLead, CrmTarget, CrmSyncState, CrmAssignee, ...
├── dolibarr/                  adaptateur REST : DolibarrConnector + DolibarrClient
└── odoo/                      adaptateur JSON-RPC : OdooConnector + OdooClient
```

- [x] **Step 2: Documenter l'idempotence et la frontière des adaptateurs**

Ajouter, après le paragraphe sur `CrmTarget` :

```markdown
**Un adaptateur ne lit jamais la base.** Il recoit `CrmSyncState` — les references deja
obtenues lors des tentatives precedentes — et saute toute etape dont la reference est
connue. C'est la, et nulle part ailleurs, que se joue l'absence de doublon au rejeu. En cas
d'echec partiel, il leve une `CrmSyncException` enrichie de ce qu'il avait obtenu, sans
quoi le rejeu recreerait ce qui existe deja.

`CrmSyncService` porte tout ce qui est propre a LeadFlow : resolution de `CrmTarget` depuis
`client.crm_config` dechiffre, reconstruction de l'etat anterieur (valeur non nulle la plus
recente, champ par champ), resolution et memorisation de `sales_rep.crm_ref`, ecriture de la
trace. Il ne choisit aucun commercial (F4), ne consomme aucune file (F3) et ne reessaie pas :
la reprise appartient a la DLQ. La trace s'ecrit en `REQUIRES_NEW` pour survivre au rollback
d'un appelant transactionnel.
```

- [x] **Step 3: Documenter les deux étages de test**

Ajouter à la section « Backend — conventions », sous « Commandes » :

````markdown
Les tests des adaptateurs ERP ont deux etages. L'etage contractuel tourne a chaque
`./mvnw test` contre `MockRestServiceServer` — il asserte les corps envoyes, pas seulement
les codes retour. L'etage d'integration, marque `@Tag("erp")`, est exclu par defaut et
demande de vrais conteneurs :

```bash
docker compose --profile dolibarr --profile odoo up -d
# puis docs/erp-integration-setup.md pour la cle d'API et la base Odoo
./mvnw verify -Perp-it
```
````

- [x] **Step 4: Mettre à jour « Etat actuel »**

Remplacer la section par :

```markdown
## Etat actuel

Le modele de donnees est complet (F1) et **les deux adaptateurs ERP existent** (F5).

Ce qui existe : la configuration, le chiffrement des secrets, les cinq entites et leurs
repositories, les migrations `V1` et `V2`, le port `CrmConnector` et son registre, les
adaptateurs Dolibarr et Odoo, et `CrmSyncService` qui les orchestre.

Ce qui n'existe pas : **l'entree et le milieu du pipeline**. Pas d'endpoint webhook (F2),
pas de consommateur RabbitMQ ni de qualification (F3), pas de routage (F4), pas d'API de
monitoring (F6) ; les quatre composants de `features/` sont des placeholders. Rien n'appelle
donc encore `CrmSyncService` en dehors des tests. Ne pas supposer l'existence d'un service
ou d'un endpoint : verifier avant de referencer.
```

- [x] **Step 5: Recette complète**

```bash
docker compose down -v
docker compose up -d
cd backend && ./mvnw verify
```

Attendu : `BUILD SUCCESS`, **76 tests, 0 échec**, et la présence dans la sortie de
`DolibarrClientTest`, `DolibarrConnectorTest`, `OdooClientTest`, `OdooConnectorTest`,
`CrmSyncServiceTest`, `CrmConnectorRegistryTest`, `CrmSyncExceptionTest`,
`BackendApplicationTests`.

Vérifier ensuite les critères de recette de la spec qui ne sont pas couverts par un test
automatique :

```bash
grep -rn "thirdparty\|res.partner\|crm.lead\|socid" backend/src/main/java/com/leadflow/crm/model/
```

Attendu : **aucun résultat**. Le modèle pivot est resté neutre.

- [x] **Step 6: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md decrit les adaptateurs ERP de F5"
```

---

## Après la dernière tâche

Utiliser la compétence `superpowers:requesting-code-review` pour une revue de branche, traiter ce qui doit l'être, puis `superpowers:finishing-a-development-branch` pour fusionner `feature/f5-connecteurs-erp` dans `main`.

**Ne pas supprimer la branche après la fusion** : les branches de feature sont conservées comme historique du projet.

---

## Etat final — 9 taches sur 9

| Tache | Commit | Etat |
| --- | --- | --- |
| 1. Sonde contre les ERP reels | `fbf7f2c` | faite |
| 2. Modele pivot, port et registre | `17a46c7` | faite |
| 3. Orchestrateur `CrmSyncService` | `afc2f1b` | faite |
| 4. Transport Dolibarr | `5b8d156` | faite |
| 5. Adaptateur Dolibarr | `a48d7b0` | faite |
| 6. Transport Odoo | `740c242` | faite |
| 7. Adaptateur Odoo | `b29688c` | faite |
| 8. Tests d'integration reels | `c4ac099` | faite |
| 9. CLAUDE.md et recette | ce commit | faite |

Recette : `./mvnw verify` -> **76 tests, 0 echec** ; `./mvnw verify -Perp-it`, conteneurs
Dolibarr et Odoo montes -> **78 tests, 0 echec**. Le modele pivot est reste neutre (le seul
resultat du grep est la phrase de `model/package-info.java` qui enonce la regle).

Ecarts assumes en cours de route, tous documentes dans les commits concernes :

1. `CrmConnectorRegistry` nomme le conflit quand deux connecteurs declarent le meme
   `providerId` (tache 5) ;
2. `CrmSyncServiceTest` se donne son propre fournisseur `espion`, active par une propriete
   de test, l'adaptateur Dolibarr reel occupant la cle `dolibarr` dans le contexte ;
3. la cle d'API Dolibarr est lue avec une valeur par defaut vide dans `ErpIntegrationTest`,
   `Map.of` refusant une valeur nulle quand la variable d'environnement n'est pas posee ;
4. la recette n'a pas rejoue `docker compose down -v` : la suite tourne sur Testcontainers
   et non sur la base de `docker-compose.yml`, et l'effacement des volumes aurait detruit
   l'installation Dolibarr / Odoo verifiee a la tache 8.

**Question encore ouverte, sans urgence** : la `ref` d'opportunite Dolibarr est tiree au
hasard (`LF-XXXXXXXX`) faute d'identifiant de lead dans le pivot. Le raisonnement est dans
le Javadoc de `DolibarrConnector.reference()`. Une `ref` stable demanderait d'ajouter une
reference de lead au modele pivot — decision a prendre avec l'utilisateur, pas seul.

Reste : revue de branche, puis fusion de `feature/f5-connecteurs-erp` dans `main` sans
supprimer la branche.
