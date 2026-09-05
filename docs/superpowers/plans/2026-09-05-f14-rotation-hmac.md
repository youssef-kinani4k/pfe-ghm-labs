# F14 — Rotation HMAC à fenêtre de transition — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Faire cohabiter l'ancien et le nouveau secret HMAC d'une boutique pendant une fenêtre bornée après une rotation, pour qu'aucun lead ne soit refusé le temps que la boutique redéploie son site.

**Architecture:** `HmacSignatureVerifier` accepte une liste ordonnée de secrets et dit lequel a répondu ; `LeadCaptureService`, seul à connaître la notion de fenêtre, construit cette liste depuis deux nouvelles colonnes de `client` et marque la ligne `raw_lead_event` quand c'est l'ancien secret qui a signé ; `ClientAdminService` pose la fenêtre à la rotation et la ferme à la révocation ; la fiche de la boutique expose l'état de transition et l'écran l'affiche.

**Tech Stack:** Java 21, Spring Boot (Spring MVC, Spring Data JPA, Spring AMQP), Flyway, PostgreSQL, Testcontainers, JUnit 5 + AssertJ + MockMvc côté backend. Angular 20 standalone (signals, `@if`/`@for`), Angular Material, Karma + Jasmine côté frontend.

**Spec:** `docs/superpowers/specs/2026-09-05-f14-rotation-hmac-design.md` — à lire en entier avant la tâche 1. Le plan argumente depuis cette spec ; en cas de contradiction entre les deux, la spec fait foi et il faut le signaler plutôt que trancher seul.

## Global Constraints

- **Français sans accents dans le code et dans `CLAUDE.md`** (identifiants, commentaires, Javadoc, libellés de templates Angular). Les fichiers `docs/*.md`, eux, s'écrivent **avec** accents. Cette distinction a déjà causé une correction en F13.
- **Les cinq causes de refus du webhook rendent le même `401`**, sans message distinctif. La fenêtre n'en ajoute pas une sixième : un secret expiré est indiscernable d'une signature fausse.
- **`ddl-auto: validate`** — Hibernate ne crée jamais de table. Toute évolution de schéma est une migration Flyway numérotée. Modifier une migration déjà appliquée fait échouer le démarrage (checksum) ; en dev, `docker compose down -v` remet à zéro.
- **Aucune entité JPA ne franchit la frontière HTTP.** Toute réponse passe par un `record` de `tenant/dto/`, et les tests d'API assertent sur le **corps JSON**, jamais sur le DTO.
- **Le daemon Docker doit tourner** pour `./mvnw test` : `BackendApplicationTests` importe `TestcontainersConfiguration`. Sans lui, l'échec `Could not find a valid Docker environment` est un problème d'environnement, pas de code.
- **Le backend écoute sur `:8090`**, pas `:8080`.
- **La copie de travail est en LF**, y compris sur Windows (`.gitattributes`). Ne pas réintroduire de CRLF : Prettier compare avec `endOfLine: lf`.
- **Le crochet `pre-push`** joue ESLint, Prettier et les tests frontend avant tout push vers `main`. `git config core.hooksPath .githooks` doit être posé dans la copie de travail.
- **Toute décision visuelle passe par les skills du plugin `ui-ux-pro-max`, invoquées AVANT d'écrire le code**, jamais en relecture (tâche 6).
- **Un test qu'on n'a pas vu échouer ne prouve rien** : chaque étape « vérifier que le test échoue » est obligatoire, et la tâche 3 demande en plus de neutraliser volontairement le code pour voir le test rougir.

## Structure des fichiers

| Fichier | Responsabilité | Tâche |
| --- | --- | --- |
| `backend/src/main/resources/db/migration/V11__hmac_secret_transition.sql` | **Créé** — les trois colonnes | 1 |
| `backend/src/main/java/com/leadflow/tenant/Client.java` | **Modifié** — `previousHmacSecret`, `previousSecretExpiresAt` | 1 |
| `backend/src/main/java/com/leadflow/capture/RawLeadEvent.java` | **Modifié** — `signedWithPreviousSecret` | 1 |
| `backend/src/main/java/com/leadflow/capture/SignatureVerifiee.java` | **Créé** — le record rendu par le vérificateur | 2 |
| `backend/src/main/java/com/leadflow/capture/HmacSignatureVerifier.java` | **Modifié** — accepte une liste de secrets | 2 |
| `backend/src/main/java/com/leadflow/config/WebhookProperties.java` | **Modifié** — `transitionSecret` | 3 |
| `backend/src/main/java/com/leadflow/capture/LeadCaptureService.java` | **Modifié** — construit la liste, pose le drapeau | 2 puis 3 |
| `backend/src/main/java/com/leadflow/tenant/ClientAdminService.java` | **Modifié** — pose et ferme la fenêtre | 4 |
| `backend/src/main/java/com/leadflow/tenant/ClientAdminController.java` | **Modifié** — route de révocation | 4 |
| `backend/src/main/java/com/leadflow/tenant/dto/SecretRotated.java` | **Modifié** — la date d'expiration | 4 |
| `backend/src/main/java/com/leadflow/capture/UsageAncienSecret.java` | **Créé** — port de lecture exposé par `capture/` | 5 |
| `backend/src/main/java/com/leadflow/tenant/dto/TransitionSecret.java` | **Créé** — l'état de transition rendu par la fiche | 5 |
| `frontend/src/app/core/models/tenant.ts` | **Modifié** — `TransitionSecret`, `SecretRotated` | 6 |
| `frontend/src/app/core/api/tenant-api.ts` | **Modifié** — appel de révocation | 6 |
| `frontend/src/app/features/boutiques/transition-secret/` | **Créé** — le bandeau, composant présentationnel | 6 |
| `frontend/src/app/features/boutiques/boutique-detail/` | **Modifié** — dialogue, bandeau, révocation | 6 |

---

### Task 1: Le schéma et les entités

**Files:**

- Create: `backend/src/main/resources/db/migration/V11__hmac_secret_transition.sql`
- Modify: `backend/src/main/java/com/leadflow/tenant/Client.java`
- Modify: `backend/src/main/java/com/leadflow/capture/RawLeadEvent.java`
- Test: `backend/src/test/java/com/leadflow/tenant/TransitionSecretPersistenceTest.java` (créé)

**Interfaces:**

- Produces: `Client.getPreviousHmacSecret()` / `setPreviousHmacSecret(String)`, `Client.getPreviousSecretExpiresAt()` / `setPreviousSecretExpiresAt(Instant)`, `RawLeadEvent.isSignedWithPreviousSecret()` / `setSignedWithPreviousSecret(boolean)`. `Client` utilise Lombok `@Getter @Setter` au niveau de la classe — les accesseurs ne s'écrivent pas à la main, il suffit de déclarer les champs.

- [ ] **Step 1: Écrire la migration**

Créer `backend/src/main/resources/db/migration/V11__hmac_secret_transition.sql` :

```sql
-- F14 : une rotation de secret ne doit plus couper la capture. L'ancien secret reste
-- accepte pendant une fenetre bornee, le temps que la boutique mette son site a jour.
--
-- Nullables et sans remplissage retroactif, comme V7 : aucune boutique existante n'est en
-- transition, et l'absence de valeur est exactement le fait a representer. Les deux vont
-- toujours ensemble — un secret precedent sans date d'expiration serait un secret
-- permanent, soit l'inverse de la feature.
ALTER TABLE client
    -- Chiffre AES-256-GCM par l'application, comme hmac_secret. Jamais lisible en SQL.
    ADD COLUMN previous_hmac_secret       TEXT,
    ADD COLUMN previous_secret_expires_at TIMESTAMPTZ;

-- Pose a l'insertion de la ligne, qui a lieu de toute facon : le chemin chaud ne paie
-- aucune ecriture de plus. C'est ce qui permet a l'ecran de repondre « plus aucun lead
-- signe avec l'ancien secret », donc de dire quand la revocation est sans risque.
ALTER TABLE raw_lead_event
    ADD COLUMN signed_with_previous_secret BOOLEAN NOT NULL DEFAULT false;

-- Aucun index ajoute : la seule requete de lecture filtre sur client_id et trie par
-- received_at, ce que idx_raw_lead_event_client_received de V2 sert deja.
```

