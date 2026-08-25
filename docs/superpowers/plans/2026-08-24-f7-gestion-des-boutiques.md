# F7 — Gestion des boutiques : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permettre à un utilisateur non technique d'accueillir une boutique cliente de bout en bout depuis le dashboard — création, paramètres ERP vérifiés, commerciaux, clés d'intégration — sans jamais ouvrir la base.

**Architecture:** Le CRUD vit dans `tenant/`, jamais dans `monitoring/`, qui reste un observateur en lecture seule. Le port `CrmConnector` gagne deux méthodes — la liste de ses réglages attendus et une sonde d'accès — pour que ni `tenant/` ni le frontend ne connaissent Dolibarr ou Odoo. Le frontend consomme une API `/api/admin/` et génère son formulaire ERP à partir de ce que le backend déclare.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, Postgres, Flyway, JUnit 5 + AssertJ + Testcontainers, MockRestServiceServer. Angular 20 standalone + signals, Angular Material, Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-08-24-f7-gestion-des-boutiques-design.md`

## Global Constraints

- Les commandes backend s'exécutent depuis `backend/`, les commandes frontend depuis `frontend/`.
- **Aucune migration Flyway.** Le schéma porte déjà tout. Une tâche qui semble en réclamer une a dévié du plan.
- Code, commentaires et Javadoc **en français, sans accents**. Les documents Markdown portent leurs accents.
- Javadoc qui explique **pourquoi**, jamais quoi : chaque classe dit la décision qu'elle incarne et l'alternative écartée.
- Lignes ~100 colonnes, indentation 4 espaces (Java) / 2 espaces (TypeScript).
- Les sous-packages sont des **étapes du pipeline**, pas des couches techniques : pas de `service/`, `repository/` ni `controller/` globaux.
- **Aucune entité JPA ne franchit la frontière HTTP.** `Client` porte `hmacSecret` et `crmConfig` déchiffrés à la lecture : toute réponse passe par un `record` de `tenant/dto/`.
- **`monitoring/` n'est jamais modifié par ce plan.** `ClientDirectoryController` et `ClientDirectoryService` restent tels quels.
- Les corps JSON sont en **camelCase anglais**, les erreurs en `ProblemDetail` (RFC 7807).
- Jackson est en version 3 : les imports sont `tools.jackson.databind.*`, **jamais** `com.fasterxml.jackson.*`.
- Les tests HTTP utilisent **`MockMvc` + `@AutoConfigureMockMvc`**, jamais `TestRestTemplate` (qui a quitté `org.springframework.boot.test.web.client`).
- Tous les tests de persistance utilisent `@SpringBootTest` + `TestcontainersConfiguration`, jamais `@DataJpaTest` : sa tranche n'inclut pas les `@Component` que sont les converters chiffrés.
- Le daemon Docker doit tourner pour `./mvnw test`.
- **Toute la partie visuelle passe par le plugin `ui-ux-pro-max`** — invoquer le skill *avant* d'écrire du code d'interface, en réutilisant `design-system/leadflow-dashboard/MASTER.md`.
- Frontend : `environment.apiBaseUrl` est vide, les services appellent des **chemins relatifs**.
- Commits en français, une tâche = un commit, message expliquant la décision et non le diff.

### Le compte de test du dashboard

Toute classe de test qui appelle une route protégée porte ces propriétés et ce helper. Le hash correspond au mot de passe `secret-de-test` et il est déjà vérifié en production de tests — le recopier tel quel :

```java
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
```

```java
    private String jeton() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"secret-de-test\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return mapper.readTree(corps).get("token").asText();
    }
```

---

## Structure des fichiers

### Backend — créés

| Fichier | Responsabilité |
| --- | --- |
| `crm/model/CrmCheck.java` | Résultat neutre d'une sonde d'accès |
| `crm/model/CrmCheckCause.java` | Les cinq causes possibles |
| `crm/model/CrmSettingSpec.java` | Un réglage attendu par un fournisseur |
| `tenant/ClientAdminController.java` | Routes `/api/admin/clients` |
| `tenant/ClientAdminService.java` | Création, mise à jour, activation, rotations |
| `tenant/SalesRepAdminService.java` | Commerciaux et règle du dernier actif |
| `tenant/CrmProviderController.java` | Routes `/api/admin/crm` — réglages et test |
| `tenant/CleGenerator.java` | Génération de la clé publique et du secret |
| `tenant/dto/ClientSummaryAdmin.java` | Ligne de la liste |
| `tenant/dto/ClientDetailAdmin.java` | Fiche, sans secret |
| `tenant/dto/ClientForm.java` | Corps de création et de mise à jour |
| `tenant/dto/SalesRepForm.java` | Corps d'ajout et de modification |
| `tenant/dto/ClientCreated.java` | Réponse de création — porte le secret |
| `tenant/dto/SecretRotated.java` | Réponse de rotation — porte le secret |
| `tenant/dto/SalesRepAdminView.java` | Commercial rendu par l'API d'administration |
| `tenant/dto/CrmProviderView.java` | Fournisseur et ses réglages attendus |
| `tenant/dto/CrmTestRequest.java` | Corps du test de connexion |
| `tenant/dto/CrmTestResult.java` | Réponse du test |
| `tenant/DernierCommercialException.java` | Refus du retrait du dernier actif |
| `tenant/ReglageManquantException.java` | Réglage ERP absent ou fournisseur inconnu |

### Backend — modifiés

| Fichier | Modification |
| --- | --- |
| `crm/CrmConnector.java` | Deux méthodes : `reglagesAttendus()` et `verifieAcces(CrmTarget)` |
| `crm/dolibarr/DolibarrConnector.java` + `DolibarrClient.java` | Sonde `/status` |
| `crm/odoo/OdooConnector.java` + `OdooClient.java` | Sonde par authentification JSON-RPC |
| `tenant/ClientRepository.java` | Liste ordonnée, existence par clé publique |
| `tenant/SalesRepRepository.java` | Comptage des actifs |
| `common/ApiExceptionHandler.java` | Deux `@ExceptionHandler` de plus |

### Frontend — créés

`core/api/tenant-api.ts`, `core/models/tenant.ts`, `features/boutiques/boutiques.ts|.html|.scss`, `features/boutiques/boutique-detail/boutique-detail.ts|.html|.scss`, `features/boutiques/boutique-nouvelle/boutique-nouvelle.ts|.html|.scss`, `features/boutiques/secret-revele/secret-revele.ts|.html|.scss`.

### Frontend — modifiés

`app.routes.ts` (trois routes), `app.ts` + `app.html` (entrée de menu).

## Ordre et dépendances

```
T1 (sonde + reglages attendus sur le port)
 ├─> T7 (endpoint de test)
 └─> T3 (creation : valide les reglages)
T2 (lecture : liste et fiche) ─> T3 ─> T4 ─> T5
T6 (commerciaux) depend de T2
T8 (service frontend) depend de T2..T7
T9, T10, T11 dependent de T8
T12 (documentation et recette) en dernier
```

---

## Task 1: La sonde d'accès et les réglages attendus sur le port `CrmConnector`

**Files:**
- Create: `backend/src/main/java/com/leadflow/crm/model/CrmCheckCause.java`
- Create: `backend/src/main/java/com/leadflow/crm/model/CrmCheck.java`
- Create: `backend/src/main/java/com/leadflow/crm/model/CrmSettingSpec.java`
- Modify: `backend/src/main/java/com/leadflow/crm/CrmConnector.java`
- Modify: `backend/src/main/java/com/leadflow/crm/dolibarr/DolibarrClient.java`
- Modify: `backend/src/main/java/com/leadflow/crm/dolibarr/DolibarrConnector.java`
- Modify: `backend/src/main/java/com/leadflow/crm/odoo/OdooClient.java`
- Modify: `backend/src/main/java/com/leadflow/crm/odoo/OdooConnector.java`
- Test: `backend/src/test/java/com/leadflow/crm/dolibarr/DolibarrSondeTest.java`
- Test: `backend/src/test/java/com/leadflow/crm/odoo/OdooSondeTest.java`

**Interfaces:**
- Produces: `CrmCheck(CrmCheckCause cause, String detail)` avec `boolean ok()`, `CrmSettingSpec(String cle, String libelle, boolean secret)`, et sur `CrmConnector` : `List<CrmSettingSpec> reglagesAttendus()` et `CrmCheck verifieAcces(CrmTarget cible)`.

**Pourquoi deux méthodes et non une.** La sonde répond « est-ce que ça marche » ; `reglagesAttendus()` répond « que faut-il saisir ». Sans la seconde, la liste des champs de Dolibarr et d'Odoo serait recopiée dans `tenant/` et dans le formulaire Angular — deux endroits de plus à modifier pour ajouter un ERP, alors que le projet promet trois gestes et aucun `switch`.

- [ ] **Step 1: Écrire les trois types du modèle pivot**

`crm/model/CrmCheckCause.java` :

```java
package com.leadflow.crm.model;

/**
 * Pourquoi une sonde d'acces a abouti ou echoue.
 *
 * <p>Enumeration et non message libre : l'ecran doit pouvoir phraser l'echec en francais
 * pour un utilisateur non technique. Un message brut d'adaptateur — « I/O error on POST
 * request ... Connection refused: getsockopt » — ne se traduit pas.
 *
 * <p>Aucun terme propre a un fournisseur n'y figure : c'est l'adaptateur qui sait qu'un 401
 * Dolibarr et un refus JSON-RPC Odoo disent la meme chose.
 */
public enum CrmCheckCause {
    JOIGNABLE,
    INJOIGNABLE,
    IDENTIFIANTS_REFUSES,
    CIBLE_INCONNUE,
    REPONSE_INATTENDUE
}
```

`crm/model/CrmCheck.java` :

```java
package com.leadflow.crm.model;

/**
 * Resultat d'une sonde d'acces.
 *
 * <p>{@code detail} porte le message technique d'origine, tronque : l'ecran le replie sous
 * la phrase lisible, pour l'exploitant qui diagnostique lui-meme.
 */
public record CrmCheck(CrmCheckCause cause, String detail) {

    private static final int DETAIL_MAX = 500;

    public static CrmCheck joignable(String detail) {
        return new CrmCheck(CrmCheckCause.JOIGNABLE, tronque(detail));
    }

    public static CrmCheck echec(CrmCheckCause cause, String detail) {
        return new CrmCheck(cause, tronque(detail));
    }

    public boolean ok() {
        return cause == CrmCheckCause.JOIGNABLE;
    }

    private static String tronque(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        return detail.length() > DETAIL_MAX ? detail.substring(0, DETAIL_MAX) : detail;
    }
}
```

`crm/model/CrmSettingSpec.java` :

```java
package com.leadflow.crm.model;

/**
 * Un reglage que le fournisseur attend dans {@code client.crm_config}.
 *
 * <p>{@code secret} vaut vrai pour ce qui ne doit jamais etre reaffiche apres saisie : le
 * formulaire masque le champ, et l'API ne rend pas la valeur enregistree.
 */
public record CrmSettingSpec(String cle, String libelle, boolean secret) {
}
```

- [ ] **Step 2: Écrire le test qui échoue pour Dolibarr**

`backend/src/test/java/com/leadflow/crm/dolibarr/DolibarrSondeTest.java` :

