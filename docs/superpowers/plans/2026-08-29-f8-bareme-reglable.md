# F8 — Bareme reglable et badge « chaud » : plan d'implementation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Regler le bareme de scoring d'une boutique depuis l'interface, et rendre le seuil
« chaud » observable par un badge sur les leads.

**Architecture:** Deux routes d'administration dans `tenant/` lisent et remplacent
`client.scoring_config`, en reutilisant `ScoringConfig` de `qualification/` comme unique
description de la forme du document. Le badge est calcule a la lecture dans `monitoring/` :
les seuils des boutiques de la page sont charges en une requete, puis appliques au score
enregistre — aucune ecriture, aucune migration.

**Tech Stack:** Spring Boot 4.1 / Java 21 / JPA Criteria / Angular 20 standalone + signals /
Karma-Jasmine / Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-29-f8-bareme-reglable-design.md`

## Global Constraints

- Branche de travail : `feature/f8-bareme-reglable` (deja creee, le design y est commite).
- **Aucune migration Flyway** : `scoring_config` existe depuis `V2` et n'est pas chiffree.
- **Aucune entite JPA en sortie HTTP** : uniquement des `record` de `tenant/dto/` et
  `monitoring/dto/`, et les tests assertent le **corps JSON**.
- **`monitoring/` n'ecrit rien** : il lit `client` pour connaitre les seuils.
- Backend sur `:8090`. Les tests exigent un daemon Docker (Testcontainers Postgres +
  RabbitMQ).
- Bornes de validation : poids de presence, poids d'intention et bonus dans `[0, 100]`,
  `seuilChaud` dans `[0, 100]` — parce que `LeadScorer` borne le total dans `[0, 100]`.
- Le score des leads existants n'est **jamais** recalcule.
- Toute decision visuelle passe par les skills du plugin `ui-ux-pro-max`, invoques **avant**
  d'ecrire un template (tache 5).
- Commandes : `./mvnw test -Dtest=<Classe>` depuis `backend/`,
  `npm test -- --watch=false --browsers=ChromeHeadless` depuis `frontend/`.

---

### Task 1: Le contrat du bareme — `ScoringForm`, `ScoringView`, et le test d'aller-retour

**Files:**
- Create: `backend/src/main/java/com/leadflow/tenant/dto/ScoringForm.java`
- Create: `backend/src/main/java/com/leadflow/tenant/dto/ScoringView.java`
- Test: `backend/src/test/java/com/leadflow/tenant/ScoringAllerRetourTest.java`

**Interfaces:**
- Consumes: `com.leadflow.qualification.ScoringConfig` (`defaut()`, `depuis(Map)`),
  `com.leadflow.qualification.LeadIntent`.
- Produces: `ScoringForm.versDocument() : Map<String, Object>`,
  `ScoringView.de(ScoringConfig) : ScoringView`, `ScoringView.valeurs() : ScoringForm`,
  `ScoringView.scoreMaximum() : int`, `ScoringView.seuilInatteignable() : boolean`,
  `ScoringView.defauts() : ScoringForm`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/leadflow/tenant/ScoringAllerRetourTest.java`

```java
package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.qualification.LeadIntent;
import com.leadflow.qualification.ScoringConfig;
import com.leadflow.tenant.dto.ScoringForm;
import com.leadflow.tenant.dto.ScoringView;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Le garde-fou du cycle de packages : {@code tenant/} ecrit un document que
 * {@code qualification/} relit, et rien dans la structure du code n'empeche les deux formes
 * de diverger. Ce test, oui.
 */
class ScoringAllerRetourTest {

    @Test
    void leDocumentEcritEstReluIdentiquement() {
        ScoringForm formulaire = new ScoringForm(
                20, 12, 6, 8,
                Map.of(
                        LeadIntent.DEVIS, 45,
                        LeadIntent.ACHAT, 35,
                        LeadIntent.INFORMATION, 10,
                        LeadIntent.SUPPORT, 4,
                        LeadIntent.AUTRE, 1),
                Set.of("industrie"), Set.of("FR"), 9, 65);

        ScoringConfig relu = ScoringConfig.depuis(formulaire.versDocument());

        assertThat(relu.telephonePresent()).isEqualTo(20);
        assertThat(relu.societePresente()).isEqualTo(12);
        assertThat(relu.nomPresent()).isEqualTo(6);
        assertThat(relu.messagePresent()).isEqualTo(8);
        assertThat(relu.intention()).containsEntry(LeadIntent.DEVIS, 45);
        assertThat(relu.intention()).containsEntry(LeadIntent.AUTRE, 1);
        assertThat(relu.secteursCibles()).containsExactly("industrie");
        assertThat(relu.paysCibles()).containsExactly("FR");
        assertThat(relu.bonusCible()).isEqualTo(9);
        assertThat(relu.seuilChaud()).isEqualTo(65);
    }

    /** L'ecran montre la normalisation au lieu de la subir : ce que l'on relit est normalise. */
    @Test
    void lesListesCiblesSontNormaliseesALaRelecture() {
        ScoringForm formulaire = new ScoringForm(
                15, 10, 5, 10, Map.of(), Set.of("  Industrie  "), Set.of("fr"), 10, 70);

        ScoringConfig relu = ScoringConfig.depuis(formulaire.versDocument());

        assertThat(relu.secteursCibles()).containsExactly("industrie");
        assertThat(relu.paysCibles()).containsExactly("FR");
    }

    /** Une intention absente du formulaire garde le poids par defaut, jamais zero. */
    @Test
    void uneIntentionAbsenteGardeLeDefaut() {
        ScoringForm formulaire = new ScoringForm(
                15, 10, 5, 10, Map.of(LeadIntent.DEVIS, 45), Set.of(), Set.of(), 10, 70);

        ScoringConfig relu = ScoringConfig.depuis(formulaire.versDocument());

        assertThat(relu.intention()).containsEntry(LeadIntent.DEVIS, 45);
        assertThat(relu.intention()).containsEntry(LeadIntent.ACHAT, 40);
    }

    @Test
    void laVueCalculeLeMaximumAtteignableEtSignaleUnSeuilHorsDePortee() {
        ScoringView pardefaut = ScoringView.de(ScoringConfig.defaut());

        // 15 + 10 + 5 + 10 + 40 (meilleure intention) + 10 (bonus) = 90
        assertThat(pardefaut.scoreMaximum()).isEqualTo(90);
        assertThat(pardefaut.seuilInatteignable()).isFalse();
        assertThat(pardefaut.defauts().seuilChaud()).isEqualTo(70);

        ScoringForm maigre = new ScoringForm(
                1, 1, 1, 1, Map.of(LeadIntent.DEVIS, 5), Set.of(), Set.of(), 0, 80);
        ScoringView vue = ScoringView.de(ScoringConfig.depuis(maigre.versDocument()));

        assertThat(vue.seuilInatteignable()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ScoringAllerRetourTest`