- [ ] **Step 2: Écrire le test de persistance, qui doit échouer**

Créer `backend/src/test/java/com/leadflow/tenant/TransitionSecretPersistenceTest.java`. Ce test est un `@SpringBootTest` et **non** un `@DataJpaTest` : les `AttributeConverter` de chiffrement sont des `@Component`, absents de la tranche `@DataJpaTest`.

```java
package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Les deux colonnes de transition existent, se relisent, et le secret precedent est chiffre
 * au repos comme le courant : le lire en SQL brut ne doit pas rendre le clair.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransitionSecretPersistenceTest {

    private static final String ANCIEN = "ancien-secret-tres-reconnaissable";

    @Autowired private ClientRepository clients;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void nettoie() {
        clients.deleteAll();
    }

    @Test
    void relitLaFenetreDeTransitionEtChiffreLAncienSecret() {
        Instant expiration = Instant.now().plus(24, ChronoUnit.HOURS);
        Client boutique = new Client();
        boutique.setName("Boutique en transition");
        boutique.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        boutique.setHmacSecret("secret-courant");
        boutique.setPreviousHmacSecret(ANCIEN);
        boutique.setPreviousSecretExpiresAt(expiration);
        boutique.setCrmProviderId("dolibarr");
        boutique.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-api"));
        boutique.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        boutique.setActive(true);
        UUID id = clients.save(boutique).getId();

        Client relu = clients.findById(id).orElseThrow();
        assertThat(relu.getPreviousHmacSecret()).isEqualTo(ANCIEN);
        assertThat(relu.getPreviousSecretExpiresAt())
                .isCloseTo(expiration, within(1, ChronoUnit.SECONDS));

        String enBase = jdbc.queryForObject(
                "SELECT previous_hmac_secret FROM client WHERE id = ?", String.class, id);
        assertThat(enBase).isNotNull().doesNotContain(ANCIEN);
    }

    @Test
    void uneBoutiqueSansTransitionPorteDeuxColonnesNulles() {
        Client boutique = new Client();
        boutique.setName("Boutique ordinaire");
        boutique.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        boutique.setHmacSecret("secret-courant");
        boutique.setCrmProviderId("dolibarr");
        boutique.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-api"));
        boutique.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        boutique.setActive(true);

        Client relu = clients.findById(clients.save(boutique).getId()).orElseThrow();

        assertThat(relu.getPreviousHmacSecret()).isNull();
        assertThat(relu.getPreviousSecretExpiresAt()).isNull();
    }
}
```

Ajouter l'import statique `import static org.assertj.core.api.Assertions.within;` en tête du fichier — `isCloseTo` sur un `Instant` l'exige.

- [ ] **Step 3: Lancer le test, vérifier qu'il échoue**

```bash
cd backend && ./mvnw test -Dtest=TransitionSecretPersistenceTest
```

Attendu : ÉCHEC à la compilation, `cannot find symbol: method setPreviousHmacSecret(String)`.

- [ ] **Step 4: Ajouter les champs aux deux entités**

Dans `Client.java`, juste après le champ `hmacSecret` :

```java
    /**
     * Secret precedent, encore accepte jusqu'a {@code previousSecretExpiresAt}. Nul hors
     * transition. Chiffre au repos par le meme converter que {@code hmacSecret} : les deux
     * sont la meme chose a un instant different.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "previous_hmac_secret", columnDefinition = "text")
    private String previousHmacSecret;

    /**
     * Fin de la fenetre de transition. Toujours posee et effacee en meme temps que
     * {@code previousHmacSecret} : un secret precedent sans expiration serait un second
     * secret permanent, donc le double de surface d'attaque pour la meme porte.
     */
    @Column(name = "previous_secret_expires_at")
    private Instant previousSecretExpiresAt;
```

Reprendre exactement la forme de l'annotation `@Convert` déjà posée sur `hmacSecret` dans ce fichier — nom du converter et style d'import compris.

Dans `RawLeadEvent.java`, après le champ `signature` :

```java
    /**
     * Vrai quand c'est le secret precedent de la boutique qui a valide cette soumission.
     * Pose a l'insertion, donc sans ecriture supplementaire sur le chemin chaud. C'est ce
     * qui permet de dire a l'operateur si la boutique a fini de migrer.
     */
    @Column(name = "signed_with_previous_secret", nullable = false)
    private boolean signedWithPreviousSecret;
```

- [ ] **Step 5: Lancer le test, vérifier qu'il passe**

```bash
cd backend && ./mvnw test -Dtest=TransitionSecretPersistenceTest
```

Attendu : SUCCÈS, 2 tests. Si Flyway se plaint d'un checksum, c'est qu'une migration antérieure a été modifiée — la réparation est `docker compose down -v`, jamais l'édition de `V11`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V11__hmac_secret_transition.sql \
        backend/src/main/java/com/leadflow/tenant/Client.java \
        backend/src/main/java/com/leadflow/capture/RawLeadEvent.java \
        backend/src/test/java/com/leadflow/tenant/TransitionSecretPersistenceTest.java