```java
package com.leadflow.crm.dolibarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.leadflow.crm.model.CrmCheck;
import com.leadflow.crm.model.CrmCheckCause;
import com.leadflow.crm.model.CrmTarget;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * La sonde est ce que l'ecran de creation appelle avant d'enregistrer : elle doit distinguer
 * les echecs, sinon l'operateur non technique ne saura pas quoi corriger.
 */
class DolibarrSondeTest {

    private static final CrmTarget CIBLE = new CrmTarget(
            "dolibarr", Map.of("baseUrl", "http://erp.test/api/index.php", "apiKey", "cle"));

    private MockRestServiceServer serveur;
    private DolibarrClient client;

    private void prepare() {
        RestClient.Builder builder = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(builder).build();
        client = new DolibarrClient(builder);
    }

    @Test
    void unStatusQuiRepondRendJoignable() {
        prepare();
        serveur.expect(requestTo("http://erp.test/api/index.php/status"))
                .andExpect(header("DOLAPIKEY", "cle"))
                .andRespond(withSuccess(
                        "{\"success\":{\"code\":200,\"dolibarr_version\":\"23.0.2\"}}",
                        MediaType.APPLICATION_JSON));

        CrmCheck resultat = client.verifieAcces(CIBLE);

        assertThat(resultat.ok()).isTrue();
        assertThat(resultat.cause()).isEqualTo(CrmCheckCause.JOIGNABLE);
        serveur.verify();
    }

    @Test
    void unQuatreCentUnRendIdentifiantsRefuses() {
        prepare();
        serveur.expect(requestTo("http://erp.test/api/index.php/status"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.verifieAcces(CIBLE).cause())
                .isEqualTo(CrmCheckCause.IDENTIFIANTS_REFUSES);
    }

    @Test
    void unQuatreCentQuatreRendCibleInconnue() {
        prepare();
        serveur.expect(requestTo("http://erp.test/api/index.php/status"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.verifieAcces(CIBLE).cause()).isEqualTo(CrmCheckCause.CIBLE_INCONNUE);
    }

    @Test
    void uneConnexionRefuseeRendInjoignable() {
        prepare();
        serveur.expect(requestTo("http://erp.test/api/index.php/status"))
                .andRespond(withException(new IOException("Connection refused")));

        CrmCheck resultat = client.verifieAcces(CIBLE);

        assertThat(resultat.cause()).isEqualTo(CrmCheckCause.INJOIGNABLE);
        // Le detail technique reste disponible pour qui diagnostique lui-meme.
        assertThat(resultat.detail()).contains("Connection refused");
    }

    @Test
    void unReglageAbsentNAppellePasLErp() {
        prepare();

        CrmCheck resultat = client.verifieAcces(new CrmTarget("dolibarr", Map.of()));

        // Aucune attente posee sur le serveur : la sonde doit rendre la main avant l'appel.
        assertThat(resultat.cause()).isEqualTo(CrmCheckCause.REPONSE_INATTENDUE);
        serveur.verify();
    }
}
```

- [ ] **Step 3: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=DolibarrSondeTest`
Expected: FAIL — `verifieAcces` n'existe pas sur `DolibarrClient`.

- [ ] **Step 4: Écrire la sonde Dolibarr**

Ajouter à `DolibarrClient` :

```java
    /**
     * Sonde d'acces, appelee par l'ecran d'administration avant d'enregistrer une boutique.
     *
     * <p>{@code /status} plutot que {@code /users} : il est concu pour cela, ne lit aucune
     * donnee metier et valide d'un coup l'adresse et la cle d'API.
     *
     * <p>Elle ne leve jamais : un echec de sonde est une reponse, pas un incident. C'est ce
     * qui permet a l'endpoint de test de rendre 200 avec une cause.
     */
    public CrmCheck verifieAcces(CrmTarget target) {
        String base = target.settings() == null ? null : target.settings().get("baseUrl");
        String cle = target.settings() == null ? null : target.settings().get("apiKey");
        if (base == null || base.isBlank() || cle == null || cle.isBlank()) {
            return CrmCheck.echec(
                    CrmCheckCause.REPONSE_INATTENDUE, "Reglage 'baseUrl' ou 'apiKey' absent");
        }
        try {
            restClient(target).get().uri("/status").retrieve().body(Map.class);
            return CrmCheck.joignable(null);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            return CrmCheck.echec(CrmCheckCause.IDENTIFIANTS_REFUSES, e.getMessage());
        } catch (HttpClientErrorException.NotFound e) {
            return CrmCheck.echec(CrmCheckCause.CIBLE_INCONNUE, e.getMessage());
        } catch (ResourceAccessException e) {
            return CrmCheck.echec(CrmCheckCause.INJOIGNABLE, e.getMessage());
        } catch (RestClientException e) {
            return CrmCheck.echec(CrmCheckCause.REPONSE_INATTENDUE, e.getMessage());
        }
    }
```

Imports à ajouter : `org.springframework.web.client.HttpClientErrorException`, `org.springframework.web.client.ResourceAccessException`, `com.leadflow.crm.model.CrmCheck`, `com.leadflow.crm.model.CrmCheckCause`.

- [ ] **Step 5: Vérifier que le test passe**

Run: `cd backend && ./mvnw test -Dtest=DolibarrSondeTest`
Expected: PASS, 5 tests.

- [ ] **Step 6: Écrire le test puis la sonde Odoo**

`backend/src/test/java/com/leadflow/crm/odoo/OdooSondeTest.java` — même structure, contre l'endpoint JSON-RPC. Odoo répond toujours `200` : l'échec vit dans le corps.

```java
package com.leadflow.crm.odoo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.leadflow.crm.model.CrmCheckCause;
import com.leadflow.crm.model.CrmTarget;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OdooSondeTest {

    private static final CrmTarget CIBLE = new CrmTarget("odoo", Map.of(
            "baseUrl", "http://odoo.test",
            "database", "leadflow",
            "username", "admin",
            "apiKey", "motdepasse"));

    private MockRestServiceServer serveur;
    private OdooClient client;

    private void prepare() {
        RestClient.Builder builder = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(builder).build();
        client = new OdooClient(builder);
    }

    @Test
    void unUidPositifRendJoignable() {
        prepare();
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess("{\"result\":2}", MediaType.APPLICATION_JSON));

        assertThat(client.verifieAcces(CIBLE).ok()).isTrue();
        serveur.verify();
    }

    @Test
    void unResultatFauxRendIdentifiantsRefuses() {
        prepare();
        // Odoo repond 200 avec « false » quand le mot de passe est mauvais.
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess("{\"result\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.verifieAcces(CIBLE).cause())
                .isEqualTo(CrmCheckCause.IDENTIFIANTS_REFUSES);
    }

    @Test
    void uneBaseInexistanteRendCibleInconnue() {
        prepare();
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess(
                        "{\"error\":{\"data\":{\"message\":\"database \\\"absente\\\" does not"
                                + " exist\"}}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.verifieAcces(CIBLE).cause()).isEqualTo(CrmCheckCause.CIBLE_INCONNUE);
    }

    @Test
    void uneConnexionRefuseeRendInjoignable() {
        prepare();
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withException(new IOException("Connection refused")));

        assertThat(client.verifieAcces(CIBLE).cause()).isEqualTo(CrmCheckCause.INJOIGNABLE);
    }
}
```

Puis dans `OdooClient` :

```java
    /**
     * Sonde d'acces : l'authentification JSON-RPC valide d'un seul appel l'adresse, la base,
     * l'utilisateur et la cle. Rien n'est cree.
     *
     * <p>Odoo repond 200 meme en cas de refus — l'echec vit dans le corps — donc la
     * distinction se fait sur le contenu et non sur le code HTTP.
     */
    public CrmCheck verifieAcces(CrmTarget target) {
        for (String cle : List.of("baseUrl", "database", "username", "apiKey")) {
            String valeur = target.settings() == null ? null : target.settings().get(cle);
            if (valeur == null || valeur.isBlank()) {
                return CrmCheck.echec(
                        CrmCheckCause.REPONSE_INATTENDUE, "Reglage '" + cle + "' absent");
            }
        }
        try {
            int uid = authentifie(target);
            return uid > 0
                    ? CrmCheck.joignable(null)
                    : CrmCheck.echec(CrmCheckCause.IDENTIFIANTS_REFUSES, null);
        } catch (CrmSyncException echec) {
            // `appelle` enveloppe toute RestClientException, injoignabilite comprise : la
            // distinction se lit dans la cause et non dans un catch separe. Verifie sur le
            // code d'OdooClient, pas suppose.
            if (echec.getCause() instanceof ResourceAccessException reseau) {
                return CrmCheck.echec(CrmCheckCause.INJOIGNABLE, reseau.getMessage());
            }
            String message = echec.getMessage() == null ? "" : echec.getMessage().toLowerCase();
            if (message.contains("database")) {
                return CrmCheck.echec(CrmCheckCause.CIBLE_INCONNUE, echec.getMessage());
            }
            return CrmCheck.echec(CrmCheckCause.IDENTIFIANTS_REFUSES, echec.getMessage());
        }
    }
```

**Le message de `CrmSyncException` ne porte pas forcement celui d'Odoo** : `appelle` construit « Appel Odoo en echec sur authenticate », tandis que `resultat` reprend `error.data.message`. Vérifier lequel des deux chemins produit « database ... does not exist » avant de figer la détection, et couvrir les deux par un test.

Run: `cd backend && ./mvnw test -Dtest=OdooSondeTest`
Expected: PASS, 4 tests.

- [ ] **Step 7: Étendre le port et les deux connecteurs**

Dans `crm/CrmConnector.java` :

```java
    /**
     * Les reglages que ce fournisseur attend dans {@code client.crm_config}.
     *
     * <p>Declares ici et non recopies dans l'ecran d'administration : sans cela, ajouter un
     * ERP demanderait de modifier aussi {@code tenant/} et le formulaire Angular, alors que
     * le projet promet trois gestes et aucun {@code switch} sur le nom du fournisseur.
     */
    List<CrmSettingSpec> reglagesAttendus();

    /**
     * Verifie que la cible repond et accepte les identifiants, sans rien creer.
     *
     * <p>Obligatoire, sans implementation par defaut : un {@code default} rendant « non
     * verifiable » laisserait un futur adaptateur degrader silencieusement une promesse
     * faite a l'ecran de creation.
     */
    CrmCheck verifieAcces(CrmTarget cible);
```

Dans `DolibarrConnector` :

```java
    @Override
    public List<CrmSettingSpec> reglagesAttendus() {
        return List.of(
                new CrmSettingSpec("baseUrl", "Adresse de l'API, /api/index.php compris", false),
                new CrmSettingSpec("apiKey", "Cle d'API de l'utilisateur de service", true));
    }

    @Override
    public CrmCheck verifieAcces(CrmTarget cible) {
        return client.verifieAcces(cible);
    }
```

Dans `OdooConnector` :

```java
    @Override
    public List<CrmSettingSpec> reglagesAttendus() {
        return List.of(
                new CrmSettingSpec("baseUrl", "Adresse du serveur, sans /jsonrpc", false),
                new CrmSettingSpec("database", "Base Odoo visee", false),
                new CrmSettingSpec("username", "Login du compte de service", false),
                new CrmSettingSpec("apiKey", "Mot de passe ou cle d'API de ce compte", true));
    }

    @Override
    public CrmCheck verifieAcces(CrmTarget cible) {
        return client.verifieAcces(cible);
    }
```

- [ ] **Step 8: Vérifier que rien d'existant n'a cassé, puis committer**

Run: `cd backend && ./mvnw test -Dtest='com.leadflow.crm.**'`
Expected: PASS — les tests de `crm/` passent, sondes comprises.

```bash
git add backend/src/main/java/com/leadflow/crm backend/src/test/java/com/leadflow/crm
git commit -m "feat: sonde d'acces et reglages declares sur le port CrmConnector

Une cle d'API fausse ne se voyait qu'au premier lead mort en DLQ. La sonde la
montre avant l'enregistrement, et rend une cause typee plutot qu'un message
brut : « I/O error on POST request ... Connection refused » ne se traduit pas
pour un utilisateur non technique.