Expected: FAIL — compilation, `ScoringForm` et `ScoringView` n'existent pas.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/leadflow/tenant/dto/ScoringForm.java`

```java
package com.leadflow.tenant.dto;

import com.leadflow.qualification.LeadIntent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bareme d'une boutique tel qu'il se saisit et se rend.
 *
 * <p>Ce record vit dans {@code tenant/} mais decrit un document que {@code qualification/}
 * relit : {@code ScoringAllerRetourTest} est ce qui empeche les deux formes de diverger. Le
 * jeu de criteres est ferme, comme {@code ScoringConfig} — seuls les poids et les listes
 * cibles se reglent.
 */
public record ScoringForm(
        int telephonePresent,
        int societePresente,
        int nomPresent,
        int messagePresent,
        Map<LeadIntent, Integer> intention,
        Set<String> secteursCibles,
        Set<String> paysCibles,
        int bonusCible,
        int seuilChaud) {

    /** Document tel qu'il part dans {@code client.scoring_config}. */
    public Map<String, Object> versDocument() {
        Map<String, Object> poids = new LinkedHashMap<>();
        poids.put("telephonePresent", telephonePresent);
        poids.put("societePresente", societePresente);
        poids.put("nomPresent", nomPresent);
        poids.put("messagePresent", messagePresent);

        Map<String, Object> intentions = new LinkedHashMap<>();
        intention.forEach((cle, valeur) -> intentions.put(cle.name(), valeur));
        poids.put("intention", intentions);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("poids", poids);
        document.put("secteursCibles", List.copyOf(secteursCibles));
        document.put("paysCibles", List.copyOf(paysCibles));
        document.put("bonusCible", bonusCible);
        document.put("seuilChaud", seuilChaud);
        return document;
    }

    public static ScoringForm de(com.leadflow.qualification.ScoringConfig bareme) {
        return new ScoringForm(
                bareme.telephonePresent(),
                bareme.societePresente(),
                bareme.nomPresent(),
                bareme.messagePresent(),
                bareme.intention(),
                bareme.secteursCibles(),
                bareme.paysCibles(),
                bareme.bonusCible(),
                bareme.seuilChaud());
    }
}
```

`backend/src/main/java/com/leadflow/tenant/dto/ScoringView.java`

```java
package com.leadflow.tenant.dto;

import com.leadflow.qualification.ScoringConfig;

/**
 * Ce que l'ecran affiche : les valeurs <b>effectives</b> — defauts compris, car c'est le
 * bareme que {@code LeadScorer} applique reellement — plus deux calculs qui remplacent
 * l'apercu volontairement absent.
 *
 * <p>{@code scoreMaximum} est borne a 100 comme le score lui-meme, et
 * {@code seuilInatteignable} dit qu'aucun lead ne pourra jamais etre chaud avec ce bareme.
 * Ce n'est pas une erreur de saisie — c'est un etat qu'il faut voir.
 */
public record ScoringView(
        ScoringForm valeurs,
        int scoreMaximum,
        boolean seuilInatteignable,
        ScoringForm defauts) {

    private static final int PLAFOND = 100;

    public static ScoringView de(ScoringConfig bareme) {
        int meilleureIntention = bareme.intention().values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        int maximum = Math.min(
                PLAFOND,
                bareme.telephonePresent()
                        + bareme.societePresente()
                        + bareme.nomPresent()
                        + bareme.messagePresent()
                        + meilleureIntention
                        + bareme.bonusCible());
        return new ScoringView(
                ScoringForm.de(bareme),
                maximum,
                bareme.seuilChaud() > maximum,
                ScoringForm.de(ScoringConfig.defaut()));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ScoringAllerRetourTest`
Expected: PASS — 4 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/tenant/dto/ScoringForm.java \
        backend/src/main/java/com/leadflow/tenant/dto/ScoringView.java \
        backend/src/test/java/com/leadflow/tenant/ScoringAllerRetourTest.java