git commit -m "feat(f14): V11 ouvre la place d une fenetre de transition du secret"
```

---

### Task 2: Le vérificateur accepte une liste de secrets

**Files:**

- Create: `backend/src/main/java/com/leadflow/capture/SignatureVerifiee.java`
- Modify: `backend/src/main/java/com/leadflow/capture/HmacSignatureVerifier.java`
- Modify: `backend/src/main/java/com/leadflow/capture/LeadCaptureService.java:66`
- Test: `backend/src/test/java/com/leadflow/capture/HmacSignatureVerifierTest.java`

**Interfaces:**

- Consumes: rien de la tâche 1.
- Produces: `record SignatureVerifiee(String canonique, boolean secretPrecedent)` et `SignatureVerifiee HmacSignatureVerifier.verifie(List<String> secrets, String corpsBrut, String enTeteSignature, Instant maintenant)`. La tâche 3 en dépend.

Cette tâche change le contrat sans encore ouvrir aucune fenêtre : `LeadCaptureService` passera `List.of(client.getHmacSecret())`. Le comportement observable du produit est **identique** à la fin de cette tâche, ce qui est voulu — c'est ce qui rend la tâche revue isolément.

- [ ] **Step 1: Écrire les tests, qui doivent échouer**

Dans `HmacSignatureVerifierTest.java`, adapter les tests existants au nouveau contrat (chaque `verifie(SECRET, ...)` devient `verifie(List.of(SECRET), ...)`, et les assertions sur la valeur rendue portent désormais sur `.canonique()`), puis ajouter ces quatre tests :

```java
    @Test
    void accepteLeSecretPrecedentEtLeDit() {
        String enTete = signe(PRECEDENT, MAINTENANT.getEpochSecond(), CORPS);

        SignatureVerifiee resultat =
                verificateur.verifie(List.of(SECRET, PRECEDENT), CORPS, enTete, MAINTENANT);

        assertThat(resultat.secretPrecedent()).isTrue();
        assertThat(resultat.canonique()).isEqualTo(enTete);
    }

    @Test
    void leSecretCourantNEstPasSignaleCommePrecedent() {
        String enTete = signe(SECRET, MAINTENANT.getEpochSecond(), CORPS);

        SignatureVerifiee resultat =
                verificateur.verifie(List.of(SECRET, PRECEDENT), CORPS, enTete, MAINTENANT);

        assertThat(resultat.secretPrecedent()).isFalse();
    }

    @Test
    void refuseUneSignatureQueAucunSecretNeValide() {
        String enTete = signe("secret-d-un-tiers", MAINTENANT.getEpochSecond(), CORPS);

        assertThatThrownBy(() -> verificateur.verifie(
                        List.of(SECRET, PRECEDENT), CORPS, enTete, MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class)
                .hasMessageContaining("Signature invalide");
    }

    @Test
    void unEnTeteMalformeEstRefuseAvantTouteComparaisonDeSecret() {
        // Deux secrets acceptables, et pourtant un seul refus : l'analyse de l'en-tete et
        // le controle de l'horodatage ne dependent pas du secret et ne sont faits qu'une
        // fois. Le message nomme l'en-tete, jamais une signature qui ne correspond pas.
        assertThatThrownBy(() -> verificateur.verifie(
                        List.of(SECRET, PRECEDENT), CORPS, "v1=abcdef", MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class)
                .hasMessageContaining("malforme");
    }
```

Ajouter la constante, à côté de `SECRET` :

```java
    private static final String PRECEDENT = "secret-de-signature-precedent";
```

et les imports `java.util.List` ainsi que `com.leadflow.common.WebhookAuthenticationException` (déjà présent).

- [ ] **Step 2: Lancer les tests, vérifier qu'ils échouent**

```bash
cd backend && ./mvnw test -Dtest=HmacSignatureVerifierTest
```

Attendu : ÉCHEC à la compilation — `verifie(List<String>, ...)` n'existe pas et `SignatureVerifiee` non plus.

- [ ] **Step 3: Créer le record**

`backend/src/main/java/com/leadflow/capture/SignatureVerifiee.java` :

```java
package com.leadflow.capture;

/**
 * Ce qu'une signature valide apprend a l'appelant.
 *
 * @param canonique la forme canonique {@code t=<epoch>,v1=<hex>}, cle d'idempotence de la
 *     soumission. Reconstruite a partir des valeurs validees, jamais reprise du texte recu.
 * @param secretPrecedent vrai quand c'est le secret precedent de la boutique qui a repondu,
 *     et non le courant. Le verificateur ne sait pas ce que cela veut dire : il rend le rang
 *     du secret qui a correspondu, l'appelant en tire la notion de transition.
 */
public record SignatureVerifiee(String canonique, boolean secretPrecedent) {
}
```

- [ ] **Step 4: Changer le vérificateur**

Dans `HmacSignatureVerifier.java`, remplacer la méthode `verifie` par :

```java
    /**
     * @param secrets les secrets acceptables, <b>le courant en premier</b>. L'ordre est
     *     porteur de sens : le cas normal ne calcule qu'un seul HMAC, et le second n'est
     *     essaye que si le premier ne correspond pas — c'est-a-dire pendant une fenetre de
     *     transition, et sur les tentatives reellement fausses.
     * @return la forme <b>canonique</b> de la signature et le rang du secret qui a repondu.
     *     La forme canonique est reconstruite a partir des valeurs validees et jamais
     *     reprise du texte recu : l'analyse tolere les espaces et les parametres inconnus,
     *     si bien que {@code t=1,v1=ab}, {@code t=1, v1=ab} et {@code t=1,v1=ab,x=9} sont
     *     trois textes valides pour une meme soumission. Les stocker tels quels laisserait
     *     injecter des doublons en repaddant l'en-tete.
     * @throws WebhookAuthenticationException si l'en-tete est absent, malforme, hors
     *     fenetre, ou si <b>aucun</b> des secrets ne correspond. Le message est destine aux
     *     logs : l'appelant rend le meme 401 pour les cinq causes de refus.
     */
    public SignatureVerifiee verifie(
            List<String> secrets, String corpsBrut, String enTeteSignature, Instant maintenant) {
        if (enTeteSignature == null || enTeteSignature.isBlank()) {
            throw new WebhookAuthenticationException("En-tete de signature absent");
        }

        // L'horodatage et la forme de l'en-tete ne dependent d'aucun secret : les controler
        // ici, une seule fois, evite d'essayer deux secrets contre un texte qui n'en
        // contient pas.
        long horodatage = horodatage(enTeteSignature);
        String signatureFournie = valeur(enTeteSignature, "v1");

        Duration ecart = Duration.between(instant(horodatage), maintenant).abs();
        if (ecart.compareTo(properties.tolerance()) > 0) {
            throw new WebhookAuthenticationException(
                    "Horodatage hors fenetre : ecart de " + ecart.toSeconds() + "s");
        }

        String charge = horodatage + "." + corpsBrut;
        for (int rang = 0; rang < secrets.size(); rang++) {
            String attendue = calcule(secrets.get(rang), charge);
            // Comparaison en temps constant : une comparaison de chaines ordinaire s'arrete
            // au premier octet different et laisse deduire la signature attendue octet par
            // octet.
            if (MessageDigest.isEqual(
                    attendue.getBytes(UTF_8), signatureFournie.getBytes(UTF_8))) {
                return new SignatureVerifiee("t=" + horodatage + ",v1=" + attendue, rang > 0);
            }
        }
        throw new WebhookAuthenticationException("Signature invalide");
    }
```

Ajouter l'import `java.util.List`.

- [ ] **Step 5: Adapter l'unique appelant**

Dans `LeadCaptureService.java:66`, remplacer :

```java
        String signature = verificateur.verifie(
                client.getHmacSecret(), corpsBrut, enTeteSignature, Instant.now());
```

par :

```java
        // Un seul secret acceptable pour l'instant : la fenetre de transition arrive en
        // tache 3, et cette tache ne change aucun comportement observable.
        SignatureVerifiee verifiee = verificateur.verifie(
                List.of(client.getHmacSecret()), corpsBrut, enTeteSignature, Instant.now());
        String signature = verifiee.canonique();
```

Ajouter l'import `java.util.List`.

- [ ] **Step 6: Lancer les tests du package capture**

```bash
cd backend && ./mvnw test -Dtest='HmacSignatureVerifierTest,LeadCaptureIntegrationTest,RawLeadEventIdempotenceTest'
```

Attendu : SUCCÈS. `LeadCaptureIntegrationTest` et `RawLeadEventIdempotenceTest` prouvent que le comportement du webhook n'a pas bougé.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/leadflow/capture/ \
        backend/src/test/java/com/leadflow/capture/HmacSignatureVerifierTest.java
git commit -m "refactor(f14): le verificateur accepte une liste de secrets et dit lequel a repondu"
```

---

### Task 3: La capture ouvre la fenêtre

**Files:**

- Modify: `backend/src/main/java/com/leadflow/config/WebhookProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/leadflow/capture/LeadCaptureService.java`
- Test: `backend/src/test/java/com/leadflow/capture/FenetreDeTransitionTest.java` (créé)

**Interfaces:**

- Consumes: `Client.getPreviousHmacSecret()`, `Client.getPreviousSecretExpiresAt()`, `RawLeadEvent.setSignedWithPreviousSecret(boolean)` (tâche 1) ; `SignatureVerifiee` (tâche 2).
- Produces: `WebhookProperties.transitionSecret()` de type `Duration`, consommé par la tâche 4.

- [ ] **Step 1: Écrire le test, qui doit échouer**

Créer `backend/src/test/java/com/leadflow/capture/FenetreDeTransitionTest.java`. Le test pose les colonnes directement sur l'entité — la rotation qui les écrira vient en tâche 4, et ce test doit pouvoir échouer pour une seule raison.

```java
package com.leadflow.capture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * La fenetre de transition se prouve sur le webhook, pas sur une colonne : un lead signe
 * avec l'ancien secret est accepte tant qu'elle court, refuse des qu'elle est close.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FenetreDeTransitionTest {

    private static final String COURANT = "secret-courant-de-la-boutique";
    private static final String ANCIEN = "ancien-secret-de-la-boutique";

    @Autowired private MockMvc mockMvc;
    @Autowired private ClientRepository clients;
    @Autowired private RawLeadEventRepository evenements;

    @AfterEach
    void nettoie() {
        evenements.deleteAll();
        clients.deleteAll();
    }

    @Test
    void pendantLaFenetreLAncienSecretSigneEncoreEtLaLigneLeDit() throws Exception {
        Client boutique = creeUneBoutique(Instant.now().plus(1, ChronoUnit.HOURS));

        envoieUnLeadSigne(boutique.getPublicKey(), ANCIEN).andExpect(status().isAccepted());

        RawLeadEvent ligne = evenements.findAll().getFirst();
        assertThat(ligne.isSignedWithPreviousSecret()).isTrue();
    }

    @Test
    void pendantLaFenetreLeSecretCourantSigneSansEtreMarque() throws Exception {
        Client boutique = creeUneBoutique(Instant.now().plus(1, ChronoUnit.HOURS));

        envoieUnLeadSigne(boutique.getPublicKey(), COURANT).andExpect(status().isAccepted());

        assertThat(evenements.findAll().getFirst().isSignedWithPreviousSecret()).isFalse();
    }

    @Test
    void unefoisLaFenetreCloseLAncienSecretEstRefuse() throws Exception {
        // Expiree d'une seconde : c'est la comparaison de date, et rien d'autre, qui
        // distingue ce cas du precedent.
        Client boutique = creeUneBoutique(Instant.now().minusSeconds(1));

        envoieUnLeadSigne(boutique.getPublicKey(), ANCIEN)
                .andExpect(status().isUnauthorized());
        assertThat(evenements.findAll()).isEmpty();
    }

    @Test
    void sansTransitionSeulLeSecretCourantSigne() throws Exception {
        Client boutique = creeUneBoutique(null);

        envoieUnLeadSigne(boutique.getPublicKey(), ANCIEN)
                .andExpect(status().isUnauthorized());
        envoieUnLeadSigne(boutique.getPublicKey(), COURANT).andExpect(status().isAccepted());
    }

    /** {@code expiration} nulle cree une boutique hors transition. */
    private Client creeUneBoutique(Instant expiration) {
        Client client = new Client();
        client.setName("Boutique en transition");
        client.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        client.setHmacSecret(COURANT);
        if (expiration != null) {
            client.setPreviousHmacSecret(ANCIEN);
            client.setPreviousSecretExpiresAt(expiration);
        }
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-api"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client.setActive(true);
        return clients.save(client);
    }

    private ResultActions envoieUnLeadSigne(String clePublique, String secret) throws Exception {
        String corps = "{\"source\":\"test\",\"email\":\"prospect@test.fr\"}";
        long horodatage = Instant.now().getEpochSecond();
        return mockMvc.perform(post("/api/webhooks/leads/" + clePublique)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Leadflow-Signature",
                        "t=" + horodatage + ",v1=" + hmacHex(secret, horodatage + "." + corps))
                .content(corps));
    }

    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 2: Lancer le test, vérifier qu'il échoue**

```bash
cd backend && ./mvnw test -Dtest=FenetreDeTransitionTest
```

Attendu : ÉCHEC — `pendantLaFenetreLAncienSecretSigneEncoreEtLaLigneLeDit` rend `401` au lieu de `202`.

- [ ] **Step 3: Ajouter le réglage de durée**

Dans `WebhookProperties.java`, ajouter un composant au record et sa ligne de Javadoc :

```java
 * @param transitionSecret duree pendant laquelle le secret precedent d'une boutique reste
 *     accepte apres une rotation. Globale a l'instance, comme le fuseau des series de F13 :
 *     c'est un parametre d'exploitation de l'agence, pas une caracteristique du client.
```

```java
public record WebhookProperties(
        String signatureHeader,
        Duration tolerance,
        int maxPayloadBytes,
        Duration relayAfter,
        Duration relayInterval,
        Duration transitionSecret) {
}
```

Dans `application.yml`, sous `leadflow.webhook`, après `max-payload-bytes` :

```yaml
    # Duree pendant laquelle l'ancien secret d'une boutique reste accepte apres une
    # rotation, le temps qu'elle mette son formulaire a jour. Elle doit finir : une fenetre
    # sans fin, ce sont deux secrets permanents pour la meme porte.
    transition-secret: 24h
```

Dans `HmacSignatureVerifierTest`, ajouter le sixième argument au constructeur du record : `new WebhookProperties("X-Leadflow-Signature", Duration.ofMinutes(5), 65536, null, null, Duration.ofHours(24))`.

- [ ] **Step 4: Construire la liste des secrets dans la capture**

Dans `LeadCaptureService.java`, remplacer le bloc écrit en tâche 2 par :

```java
        SignatureVerifiee verifiee = verificateur.verifie(
                secretsAcceptables(client, Instant.now()),
                corpsBrut,
                enTeteSignature,
                Instant.now());
        String signature = verifiee.canonique();
```

et poser le drapeau sur la ligne, à côté des autres `set` de l'événement :

```java
        evenement.setSignedWithPreviousSecret(verifiee.secretPrecedent());
```

Ajouter la méthode privée, en bas de la classe :

```java
    /**
     * Le secret courant, plus le precedent tant que sa fenetre court.
     *
     * <p>C'est le seul endroit du projet qui connaisse la notion de fenetre de transition :
     * le verificateur ne recoit qu'une liste de secrets acceptables. L'expiration est
     * paresseuse — une fenetre close n'est pas balayee, le secret precedent reste en base
     * jusqu'a la rotation suivante ou la revocation. Le nettoyer ici ferait payer une
     * ecriture sur {@code client} a des requetes qui n'ont rien a corriger.
     */
    private List<String> secretsAcceptables(Client client, Instant maintenant) {
        String precedent = client.getPreviousHmacSecret();
        Instant expiration = client.getPreviousSecretExpiresAt();
        if (precedent == null || expiration == null || !expiration.isAfter(maintenant)) {
            return List.of(client.getHmacSecret());
        }
        // Le courant d'abord : le cas normal ne calcule qu'un seul HMAC.
        return List.of(client.getHmacSecret(), precedent);
    }
```

- [ ] **Step 5: Lancer le test, vérifier qu'il passe**

```bash
cd backend && ./mvnw test -Dtest=FenetreDeTransitionTest
```

Attendu : SUCCÈS, 4 tests.

- [ ] **Step 6: Éprouver le test d'expiration en le faisant échouer**

C'est la leçon du faux verrou de F13 : un test qu'on n'a pas vu échouer ne prouve rien. Remplacer temporairement, dans `secretsAcceptables`, la condition par `if (precedent == null || expiration == null) {` — la comparaison de date est neutralisée, tout le reste tient.

```bash
cd backend && ./mvnw test -Dtest=FenetreDeTransitionTest
```

Attendu : ÉCHEC de `unefoisLaFenetreCloseLAncienSecretEstRefuse` — `Status expected:<401> but was:<202>`. **Puis rétablir la condition** et relancer pour retrouver les 4 tests verts. Si le test passe malgré la neutralisation, il ne prouve rien et doit être repensé avant d'aller plus loin.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/leadflow/ backend/src/main/resources/application.yml \
        backend/src/test/java/com/leadflow/capture/
git commit -m "feat(f14): la capture accepte l ancien secret tant que sa fenetre court"
```

---

### Task 4: La rotation pose la fenêtre, la révocation la ferme

**Files:**

- Modify: `backend/src/main/java/com/leadflow/tenant/ClientAdminService.java:171`
- Modify: `backend/src/main/java/com/leadflow/tenant/ClientAdminController.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/dto/SecretRotated.java`
- Test: `backend/src/test/java/com/leadflow/tenant/RotationDesClesTest.java`

**Interfaces:**

- Consumes: `WebhookProperties.transitionSecret()` (tâche 3), les accesseurs de `Client` (tâche 1).
- Produces: `SecretRotated(String hmacSecret, Instant ancienSecretValideJusquA)`, `ClientAdminService.revoqueLeSecretPrecedent(UUID)` rendant `ClientDetailAdmin`, et la route `POST /api/admin/clients/{id}/revoke-previous-secret`.

- [ ] **Step 1: Écrire les tests, qui doivent échouer**

Dans `RotationDesClesTest.java`, **remplacer** `apresRotationLAncienSecretNeSignePlus` — il affirme exactement le défaut que F14 répare — par les trois tests suivants, et garder tous les autres tests du fichier tels quels :

```java
    @Test
    void pendantLaFenetreLAncienSecretSigneEncore() throws Exception {
        Client boutique = creeUneBoutique("Boutique a tourner");
        String nouveau = tourneLeSecret(boutique.getId());

        // Le defaut repare par F14 : sans fenetre, cette ligne rendait 401 et la boutique
        // perdait ses leads jusqu'a ce que son developpeur redeploie.
        envoieUnLeadSigne(boutique.getPublicKey(), SECRET_EN_CLAIR)
                .andExpect(status().isAccepted());
        envoieUnLeadSigne(boutique.getPublicKey(), nouveau).andExpect(status().isAccepted());
    }

    @Test
    void laRevocationTueLAncienSecretImmediatement() throws Exception {
        Client boutique = creeUneBoutique("Boutique pressee");
        String nouveau = tourneLeSecret(boutique.getId());

        mockMvc.perform(post("/api/admin/clients/" + boutique.getId()
                                + "/revoke-previous-secret")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk());

        envoieUnLeadSigne(boutique.getPublicKey(), SECRET_EN_CLAIR)
                .andExpect(status().isUnauthorized());
        envoieUnLeadSigne(boutique.getPublicKey(), nouveau).andExpect(status().isAccepted());
    }

    @Test
    void uneSecondeRotationRemplaceLAncienSecretParCeluiQuOnRetire() throws Exception {
        Client boutique = creeUneBoutique("Boutique tournee deux fois");
        String deuxieme = tourneLeSecret(boutique.getId());
        String troisieme = tourneLeSecret(boutique.getId());

        // Jamais plus de deux secrets vivants : le tout premier meurt a la seconde
        // rotation, et l'ecran doit le dire avant de confirmer.
        envoieUnLeadSigne(boutique.getPublicKey(), SECRET_EN_CLAIR)
                .andExpect(status().isUnauthorized());
        envoieUnLeadSigne(boutique.getPublicKey(), deuxieme).andExpect(status().isAccepted());
        envoieUnLeadSigne(boutique.getPublicKey(), troisieme).andExpect(status().isAccepted());
    }

    @Test
    void laRotationRendLaDateDeFinDeFenetre() throws Exception {
        Client boutique = creeUneBoutique("Boutique informee");

        String corps = mockMvc.perform(
                        post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                                .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // L'ecran a besoin des deux au meme instant : le secret a copier, et jusqu'a quand
        // l'ancien tient encore.
        assertThat(mapper.readTree(corps).get("ancienSecretValideJusquA").asText())
                .isNotBlank();
    }

    /** Tourne le secret et rend le nouveau, en clair. */
    private String tourneLeSecret(UUID id) throws Exception {
        String corps = mockMvc.perform(post("/api/admin/clients/" + id + "/rotate-secret")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(corps).get("hmacSecret").asText();
    }
```

Le `@AfterEach` du fichier efface déjà `raw_lead_event` avant `client` — ne pas y toucher, ces tests envoient de vrais leads.

- [ ] **Step 2: Lancer les tests, vérifier qu'ils échouent**

```bash
cd backend && ./mvnw test -Dtest=RotationDesClesTest
```

Attendu : ÉCHEC — `pendantLaFenetreLAncienSecretSigneEncore` rend `401`, et la route de révocation rend `404`.

- [ ] **Step 3: Enrichir le DTO de rotation**

`SecretRotated.java` :

```java
package com.leadflow.tenant.dto;

import java.time.Instant;

/**
 * Reponse de rotation : le secret en clair, une derniere fois, et la fin de la fenetre
 * pendant laquelle l'ancien reste accepte.
 *
 * <p>Un record de deux champs plutot que la fiche complete : ce que l'ecran doit faire ici
 * est different — afficher, faire copier, dire jusqu'a quand l'ancien vaut, puis oublier.
 */
public record SecretRotated(String hmacSecret, Instant ancienSecretValideJusquA) {
}
```

- [ ] **Step 4: Poser et fermer la fenêtre dans le service**

Dans `ClientAdminService.java`, injecter `WebhookProperties` par le constructeur (suivre exactement la forme des dépendances déjà injectées dans cette classe), puis remplacer `tourneLeSecret` et ajouter la révocation :

```java
    /**
     * Regenere le secret HMAC, en laissant l'ancien vivre le temps d'une fenetre.
     *
     * <p>Sans cette fenetre, chaque lead de la boutique etait refuse en 401 entre la
     * rotation et le redeploiement de son site. L'ancien secret <b>doit</b> finir : une
     * fenetre sans fin, ce sont deux secrets permanents pour la meme porte.
     *
     * <p>Une seconde rotation pendant la fenetre est autorisee, et l'ancien devient celui
     * qu'on vient de retirer : il n'y a jamais plus de deux secrets vivants. Le secret
     * d'origine cesse alors immediatement de valoir, et l'ecran l'annonce avant de
     * confirmer.
     */
    @Transactional
    public SecretRotated tourneLeSecret(UUID id) {
        Client client = trouve(id);
        Instant expiration = Instant.now().plus(webhook.transitionSecret());
        client.setPreviousHmacSecret(client.getHmacSecret());
        client.setPreviousSecretExpiresAt(expiration);
        client.setHmacSecret(generateur.secretHmac());
        return new SecretRotated(client.getHmacSecret(), expiration);
    }

    /**
     * Ferme la fenetre de transition sans attendre son terme.
     *
     * <p>Le geste d'une fuite averee. Les deux colonnes tombent ensemble : un secret
     * precedent sans expiration serait un second secret permanent.
     *
     * <p>Revoquer une transition inexistante est un succes sans effet — l'etat vise est
     * atteint, et un 404 obligerait l'ecran a distinguer deux cas identiques pour
     * l'operateur.
     */
    @Transactional
    public ClientDetailAdmin revoqueLeSecretPrecedent(UUID id) {
        Client client = trouve(id);
        client.setPreviousHmacSecret(null);
        client.setPreviousSecretExpiresAt(null);
        return fiche(client.getId());
    }
```

Attention : `client.setHmacSecret(...)` doit être appelé **après** avoir copié l'ancien dans `previousHmacSecret`, sans quoi la fenêtre porterait le nouveau secret et ne servirait à rien.

- [ ] **Step 5: Ouvrir la route**

Dans `ClientAdminController.java`, sous la route `rotate-secret`, en reprenant sa forme :

```java
    /**
     * Ferme la fenetre de transition ouverte par la derniere rotation. Un POST sur une
     * sous-ressource nommee par son geste, comme {@code rotate-secret} et
     * {@code deactivate} : c'est la convention de ce controleur.
     */
    @PostMapping("/{id}/revoke-previous-secret")
    public ClientDetailAdmin revoqueLeSecretPrecedent(@PathVariable UUID id) {
        return service.revoqueLeSecretPrecedent(id);
    }
```

- [ ] **Step 6: Lancer les tests, vérifier qu'ils passent**

```bash
cd backend && ./mvnw test -Dtest='RotationDesClesTest,FenetreDeTransitionTest'
```

Attendu : SUCCÈS. `leSecretTourneNApparaitDansAucuneAutreReponse`, resté intact, prouve qu'aucun secret ne fuit par la fiche.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/leadflow/tenant/ \
        backend/src/test/java/com/leadflow/tenant/RotationDesClesTest.java
git commit -m "feat(f14): la rotation ouvre la fenetre, la revocation la ferme"
```

---

### Task 5: La fiche dit où en est la migration

**Files:**

- Create: `backend/src/main/java/com/leadflow/capture/UsageAncienSecret.java`
- Create: `backend/src/main/java/com/leadflow/tenant/dto/TransitionSecret.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/dto/ClientDetailAdmin.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/ClientAdminService.java`
- Modify: `backend/src/main/java/com/leadflow/capture/RawLeadEventRepository.java`
- Test: `backend/src/test/java/com/leadflow/tenant/TransitionDansLaFicheTest.java` (créé)

**Interfaces:**

- Consumes: le drapeau posé en tâche 3, les colonnes de la tâche 1, la rotation de la tâche 4.
- Produces: `record TransitionSecret(Instant expireLe, Instant dernierLeadAncienSecret)` et le champ nullable `transition` de `ClientDetailAdmin`, consommés par la tâche 6.

- [ ] **Step 1: Écrire le test, qui doit échouer**

Créer `backend/src/test/java/com/leadflow/tenant/TransitionDansLaFicheTest.java`. Il asserte sur le **corps JSON** : asserter sur le DTO ne prouverait pas ce qui sort réellement.

```java
package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * La fiche doit repondre a « puis-je revoquer maintenant ? », et a rien d'autre : aucun
 * secret n'en sort, ni le courant ni le precedent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class TransitionDansLaFicheTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClientRepository clients;
    @Autowired private RawLeadEventRepository evenements;

    @AfterEach
    void nettoie() {
        evenements.deleteAll();
        clients.deleteAll();
    }

    @Test
    void horsTransitionLeChampEstNul() throws Exception {
        Client boutique = creeUneBoutique();

        assertThat(fiche(boutique.getId()).get("transition").isNull()).isTrue();
    }

    @Test
    void pendantLaTransitionLaFicheDonneLaDateDeFinEtAucunUsage() throws Exception {
        Client boutique = creeUneBoutique();
        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                .header("Authorization", "Bearer " + jeton()));

        JsonNode transition = fiche(boutique.getId()).get("transition");

        assertThat(transition.get("expireLe").asText()).isNotBlank();
        // Aucun lead n'est arrive depuis la rotation : c'est le feu vert pour revoquer.
        assertThat(transition.get("dernierLeadAncienSecret").isNull()).isTrue();
    }

    @Test
    void laFicheDatteLeDernierLeadSigneAvecLAncienSecret() throws Exception {
        Client boutique = creeUneBoutique();
        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                .header("Authorization", "Bearer " + jeton()));
        Instant quand = Instant.now().minus(2, ChronoUnit.HOURS);
        ecritUnLead(boutique.getId(), quand, true);
        // Un lead plus recent signe avec le secret courant ne doit pas deplacer la date :
        // ce qu'on cherche, c'est le dernier retardataire.
        ecritUnLead(boutique.getId(), Instant.now(), false);

        JsonNode transition = fiche(boutique.getId()).get("transition");

        assertThat(Instant.parse(transition.get("dernierLeadAncienSecret").asText()))
                .isCloseTo(quand, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void aucunSecretNeSortDeLaFiche() throws Exception {
        Client boutique = creeUneBoutique();
        String corps = mockMvc.perform(
                        post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                                .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();
        String nouveau = mapper.readTree(corps).get("hmacSecret").asText();

        String fiche = mockMvc.perform(get("/api/admin/clients/" + boutique.getId())
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();

        assertThat(fiche).doesNotContain(nouveau).doesNotContain("secret-d-origine");
    }

    private JsonNode fiche(UUID id) throws Exception {
        String corps = mockMvc.perform(get("/api/admin/clients/" + id)
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(corps);
    }

    private void ecritUnLead(UUID clientId, Instant recu, boolean ancienSecret) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("test");
        evenement.setPayload(Map.of("email", "prospect@test.fr"));
        evenement.setSignature("t=1,v1=" + UUID.randomUUID());
        evenement.setReceivedAt(recu);
        evenement.setStatus(RawLeadEventStatus.RECEIVED);
        evenement.setSignedWithPreviousSecret(ancienSecret);
        evenements.save(evenement);
    }

    private Client creeUneBoutique() {
        Client client = new Client();
        client.setName("Boutique observee");
        client.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        client.setHmacSecret("secret-d-origine");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-api"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client.setActive(true);
        return clients.save(client);
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

Ajouter `import static org.assertj.core.api.Assertions.within;`.

- [ ] **Step 2: Lancer le test, vérifier qu'il échoue**

```bash
cd backend && ./mvnw test -Dtest=TransitionDansLaFicheTest
```

Attendu : ÉCHEC — `transition` n'existe pas dans le corps JSON (`NullPointerException` sur `get("transition")`).

- [ ] **Step 3: Exposer la lecture depuis `capture/`**

Créer `backend/src/main/java/com/leadflow/capture/UsageAncienSecret.java` :

```java
package com.leadflow.capture;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Quand un lead a-t-il ete signe pour la derniere fois avec le secret precedent d'une
 * boutique ?
 *
 * <p>Existe pour que {@code tenant/} n'ait pas a ouvrir un repository sur
 * {@code raw_lead_event}, qui appartient a cette etape. Chaque package garde sa table, et
 * l'administration ne sait pas ou l'information est rangee.
 */
@Component
public class UsageAncienSecret {

    private final RawLeadEventRepository evenements;

    public UsageAncienSecret(RawLeadEventRepository evenements) {
        this.evenements = evenements;
    }

    /** Vide quand aucun lead de cette boutique n'a jamais ete signe avec l'ancien secret. */
    public Optional<Instant> dernierUsage(java.util.UUID clientId) {
        return evenements
                .findFirstByClientIdAndSignedWithPreviousSecretTrueOrderByReceivedAtDesc(clientId)
                .map(RawLeadEvent::getReceivedAt);
    }
}
```

Dans `RawLeadEventRepository.java`, ajouter la méthode dérivée :

```java
    Optional<RawLeadEvent> findFirstByClientIdAndSignedWithPreviousSecretTrueOrderByReceivedAtDesc(
            UUID clientId);
```

- [ ] **Step 4: Ajouter le DTO et le remplir**

Créer `backend/src/main/java/com/leadflow/tenant/dto/TransitionSecret.java` :

```java
package com.leadflow.tenant.dto;

import java.time.Instant;

/**
 * Etat de la fenetre pendant laquelle l'ancien secret d'une boutique reste accepte.
 *
 * <p>Aucun secret n'y figure, pas plus le precedent que le courant : la fiche n'en a jamais
 * rendu, et une transition n'est pas une raison de commencer.
 *
 * @param expireLe fin de la fenetre
 * @param dernierLeadAncienSecret date du dernier lead encore signe avec l'ancien secret,
 *     nulle quand il n'y en a aucun — c'est alors le feu vert pour revoquer
 */
public record TransitionSecret(Instant expireLe, Instant dernierLeadAncienSecret) {
}
```

Ajouter le champ `TransitionSecret transition` à la fin du record `ClientDetailAdmin`, avec cette ligne de Javadoc dans le bloc existant : « {@code transition} est nul hors transition — l'etat de toutes les boutiques qui n'ont pas tourne leur secret recemment. »

Dans `ClientAdminService`, injecter `UsageAncienSecret` et remplir le champ là où la classe construit déjà le `ClientDetailAdmin` (méthode `fiche`) :

```java
        TransitionSecret transition = null;
        Instant expiration = client.getPreviousSecretExpiresAt();
        // Une fenetre close ne s'affiche pas : le secret precedent survit en base jusqu'a la
        // rotation suivante, mais il n'est plus accepte, donc il n'y a plus rien a dire.
        if (client.getPreviousHmacSecret() != null
                && expiration != null
                && expiration.isAfter(Instant.now())) {
            transition = new TransitionSecret(
                    expiration, usageAncienSecret.dernierUsage(client.getId()).orElse(null));
        }
```

et passer `transition` en dernier argument du constructeur de `ClientDetailAdmin`.

- [ ] **Step 5: Lancer les tests, vérifier qu'ils passent**

```bash
cd backend && ./mvnw test -Dtest='TransitionDansLaFicheTest,ClientAdminReadTest,ClientAdminCreationTest'
```

Attendu : SUCCÈS. Les deux tests existants prouvent que l'ajout d'un champ nullable n'a rien cassé de la fiche.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/leadflow/ backend/src/test/java/com/leadflow/tenant/
git commit -m "feat(f14): la fiche dit ou en est la migration de la boutique"
```

---

### Task 6: L'écran — le dialogue, le bandeau, la révocation

**Files:**

- Modify: `frontend/src/app/core/models/tenant.ts`
- Modify: `frontend/src/app/core/api/tenant-api.ts`
- Create: `frontend/src/app/features/boutiques/transition-secret/transition-secret.ts` (+ `.html`, `.scss`, `.spec.ts`)
- Modify: `frontend/src/app/features/boutiques/boutique-detail/boutique-detail.ts` (+ `.html`)
- Modify: `frontend/src/app/features/boutiques/secret-revele/secret-revele.ts` (+ `.html`)
- Test: `frontend/src/app/core/api/tenant-api.spec.ts`, `frontend/src/app/features/boutiques/transition-secret/transition-secret.spec.ts`

**Interfaces:**

- Consumes: le champ `transition` de la fiche et le champ `ancienSecretValideJusquA` de la rotation (tâches 4 et 5).
- Produces: rien pour les tâches suivantes.

- [ ] **Step 1: Invoquer le plugin visuel AVANT d'écrire la moindre ligne**

Règle permanente du projet : toute décision visuelle passe par les skills du plugin `ui-ux-pro-max`, invoquées **avant** le code et jamais en relecture. Invoquer `ui-ux-pro-max:ui-ux-pro-max` puis `ui-ux-pro-max:ui-styling` pour le bandeau — un état transitoire et daté, à mi-chemin entre l'information et l'avertissement, qui doit s'accorder aux jetons de la console existante sans crier comme une erreur. Le bandeau porte trois choses : la date de fin, l'état de migration, et le bouton de révocation.

- [ ] **Step 2: Écrire les tests, qui doivent échouer**

Dans `frontend/src/app/core/api/tenant-api.spec.ts`, ajouter :

```ts
  it('revoque le secret precedent par une route dediee', () => {
    api.revoqueLeSecretPrecedent('abc').subscribe();

    const requete = httpMock.expectOne('/api/admin/clients/abc/revoke-previous-secret');
    expect(requete.request.method).toBe('POST');
    requete.flush({});
  });
```

Créer `frontend/src/app/features/boutiques/transition-secret/transition-secret.spec.ts` :

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TransitionSecret } from './transition-secret';

describe('TransitionSecret', () => {
  let fixture: ComponentFixture<TransitionSecret>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [TransitionSecret] }).compileComponents();
    fixture = TestBed.createComponent(TransitionSecret);
  });

  it('annonce que la boutique a fini de migrer quand aucun lead ne porte l ancien secret', () => {
    fixture.componentRef.setInput('transition', {
      expireLe: '2026-09-06T14:30:00Z',
      dernierLeadAncienSecret: null,
    });
    fixture.detectChanges();

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('Plus aucun lead');
  });

  it('signale un retardataire tant qu un lead porte encore l ancien secret', () => {
    fixture.componentRef.setInput('transition', {
      expireLe: '2026-09-06T14:30:00Z',
      dernierLeadAncienSecret: '2026-09-05T12:00:00Z',
    });
    fixture.detectChanges();

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('Dernier lead');
    expect(texte).not.toContain('Plus aucun lead');
  });

  it('emet la revocation au clic', () => {
    fixture.componentRef.setInput('transition', {
      expireLe: '2026-09-06T14:30:00Z',
      dernierLeadAncienSecret: null,
    });
    let demande = 0;
    fixture.componentInstance.revoque.subscribe(() => (demande += 1));
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-test="revoquer"]')
      ?.click();

    expect(demande).toBe(1);
  });
});
```

- [ ] **Step 3: Lancer les tests, vérifier qu'ils échouent**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Attendu : ÉCHEC de compilation TypeScript — `revoqueLeSecretPrecedent` et le composant `TransitionSecret` n'existent pas.

- [ ] **Step 4: Ajouter le modèle et l'appel**

Dans `core/models/tenant.ts` :

```ts
/**
 * Fenetre pendant laquelle l'ancien secret d'une boutique reste accepte. `null` sur la
 * fiche quand aucune transition ne court, ce qui est le cas ordinaire.
 */
export interface TransitionSecret {
  expireLe: string;
  /** Nul quand plus aucun lead n'arrive signe avec l'ancien secret : feu vert pour revoquer. */
  dernierLeadAncienSecret: string | null;
}
```

Ajouter `transition: TransitionSecret | null;` à `ClientDetailAdmin` et `ancienSecretValideJusquA: string;` à `SecretRotated`.

Dans `core/api/tenant-api.ts`, sous `tourneLeSecret` :

```ts
  revoqueLeSecretPrecedent(id: string) {
    return this.http.post<ClientDetailAdmin>(
      `/api/admin/clients/${id}/revoke-previous-secret`,
      {},
    );
  }
```

- [ ] **Step 5: Écrire le composant du bandeau**

`transition-secret.ts` — composant présentationnel, aucun appel HTTP : il reçoit et il émet, comme `secret-revele`.

```ts
import { Component, input, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { TransitionSecret as Transition } from '../../../core/models/tenant';

/**
 * Le bandeau d'une rotation en cours : jusqu'a quand l'ancien secret vaut, et si la
 * boutique a fini de migrer.
 *
 * Le bouton de revocation vit ici et nulle part ailleurs — hors transition, il n'aurait
 * rien a revoquer.
 */
@Component({
  selector: 'app-transition-secret',
  imports: [DatePipe, MatButtonModule, MatIconModule],
  templateUrl: './transition-secret.html',
  styleUrl: './transition-secret.scss',
})
export class TransitionSecret {
  readonly transition = input.required<Transition>();

  /** Emis au clic : c'est la fiche qui appelle l'API et rafraichit. */
  readonly revoque = output<void>();
}
```

Le template affiche « Ancien secret encore accepte jusqu'au {{ ... | date: 'medium' }} », puis, selon `dernierLeadAncienSecret`, « Plus aucun lead signe avec l'ancien secret » ou « Dernier lead signe avec l'ancien secret le {{ ... | date: 'short' }} », et le bouton portant `data-test="revoquer"`. Libellés **sans accents**, comme le reste des templates du projet. Le style suit ce qu'a produit l'étape 1.

- [ ] **Step 6: Câbler la fiche**

Dans `boutique-detail.ts`, importer `TransitionSecret`, changer le texte du dialogue de rotation et ajouter la révocation :

```ts
  regenereLeSecret(): void {
    const fiche = this.boutique();
    if (!fiche) {
      return;
    }
    const enTransition = fiche.transition !== null;
    const avertissement = enTransition
      ? '\n\nUne rotation est deja en cours : le secret d origine cessera immediatement ' +
        'd etre accepte.'
      : '';
    if (
      !confirm(
        'Regenerer le secret HMAC ?\n\n' +
          'L ancien secret restera accepte pendant la fenetre de transition, le temps ' +
          'que la boutique mette son formulaire a jour.' +
          avertissement,
      )
    ) {
      return;
    }
    this.api.tourneLeSecret(fiche.id).subscribe({
      next: (rendu) => {
        this.secretRevele.set(rendu.hmacSecret);
        this.ancienValideJusquA.set(rendu.ancienSecretValideJusquA);
        this.message.set('Secret regenere. Il n est affiche qu une fois.');
        this.recharge(fiche.id);
      },
      error: (echec: { status?: number }) => this.message.set(this.explique(echec?.status)),
    });
  }

  revoqueLeSecretPrecedent(): void {
    const fiche = this.boutique();
    if (
      !fiche ||
      !confirm(
        'Revoquer l ancien secret ?\n\n' +
          'Tout lead encore signe avec lui sera refuse immediatement.',
      )
    ) {
      return;
    }
    this.api.revoqueLeSecretPrecedent(fiche.id).subscribe({
      next: (mise) => {
        this.applique(mise);
        this.message.set('Ancien secret revoque.');
      },
      error: (echec: { status?: number }) => this.message.set(this.explique(echec?.status)),
    });
  }
```

Ajouter le signal `readonly ancienValideJusquA = signal<string | null>(null);`. Pour `recharge(id)`, réutiliser le chargement de fiche déjà présent dans la classe — la rotation doit faire apparaître le bandeau sans navigation. Si aucune méthode de rechargement n'existe, appeler `this.api.boutique(fiche.id).subscribe((mise) => this.applique(mise))`, en reprenant le nom exact de la méthode de `TenantApi` utilisée à l'initialisation.

Dans `boutique-detail.html`, poser le bandeau au-dessus de la section des clés :

```html
@if (boutique(); as fiche) {
  @if (fiche.transition) {
    <app-transition-secret
      [transition]="fiche.transition"
      (revoque)="revoqueLeSecretPrecedent()"
    />
  }
}
```

en l'insérant dans le bloc `@if` existant de la fiche plutôt qu'en ouvrant un second.

Enfin, dans `secret-revele.ts`, ajouter `readonly valideJusquA = input<string | null>(null);` et, dans son template, une ligne « L ancien secret reste accepte jusqu au {{ valideJusquA() | date: 'medium' }} » affichée seulement quand l'entrée est fournie — la création d'une boutique ne la passe pas.

- [ ] **Step 7: Lancer les tests, la vérification de format et le build**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
npm run lint && npm run format:check
npm run build
```

Attendu : tous les tests passent (les 76 existants plus les 4 ajoutés), ESLint et Prettier verts, build réussi. Si Prettier signale des dizaines de fichiers non touchés, la copie de travail est en CRLF — vérifier `.gitattributes` plutôt que reformater.

- [ ] **Step 8: Commit**

```bash
git add frontend/src
git commit -m "feat(f14): l ecran annonce la fenetre de transition et permet de la fermer"
```

---

### Task 7: La documentation et la vérification d'ensemble

**Files:**

- Modify: `CLAUDE.md`
- Modify: `docs/monitoring-api.md`
- Modify: `docs/webhook-integration.md`

**Interfaces:**

- Consumes: tout ce qui précède.

- [ ] **Step 1: Mettre `CLAUDE.md` à jour**

Français **sans accents** dans ce fichier. Quatre endroits :

1. Section « Capture — le contrat d'entree » : dire qu'une rotation ouvre une fenêtre pendant laquelle deux secrets sont acceptés, que le vérificateur reçoit une liste ordonnée et ignore la notion de fenêtre, et que les cinq causes de refus rendent toujours le même `401`.
2. Section « Base de donnees » : passer le décompte de dix à **onze migrations** et décrire `V11__hmac_secret_transition.sql` — deux colonnes nullables sur `client`, un booléen sur `raw_lead_event`, aucun index.
3. Le tableau des variables et réglages : `leadflow.webhook.transition-secret`, défaut `24h`, global à l'instance.
4. Section « Etat actuel » : F14 livrée, et retirer de « ce qui n'existe pas » la mention d'une rotation qui coupe la capture si elle y figure.

- [ ] **Step 2: Documenter les routes**

Dans `docs/monitoring-api.md` (accents autorisés), à côté de l'exemple `curl` de `rotate-secret` déjà présent : la réponse porte maintenant `ancienSecretValideJusquA`, la fiche porte `transition`, et la nouvelle route `POST /api/admin/clients/{id}/revoke-previous-secret` ferme la fenêtre. Donner un exemple `curl` de chacune, sur le modèle des exemples existants.

Dans `docs/webhook-integration.md`, une section courte à destination de l'intégrateur : après une rotation, les deux secrets sont acceptés pendant la fenêtre, donc le redéploiement du site peut se faire tranquillement — mais il **doit** se faire avant la fin de la fenêtre.

- [ ] **Step 3: Lancer la suite entière**

```bash
cd backend && ./mvnw verify
```

Attendu : SUCCÈS, sans `-Perp-it` (ce profil demande des conteneurs Dolibarr et Odoo préparés à la main). Si la machine ne supporte pas la suite entière, le dire explicitement et s'en remettre au job `backend` de la CI plutôt que de prétendre l'avoir jouée.

- [ ] **Step 4: Vérifier le pipeline à la main**

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Contrôle qui tranche : `docker exec leadflow-rabbitmq rabbitmqctl list_queues name messages consumers` doit montrer **cinq files avec un consommateur chacune**. Ne pas utiliser `spring-boot:test-run` : il démarre en profil `default` et éteint les cinq consommateurs.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md docs/
git commit -m "docs(f14): la rotation n interrompt plus la capture, et la doc le dit"
```

---

## Après le plan

La recette à l'écran revient à l'utilisateur, sauf demande explicite du contraire. Les points à parcourir :

1. Sur une boutique, régénérer le secret : le dialogue annonce la fenêtre, l'écran révèle le nouveau secret **et** la date de fin.
2. Le bandeau de transition apparaît sans recharger la page.
3. Envoyer un lead signé avec l'**ancien** secret : il est accepté, et le bandeau passe à « Dernier lead signé avec l'ancien secret ».
4. Envoyer un lead signé avec le nouveau : accepté, le bandeau ne bouge pas.
5. Révoquer : le bandeau disparaît, et un lead signé avec l'ancien secret est désormais refusé.
6. Régénérer deux fois de suite : le dialogue avertit que le secret d'origine va mourir immédiatement.