reglagesAttendus() evite de recopier la liste des champs de chaque ERP dans
tenant/ et dans le formulaire Angular — ajouter un ERP doit rester trois gestes.
Les deux methodes sont obligatoires : un default rendant « non verifiable »
laisserait un futur adaptateur degrader une promesse faite a l'ecran."
```

---

## Task 2: Lecture des boutiques — liste et fiche

**Files:**
- Create: `backend/src/main/java/com/leadflow/tenant/dto/ClientSummaryAdmin.java`
- Create: `backend/src/main/java/com/leadflow/tenant/dto/ClientDetailAdmin.java`
- Create: `backend/src/main/java/com/leadflow/tenant/dto/SalesRepAdminView.java`
- Create: `backend/src/main/java/com/leadflow/tenant/ClientAdminService.java`
- Create: `backend/src/main/java/com/leadflow/tenant/ClientAdminController.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/ClientRepository.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/SalesRepRepository.java`
- Test: `backend/src/test/java/com/leadflow/tenant/ClientAdminReadTest.java`

**Interfaces:**
- Produces: `GET /api/admin/clients` → `List<ClientSummaryAdmin>` ; `GET /api/admin/clients/{id}` → `ClientDetailAdmin`. `ClientAdminService.liste()` et `.fiche(UUID)`.

- [ ] **Step 1: Écrire le test qui échoue**

`backend/src/test/java/com/leadflow/tenant/ClientAdminReadTest.java` :

```java
package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * L'assertion qui compte porte sur le corps JSON et non sur le DTO : c'est la serialisation
 * qui peut fuir, {@code Client} portant {@code hmacSecret} et {@code crmConfig} dechiffres a
 * la lecture par les converters.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class ClientAdminReadTest {

    private static final String SECRET_EN_CLAIR = "secret-hmac-tres-reconnaissable";
    private static final String CLE_ERP_EN_CLAIR = "cle-api-tres-reconnaissable";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClientRepository clients;
    @Autowired private SalesRepRepository commerciaux;

    @AfterEach
    void nettoie() {
        commerciaux.deleteAll();
        clients.deleteAll();
    }

    @Test
    void listeLesBoutiquesAvecLeNombreDeCommerciauxActifs() throws Exception {
        Client boutique = creeUneBoutique("Boutique du Nord");
        creeUnCommercial(boutique, "actif@test.fr", true);
        creeUnCommercial(boutique, "parti@test.fr", false);

        String corps = mockMvc.perform(get("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(corps)).hasSize(1);
        assertThat(mapper.readTree(corps).get(0).get("name").asText())
                .isEqualTo("Boutique du Nord");
        assertThat(mapper.readTree(corps).get(0).get("activeSalesReps").asInt()).isEqualTo(1);
        assertThat(corps).doesNotContain(SECRET_EN_CLAIR).doesNotContain(CLE_ERP_EN_CLAIR);
    }

    @Test
    void laFicheNeContientJamaisLeSecret() throws Exception {
        Client boutique = creeUneBoutique("Boutique du Sud");

        String corps = mockMvc.perform(get("/api/admin/clients/" + boutique.getId())
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(corps).contains("Boutique du Sud").contains(boutique.getPublicKey());
        assertThat(corps).doesNotContain(SECRET_EN_CLAIR);
        // Les reglages non secrets sont rendus pour que le formulaire les reaffiche ;
        // la cle d'API, elle, ne revient jamais.
        assertThat(corps).contains("http://erp.test");
        assertThat(corps).doesNotContain(CLE_ERP_EN_CLAIR);
    }

    @Test
    void uneBoutiqueInconnueRend404() throws Exception {
        mockMvc.perform(get("/api/admin/clients/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sansJetonLaListeEstRefusee() throws Exception {
        mockMvc.perform(get("/api/admin/clients")).andExpect(status().isUnauthorized());
    }

    private Client creeUneBoutique(String nom) {
        Client client = new Client();
        client.setName(nom);
        client.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        client.setHmacSecret(SECRET_EN_CLAIR);
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", CLE_ERP_EN_CLAIR));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client.setActive(true);
        return clients.save(client);
    }

    private void creeUnCommercial(Client boutique, String email, boolean actif) {
        SalesRep rep = new SalesRep();
        rep.setClient(boutique);
        rep.setFullName("Commercial " + email);
        rep.setEmail(email);
        rep.setActive(actif);
        commerciaux.save(rep);
    }

    private String jeton() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"secret-de-test\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(corps).get("token").asText();
    }
}
```

**Vérifier les setters de `Client` et `SalesRep` avant d'écrire ce test** : les noms utilisés ici sont déduits des champs, pas lus. Si `SalesRep` référence son client autrement, adapter.

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=ClientAdminReadTest`
Expected: FAIL — `/api/admin/clients` rend 401 puis 404, le contrôleur n'existe pas.

- [ ] **Step 3: Déplacer `RessourceIntrouvableException` vers `common/`**

Elle vit aujourd'hui dans `monitoring/`, et `tenant/` en a besoin. Faire dépendre `tenant/` de
`monitoring/` pour une exception REST inverserait la relation entre un observateur et ce qu'il
observe. Or `CLAUDE.md` place déjà les exceptions et la gestion d'erreurs REST dans `common/`,
où vit `ApiExceptionHandler` qui la traite.

```bash
cd backend
git mv src/main/java/com/leadflow/monitoring/RessourceIntrouvableException.java \
       src/main/java/com/leadflow/common/RessourceIntrouvableException.java
```

Changer sa déclaration de package, puis corriger les imports partout :

```bash
grep -rl "monitoring.RessourceIntrouvableException" src | xargs sed -i \
  "s/com.leadflow.monitoring.RessourceIntrouvableException/com.leadflow.common.RessourceIntrouvableException/"
```

Run: `./mvnw -q test -Dtest='com.leadflow.monitoring.**' -DfailIfNoSpecifiedTests=false`
Expected: PASS — le déplacement ne change aucun comportement.

- [ ] **Step 4: Écrire les DTO**

```java
package com.leadflow.tenant.dto;

import com.leadflow.tenant.AssignmentStrategyType;
import java.util.UUID;

/** Ligne de la liste des boutiques. Aucun reglage ERP : la fiche s'en charge. */
public record ClientSummaryAdmin(
        UUID id,
        String name,
        String crmProviderId,
        AssignmentStrategyType assignmentStrategy,
        boolean active,
        long activeSalesReps) {
}
```

```java
package com.leadflow.tenant.dto;

import com.leadflow.tenant.AssignmentStrategyType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fiche d'une boutique.
 *
 * <p>{@code crmSettings} ne porte que les reglages <b>non secrets</b> : le formulaire doit
 * pouvoir reafficher l'adresse du serveur, jamais la cle d'API. Le secret HMAC n'y figure
 * a aucun titre — il n'est rendu qu'a la creation et a la rotation.
 */
public record ClientDetailAdmin(
        UUID id,
        String name,
        String publicKey,
        String webhookPath,
        String crmProviderId,
        Map<String, String> crmSettings,
        AssignmentStrategyType assignmentStrategy,
        boolean active,
        List<SalesRepAdminView> salesReps) {
}
```

```java
package com.leadflow.tenant.dto;

import java.util.UUID;

public record SalesRepAdminView(
        UUID id,
        String fullName,
        String email,
        String sector,
        String zone,
        String crmRef,
        boolean active) {
}
```

- [ ] **Step 5: Compléter les repositories**

Dans `ClientRepository` :

```java
    List<Client> findAllByOrderByNameAsc();

    boolean existsByPublicKey(String publicKey);
```

Dans `SalesRepRepository` :

```java
    long countByClientIdAndActiveTrue(UUID clientId);
```

- [ ] **Step 6: Écrire `ClientAdminService`**

```java
package com.leadflow.tenant;

import com.leadflow.crm.CrmConnectorRegistry;
import com.leadflow.crm.model.CrmSettingSpec;
import com.leadflow.monitoring.RessourceIntrouvableException;
import com.leadflow.tenant.dto.ClientDetailAdmin;
import com.leadflow.tenant.dto.ClientSummaryAdmin;
import com.leadflow.tenant.dto.SalesRepAdminView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration des boutiques.
 *
 * <p>Ce service vit dans {@code tenant/} et non dans {@code monitoring/} : il ecrit dans
 * {@code client} et {@code sales_rep}, alors que {@code monitoring/} est un observateur qui
 * n'ecrit que sa table {@code dead_letter}.
 *
 * <p>Il ne chiffre rien lui-meme : {@code EncryptedStringConverter} et
 * {@code EncryptedJsonConverter} s'en chargent a l'ecriture. Le service manipule du clair.
 */
@Service
public class ClientAdminService {

    private final ClientRepository clients;
    private final SalesRepRepository commerciaux;
    private final CrmConnectorRegistry connecteurs;

    public ClientAdminService(
            ClientRepository clients,
            SalesRepRepository commerciaux,
            CrmConnectorRegistry connecteurs) {
        this.clients = clients;
        this.commerciaux = commerciaux;
        this.connecteurs = connecteurs;
    }

    @Transactional(readOnly = true)
    public List<ClientSummaryAdmin> liste() {
        return clients.findAllByOrderByNameAsc().stream()
                .map(client -> new ClientSummaryAdmin(
                        client.getId(),
                        client.getName(),
                        client.getCrmProviderId(),
                        client.getAssignmentStrategy(),
                        client.isActive(),
                        commerciaux.countByClientIdAndActiveTrue(client.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClientDetailAdmin fiche(UUID id) {
        Client client = clients.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException(
                        "Aucune boutique avec cet identifiant"));
        return new ClientDetailAdmin(
                client.getId(),
                client.getName(),
                client.getPublicKey(),
                "/api/webhooks/leads/" + client.getPublicKey(),
                client.getCrmProviderId(),
                reglagesNonSecrets(client),
                client.getAssignmentStrategy(),
                client.isActive(),
                commerciaux.findByClientIdOrderByFullName(client.getId()).stream()
                        .map(this::vue)
                        .toList());
    }

    /**
     * Ne rend que ce qui n'est pas secret.
     *
     * <p>La liste des cles secretes vient du connecteur, jamais d'un {@code switch} ecrit
     * ici : {@code tenant/} ne doit rien savoir de Dolibarr ni d'Odoo. Un fournisseur dont
     * le connecteur a disparu de la configuration rend une carte vide plutot que d'echouer —
     * la fiche doit rester consultable pour qu'on puisse corriger le fournisseur.
     */
    private Map<String, String> reglagesNonSecrets(Client client) {
        Set<String> secretes;
        try {
            secretes = connecteurs.forProvider(client.getCrmProviderId()).reglagesAttendus()
                    .stream()
                    .filter(CrmSettingSpec::secret)
                    .map(CrmSettingSpec::cle)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException fournisseurInconnu) {
            return Map.of();
        }
        Map<String, String> visibles = new LinkedHashMap<>();
        client.getCrmConfig().forEach((cle, valeur) -> {
            if (!secretes.contains(cle)) {
                visibles.put(cle, valeur);
            }
        });
        return visibles;
    }

    private SalesRepAdminView vue(SalesRep rep) {
        return new SalesRepAdminView(
                rep.getId(), rep.getFullName(), rep.getEmail(),
                rep.getSector(), rep.getZone(), rep.getCrmRef(), rep.isActive());
    }
}
```

`RessourceIntrouvableException` est importée depuis `com.leadflow.common` après le déplacement de l'étape 3.

- [ ] **Step 7: Écrire le contrôleur**

```java
package com.leadflow.tenant;

import com.leadflow.tenant.dto.ClientDetailAdmin;
import com.leadflow.tenant.dto.ClientSummaryAdmin;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration des boutiques, sous {@code /api/admin} pour ne pas entrer en collision avec
 * {@code /api/clients}, l'annuaire en lecture seule de {@code monitoring/} qui alimente les
 * filtres du dashboard. Les deux coexistent : ils servent deux besoins distincts.
 */
@RestController
@RequestMapping("/api/admin/clients")
public class ClientAdminController {

    private final ClientAdminService service;

    public ClientAdminController(ClientAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClientSummaryAdmin> liste() {
        return service.liste();
    }

    @GetMapping("/{id}")
    public ClientDetailAdmin fiche(@PathVariable UUID id) {
        return service.fiche(id);
    }
}
```

- [ ] **Step 8: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=ClientAdminReadTest`
Expected: PASS, 4 tests.

```bash
git add backend/src/main/java/com/leadflow/tenant backend/src/test/java/com/leadflow/tenant
git commit -m "feat: lecture des boutiques par l'API d'administration

Sous /api/admin pour ne pas heurter /api/clients, l'annuaire en lecture seule de
monitoring/ : les deux coexistent parce qu'ils servent deux besoins, et fusionner
ferait entrer une ecriture dans un package qui n'ecrit que dead_letter.

La fiche rend les reglages ERP non secrets pour que le formulaire les reaffiche,
et jamais la cle d'API ni le secret HMAC. Quelles cles sont secretes est declare
par le connecteur, pas ecrit ici : tenant/ ne sait rien de Dolibarr ni d'Odoo."
```

---

## Task 3: Création d'une boutique et de son premier commercial

**Files:**
- Create: `backend/src/main/java/com/leadflow/tenant/CleGenerator.java`
- Create: `backend/src/main/java/com/leadflow/tenant/dto/ClientForm.java`
- Create: `backend/src/main/java/com/leadflow/tenant/dto/SalesRepForm.java`
- Create: `backend/src/main/java/com/leadflow/tenant/dto/ClientCreated.java`
- Create: `backend/src/main/java/com/leadflow/tenant/ReglageManquantException.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/ClientAdminService.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/ClientAdminController.java`
- Modify: `backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/leadflow/tenant/ClientAdminCreationTest.java`

**Interfaces:**
- Produces: `POST /api/admin/clients` → `201` + `ClientCreated(id, publicKey, hmacSecret, webhookPath)`. `CleGenerator.clePublique()` et `.secretHmac()`.

**Une seule transaction.** La boutique et son premier commercial sont créés ensemble. Deux appels séparés laisseraient, si le second échoue, exactement l'état que cette feature empêche : une boutique active sans commercial, dont les leads partent en DLQ.

- [ ] **Step 1: Écrire le test qui échoue**

```java
package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ... memes imports et annotations que ClientAdminReadTest ...

class ClientAdminCreationTest {

    private static final String CORPS_VALIDE = """
            {
              "name": "Boutique du Centre",
              "crmProviderId": "dolibarr",
              "assignmentStrategy": "ROUND_ROBIN",
              "crmSettings": {"baseUrl": "http://erp.test", "apiKey": "cle-secrete"},
              "firstSalesRep": {"fullName": "Sara Bennani", "email": "sara@test.fr"}
            }
            """;

    @Test
    void creeLaBoutiqueEtRendLeSecretUneSeuleFois() throws Exception {
        String corps = mockMvc.perform(post("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS_VALIDE))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String secret = mapper.readTree(corps).get("hmacSecret").asText();
        String clePublique = mapper.readTree(corps).get("publicKey").asText();
        assertThat(secret).hasSize(64);
        assertThat(mapper.readTree(corps).get("webhookPath").asText())
                .isEqualTo("/api/webhooks/leads/" + clePublique);

        UUID id = UUID.fromString(mapper.readTree(corps).get("id").asText());
        String fiche = mockMvc.perform(get("/api/admin/clients/" + id)
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();

        // Le secret ne reapparait jamais : perdu veut dire regenere, pas recupere.
        assertThat(fiche).doesNotContain(secret);
        assertThat(fiche).doesNotContain("cle-secrete");
    }

    @Test
    void creeLePremierCommercialDansLaMemeTransaction() throws Exception {
        String corps = mockMvc.perform(post("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS_VALIDE))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(mapper.readTree(corps).get("id").asText());
        assertThat(commerciaux.countByClientIdAndActiveTrue(id)).isEqualTo(1);
    }

    @Test
    void refuseUnFournisseurInconnu() throws Exception {
        mockMvc.perform(post("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS_VALIDE.replace("dolibarr", "sap")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refuseUnReglageErpManquant() throws Exception {
        String sansCle = CORPS_VALIDE.replace(", \\"apiKey\\": \\"cle-secrete\\"", "");

        mockMvc.perform(post("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sansCle))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refuseUneCreationSansCommercial() throws Exception {
        String sansCommercial = CORPS_VALIDE.replaceAll(
                ",\\s*\\"firstSalesRep\\".*?\\}", "");

        mockMvc.perform(post("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sansCommercial))
                .andExpect(status().isBadRequest());
    }
}
```

**Note d'écriture :** les échappements de `replace` ci-dessus sont fragiles dans un bloc texte Java. Préférer construire les variantes avec des constantes distinctes plutôt que par manipulation de chaîne — le test doit être lisible, pas malin.

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=ClientAdminCreationTest`
Expected: FAIL — `POST /api/admin/clients` rend 405 ou 404.

- [ ] **Step 3: Écrire `CleGenerator`**

```java
package com.leadflow.tenant;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Fabrique les deux valeurs d'integration d'une boutique.
 *
 * <p>La cle publique est <b>opaque</b> : elle voyage dans l'URL du webhook, et y inscrire le
 * nom de la boutique revelerait la liste des clients a quiconque verrait passer un lien.
 *
 * <p>Le secret fait 32 octets, rendus en hexadecimal — le format de 64 caracteres deja en
 * usage dans le jeu de demonstration.
 */
@Component
public class CleGenerator {

    private static final SecureRandom ALEA = new SecureRandom();

    public String clePublique() {
        byte[] octets = new byte[12];
        ALEA.nextBytes(octets);
        return HexFormat.of().formatHex(octets);
    }

    public String secretHmac() {
        byte[] octets = new byte[32];
        ALEA.nextBytes(octets);
        return HexFormat.of().formatHex(octets);
    }
}
```

- [ ] **Step 4: Écrire les DTO d'entrée et de sortie**

```java
package com.leadflow.tenant.dto;

import com.leadflow.tenant.AssignmentStrategyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Corps de creation et de mise a jour.
 *
 * <p>{@code firstSalesRep} n'est exige qu'a la creation — la mise a jour reutilise ce record
 * en l'ignorant. C'est la contrepartie assumee d'un seul type de formulaire.
 */
public record ClientForm(
        @NotBlank String name,
        @NotBlank String crmProviderId,
        @NotNull AssignmentStrategyType assignmentStrategy,
        @NotNull Map<String, String> crmSettings,
        @Valid SalesRepForm firstSalesRep) {
}
```

```java
package com.leadflow.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SalesRepForm(
        @NotBlank String fullName,
        @NotBlank @Email String email,
        String sector,
        String zone,
        String crmRef) {
}
```

```java
package com.leadflow.tenant.dto;

import java.util.UUID;

/**
 * Reponse de creation — la seule, avec celle de rotation, a porter le secret en clair.
 *
 * <p>L'ecran l'affiche une fois puis l'oublie : le dashboard n'est pas un coffre a secrets
 * consultable, et le rendre a nouveau demanderait de le dechiffrer sur demande.
 */
public record ClientCreated(UUID id, String publicKey, String hmacSecret, String webhookPath) {
}
```

- [ ] **Step 5: Écrire l'exception et son handler**

```java
package com.leadflow.tenant;

/** Traduite en 400 : fournisseur inconnu, reglage ERP absent, ou commercial manquant. */
public class ReglageManquantException extends RuntimeException {
    public ReglageManquantException(String message) {
        super(message);
    }
}
```

Dans `common/ApiExceptionHandler` :

```java
    @ExceptionHandler(ReglageManquantException.class)
    ProblemDetail reglageManquant(ReglageManquantException echec) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, echec.getMessage());
    }
```

- [ ] **Step 6: Écrire la création dans `ClientAdminService`**

```java
    /**
     * Cree la boutique et son premier commercial <b>dans une seule transaction</b>.
     *
     * <p>Deux appels separes laisseraient, si le second echoue, une boutique active sans
     * commercial : le routage leverait AssignmentException et ses leads partiraient en DLQ
     * des le premier formulaire soumis. C'est exactement l'etat que cet ecran doit rendre
     * impossible a fabriquer.
     */
    @Transactional
    public ClientCreated cree(ClientForm formulaire) {
        if (formulaire.firstSalesRep() == null) {
            throw new ReglageManquantException(
                    "Une boutique doit etre creee avec au moins un commercial");
        }
        valideLesReglages(formulaire.crmProviderId(), formulaire.crmSettings());

        Client client = new Client();
        client.setName(formulaire.name());
        client.setPublicKey(generateur.clePublique());
        String secret = generateur.secretHmac();
        client.setHmacSecret(secret);
        client.setCrmProviderId(formulaire.crmProviderId());
        client.setCrmConfig(new LinkedHashMap<>(formulaire.crmSettings()));
        client.setAssignmentStrategy(formulaire.assignmentStrategy());
        client.setActive(true);
        Client enregistre = clients.save(client);

        SalesRep rep = new SalesRep();
        rep.setClient(enregistre);
        rep.setFullName(formulaire.firstSalesRep().fullName());
        rep.setEmail(formulaire.firstSalesRep().email());
        rep.setSector(formulaire.firstSalesRep().sector());
        rep.setZone(formulaire.firstSalesRep().zone());
        rep.setCrmRef(formulaire.firstSalesRep().crmRef());
        rep.setActive(true);
        commerciaux.save(rep);

        return new ClientCreated(
                enregistre.getId(),
                enregistre.getPublicKey(),
                secret,
                "/api/webhooks/leads/" + enregistre.getPublicKey());
    }

    /**
     * Valide contre ce que le connecteur declare, jamais contre une liste ecrite ici :
     * ajouter un ERP ne doit rien demander a ce package.
     */
    private void valideLesReglages(String providerId, Map<String, String> reglages) {
        List<CrmSettingSpec> attendus;
        try {
            attendus = connecteurs.forProvider(providerId).reglagesAttendus();
        } catch (IllegalArgumentException inconnu) {
            throw new ReglageManquantException(inconnu.getMessage());
        }
        for (CrmSettingSpec attendu : attendus) {
            String valeur = reglages == null ? null : reglages.get(attendu.cle());
            if (valeur == null || valeur.isBlank()) {
                throw new ReglageManquantException(
                        "Le reglage '" + attendu.cle() + "' est requis pour " + providerId);
            }
        }
    }
```

Injecter `CleGenerator generateur` dans le constructeur.

- [ ] **Step 7: Brancher l'endpoint**

```java
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientCreated cree(@Valid @RequestBody ClientForm formulaire) {
        return service.cree(formulaire);
    }
```

- [ ] **Step 8: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=ClientAdminCreationTest`
Expected: PASS, 5 tests.

```bash
git add backend/src/main backend/src/test
git commit -m "feat: creation d'une boutique et de son premier commercial

Les deux dans une seule transaction : deux appels separes laisseraient, si le
second echoue, une boutique active sans commercial — le routage leverait
AssignmentException et ses leads partiraient en DLQ des le premier formulaire.
C'est l'etat que cet ecran doit rendre impossible a fabriquer.

Le secret est rendu une seule fois et ne reapparait dans aucune autre reponse.
La cle publique est opaque : elle voyage dans l'URL du webhook, et y inscrire le
nom de la boutique revelerait la liste des clients."
```

---

## Task 4: Mise à jour, activation et désactivation

**Files:**
- Modify: `backend/src/main/java/com/leadflow/tenant/ClientAdminService.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/ClientAdminController.java`
- Test: `backend/src/test/java/com/leadflow/tenant/ClientDesactivationTest.java`

**Interfaces:**
- Produces: `PUT /api/admin/clients/{id}`, `POST /api/admin/clients/{id}/activate`, `POST /api/admin/clients/{id}/deactivate`.

**Le test qui compte relie l'écran au pipeline** : après désactivation, le webhook de la boutique doit rendre `401`. C'est la preuve que le bouton agit réellement sur la capture, et pas seulement sur une colonne.

- [ ] **Step 1: Écrire le test qui échoue**

```java
    @Test
    void unWebhookDeBoutiqueDesactiveeEstRefuse() throws Exception {
        Client boutique = creeUneBoutique("Boutique a fermer");
        String clePublique = boutique.getPublicKey();

        // Avant : la capture accepte.
        envoieUnLeadSigne(clePublique, SECRET_EN_CLAIR).andExpect(status().isAccepted());

        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/deactivate")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk());

        // Apres : le meme appel, correctement signe, est refuse.
        envoieUnLeadSigne(clePublique, SECRET_EN_CLAIR).andExpect(status().isUnauthorized());
    }

    @Test
    void laReactivationRestitueLaCapture() throws Exception {
        Client boutique = creeUneBoutique("Boutique a rouvrir");
        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/deactivate")
                .header("Authorization", "Bearer " + jeton()));

        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/activate")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk());

        envoieUnLeadSigne(boutique.getPublicKey(), SECRET_EN_CLAIR)
                .andExpect(status().isAccepted());
    }

    @Test
    void desactiverUneBoutiqueNeTouchePasAsesCommerciaux() throws Exception {
        Client boutique = creeUneBoutique("Boutique en pause");
        creeUnCommercial(boutique, "actif@test.fr", true);

        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/deactivate")
                .header("Authorization", "Bearer " + jeton()));

        // Coupler les deux ferait perdre l'information de qui etait actif.
        assertThat(commerciaux.countByClientIdAndActiveTrue(boutique.getId())).isEqualTo(1);
    }
```

Le helper de signature, calqué sur `LeadCaptureIntegrationTest` :

```java
    private ResultActions envoieUnLeadSigne(String clePublique, String secret) throws Exception {
        String corps = "{\"source\":\"test\",\"email\":\"prospect@test.fr\"}";
        long horodatage = Instant.now().getEpochSecond();
        String signature = hmacHex(secret, horodatage + "." + corps);
        return mockMvc.perform(post("/api/webhooks/leads/" + clePublique)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Leadflow-Signature", "t=" + horodatage + ",v1=" + signature)
                .content(corps));
    }
```

**Reprendre `hmacHex` de `LeadCaptureIntegrationTest`** plutôt que de le réécrire : la forme canonique de la signature y est déjà correcte.

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=ClientDesactivationTest`
Expected: FAIL — les routes d'activation n'existent pas.

- [ ] **Step 3: Écrire les trois méthodes de service**

```java
    /**
     * Met a jour l'identite, le fournisseur et les reglages ERP.
     *
     * <p>Ni les cles ni l'etat actif ne passent par la : ce sont des actions aux consequences
     * distinctes, exposees en sous-ressources pour qu'une simple correction de nom ne puisse
     * pas casser la signature d'une boutique par inadvertance.
     */
    @Transactional
    public ClientDetailAdmin metAJour(UUID id, ClientForm formulaire) {
        Client client = trouve(id);
        // Fusionner d'abord, valider ensuite : un champ secret laisse vide n'est pas un
        // reglage manquant, c'est un reglage inchange.
        Map<String, String> reglages = fusionne(client, formulaire);
        valideLesReglages(formulaire.crmProviderId(), reglages);
        client.setName(formulaire.name());
        client.setCrmProviderId(formulaire.crmProviderId());
        client.setCrmConfig(new LinkedHashMap<>(reglages));
        client.setAssignmentStrategy(formulaire.assignmentStrategy());
        return fiche(client.getId());
    }

    @Transactional
    public ClientDetailAdmin change(UUID id, boolean actif) {
        Client client = trouve(id);
        // Les commerciaux ne sont pas touches : la reactivation doit restituer la
        // configuration telle quelle, et coupler les deux ferait perdre qui etait actif.
        client.setActive(actif);
        return fiche(client.getId());
    }

    private Client trouve(UUID id) {
        return clients.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException(
                        "Aucune boutique avec cet identifiant"));
    }
```

**Les réglages secrets à la mise à jour.** La fiche ne rend pas la clé d'API : renvoyée vide par le formulaire, la validation la croirait absente. On **conserve l'ancienne valeur quand le champ secret arrive vide**, plutôt que d'exiger la ressaisie — sinon corriger un nom de boutique obligerait à retrouver la clé d'API de son ERP. D'où `fusionne`, appelée **avant** la validation :

```java
    /** Un champ secret laisse vide conserve la valeur enregistree : la fiche ne le rend pas. */
    private Map<String, String> fusionne(Client client, ClientForm formulaire) {
        Map<String, String> fusion = new LinkedHashMap<>(formulaire.crmSettings());
        connecteurs.forProvider(formulaire.crmProviderId()).reglagesAttendus().stream()
                .filter(CrmSettingSpec::secret)
                .forEach(spec -> {
                    String fourni = fusion.get(spec.cle());
                    if ((fourni == null || fourni.isBlank())
                            && client.getCrmConfig().containsKey(spec.cle())) {
                        fusion.put(spec.cle(), client.getCrmConfig().get(spec.cle()));
                    }
                });
        return fusion;
    }
```

et valider **après** fusion.

- [ ] **Step 4: Brancher les endpoints**

```java
    @PutMapping("/{id}")
    public ClientDetailAdmin metAJour(@PathVariable UUID id, @Valid @RequestBody ClientForm f) {
        return service.metAJour(id, f);
    }

    @PostMapping("/{id}/activate")
    public ClientDetailAdmin active(@PathVariable UUID id) {
        return service.change(id, true);
    }

    @PostMapping("/{id}/deactivate")
    public ClientDetailAdmin desactive(@PathVariable UUID id) {
        return service.change(id, false);
    }
```

- [ ] **Step 5: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest='ClientDesactivationTest,ClientAdminCreationTest,ClientAdminReadTest'`
Expected: PASS.

```bash
git commit -am "feat: mise a jour et desactivation d'une boutique

La desactivation remplace la suppression, que le schema interdit : lead et
raw_lead_event referencent client sans cascade. Un test le relie au pipeline —
apres desactivation, le webhook correctement signe rend 401, ce qui prouve que
le bouton agit sur la capture et pas seulement sur une colonne.

Les commerciaux ne sont pas touches : la reactivation restitue la configuration
telle quelle. Un champ secret laisse vide conserve sa valeur enregistree, sinon
corriger un nom de boutique obligerait a retrouver la cle d'API de son ERP."
```

---

## Task 5: Rotation du secret et de la clé publique

**Files:**
- Create: `backend/src/main/java/com/leadflow/tenant/dto/SecretRotated.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/ClientAdminService.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/ClientAdminController.java`
- Test: `backend/src/test/java/com/leadflow/tenant/RotationDesClesTest.java`

**Interfaces:**
- Produces: `POST /api/admin/clients/{id}/rotate-secret` → `SecretRotated(hmacSecret)` ; `POST /api/admin/clients/{id}/rotate-public-key` → `ClientDetailAdmin`.

- [ ] **Step 1: Écrire le test qui échoue**

```java
    @Test
    void apresRotationLAncienSecretNeSignePlus() throws Exception {
        Client boutique = creeUneBoutique("Boutique a tourner");

        envoieUnLeadSigne(boutique.getPublicKey(), SECRET_EN_CLAIR)
                .andExpect(status().isAccepted());

        String corps = mockMvc.perform(
                        post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                                .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String nouveau = mapper.readTree(corps).get("hmacSecret").asText();

        // L'ancien secret est mort — c'est toute la raison d'etre du bouton.
        envoieUnLeadSigne(boutique.getPublicKey(), SECRET_EN_CLAIR)
                .andExpect(status().isUnauthorized());
        envoieUnLeadSigne(boutique.getPublicKey(), nouveau)
                .andExpect(status().isAccepted());
    }

    @Test
    void apresRotationDeLaClePubliqueLAncienneUrlEstInconnue() throws Exception {
        Client boutique = creeUneBoutique("Boutique a re-adresser");
        String ancienne = boutique.getPublicKey();

        String corps = mockMvc.perform(
                        post("/api/admin/clients/" + boutique.getId() + "/rotate-public-key")
                                .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String nouvelle = mapper.readTree(corps).get("publicKey").asText();

        assertThat(nouvelle).isNotEqualTo(ancienne);
        envoieUnLeadSigne(ancienne, SECRET_EN_CLAIR).andExpect(status().isUnauthorized());
        envoieUnLeadSigne(nouvelle, SECRET_EN_CLAIR).andExpect(status().isAccepted());
    }

    @Test
    void leSecretTourneNApparaitDansAucuneAutreReponse() throws Exception {
        Client boutique = creeUneBoutique("Boutique discrete");
        String corps = mockMvc.perform(
                        post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                                .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();
        String nouveau = mapper.readTree(corps).get("hmacSecret").asText();

        String fiche = mockMvc.perform(get("/api/admin/clients/" + boutique.getId())
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();
        String liste = mockMvc.perform(get("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();

        assertThat(fiche).doesNotContain(nouveau);
        assertThat(liste).doesNotContain(nouveau);
    }
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=RotationDesClesTest`
Expected: FAIL — les routes n'existent pas.

- [ ] **Step 3: Écrire le DTO et les deux méthodes**

```java
package com.leadflow.tenant.dto;

/**
 * Reponse de rotation : le secret en clair, une derniere fois.
 *
 * <p>Un record d'un seul champ plutot que la fiche complete : ce que l'ecran doit faire ici
 * est different — afficher, faire copier, puis oublier.
 */
public record SecretRotated(String hmacSecret) {
}
```

```java
    /**
     * Regenere le secret HMAC.
     *
     * <p>Irreversible : l'ancien n'est nulle part, et le formulaire de la boutique cessera de
     * fonctionner tant qu'elle n'aura pas mis a jour son cote. L'ecran l'annonce avant de
     * confirmer.
     */
    @Transactional
    public SecretRotated tourneLeSecret(UUID id) {
        Client client = trouve(id);
        String secret = generateur.secretHmac();
        client.setHmacSecret(secret);
        return new SecretRotated(secret);
    }

    /**
     * Regenere la cle publique, donc l'URL du webhook.
     *
     * <p>C'est precisement pour cela que la cle publique est distincte de la cle primaire :
     * elle est revocable sans recreer la ligne, et sans perdre les leads deja captures.
     */
    @Transactional
    public ClientDetailAdmin tourneLaClePublique(UUID id) {
        Client client = trouve(id);
        client.setPublicKey(generateur.clePublique());
        return fiche(client.getId());
    }
```

- [ ] **Step 4: Brancher les endpoints, vérifier et committer**

```java
    @PostMapping("/{id}/rotate-secret")
    public SecretRotated tourneLeSecret(@PathVariable UUID id) {
        return service.tourneLeSecret(id);
    }

    @PostMapping("/{id}/rotate-public-key")
    public ClientDetailAdmin tourneLaClePublique(@PathVariable UUID id) {
        return service.tourneLaClePublique(id);
    }
```

Run: `cd backend && ./mvnw test -Dtest=RotationDesClesTest`
Expected: PASS, 3 tests.

```bash
git commit -am "feat: rotation du secret HMAC et de la cle publique

Deux tests relient la rotation au pipeline : apres rotation du secret, une
requete webhook signee avec l'ancien rend 401 ; apres rotation de la cle
publique, l'ancienne URL est inconnue. Sans eux, on verifierait qu'une colonne
a change, pas que la boutique a reellement change de cles.

La cle publique est revocable sans recreer la ligne — c'est la raison pour
laquelle elle est distincte de la cle primaire — donc les leads deja captures
sont conserves."
```

---

## Task 6: Gestion des commerciaux

**Files:**
- Create: `backend/src/main/java/com/leadflow/tenant/SalesRepAdminService.java`
- Create: `backend/src/main/java/com/leadflow/tenant/DernierCommercialException.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/ClientAdminController.java`
- Modify: `backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/leadflow/tenant/SalesRepAdminTest.java`

**Interfaces:**
- Produces: `GET`/`POST /api/admin/clients/{id}/sales-reps`, `PUT`/`POST /api/admin/sales-reps/{id}/(de)activate`.

- [ ] **Step 1: Écrire le test qui échoue**

```java
    @Test
    void ajouteUnCommercial() throws Exception {
        Client boutique = creeUneBoutique("Boutique qui recrute");

        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/sales-reps")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Karim Haddad\",\"email\":\"karim@test.fr\"}"))
                .andExpect(status().isCreated());

        assertThat(commerciaux.countByClientIdAndActiveTrue(boutique.getId())).isEqualTo(1);
    }

    @Test
    void refuseUnEmailDejaPrisDansLaMemeBoutique() throws Exception {
        Client boutique = creeUneBoutique("Boutique doublon");
        creeUnCommercial(boutique, "karim@test.fr", true);

        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/sales-reps")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Karim Bis\",\"email\":\"karim@test.fr\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void refuseDeDesactiverLeDernierCommercialActif() throws Exception {
        Client boutique = creeUneBoutique("Boutique fragile");
        SalesRep seul = creeUnCommercial(boutique, "seul@test.fr", true);

        // Sans lui, AssignmentException a chaque lead, donc DLQ : l'ecran ne doit pas
        // laisser fabriquer cet etat.
        mockMvc.perform(post("/api/admin/sales-reps/" + seul.getId() + "/deactivate")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isConflict());

        assertThat(commerciaux.countByClientIdAndActiveTrue(boutique.getId())).isEqualTo(1);
    }

    @Test
    void autoriseADesactiverQuandUnAutreResteActif() throws Exception {
        Client boutique = creeUneBoutique("Boutique fournie");
        SalesRep premier = creeUnCommercial(boutique, "un@test.fr", true);
        creeUnCommercial(boutique, "deux@test.fr", true);

        mockMvc.perform(post("/api/admin/sales-reps/" + premier.getId() + "/deactivate")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk());

        assertThat(commerciaux.countByClientIdAndActiveTrue(boutique.getId())).isEqualTo(1);
    }

    @Test
    void autoriseADesactiverLeDernierSiLaBoutiqueEstDejaDesactivee() throws Exception {
        Client boutique = creeUneBoutique("Boutique fermee");
        boutique.setActive(false);
        clients.save(boutique);
        SalesRep seul = creeUnCommercial(boutique, "seul@test.fr", true);

        // La regle protege le pipeline d'une boutique active. Fermee, elle ne capture plus.
        mockMvc.perform(post("/api/admin/sales-reps/" + seul.getId() + "/deactivate")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk());
    }
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=SalesRepAdminTest`
Expected: FAIL — les routes n'existent pas.

- [ ] **Step 3: Écrire l'exception, son handler et le service**

```java
package com.leadflow.tenant;

/**
 * Traduite en 409 : retirer le dernier commercial actif d'une boutique active ferait lever
 * AssignmentException au routage, donc partir ses leads en DLQ des le prochain formulaire.
 */
public class DernierCommercialException extends RuntimeException {
    public DernierCommercialException(String message) {
        super(message);
    }
}
```

```java
    @ExceptionHandler(DernierCommercialException.class)
    ProblemDetail dernierCommercial(DernierCommercialException echec) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, echec.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflitDeContrainte(DataIntegrityViolationException echec) {
        // Tranche par la base plutot que par un « existe-t-il deja ? » prealable, que deux
        // requetes concurrentes passeraient toutes les deux.
        log.warn("Contrainte d'unicite violee", echec);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Cette valeur est deja utilisee pour cette boutique");
    }
```

```java
package com.leadflow.tenant;

import com.leadflow.monitoring.RessourceIntrouvableException;
import com.leadflow.tenant.dto.SalesRepAdminView;
import com.leadflow.tenant.dto.SalesRepForm;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commerciaux d'une boutique.
 *
 * <p>La regle du dernier commercial actif est gardee <b>ici</b> et pas seulement a l'ecran :
 * l'API est appelable directement, et cette regle protege le pipeline, pas le confort de
 * l'interface.
 */
@Service
public class SalesRepAdminService {

    private final SalesRepRepository commerciaux;
    private final ClientRepository clients;

    public SalesRepAdminService(SalesRepRepository commerciaux, ClientRepository clients) {
        this.commerciaux = commerciaux;
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public List<SalesRepAdminView> deLaBoutique(UUID clientId) {
        return commerciaux.findByClientIdOrderByFullName(clientId).stream()
                .map(this::vue)
                .toList();
    }

    @Transactional
    public SalesRepAdminView ajoute(UUID clientId, SalesRepForm formulaire) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new RessourceIntrouvableException(
                        "Aucune boutique avec cet identifiant"));
        SalesRep rep = new SalesRep();
        rep.setClient(client);
        applique(rep, formulaire);
        rep.setActive(true);
        return vue(commerciaux.save(rep));
    }

    @Transactional
    public SalesRepAdminView metAJour(UUID id, SalesRepForm formulaire) {
        SalesRep rep = trouve(id);
        applique(rep, formulaire);
        return vue(rep);
    }

    @Transactional
    public SalesRepAdminView change(UUID id, boolean actif) {
        SalesRep rep = trouve(id);
        if (!actif && rep.isActive()) {
            UUID clientId = rep.getClient().getId();
            boolean boutiqueActive = rep.getClient().isActive();
            long actifs = commerciaux.countByClientIdAndActiveTrue(clientId);
            if (boutiqueActive && actifs <= 1) {
                throw new DernierCommercialException(
                        "C'est le dernier commercial actif de cette boutique. Sans lui, ses "
                                + "leads partiraient en file d'echec : ajouter un remplacant "
                                + "d'abord, ou desactiver la boutique.");
            }
        }
        rep.setActive(actif);
        return vue(rep);
    }

    private SalesRep trouve(UUID id) {
        return commerciaux.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException(
                        "Aucun commercial avec cet identifiant"));
    }

    private void applique(SalesRep rep, SalesRepForm formulaire) {
        rep.setFullName(formulaire.fullName());
        rep.setEmail(formulaire.email());
        rep.setSector(formulaire.sector());
        rep.setZone(formulaire.zone());
        rep.setCrmRef(formulaire.crmRef());
    }

    private SalesRepAdminView vue(SalesRep rep) {
        return new SalesRepAdminView(
                rep.getId(), rep.getFullName(), rep.getEmail(),
                rep.getSector(), rep.getZone(), rep.getCrmRef(), rep.isActive());
    }
}
```

- [ ] **Step 4: Brancher les endpoints, vérifier et committer**

Les routes de commerciaux vivent dans `ClientAdminController` pour celles imbriquées, et un `SalesRepAdminController` pour `/api/admin/sales-reps/{id}` — un commercial se désigne par son seul identifiant, sans avoir à rappeler sa boutique.

Run: `cd backend && ./mvnw test -Dtest=SalesRepAdminTest`
Expected: PASS, 5 tests.

```bash
git commit -am "feat: gestion des commerciaux d'une boutique

Le refus de desactiver le dernier commercial actif est garde dans le service et
pas seulement a l'ecran : l'API est appelable directement, et cette regle protege
le pipeline — sans commercial actif, le routage leve AssignmentException et les
leads partent en DLQ. Elle ne s'applique qu'aux boutiques actives : une boutique
fermee ne capture plus rien.

L'unicite de l'email par boutique est tranchee par la contrainte SQL existante,
pas par un « existe-t-il deja ? » prealable que deux requetes concurrentes
passeraient toutes les deux."
```

---

## Task 7: Fournisseurs ERP et test de connexion

**Files:**
- Create: `backend/src/main/java/com/leadflow/tenant/CrmProviderController.java`
- Create: `backend/src/main/java/com/leadflow/tenant/dto/CrmProviderView.java`
- Create: `backend/src/main/java/com/leadflow/tenant/dto/CrmTestRequest.java`
- Create: `backend/src/main/java/com/leadflow/tenant/dto/CrmTestResult.java`
- Test: `backend/src/test/java/com/leadflow/tenant/CrmProviderControllerTest.java`

**Interfaces:**
- Produces: `GET /api/admin/crm/providers` → `List<CrmProviderView>` ; `POST /api/admin/crm/test` → `CrmTestResult(boolean ok, String cause, String detail)`.

- [ ] **Step 1: Écrire le test qui échoue**

```java
    @Test
    void listeLesFournisseursEtLeursReglages() throws Exception {
        String corps = mockMvc.perform(get("/api/admin/crm/providers")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Le formulaire se genere a partir de cette reponse : ajouter un ERP ne doit
        // demander aucune modification du frontend.
        assertThat(corps).contains("dolibarr").contains("odoo");
        assertThat(corps).contains("baseUrl").contains("database");
    }

    @Test
    void unTestQuiEchoueRend200AvecSaCause() throws Exception {
        String corps = mockMvc.perform(post("/api/admin/crm/test")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"crmProviderId":"dolibarr",
                                 "crmSettings":{"baseUrl":"http://127.0.0.1:9",
                                                "apiKey":"peu-importe"}}
                                """))
                // Un echec de diagnostic n'est pas une panne du serveur : le traiter en 502
                // ferait passer l'intercepteur du dashboard pour un incident.
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(corps).get("ok").asBoolean()).isFalse();
        assertThat(mapper.readTree(corps).get("cause").asText()).isEqualTo("INJOIGNABLE");
    }

    @Test
    void unFournisseurInconnuRend400() throws Exception {
        mockMvc.perform(post("/api/admin/crm/test")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"crmProviderId\":\"sap\",\"crmSettings\":{}}"))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=CrmProviderControllerTest`
Expected: FAIL — les routes n'existent pas.

- [ ] **Step 3: Écrire les DTO et le contrôleur**

```java
package com.leadflow.tenant.dto;

import com.leadflow.crm.model.CrmSettingSpec;
import java.util.List;

/** Un fournisseur disponible et les champs que son formulaire doit proposer. */
public record CrmProviderView(String providerId, List<CrmSettingSpec> settings) {
}
```

```java
package com.leadflow.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** Parametres a eprouver, non enregistres : tout l'interet est de verifier avant de sauver. */
public record CrmTestRequest(
        @NotBlank String crmProviderId, @NotNull Map<String, String> crmSettings) {
}
```

```java
package com.leadflow.tenant.dto;

/**
 * Resultat du test.
 *
 * <p>{@code cause} est le nom de l'enumeration, pas une phrase : l'ecran phrase en francais,
 * et un message construit ici obligerait a redeployer le backend pour corriger un libelle.
 */
public record CrmTestResult(boolean ok, String cause, String detail) {
}
```

```java
package com.leadflow.tenant;

/**
 * Ce que le formulaire de boutique a besoin de savoir sur les ERP disponibles.
 *
 * <p>Le controleur vit dans {@code tenant/} bien qu'il parle d'ERP : il sert l'ecran
 * d'administration des boutiques, et {@code crm/} n'expose aucune route — c'est un port de
 * sortie, pas une facade HTTP.
 */
@RestController
@RequestMapping("/api/admin/crm")
public class CrmProviderController {

    private final CrmConnectorRegistry registre;

    public CrmProviderController(CrmConnectorRegistry registre) {
        this.registre = registre;
    }

    @GetMapping("/providers")
    public List<CrmProviderView> fournisseurs() {
        return registre.availableProviders().stream()
                .sorted()
                .map(id -> new CrmProviderView(id, registre.forProvider(id).reglagesAttendus()))
                .toList();
    }

    @PostMapping("/test")
    public CrmTestResult teste(@Valid @RequestBody CrmTestRequest demande) {
        CrmConnector connecteur;
        try {
            connecteur = registre.forProvider(demande.crmProviderId());
        } catch (IllegalArgumentException inconnu) {
            throw new ReglageManquantException(inconnu.getMessage());
        }
        CrmCheck resultat = connecteur.verifieAcces(
                new CrmTarget(demande.crmProviderId(), demande.crmSettings()));
        return new CrmTestResult(resultat.ok(), resultat.cause().name(), resultat.detail());
    }
}
```

- [ ] **Step 4: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=CrmProviderControllerTest`
Expected: PASS, 3 tests.

```bash
git commit -am "feat: fournisseurs ERP declares et test de connexion avant enregistrement

Le formulaire se genere a partir de ce que le backend declare : ajouter un ERP
ne demande aucune modification du frontend, ce qui prolonge la promesse des trois
gestes jusqu'a l'ecran.

Un test qui echoue rend 200 avec sa cause. C'est un resultat de diagnostic, pas
une panne du serveur : le traiter en 502 ferait passer l'intercepteur du
dashboard pour un incident et brouillerait le message."
```

---

## Task 8: Service frontend et modèles

**Files:**
- Create: `frontend/src/app/core/models/tenant.ts`
- Create: `frontend/src/app/core/api/tenant-api.ts`
- Test: `frontend/src/app/core/api/tenant-api.spec.ts`

**Interfaces:**
- Produces: `TenantApi` avec `boutiques()`, `boutique(id)`, `cree(form)`, `metAJour(id, form)`, `active(id)`, `desactive(id)`, `tourneLeSecret(id)`, `tourneLaClePublique(id)`, `fournisseurs()`, `teste(providerId, settings)`, et les méthodes de commerciaux.

- [ ] **Step 1: Écrire le test qui échoue**

```ts
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TenantApi } from './tenant-api';

describe('TenantApi', () => {
  let api: TenantApi;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(TenantApi);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('liste les boutiques sur un chemin relatif', () => {
    api.boutiques().subscribe();

    const requete = httpMock.expectOne('/api/admin/clients');
    expect(requete.request.method).toBe('GET');
    requete.flush([]);
  });

  it('teste une connexion sans enregistrer', () => {
    api.teste('dolibarr', { baseUrl: 'http://erp.test', apiKey: 'cle' }).subscribe();

    const requete = httpMock.expectOne('/api/admin/crm/test');
    expect(requete.request.method).toBe('POST');
    expect(requete.request.body.crmProviderId).toBe('dolibarr');
    requete.flush({ ok: true, cause: 'JOIGNABLE', detail: null });
  });

  it('desactive par un POST sur la sous-ressource', () => {
    api.desactive('abc').subscribe();

    // Une action aux consequences propres, pas un champ du PUT : la capture s'arrete.
    const requete = httpMock.expectOne('/api/admin/clients/abc/deactivate');
    expect(requete.request.method).toBe('POST');
    requete.flush({});
  });

  it('regenere le secret par une route dediee', () => {
    api.tourneLeSecret('abc').subscribe();

    const requete = httpMock.expectOne('/api/admin/clients/abc/rotate-secret');
    expect(requete.request.method).toBe('POST');
    requete.flush({ hmacSecret: 'nouveau' });
  });
});
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — `TenantApi` n'existe pas.

- [ ] **Step 3: Écrire les modèles**

`core/models/tenant.ts` — interfaces calquées champ pour champ sur les records du backend :
`ClientSummaryAdmin`, `ClientDetailAdmin`, `SalesRepAdminView`, `ClientForm`, `SalesRepForm`, `ClientCreated`, `SecretRotated`, `CrmSettingSpec`, `CrmProviderView`, `CrmTestResult`, plus :

```ts
/** Les causes que la sonde peut rendre, telles que l'enumeration du backend les nomme. */
export type CrmCheckCause =
  | 'JOIGNABLE'
  | 'INJOIGNABLE'
  | 'IDENTIFIANTS_REFUSES'
  | 'CIBLE_INCONNUE'
  | 'REPONSE_INATTENDUE';
```

- [ ] **Step 4: Écrire `TenantApi`**

Même forme que `LeadApi` et `DeadLetterApi` : `inject(HttpClient)`, chemins relatifs, une méthode par route. Javadoc de tête :

```ts
/**
 * Administration des boutiques.
 *
 * <p>Distinct de ClientApi, qui lit l'annuaire de monitoring pour alimenter les filtres :
 * celui-ci ecrit, et vise /api/admin. Les deux coexistent parce que le backend les separe.
 */
```

- [ ] **Step 5: Vérifier et committer**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

```bash
git add frontend/src/app/core
git commit -m "feat: service frontend d'administration des boutiques

Distinct de ClientApi, qui lit l'annuaire de monitoring pour les filtres : celui-ci
ecrit et vise /api/admin. Les deux coexistent parce que le backend les separe, et
les fusionner ferait entrer une ecriture dans un package observateur."
```

---

## Task 9: Écran de liste des boutiques

**Files:**
- Create: `frontend/src/app/features/boutiques/boutiques.ts` + `.html` + `.scss`
- Modify: `frontend/src/app/app.routes.ts`, `frontend/src/app/app.ts`, `frontend/src/app/app.html`

**Interfaces:**
- Consumes: `TenantApi.boutiques()`.
- Produces: la route `/boutiques` et l'entrée de menu.

- [ ] **Step 1: Invoquer le plugin visuel avant d'écrire le code**

```bash
python "C:/Users/Lenovo/.claude/plugins/cache/ui-ux-pro-max-skill/ui-ux-pro-max/2.13.0/.claude/skills/ui-ux-pro-max/scripts/search.py" "table row status action buttons" --domain ux -n 3
```

Lire `design-system/leadflow-dashboard/MASTER.md` et réutiliser les jetons `--lf-space-*`, `--lf-succes`, `--lf-echec`, la police Fira Sans sur les titres.

- [ ] **Step 2: Écrire l'écran**

`mat-table` avec les colonnes `name`, `crmProviderId`, `assignmentStrategy`, `activeSalesReps`, `active`, `actions`. Un bouton « Nouvelle boutique » en tête.

**Le compteur de commerciaux se signale quand il vaut zéro**, comme « aucun consommateur » sur l'écran File d'attente :

```html
<td mat-cell *matCellDef="let boutique">
  @if (boutique.activeSalesReps === 0) {
    <span class="boutiques__critique">aucun commercial</span>
  } @else {
    {{ boutique.activeSalesReps }}
  }
</td>
```

Une ligne mène à `/boutiques/:id`, comme la liste des leads mène au détail.

- [ ] **Step 3: Câbler la route et le menu**

Dans `app.routes.ts`, sous `authGuard` comme les autres :

```ts
  {
    path: 'boutiques',
    title: 'Boutiques',
    canActivate: [authGuard],
    loadComponent: () => import('./features/boutiques/boutiques').then((m) => m.Boutiques),
  },
```

Dans `app.ts`, ajouter à `entrees` : `{ chemin: '/boutiques', libelle: 'Boutiques', icone: 'storefront' }`.

- [ ] **Step 4: Vérifier et committer**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless && npm run build`
Expected: PASS, et un chunk lazy `boutiques` dans la sortie du build.

```bash
git commit -am "feat: ecran de liste des boutiques

Le compteur de commerciaux actifs se signale quand il vaut zero, comme « aucun
consommateur » sur l'ecran File d'attente : meme intention, rendre visible la
configuration qui casse silencieusement le pipeline avant que les leads ne
meurent."
```

---

## Task 10: Fiche d'une boutique

**Files:**
- Create: `frontend/src/app/features/boutiques/boutique-detail/boutique-detail.ts` + `.html` + `.scss`
- Modify: `frontend/src/app/app.routes.ts`

**Interfaces:**
- Consumes: `TenantApi.boutique(id)`, `.metAJour`, `.active`, `.desactive`, `.tourneLeSecret`, `.tourneLaClePublique`, `.fournisseurs`, `.teste`, et les méthodes de commerciaux.

- [ ] **Step 1: Écrire les quatre blocs**

Dans l'ordre d'usage : **Identité**, **ERP**, **Commerciaux**, **Intégration**.

**Le bloc ERP se génère** à partir de `fournisseurs()` : pour le fournisseur choisi, un champ par `CrmSettingSpec`, en `type="password"` quand `secret` vaut vrai, avec un texte d'aide portant `libelle`. Aucune liste de champs écrite en dur — c'est ce qui fait qu'ajouter un ERP ne touche pas cet écran.

Un champ secret laissé vide conserve la valeur enregistrée ; le placeholder le dit : « inchangée ».

Le squelette du bloc ERP généré, qui est la partie réellement nouvelle de cet écran :

```ts
  readonly fournisseurs = signal<CrmProviderView[]>([]);
  readonly reglages = computed(
    () => this.fournisseurs().find((f) => f.providerId === this.providerId())?.settings ?? [],
  );
```

```html
@for (reglage of reglages(); track reglage.cle) {
  <mat-form-field appearance="outline">
    <mat-label>{{ reglage.cle }}</mat-label>
    <input
      matInput
      [type]="reglage.secret ? 'password' : 'text'"
      [formControlName]="reglage.cle"
      [placeholder]="reglage.secret ? 'inchangee' : ''"
    />
    <mat-hint>{{ reglage.libelle }}</mat-hint>
  </mat-form-field>
}
```

Les contrôles du `FormGroup` sont ajoutés et retirés quand le fournisseur change — un champ
resté d'un fournisseur précédent partirait dans `crmSettings` et serait enregistré.

- [ ] **Step 2: Écrire le bouton de test et la traduction des causes**

```ts
  /**
   * Le backend rend un nom d'enumeration ; la phrase vit ici pour qu'un libelle se corrige
   * sans redeployer le backend.
   */
  private readonly phrases: Record<CrmCheckCause, string> = {
    JOIGNABLE: 'Connexion etablie.',
    INJOIGNABLE: 'Aucun serveur ne repond a cette adresse.',
    IDENTIFIANTS_REFUSES: 'Le serveur repond mais refuse la cle ou le compte.',
    CIBLE_INCONNUE: 'Le serveur repond mais ne connait pas cette base ou cette adresse.',
    REPONSE_INATTENDUE: 'Reponse illisible : cette adresse pointe probablement ailleurs.',
  };
```

Le détail technique est replié sous un `mat-expansion-panel`, pour l'exploitant qui diagnostique. Le bouton affiche un `mat-progress-bar` pendant l'appel — il peut durer jusqu'à quinze secondes.

- [ ] **Step 3: Écrire les confirmations qui disent leur conséquence**

Trois `confirm` — ou trois `mat-dialog` si le plugin visuel l'indique — dont le texte annonce l'effet plutôt que de demander « êtes-vous sûr ? » :

- désactiver : « Le formulaire de cette boutique recevra une erreur d'authentification immédiatement. »
- régénérer le secret : « Son formulaire cessera de fonctionner tant qu'elle n'aura pas mis à jour son secret. Le secret actuel sera définitivement perdu. »
- régénérer la clé publique : « L'URL de son webhook change. L'ancienne ne sera plus reconnue. »

- [ ] **Step 4: Câbler la route, vérifier et committer**

```ts
  {
    path: 'boutiques/:id',
    title: 'Boutique',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/boutiques/boutique-detail/boutique-detail').then((m) => m.BoutiqueDetail),
  },
```

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless && npm run build`
Expected: PASS.

```bash
git commit -am "feat: fiche d'une boutique, avec test de connexion et rotations

Le bloc ERP se genere a partir des reglages que le backend declare : ajouter un
ERP ne touche pas cet ecran. Les confirmations annoncent leur consequence au lieu
de demander « etes-vous sur ? » — desactiver coupe la capture, regenerer le secret
casse le formulaire de la boutique jusqu'a sa mise a jour."
```

---

## Task 11: Création d'une boutique et révélation du secret

**Files:**
- Create: `frontend/src/app/features/boutiques/boutique-nouvelle/boutique-nouvelle.ts` + `.html` + `.scss`
- Create: `frontend/src/app/features/boutiques/secret-revele/secret-revele.ts` + `.html` + `.scss`
- Modify: `frontend/src/app/app.routes.ts`

**Interfaces:**
- Consumes: `TenantApi.cree(form)`, `.fournisseurs()`, `.teste(...)`.

- [ ] **Step 1: Écrire l'écran de création**

Trois sections : identité, ERP, premier commercial. La règle centrale :

```ts
  /**
   * Le bouton reste inactif tant que la connexion ERP n'a pas ete testee avec succes et
   * qu'aucun commercial n'est saisi.
   *
   * Sans commercial actif, le routage leve AssignmentException et les leads de la boutique
   * partent en DLQ des le premier formulaire soumis. Un utilisateur non technique ne doit
   * pas pouvoir fabriquer cet etat.
   */
  readonly peutCreer = computed(
    () => this.formulaire.valid && this.testeAvecSucces() && !this.enCours(),
  );
```

`testeAvecSucces` retombe à `false` dès qu'un champ ERP change : un test réussi sur d'anciennes valeurs ne prouve rien sur les nouvelles.

- [ ] **Step 2: Écrire l'écran de révélation du secret**

Affiché après création — et réutilisé après rotation depuis la fiche. Il montre le secret et l'URL de webhook complète, chacun avec un bouton de copie, et **exige un clic explicite** sur « J'ai transmis ces informations » pour disparaître.

Un avertissement visible : « Ce secret ne sera plus jamais affiché. Perdu, il devra être régénéré, et le formulaire de la boutique cessera de fonctionner jusqu'à sa mise à jour. »

- [ ] **Step 3: Écrire le test du bouton**

```ts
  it('garde le bouton inactif tant que la connexion ERP n a pas ete testee', () => {
    // ... remplir le formulaire complet, sans tester la connexion ...
    expect(composant.peutCreer()).toBeFalse();
  });

  it('reactive le test quand un champ ERP change', () => {
    // ... tester avec succes, puis modifier baseUrl ...
    expect(composant.peutCreer()).toBeFalse();
  });
```

- [ ] **Step 4: Câbler la route, vérifier et committer**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless && npm run build`
Expected: PASS.

```bash
git commit -am "feat: creation d'une boutique et revelation unique du secret

Le bouton de creation reste inactif tant que la connexion ERP n'a pas ete testee
et qu'aucun commercial n'est saisi : sans commercial actif, les leads de la
boutique partiraient en DLQ des le premier formulaire, et un utilisateur non
technique ne doit pas pouvoir fabriquer cet etat. Un test reussi est invalide des
qu'un champ ERP change.

Le secret s'affiche une fois, derriere un accuse de reception explicite."
```

---

## Task 12: Documentation et recette

**Files:**
- Create: `docs/boutique-onboarding.md`
- Modify: `docs/monitoring-api.md`, `CLAUDE.md`

- [ ] **Step 1: Écrire `docs/boutique-onboarding.md`**

Accueillir une boutique de bout en bout, écrit pour quelqu'un qui n'ouvrira jamais la base : se connecter, créer, tester l'ERP, ajouter le premier commercial, transmettre l'URL de webhook et le secret, puis vérifier le premier lead sur le dashboard. Une section sur ce qu'il faut faire quand une boutique perd son secret.

- [ ] **Step 2: Compléter `docs/monitoring-api.md`**

Une section `/api/admin/` : les routes, un exemple `curl` par geste, et la règle du secret rendu une seule fois.

Y consigner aussi la **réserve de sécurité de `/api/admin/crm/test`** : l'endpoint fait émettre au serveur un appel HTTP vers une URL fournie par l'opérateur. Acceptable sur une console interne authentifiée ; à restreindre le jour où le dashboard s'ouvrirait à des utilisateurs moins fiables.

- [ ] **Step 3: Mettre `CLAUDE.md` d'accord avec le code**

Dans la section « Monitoring — l'observateur », ajouter que **le CRUD des boutiques vit dans `tenant/`** et pourquoi. Dans « Connecteurs ERP/CRM », ajouter les deux nouvelles méthodes du port aux trois gestes d'ajout d'un ERP. Mettre à jour « État actuel » : l'ajout d'une boutique ne demande plus la base.

- [ ] **Step 4: Passer toute la suite**

```bash
cd backend && ./mvnw test -Dtest='com.leadflow.capture.**,com.leadflow.common.**,com.leadflow.crm.**' -DfailIfNoSpecifiedTests=false
./mvnw test -Dtest='com.leadflow.monitoring.**' -DfailIfNoSpecifiedTests=false
./mvnw test -Dtest='com.leadflow.qualification.**' -DfailIfNoSpecifiedTests=false
./mvnw test -Dtest='com.leadflow.routing.**,com.leadflow.tenant.**,com.leadflow.BackendApplicationTests' -DfailIfNoSpecifiedTests=false
cd ../frontend && npm test -- --watch=false --browsers=ChromeHeadless && npm run build
```

Agréger les rapports après avoir vérifié qu'aucun n'est périmé — un rapport laissé par une exécution antérieure fausse le total :

```bash
cd backend && python -c "
import glob,re,io
tot=f=e=0
for p in glob.glob('target/surefire-reports/*.txt'):
    m=re.search(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+)', io.open(p,encoding='utf-8',errors='replace').read())
    if m: tot+=int(m.group(1)); f+=int(m.group(2)); e+=int(m.group(3))
print('Tests', tot, 'Echecs', f, 'Erreurs', e)"
```

- [ ] **Step 5: Dérouler les sept critères de recette sur pile réelle**

`docker compose up -d`, backend en profil `dev`, front sur `:4200`, puis, un par un, les sept critères de la spec. Consigner les résultats en fin de plan comme cela a été fait pour F6 — ce qui a été vérifié, comment, et ce qui reste ouvert.

- [ ] **Step 6: Commit final**

```bash
git add docs CLAUDE.md
git commit -m "docs: accueil d'une boutique, API d'administration, invariants

Ajouter une boutique ne demande plus la base : CLAUDE.md le dit, et le document
d'onboarding decrit le geste pour quelqu'un qui n'ouvrira jamais psql. La regle
« le CRUD des boutiques vit dans tenant/, monitoring/ reste un observateur »
rejoint les invariants, avec les deux methodes ajoutees au port CrmConnector."
```

Puis la fusion, selon la méthode du projet : `feature/f7-gestion-des-boutiques` est fusionnée dans `main` en `--no-ff` et **conservée** — elle sert d'historique de la feature.

---

# Résultats de recette — 25 août 2026

Pile réelle : `docker compose --profile dolibarr up -d` (Postgres, RabbitMQ, Dolibarr 23.0.2
avec module API, Tiers et Projets activés), backend en profil `dev` sur `:8090`, frontend
`ng serve` sur `:4200`. Les gestes ont été passés par l'API que consomme le dashboard, avec
le même jeton et les mêmes routes que les écrans.

| # | Critère | Résultat |
| - | ------- | -------- |
| 1 | Créer une boutique sans ouvrir la base, et voir son premier lead aller jusqu'à l'ERP | **Vérifié** |
| 2 | Une clé d'API fausse est signalée en français avant enregistrement | **Vérifié après correction** |
| 3 | Le bouton de création reste inactif sans test ERP ni commercial | **Vérifié, avec réserve** |
| 4 | Le secret n'est visible qu'une fois ; aucune autre réponse ne le contient | **Vérifié** |
| 5 | Après rotation, l'ancien secret ne signe plus | **Vérifié** |
| 6 | Une boutique désactivée rend `401`, et se réactive sans perte | **Vérifié** |
| 7 | Désactiver le dernier commercial actif est refusé, avec la raison | **Vérifié** |

## Critère 1 — de la création à Dolibarr

`POST /api/admin/clients` a rendu `id`, `publicKey`, `hmacSecret` et
`webhookPath` ; aucune requête SQL n'a été écrite de toute la recette. Un formulaire signé
HMAC posté sur le chemin rendu a été accepté en `202`, et le lead était `SYNCED` **au premier
sondage, moins de trois secondes plus tard** : score 70, intention `DEVIS` par le lexique
(`GEMINI_API_KEY` absente, repli nominal), attribué à Amina Benali — la commerciale créée
avec la boutique.

Côté Dolibarr, le tiers et le contact existent bien, portant l'adresse du prospect. La même
requête rejouée a rendu **le même `eventId`** : l'idempotence de la capture tient sur une
boutique créée par l'interface comme sur celle de démonstration.

Deux observations, sans conséquence sur le critère :

- Mon corps de test omettait d'abord `source`, obligatoire au contrat de capture : le webhook
  a rendu `400`. C'était une erreur de la recette, pas du produit.
- `companyName` est resté `null` alors que le corps le portait. `PayloadFieldMapper` reconnaît
  `societe`, `entreprise`, `company`, `raisonsociale` et `organisation`, mais pas
  `companyname`. Comportement de F3, documenté, hors périmètre de F7 — mais l'alias mériterait
  d'être ajouté, la clé étant naturelle pour un intégrateur anglophone.

## Critère 2 — le défaut que seule la pile réelle pouvait montrer

Premier passage : **une clé d'API fausse rendait `500` avec une trace Java**, exactement ce
que le critère interdit.

La cause n'était ni dans la sonde ni dans le contrôleur. **Dolibarr renvoie un en-tête
`WWW-Authenticate` vide sur ses `401`.** `HttpURLConnection` — la pile derrière
`SimpleClientHttpRequestFactory`, que `CrmHttpConfig` utilisait — analyse cet en-tête et lève
`IllegalArgumentException: invalid start or end` **avant** que Spring ne voie le code de
statut. L'exception n'étant pas une `RestClientException`, aucun `catch` de la sonde ne la
rattrapait, et elle remontait jusqu'au gestionnaire d'erreurs global.

Corrigé en deux endroits :

1. **À la racine** — `CrmHttpConfig` construit désormais un `JdkClientHttpRequestFactory` sur
   `java.net.http.HttpClient`, qui n'analyse pas cet en-tête. Le `401` redevient un
   `HttpClientErrorException.Unauthorized`, donc `IDENTIFIANTS_REFUSES`. La correction profite
   à tous les appels ERP, pas seulement à la sonde : la même exception aurait produit une mort
   opaque en DLQ lors d'une synchronisation avec une clé périmée.
2. **En filet** — les deux sondes rattrapent toute `RuntimeException` et rendent
   `REPONSE_INATTENDUE`. Leur contrat dit qu'elles ne lèvent jamais ; il est maintenant tenu
   quoi qu'il arrive sous la pile HTTP. Un test de régression le verrouille
   (`DolibarrSondeTest`).

Après correction, les quatre cas rendent tous `200` avec leur cause :

| Réglages | Cause rendue |
| -------- | ------------ |
| adresse et clé justes | `JOIGNABLE` |
| clé fausse | `IDENTIFIANTS_REFUSES` |
| port fermé | `INJOIGNABLE` |
| adresse qui pointe ailleurs | `CIBLE_INCONNUE` |

Un fournisseur inconnu rend bien `400`, et non `200`.

## Critère 3 — la réserve

La règle a deux moitiés. Côté serveur, `POST /api/admin/clients` **sans `firstSalesRep` rend
`400`** : vérifié sur la pile réelle. Côté écran, l'état du bouton est verrouillé par cinq
tests (`boutique-nouvelle.spec.ts`) qui couvrent le formulaire complet sans test ERP, le test
réussi, l'invalidation par modification d'un champ ERP, l'absence de commercial et un test en
échec.

**Ce qui n'a pas été fait : cliquer le bouton dans un navigateur.** Le frontend a été servi
contre la pile réelle et son proxy vérifié — `/api/admin/crm/providers` répond `401` sans
jeton et rend les deux fournisseurs avec jeton, le chunk `boutiques` est servi — mais
l'assertion sur l'état visuel du bouton repose sur les tests, pas sur une manipulation. C'est
la même réserve que le critère 7 de F6.

## Critère 4 — le secret ne fuit nulle part

Le secret rendu à la création a été cherché littéralement dans le corps de cinq réponses :
`/api/admin/clients`, la fiche, `/api/clients`, la liste des commerciaux et `/api/leads`.
**Absent partout.** La fiche ne porte aucun champ de secret, et ses `crmSettings` ne
contiennent que `baseUrl` — `apiKey`, déclaré secret par le connecteur, n'est pas rendu.

## Critères 5 et 6 — rotations et désactivation

Le webhook répond `202` avant désactivation, `401` pendant, `202` de nouveau après
réactivation ; la fiche retrouve ses réglages ERP et ses commerciaux dans l'état exact où ils
étaient, y compris celui qui avait été désactivé entre-temps.

Après `rotate-secret`, l'ancien secret rend `401` et le nouveau `202`. Après
`rotate-public-key`, l'ancienne URL rend `401` et la nouvelle `202`. Les leads déjà reçus ne
bougent pas.

## Critère 7 — le dernier commercial

Le refus est un `409` portant sa raison en clair : « C'est le dernier commercial actif de
cette boutique. Sans lui, ses leads partiraient en file d'échec : ajouter un remplaçant
d'abord, ou désactiver la boutique. » Après ajout d'un second commercial, la désactivation du
premier passe, sa réactivation aussi, et celle du second également — la règle porte bien sur
le *dernier actif*, pas sur un commercial en particulier.

## Ce qui reste ouvert

- **Le bouton de création n'a pas été manipulé dans un navigateur** (critère 3).
- **`POST /api/admin/crm/test` fait émettre au serveur un appel vers une URL fournie par
  l'opérateur.** Acceptable sur une console interne à compte unique ; à restreindre — liste
  blanche d'hôtes, refus des adresses privées — si le dashboard s'ouvre un jour aux boutiques
  elles-mêmes. Consigné dans `docs/monitoring-api.md` et dans `CLAUDE.md`.
- **L'alias `companyname` manque à `PayloadFieldMapper`** (F3, hors périmètre).
- Le connecteur Odoo n'a pas été éprouvé pendant cette recette : seul le profil `dolibarr`
  était monté. Sa sonde reste couverte par `OdooSondeTest`.