git commit -m "feat: la forme du bareme, et le test qui l empeche de deriver"
```

---

### Task 2: Les routes de lecture et d'ecriture du bareme

**Files:**
- Create: `backend/src/main/java/com/leadflow/tenant/ScoringAdminService.java`
- Create: `backend/src/main/java/com/leadflow/tenant/ScoringAdminController.java`
- Test: `backend/src/test/java/com/leadflow/tenant/ScoringAdminTest.java`

**Interfaces:**
- Consumes: `ScoringForm`, `ScoringView` (tache 1), `ClientRepository.findById`,
  `com.leadflow.common.RessourceIntrouvableException`.
- Produces: `ScoringAdminService.lit(UUID) : ScoringView`,
  `ScoringAdminService.remplace(UUID, ScoringForm) : ScoringView`, et les routes
  `GET`/`PUT /api/admin/clients/{id}/scoring`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/leadflow/tenant/ScoringAdminTest.java`

```java
package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * L'assertion porte sur le corps JSON : c'est la serialisation qui sera consommee par
 * l'ecran, et un DTO correct ne prouve pas un JSON correct.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class ScoringAdminTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClientRepository clients;

    private UUID boutique;

    @AfterEach
    void nettoie() {
        clients.deleteAll();
    }

    /**
     * Un document vide ne doit pas rendre un formulaire vide : l'ecran afficherait des zeros
     * la ou le scoreur applique les defauts.
     */
    @Test
    void unDocumentVideRendLesValeursParDefaut() throws Exception {
        boutique = creeBoutique(Map.of());

        String corps = mockMvc.perform(get("/api/admin/clients/" + boutique + "/scoring")
                        .with(operateur()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var json = mapper.readTree(corps);
        assertThat(json.get("valeurs").get("telephonePresent").asInt()).isEqualTo(15);
        assertThat(json.get("valeurs").get("intention").get("DEVIS").asInt()).isEqualTo(40);
        assertThat(json.get("valeurs").get("seuilChaud").asInt()).isEqualTo(70);
        assertThat(json.get("scoreMaximum").asInt()).isEqualTo(90);
        assertThat(json.get("seuilInatteignable").asBoolean()).isFalse();
        assertThat(json.get("defauts").get("bonusCible").asInt()).isEqualTo(10);
    }

    @Test
    void ceQuiEstEcritEstReluTelQuel() throws Exception {
        boutique = creeBoutique(Map.of());
        String formulaire = """
                {"telephonePresent":20,"societePresente":12,"nomPresent":6,"messagePresent":8,
                 "intention":{"DEVIS":45,"ACHAT":35,"INFORMATION":10,"SUPPORT":4,"AUTRE":1},
                 "secteursCibles":["Industrie"],"paysCibles":["fr"],
                 "bonusCible":9,"seuilChaud":65}""";

        mockMvc.perform(put("/api/admin/clients/" + boutique + "/scoring")
                        .with(operateur())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(formulaire))
                .andExpect(status().isOk());

        String corps = mockMvc.perform(get("/api/admin/clients/" + boutique + "/scoring")
                        .with(operateur()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var json = mapper.readTree(corps);
        assertThat(json.get("valeurs").get("telephonePresent").asInt()).isEqualTo(20);
        assertThat(json.get("valeurs").get("seuilChaud").asInt()).isEqualTo(65);
        // Normalisation visible : minuscules pour les secteurs, majuscules pour les pays.
        assertThat(json.get("valeurs").get("secteursCibles").get(0).asText())
                .isEqualTo("industrie");
        assertThat(json.get("valeurs").get("paysCibles").get(0).asText()).isEqualTo("FR");
    }

    /** Un document deja en base ecrit de travers donne les defauts, jamais une erreur 500. */
    @Test
    void unDocumentMalformeRendLesDefauts() throws Exception {
        boutique = creeBoutique(Map.of("poids", "ceci n est pas un objet", "seuilChaud", "haut"));

        String corps = mockMvc.perform(get("/api/admin/clients/" + boutique + "/scoring")
                        .with(operateur()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(mapper.readTree(corps).get("valeurs").get("seuilChaud").asInt())
                .isEqualTo(70);
    }

    private UUID creeBoutique(Map<String, Object> scoring) {
        Client client = new Client();
        client.setName("Boutique de test");
        client.setPublicKey("pk-" + UUID.randomUUID());
        client.setHmacSecret("secret-de-test");
        client.setCrmProviderId("dolibarr");
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client.setScoringConfig(new java.util.HashMap<>(scoring));
        return clients.save(client).getId();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor operateur() {
        return org.springframework.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .httpBasic("operateur", "leadflow-demo-2026");
    }
}
```

> **Note pour l'implementeur :** le mecanisme d'authentification des tests
> (`operateur()`) doit etre copie **verbatim** depuis `ClientAdminReadTest`, qui tourne deja
> vert dans ce projet. Si ce fichier obtient son jeton autrement (appel a `/api/auth/login`
> puis en-tete `Authorization: Bearer`), reprendre exactement sa methode et supprimer le
> `RequestPostProcessor` ci-dessus. De meme pour la construction d'un `Client` : reprendre le
> helper de `ClientAdminReadTest` plutot que d'inventer les setters.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ScoringAdminTest`
Expected: FAIL — `404` sur les deux routes, qui n'existent pas encore.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/leadflow/tenant/ScoringAdminService.java`

```java
package com.leadflow.tenant;

import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.qualification.ScoringConfig;
import com.leadflow.tenant.dto.ScoringForm;
import com.leadflow.tenant.dto.ScoringView;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reglage du bareme d'une boutique.
 *
 * <p>Ce service importe {@code ScoringConfig} de {@code qualification/}, alors que
 * {@code qualification/} importe deja {@code tenant/} : le cycle entre les deux packages est
 * assume. L'alternative — redeclarer les noms de cles ici — garantissait une derive le jour
 * ou une cle change. C'est {@code ScoringAllerRetourTest} qui tient la garantie.
 */
@Service
public class ScoringAdminService {

    private final ClientRepository clients;

    public ScoringAdminService(ClientRepository clients) {
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public ScoringView lit(UUID id) {
        return ScoringView.de(ScoringConfig.depuis(trouve(id).getScoringConfig()));
    }

    /**
     * Remplace le document entier. Pas de fusion partielle : elle rendrait indecidable la
     * difference entre « poids absent » et « poids remis a zero ».
     */
    @Transactional
    public ScoringView remplace(UUID id, ScoringForm formulaire) {
        Client client = trouve(id);
        client.setScoringConfig(formulaire.versDocument());
        return ScoringView.de(ScoringConfig.depuis(client.getScoringConfig()));
    }

    private Client trouve(UUID id) {
        return clients.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException(
                        "Aucune boutique avec cet identifiant"));
    }
}
```

`backend/src/main/java/com/leadflow/tenant/ScoringAdminController.java`

```java
package com.leadflow.tenant;

import com.leadflow.tenant.dto.ScoringForm;
import com.leadflow.tenant.dto.ScoringView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le bareme a son propre controleur plutot qu'une methode de plus sur
 * {@code ClientAdminController} : c'est un sous-etat de la boutique, avec sa validation et sa
 * vue calculee, et {@code ClientForm} ne doit pas se mettre a porter dix champs de scoring.
 */
@RestController
@RequestMapping("/api/admin/clients/{id}/scoring")
public class ScoringAdminController {

    private final ScoringAdminService service;

    public ScoringAdminController(ScoringAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ScoringView lit(@PathVariable UUID id) {
        return service.lit(id);
    }

    @PutMapping
    public ScoringView remplace(@PathVariable UUID id, @Valid @RequestBody ScoringForm form) {
        return service.remplace(id, form);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ScoringAdminTest`
Expected: PASS — 3 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/tenant/ScoringAdminService.java \
        backend/src/main/java/com/leadflow/tenant/ScoringAdminController.java \
        backend/src/test/java/com/leadflow/tenant/ScoringAdminTest.java
git commit -m "feat: le bareme d une boutique se lit et s ecrit par l API"
```

---

### Task 3: Les bornes de validation, et la boutique inconnue

**Files:**
- Modify: `backend/src/main/java/com/leadflow/tenant/dto/ScoringForm.java`
- Test: `backend/src/test/java/com/leadflow/tenant/ScoringValidationTest.java`

**Interfaces:**
- Consumes: les routes de la tache 2.
- Produces: `ScoringForm` annote — poids, bonus et seuil dans `[0, 100]`, pays sur deux
  lettres.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/leadflow/tenant/ScoringValidationTest.java`

```java
package com.leadflow.tenant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class ScoringValidationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ClientRepository clients;

    @AfterEach
    void nettoie() {
        clients.deleteAll();
    }

    /** Au-dela de 100, la valeur serait ineffective : LeadScorer borne le total a 100. */
    @Test
    void unPoidsHorsBornesEstRefuse() throws Exception {
        UUID boutique = creeBoutique();
        String formulaire = """
                {"telephonePresent":150,"societePresente":10,"nomPresent":5,"messagePresent":10,
                 "intention":{},"secteursCibles":[],"paysCibles":[],
                 "bonusCible":10,"seuilChaud":70}""";

        mockMvc.perform(put("/api/admin/clients/" + boutique + "/scoring")
                        .with(operateur())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(formulaire))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unSeuilNegatifEstRefuse() throws Exception {
        UUID boutique = creeBoutique();
        String formulaire = """
                {"telephonePresent":15,"societePresente":10,"nomPresent":5,"messagePresent":10,
                 "intention":{},"secteursCibles":[],"paysCibles":[],
                 "bonusCible":10,"seuilChaud":-1}""";

        mockMvc.perform(put("/api/admin/clients/" + boutique + "/scoring")
                        .with(operateur())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(formulaire))
                .andExpect(status().isBadRequest());
    }

    /**
     * Un seuil au-dessus du maximum atteignable n'est pas une erreur : c'est un etat
     * legitime, le temps de monter les poids. Il est accepte, et signale.
     */
    @Test
    void unSeuilInatteignableEstAccepteEtSignale() throws Exception {
        UUID boutique = creeBoutique();
        String formulaire = """
                {"telephonePresent":1,"societePresente":1,"nomPresent":1,"messagePresent":1,
                 "intention":{"DEVIS":5,"ACHAT":5,"INFORMATION":0,"SUPPORT":0,"AUTRE":0},
                 "secteursCibles":[],"paysCibles":[],"bonusCible":0,"seuilChaud":80}""";

        mockMvc.perform(put("/api/admin/clients/" + boutique + "/scoring")
                        .with(operateur())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(formulaire))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seuilInatteignable").value(true))
                .andExpect(jsonPath("$.scoreMaximum").value(9));
    }

    @Test
    void uneBoutiqueInconnueRendUn404() throws Exception {
        mockMvc.perform(get("/api/admin/clients/" + UUID.randomUUID() + "/scoring")
                        .with(operateur()))
                .andExpect(status().isNotFound());
    }

    private UUID creeBoutique() {
        // Reprendre le helper de ScoringAdminTest (tache 2).
        Client client = new Client();
        client.setName("Boutique de test");
        client.setPublicKey("pk-" + UUID.randomUUID());
        client.setHmacSecret("secret-de-test");
        client.setCrmProviderId("dolibarr");
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        return clients.save(client).getId();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor operateur() {
        return org.springframework.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .httpBasic("operateur", "leadflow-demo-2026");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ScoringValidationTest`
Expected: FAIL — `unPoidsHorsBornesEstRefuse` et `unSeuilNegatifEstRefuse` rendent `200`
faute de contraintes. Les deux autres passent deja.

- [ ] **Step 3: Write minimal implementation**

Dans `ScoringForm`, annoter les composants (les autres parties du record restent
inchangees) :

```java
public record ScoringForm(
        @Min(0) @Max(100) int telephonePresent,
        @Min(0) @Max(100) int societePresente,
        @Min(0) @Max(100) int nomPresent,
        @Min(0) @Max(100) int messagePresent,
        @NotNull Map<LeadIntent, @Min(0) @Max(100) Integer> intention,
        @NotNull Set<@Size(max = 80) String> secteursCibles,
        @NotNull Set<@Pattern(regexp = "(?i)[a-z]{2}") String> paysCibles,
        @Min(0) @Max(100) int bonusCible,
        @Min(0) @Max(100) int seuilChaud) {
```

Imports a ajouter : `jakarta.validation.constraints.Max`, `Min`, `NotNull`, `Pattern`,
`Size`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ScoringValidationTest,ScoringAdminTest,ScoringAllerRetourTest`
Expected: PASS — 11 tests au total.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/tenant/dto/ScoringForm.java \
        backend/src/test/java/com/leadflow/tenant/ScoringValidationTest.java
git commit -m "feat: les bornes du bareme viennent du bornage du score"
```

---

### Task 4: Le badge et le filtre « chaud » cote lecture

**Files:**
- Modify: `backend/src/main/java/com/leadflow/monitoring/dto/LeadSummary.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/dto/LeadDetail.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadFilter.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadSpecifications.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadQueryService.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadDetailService.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadQueryController.java:38-59`
- Test: `backend/src/test/java/com/leadflow/monitoring/LeadChaudTest.java`

**Interfaces:**
- Consumes: `ScoringConfig.depuis(Map)` pour extraire `seuilChaud`.
- Produces: `LeadSummary.chaud() : boolean`, `LeadDetail.chaud() : boolean`,
  `LeadFilter.chaud() : Boolean` avec `avecChaud(Boolean)`,
  `LeadSpecifications.depuis(LeadFilter, Map<UUID, Integer>)`, et le parametre de requete
  `?chaud=true` sur `GET /api/leads`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/leadflow/monitoring/LeadChaudTest.java`

```java
package com.leadflow.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Le cas qui casse une implementation naive : deux boutiques, deux seuils, un meme score.
 *
 * <p>Boutique A a un seuil de 50, boutique B de 90. Un lead a 60 est chaud chez A et tiede
 * chez B. Une implementation qui appliquerait un seuil global — ou celui de la premiere
 * boutique rencontree — passerait tous les autres tests et echouerait sur celui-ci.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LeadChaudTest {

    @Autowired private MockMvc mockMvc;

    // Le jeu de donnees se monte avec les repositories, comme dans LeadQueryControllerTest :
    // deux Client (seuils 50 et 90 dans scoring_config), un Lead a 60 dans chacun.

    @Test
    void leMemeScoreEstChaudChezLUneEtTiedeChezLAutre() throws Exception {
        mockMvc.perform(get("/api/leads").param("clientId", boutiqueA.toString()).with(operateur()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].chaud").value(true));

        mockMvc.perform(get("/api/leads").param("clientId", boutiqueB.toString()).with(operateur()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].chaud").value(false));
    }

    /** Sans clientId, le filtre doit encore distinguer les deux boutiques. */
    @Test
    void leFiltreChaudTraverseLesBoutiques() throws Exception {
        mockMvc.perform(get("/api/leads").param("chaud", "true").with(operateur()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].clientId").value(boutiqueA.toString()));
    }

    @Test
    void laFicheDUnLeadPorteLeMemeDrapeau() throws Exception {
        mockMvc.perform(get("/api/leads/" + leadDeA).with(operateur()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chaud").value(true));
    }
}
```

> **Note pour l'implementeur :** completer ce squelette en copiant la mise en place de
> `LeadQueryControllerTest` (annotations de securite, creation des `Client` et `Lead` par les
> repositories, nettoyage). Les trois assertions ci-dessus sont le contrat ; le montage du jeu
> de donnees suit le fichier voisin.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=LeadChaudTest`
Expected: FAIL — le champ `chaud` n'existe dans aucun corps JSON.

- [ ] **Step 3: Write minimal implementation**

`LeadSummary` et `LeadDetail` : ajouter `boolean chaud` en dernier composant.

`LeadFilter` : ajouter `Boolean chaud` en dernier composant, propager dans `vide()` et dans
chaque fabrique `avec...`, et ajouter :

```java
    public LeadFilter avecChaud(Boolean valeur) {
        return new LeadFilter(clientId, statuts, intent, intentSource, salesRepId, minScore,
                from, to, recherche, valeur);
    }
```

`LeadSpecifications` : la methode prend desormais la carte des seuils.

```java
    static Specification<Lead> depuis(LeadFilter filtre, Map<UUID, Integer> seuils) {
        return (racine, requete, constructeur) -> {
            List<Predicate> predicats = new ArrayList<>();
            // ... les huit predicats existants, inchanges ...

            // Chaque boutique a son seuil : le predicat est une disjonction de couples
            // (boutique, seuil). Une clause SQL sur scoring_config n'est pas possible ici —
            // Lead ne porte pas d'association vers Client, et le document passe par un
            // JdbcTypeCode JSON.
            if (Boolean.TRUE.equals(filtre.chaud())) {
                if (seuils.isEmpty()) {
                    return constructeur.disjunction();
                }
                List<Predicate> parBoutique = seuils.entrySet().stream()
                        .map(entree -> constructeur.and(
                                constructeur.equal(racine.get("clientId"), entree.getKey()),
                                constructeur.greaterThanOrEqualTo(
                                        racine.get("score"), entree.getValue())))
                        .toList();
                predicats.add(constructeur.or(parBoutique.toArray(Predicate[]::new)));
            }
            return constructeur.and(predicats.toArray(Predicate[]::new));
        };
    }
```

`LeadQueryService` : charger les seuils avant la requete quand le filtre l'exige, et poser le
drapeau apres.

```java
    @Transactional(readOnly = true)
    public PageResponse<LeadSummary> cherche(LeadFilter filtre, Pageable pagination) {
        Map<UUID, Integer> seuilsDuFiltre = Boolean.TRUE.equals(filtre.chaud())
                ? seuils(filtre.clientId())
                : Map.of();
        Page<Lead> page = leads.findAll(
                LeadSpecifications.depuis(filtre, seuilsDuFiltre), pagination);

        Map<UUID, Client> boutiques = boutiques(page.getContent());
        // ... nomsDeCommercial inchange ...

        List<LeadSummary> lignes = page.getContent().stream()
                .map(lead -> new LeadSummary(
                        // ... les quatorze composants existants ...
                        estChaud(lead, boutiques)))
                .toList();
        return PageResponse.de(page, lignes);
    }

    /**
     * Une requete pour les boutiques de la page, comme la resolution des noms : le seuil en
     * sort avec le nom, sans requete supplementaire.
     */
    private Map<UUID, Client> boutiques(List<Lead> page) {
        Set<UUID> identifiants = page.stream()
                .map(Lead::getClientId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (identifiants.isEmpty()) {
            return Map.of();
        }
        return clients.findAllById(identifiants).stream()
                .collect(Collectors.toMap(Client::getId, client -> client));
    }

    /** Seuils de toutes les boutiques, ou d'une seule quand le filtre en designe une. */
    private Map<UUID, Integer> seuils(UUID clientId) {
        List<Client> boutiques = clientId == null
                ? clients.findAll()
                : clients.findById(clientId).stream().toList();
        return boutiques.stream()
                .collect(Collectors.toMap(
                        Client::getId,
                        client -> ScoringConfig.depuis(client.getScoringConfig()).seuilChaud()));
    }

    private boolean estChaud(Lead lead, Map<UUID, Client> boutiques) {
        Client boutique = boutiques.get(lead.getClientId());
        if (boutique == null) {
            return false;
        }
        return lead.getScore()
                >= ScoringConfig.depuis(boutique.getScoringConfig()).seuilChaud();
    }
```

`LeadDetailService` : la boutique est deja chargee pour son nom
(`clients.findById(lead.getClientId())`) — garder l'entite au lieu de n'en tirer que le nom,
et passer `lead.getScore() >= ScoringConfig.depuis(client.getScoringConfig()).seuilChaud()`
en dernier argument de `new LeadDetail(...)`.

`LeadQueryController` : un parametre de plus et sa propagation.

```java
            @RequestParam(required = false) Boolean chaud,
```
puis `.avecChaud(chaud)` dans la construction du `LeadFilter`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=LeadChaudTest,LeadQueryControllerTest,LeadQueryServiceTest,LeadDetailServiceTest`
Expected: PASS. Les trois tests existants doivent rester verts — s'ils cassent, c'est le
composant `chaud` ajoute aux DTO : completer leurs constructeurs.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring backend/src/test/java/com/leadflow/monitoring/LeadChaudTest.java
git commit -m "feat: un lead est chaud selon le seuil de sa propre boutique"
```

---

### Task 5: L'ecran de reglage du bareme

**Files:**
- Modify: `frontend/src/app/core/models/tenant.ts`
- Modify: `frontend/src/app/core/api/tenant-api.ts`
- Create: `frontend/src/app/features/boutiques/bareme/bareme.ts`
- Create: `frontend/src/app/features/boutiques/bareme/bareme.html`
- Create: `frontend/src/app/features/boutiques/bareme/bareme.scss`
- Create: `frontend/src/app/features/boutiques/bareme/bareme.spec.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/features/boutiques/boutique-detail/boutique-detail.html`

**Interfaces:**
- Consumes: `GET`/`PUT /api/admin/clients/{id}/scoring` (taches 2 et 3).
- Produces: la route `boutiques/:id/bareme` et `TenantApi.bareme(id)` /
  `TenantApi.enregistreBareme(id, form)`.

- [ ] **Step 1: Invoquer le plugin visuel**

**Avant toute ligne de template**, invoquer `ui-ux-pro-max:ui-styling` pour la mise en page du
formulaire (quatre blocs, saisie de tags, avertissement) et `ui-ux-pro-max:design-system` pour
reutiliser les jetons deja en place dans `boutique-detail.scss`. Aucune decision visuelle ne
s'improvise ici.

- [ ] **Step 2: Write the failing test**

`frontend/src/app/features/boutiques/bareme/bareme.spec.ts`

```typescript
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Bareme } from './bareme';

const VUE = {
  valeurs: {
    telephonePresent: 15,
    societePresente: 10,
    nomPresent: 5,
    messagePresent: 10,
    intention: { DEVIS: 40, ACHAT: 40, INFORMATION: 15, SUPPORT: 5, AUTRE: 0 },
    secteursCibles: [],
    paysCibles: [],
    bonusCible: 10,
    seuilChaud: 70,
  },
  scoreMaximum: 90,
  seuilInatteignable: false,
  defauts: {
    telephonePresent: 15,
    societePresente: 10,
    nomPresent: 5,
    messagePresent: 10,
    intention: { DEVIS: 40, ACHAT: 40, INFORMATION: 15, SUPPORT: 5, AUTRE: 0 },
    secteursCibles: [],
    paysCibles: [],
    bonusCible: 10,
    seuilChaud: 70,
  },
};

describe('Bareme', () => {
  let fixture: ComponentFixture<Bareme>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Bareme],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(Bareme);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('affiche les valeurs effectives rendues par le serveur', () => {
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url.includes('/scoring')).flush(VUE);
    fixture.detectChanges();

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('90');
  });

  it('avertit quand le seuil depasse le maximum atteignable', () => {
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url.includes('/scoring')).flush(VUE);
    fixture.detectChanges();

    fixture.componentInstance.formulaire.controls.seuilChaud.setValue(95);
    fixture.detectChanges();

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('Aucun lead ne pourra etre chaud');
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — `Bareme` n'existe pas.

- [ ] **Step 4: Implementer le modele et l'API**

Dans `core/models/tenant.ts` :

```typescript
export interface ScoringForm {
  telephonePresent: number;
  societePresente: number;
  nomPresent: number;
  messagePresent: number;
  intention: Record<string, number>;
  secteursCibles: string[];
  paysCibles: string[];
  bonusCible: number;
  seuilChaud: number;
}

export interface ScoringView {
  valeurs: ScoringForm;
  scoreMaximum: number;
  seuilInatteignable: boolean;
  defauts: ScoringForm;
}
```

Dans `core/api/tenant-api.ts` :

```typescript
  bareme(id: string) {
    return this.http.get<ScoringView>(`/api/admin/clients/${id}/scoring`);
  }

  enregistreBareme(id: string, formulaire: ScoringForm) {
    return this.http.put<ScoringView>(`/api/admin/clients/${id}/scoring`, formulaire);
  }
```

- [ ] **Step 5: Implementer le composant**

`bareme.ts` — composant standalone, `ReactiveFormsModule`, signals. Il porte :

- `formulaire` : un `FormGroup` typé avec les quatre poids, un sous-groupe `intention` a cinq
  controles (`DEVIS`, `ACHAT`, `INFORMATION`, `SUPPORT`, `AUTRE`), `bonusCible`, `seuilChaud`,
  plus deux tableaux de tags tenus en signals (`secteurs`, `pays`) ;
- `scoreMaximum` : un `computed()` qui refait la somme **bornee a 100** — quatre poids +
  meilleur poids d'intention + bonus. C'est une addition, pas une reimplementation du bareme :
  ni normalisation, ni logique de ciblage ;
- `seuilInatteignable` : `computed(() => seuilChaud > scoreMaximum())` ;
- `enregistre()` : `PUT`, puis reprend les valeurs rendues par le serveur (qui sont
  normalisees) ;
- `revientAuxDefauts()` : remplit le formulaire avec `vue().defauts`, sans appel reseau.

`bareme.html` — quatre blocs (poids de presence, poids par intention, ciblage, seuil), le
maximum affiche a cote du seuil, l'avertissement `Aucun lead ne pourra etre chaud` quand
`seuilInatteignable()`, et les rappels : le bonus ne se cumule pas, la correspondance de
secteur est exacte, les pays sont des codes a deux lettres.

- [ ] **Step 6: Cabler la route et le bouton**

Dans `app.routes.ts`, **avant** l'entree `boutiques/:id` :

```typescript
  {
    path: 'boutiques/:id/bareme',
    title: 'Bareme de scoring',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/boutiques/bareme/bareme').then((m) => m.Bareme),
  },
```

Dans `boutique-detail.html`, un lien vers `['/boutiques', fiche.id, 'bareme']`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS. Puis `npm run build` — verifier que `bareme` apparait comme **chunk separe**
dans la sortie.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/core frontend/src/app/features/boutiques frontend/src/app/app.routes.ts
git commit -m "feat: le bareme d une boutique se regle depuis l ecran"
```

---

### Task 6: La pastille et le filtre dans la liste des leads

**Files:**
- Modify: `frontend/src/app/core/models/lead.ts`
- Modify: `frontend/src/app/core/api/lead-api.ts`
- Modify: `frontend/src/app/features/leads/leads.ts`
- Modify: `frontend/src/app/features/leads/leads.html`
- Modify: `frontend/src/app/features/leads/lead-detail/lead-detail.html`
- Test: `frontend/src/app/core/api/lead-api.spec.ts`

**Interfaces:**
- Consumes: `chaud` sur `LeadSummary` / `LeadDetail` et `?chaud=true` (tache 4).
- Produces: la case « chauds seulement » et la pastille.

- [ ] **Step 1: Invoquer le plugin visuel pour la pastille**

`ui-ux-pro-max:ui-styling` — forme, couleur et contraste de la pastille en clair et en sombre,
avant d'ecrire le template.

- [ ] **Step 2: Write the failing test**

Dans `lead-api.spec.ts`, ajouter :

```typescript
  it("n'envoie le parametre chaud que lorsqu'il est demande", () => {
    api.liste({ page: 0, size: 20, sort: 'createdAt,desc', chaud: true }).subscribe();
    const requete = httpMock.expectOne((r) => r.url === '/api/leads');
    expect(requete.request.params.get('chaud')).toBe('true');

    api.liste({ page: 0, size: 20, sort: 'createdAt,desc' }).subscribe();
    const sansFiltre = httpMock.expectOne((r) => r.url === '/api/leads');
    expect(sansFiltre.request.params.has('chaud')).toBeFalse();
  });
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — `chaud` n'est pas un champ de `LeadQuery`.

- [ ] **Step 4: Implementer**

Dans `core/models/lead.ts`, ajouter `chaud: boolean;` a `LeadSummary` et a `LeadDetail`.

Dans `lead-api.ts`, ajouter `chaud?: true;` a `LeadQuery`. **Le type est `true`, pas
`boolean`** : la boucle de construction des parametres ne saute que `undefined`, `null` et la
chaine vide — un `false` partirait dans l'URL et ajouterait un predicat cote serveur. La case
decochee doit donc valoir `undefined`.

Dans `leads.ts` / `leads.html` : une case « chauds seulement » dans la barre de filtres, qui
pose `chaud: true` ou `undefined`, et une pastille rendue par `@if (lead.chaud)` dans la
ligne. Meme pastille sur `lead-detail.html`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/core frontend/src/app/features/leads
git commit -m "feat: la pastille chaud et son filtre dans la liste des leads"
```

---

### Task 7: La documentation, et la verification de bout en bout

**Files:**
- Modify: `CLAUDE.md`
- Create: `docs/superpowers/plans/2026-08-29-f8-etat-de-fin-de-session.md`

- [ ] **Step 1: Verifier la suite complete**

```bash
cd backend && ./mvnw test
cd ../frontend && npm test -- --watch=false --browsers=ChromeHeadless && npm run build
```

Si l'environnement tue l'execution (elle dure environ 18 minutes), la decouper par package
avec `-Dtest=...` et purger les conteneurs orphelins :
`docker rm -f $(docker ps -q --filter "label=org.testcontainers=true")`.

- [ ] **Step 2: Verifier a l'ecran**

Lancer `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` et `npm start`, puis : regler le
bareme de la boutique de demonstration, descendre le seuil, recharger la liste des leads et
**voir les pastilles se deplacer sans qu'aucun score n'ait change**. C'est la demonstration de
la feature.

- [ ] **Step 3: Mettre a jour `CLAUDE.md`**

Dans la section « Qualification » : le bareme se regle depuis l'ecran, `seuilChaud` a
desormais un consommateur — le badge — et le score reste fige. Dans « Monitoring » : le badge
est calcule a la lecture a partir du seuil de la boutique du lead. Dans « Etat actuel » :
dixieme ecran, et retirer la ligne disant que `scoring_config` ne se regle qu'en base.

- [ ] **Step 4: Consigner l'etat de la session**

`docs/superpowers/plans/2026-08-29-f8-etat-de-fin-de-session.md` : ce qui a ete fait, ce que la
recette a revele, et ce qui reste ouvert pour F11.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md docs/superpowers/plans/
git commit -m "docs: consigner F8 et ce que la recette a revele"
```

- [ ] **Step 6: Fusionner**

Invoquer `superpowers:finishing-a-development-branch`. La branche `feature/f8-bareme-reglable`
est **conservee** apres la fusion : elle sert d'historique de la feature.

---

## Self-review

**Couverture du spec.** Les deux routes → taches 2 et 3. `ScoringView` et ses trois valeurs
calculees → tache 1. Le cycle de packages et son test → tache 1. Badge, filtre et les trois
DTO → tache 4. L'ecran, ses quatre blocs et le maximum recalcule en direct → tache 5. La
pastille et le filtre → tache 6. Les quatre tests nommes dans le spec existent tous :
`ScoringAllerRetourTest`, `ScoringAdminTest`, `ScoringValidationTest`, `LeadChaudTest`.

**Coherence des types.** `ScoringForm` et `ScoringView` portent les memes noms de composants
cote Java et cote TypeScript. `LeadFilter.avecChaud` prend un `Boolean` (nullable, cote
serveur), `LeadQuery.chaud` est un `true` optionnel (cote client) — asymetrie voulue et
justifiee dans la tache 6.

**Ce qui reste a la charge de l'implementeur**, faute d'etre lisible depuis ce plan : le
mecanisme d'authentification exact des tests MockMvc et le montage des jeux de donnees, a
copier depuis `ClientAdminReadTest` et `LeadQueryControllerTest`. Les assertions, elles, sont
donnees en entier.
