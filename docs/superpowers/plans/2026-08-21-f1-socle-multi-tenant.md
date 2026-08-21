# F1 — Socle et multi-tenant : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Poser le schéma de données complet de LeadFlow, les entités et repositories associés, et le chiffrement des secrets, de sorte qu'aucune feature ultérieure n'ait à rouvrir une migration.

**Architecture:** Cinq tables PostgreSQL créées par une migration Flyway `V2`, mappées par des entités JPA réparties dans le package de l'étape du pipeline qui les produit. Les secrets — secret HMAC du client et paramètres de connexion à son ERP — sont chiffrés au repos en AES-256-GCM par des `AttributeConverter` JPA, transparents pour le reste du code. La configuration propre au tenant quitte `application.yml` pour la table `client`, ce qui impose de changer la signature de `CrmConnector.sync`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA / Hibernate 7, Flyway, PostgreSQL 16, Jackson, Lombok, JUnit 5, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-21-f1-socle-multi-tenant-design.md`

## Global Constraints

- Branche de travail : `feature/f1-socle-multi-tenant`. Ne jamais commiter sur `main`.
- Toutes les commandes Maven s'exécutent depuis `backend/`. Sous Git Bash : `./mvnw`. Sous PowerShell : `.\mvnw.cmd`.
- **Le daemon Docker doit tourner** : tous les tests d'intégration démarrent des conteneurs Testcontainers. Sans Docker, l'échec `Could not find a valid Docker environment` est un problème d'environnement, pas de code.
- `ddl-auto: validate` — **Hibernate ne crée jamais de table.** Toute colonne mappée doit exister dans une migration Flyway.
- **Ne jamais modifier `V1__raw_lead_event.sql`.** Flyway échouerait au démarrage sur une somme de contrôle divergente.
- Le modèle pivot `crm/model` ne doit contenir aucun terme propre à un fournisseur : ni `thirdparty`, ni `res.partner`.
- Convention de commit : `feat:`, `fix:`, `test:`, `docs:`, `refactor:`.
- Package racine : `com.leadflow`. Les sous-packages sont des étapes du pipeline, jamais des couches techniques.
- **Règle d'association JPA retenue pour F1** : une association `@ManyToOne` n'est autorisée qu'entre deux entités du **même package**. Entre packages, on porte l'identifiant brut (`UUID`). Cela évite que `qualification` dépende de `capture` et de `tenant` au niveau du typage.
- Clé maître de chiffrement utilisée par les tests : `gh/SD1Jp/HfWc/sB/BybtvbUqehzXmv0YIZ8uMwutME=`
- Clé maître de chiffrement utilisée par le profil `dev` : `gmLN1lvFdN9MsO6NuKFrFHHVzFzWQF7bgrJYmNY8Rok=`

---

## Structure des fichiers

**À créer — code principal**

| Fichier | Responsabilité |
| --- | --- |
| `config/SecurityProperties.java` | Propriété typée `leadflow.security.master-key` |
| `common/SecretCipher.java` | Chiffrement et déchiffrement AES-256-GCM |
| `common/EncryptedStringConverter.java` | `AttributeConverter` pour une chaîne chiffrée |
| `common/EncryptedJsonConverter.java` | `AttributeConverter` pour une `Map` sérialisée en JSON puis chiffrée |
| `common/BaseEntity.java` | `@MappedSuperclass` : `id` UUID, `created_at`, `updated_at` |
| `tenant/Client.java` | Entité tenant |
| `tenant/SalesRep.java` | Entité commercial |
| `tenant/AssignmentStrategyType.java` | Énumération de stratégie d'attribution |
| `tenant/ClientRepository.java` | Accès aux clients |
| `tenant/SalesRepRepository.java` | Accès aux commerciaux |
| `tenant/package-info.java` | Documentation du package |
| `capture/RawLeadEvent.java` | Entité événement brut |
| `capture/RawLeadEventStatus.java` | Énumération de statut de capture |
| `capture/RawLeadEventRepository.java` | Accès aux événements bruts |
| `qualification/Lead.java` | Entité lead qualifié |
| `qualification/LeadStatus.java` | Énumération de statut de lead |
| `qualification/IntentSource.java` | Énumération de provenance de l'analyse d'intention |
| `qualification/LeadRepository.java` | Accès aux leads |
| `crm/CrmSyncAttempt.java` | Entité trace de synchronisation |
| `crm/CrmSyncAttemptStatus.java` | Énumération de statut de synchronisation |
| `crm/CrmSyncAttemptRepository.java` | Accès aux traces de synchronisation |
| `crm/model/CrmTarget.java` | Instance ERP cible, neutre vis-à-vis du fournisseur |

**À créer — ressources**

| Fichier | Responsabilité |
| --- | --- |
| `src/main/resources/db/migration/V2__multi_tenant_schema.sql` | Les cinq tables |
| `src/main/resources/db/dev/R__demo_data.sql` | Jeu de démonstration, profil `dev` uniquement |
| `src/test/resources/application.properties` | Clé maître de test |

**À modifier**

| Fichier | Modification |
| --- | --- |
| `crm/CrmConnector.java` | Signature de `sync` |
| `crm/CrmConnectorRegistry.java` | Retrait de `defaultConnector()` |
| `config/CrmProperties.java` | `Provider` réduit à trois champs, retrait de `defaultProvider` |
| `config/WebhookProperties.java` | Retrait de `hmacSecret` |
| `src/main/resources/application.yml` | Propriétés supprimées et ajoutées |
| `src/main/resources/application-dev.yml` | Clé maître de dev, `flyway.locations` |
| `src/test/java/com/leadflow/TestcontainersConfiguration.java` | Passage en `public` |
| `CLAUDE.md` | Mise à jour de la mémoire du projet |

**À créer — tests**

`SecretCipherTest`, `EncryptedStringConverterTest`, `EncryptedJsonConverterTest`, `ClientPersistenceTest`, `SalesRepConstraintTest`, `LeadPersistenceTest`, `CrmSyncAttemptPersistenceTest`, `CrmConnectorRegistryTest`.

---

## Task 1: Chiffrement des secrets

Fondation de tout le reste : les entités de la tâche 3 ne peuvent pas être écrites avant que le chiffrement existe.

**Files:**
- Create: `backend/src/main/java/com/leadflow/config/SecurityProperties.java`
- Create: `backend/src/main/java/com/leadflow/common/SecretCipher.java`
- Create: `backend/src/test/resources/application.properties`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-dev.yml`
- Modify: `backend/src/test/java/com/leadflow/TestcontainersConfiguration.java`
- Test: `backend/src/test/java/com/leadflow/common/SecretCipherTest.java`

**Interfaces:**
- Consumes: rien.
- Produces: `SecurityProperties(String masterKey)` ; `SecretCipher.encrypt(String) -> String` et `SecretCipher.decrypt(String) -> String`, tous deux levant `IllegalStateException` en cas d'échec cryptographique.

- [ ] **Step 1: Rendre `TestcontainersConfiguration` accessible depuis les autres packages**

Elle est aujourd'hui package-private, donc invisible depuis `com.leadflow.common` ou `com.leadflow.tenant`. Passer la classe **et** ses deux méthodes de bean en `public` dans `backend/src/test/java/com/leadflow/TestcontainersConfiguration.java` :

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	public PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
	}

	@Bean
	@ServiceConnection
	public RabbitMQContainer rabbitContainer() {
		return new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine"));
	}

}
```

- [ ] **Step 2: Déclarer la propriété de clé maître**

Créer `backend/src/main/java/com/leadflow/config/SecurityProperties.java` :

```java
package com.leadflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cle maitre de chiffrement des secrets stockes en base. Elle n'est jamais persistee :
 * elle vient de l'environnement, et sa perte rend les secrets irrecuperables.
 */
@ConfigurationProperties(prefix = "leadflow.security")
public record SecurityProperties(String masterKey) {
}
```

Aucun enregistrement manuel n'est nécessaire : `@ConfigurationPropertiesScan` est actif sur `BackendApplication`.

Ajouter dans `backend/src/main/resources/application.yml`, sous la clé `leadflow`, **avant** le bloc `webhook` :

```yaml
  security:
    # Cle AES-256 en base64 sur 32 octets. Aucune valeur de repli : l'application
    # doit refuser de demarrer plutot que de chiffrer avec une cle devinable.
    master-key: ${LEADFLOW_MASTER_KEY:}
```

Ajouter à la fin de `backend/src/main/resources/application-dev.yml` :

```yaml
leadflow:
  security:
    # Cle fixe de developpement. Elle rend reproductibles les valeurs chiffrees du
    # jeu de demonstration. Ne jamais l'utiliser hors du poste de developpement.
    master-key: gmLN1lvFdN9MsO6NuKFrFHHVzFzWQF7bgrJYmNY8Rok=
```

Créer `backend/src/test/resources/application.properties` :

```properties
# Cle maitre fixe pour la suite de tests. Ce fichier s'ajoute a application.yml du
# classpath principal sans le remplacer : les proprietes y ont simplement priorite.
leadflow.security.master-key=gh/SD1Jp/HfWc/sB/BybtvbUqehzXmv0YIZ8uMwutME=
```

- [ ] **Step 3: Écrire les tests du chiffrement, qui doivent échouer**

Créer `backend/src/test/java/com/leadflow/common/SecretCipherTest.java` :

```java
package com.leadflow.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.config.SecurityProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String MASTER_KEY = "gh/SD1Jp/HfWc/sB/BybtvbUqehzXmv0YIZ8uMwutME=";

    private final SecretCipher cipher = new SecretCipher(new SecurityProperties(MASTER_KEY));

    @Test
    void chiffreEtDechiffreLaMemeValeur() {
        String clair = "c6702b700b1673ae027ce903ff753c4239522e71b21297259c396540b9e5ec19";

        assertThat(cipher.decrypt(cipher.encrypt(clair))).isEqualTo(clair);
    }

    @Test
    void deuxChiffrementsDeLaMemeValeurDifferent() {
        String clair = "secret-partage";

        assertThat(cipher.encrypt(clair)).isNotEqualTo(cipher.encrypt(clair));
    }

    @Test
    void rejetteUnChiffreAltere() {
        byte[] chiffre = Base64.getDecoder().decode(cipher.encrypt("secret-partage"));
        chiffre[chiffre.length - 1] ^= 0x01;
        String altere = Base64.getEncoder().encodeToString(chiffre);

        assertThatThrownBy(() -> cipher.decrypt(altere)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refuseUneCleDeLongueurIncorrecte() {
        SecurityProperties tropCourte = new SecurityProperties(
                Base64.getEncoder().encodeToString(new byte[16]));

        assertThatThrownBy(() -> new SecretCipher(tropCourte))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
```

Le troisième test est le plus important : il vérifie que le tag d'authentification GCM fait son travail. Sans lui, on aurait un chiffrement sans garantie d'intégrité, et une valeur corrompue en base pourrait être déchiffrée en silence vers n'importe quoi.

- [ ] **Step 4: Lancer les tests et confirmer qu'ils échouent**

```bash
./mvnw test -Dtest=SecretCipherTest
```

Attendu : échec de compilation, `SecretCipher` n'existe pas.

- [ ] **Step 5: Implémenter `SecretCipher`**

Créer `backend/src/main/java/com/leadflow/common/SecretCipher.java` :

```java
package com.leadflow.common;

import com.leadflow.config.SecurityProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Chiffrement des secrets stockes en base, en AES-256-GCM.
 *
 * <p>Un vecteur d'initialisation aleatoire est tire a chaque ecriture et prefixe au
 * chiffre. Deux chiffrements d'une meme valeur different donc, ce qui interdit toute
 * recherche par valeur chiffree — comportement voulu, aucun cas d'usage ne le demande.
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(SecurityProperties properties) {
        if (properties.masterKey() == null || properties.masterKey().isBlank()) {
            throw new IllegalStateException(
                    "leadflow.security.master-key est absente. Definir LEADFLOW_MASTER_KEY.");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(properties.masterKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("leadflow.security.master-key n'est pas du base64 valide", e);
        }
        if (raw.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "leadflow.security.master-key doit faire 32 octets, recu " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] packed = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Echec du chiffrement d'un secret", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] packed = Base64.getDecoder().decode(encoded);
            if (packed.length <= IV_LENGTH_BYTES) {
                throw new IllegalStateException("Valeur chiffree tronquee");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[packed.length - IV_LENGTH_BYTES];
            ByteBuffer.wrap(packed).get(iv).get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Echec du dechiffrement d'un secret", e);
        }
    }
}
```

- [ ] **Step 6: Lancer les tests et confirmer qu'ils passent**

```bash
./mvnw test -Dtest=SecretCipherTest
```

Attendu : 4 tests passent.

- [ ] **Step 7: Vérifier que le contexte Spring démarre toujours**

```bash
./mvnw test -Dtest=BackendApplicationTests
```

Attendu : succès. Ce test valide que `SecurityProperties` est bien détectée et que la clé de `src/test/resources/application.properties` est prise en compte. S'il échoue sur une clé absente, c'est que le fichier de test n'est pas au bon endroit.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/leadflow/config/SecurityProperties.java \
        backend/src/main/java/com/leadflow/common/SecretCipher.java \
        backend/src/test/java/com/leadflow/common/SecretCipherTest.java \
        backend/src/test/java/com/leadflow/TestcontainersConfiguration.java \
        backend/src/test/resources/application.properties \
        backend/src/main/resources/application.yml \
        backend/src/main/resources/application-dev.yml
git commit -m "feat: chiffrement AES-256-GCM des secrets stockes en base"
```

---

## Task 2: Converters JPA de chiffrement

**Files:**
- Create: `backend/src/main/java/com/leadflow/common/EncryptedStringConverter.java`
- Create: `backend/src/main/java/com/leadflow/common/EncryptedJsonConverter.java`
- Test: `backend/src/test/java/com/leadflow/common/EncryptedStringConverterTest.java`
- Test: `backend/src/test/java/com/leadflow/common/EncryptedJsonConverterTest.java`

**Interfaces:**
- Consumes: `SecretCipher.encrypt(String)`, `SecretCipher.decrypt(String)`.
- Produces: `EncryptedStringConverter implements AttributeConverter<String, String>` et `EncryptedJsonConverter implements AttributeConverter<Map<String, String>, String>`, tous deux beans Spring, utilisables via `@Convert(converter = ...)`.

- [ ] **Step 1: Écrire les tests, qui doivent échouer**

Créer `backend/src/test/java/com/leadflow/common/EncryptedStringConverterTest.java` :

```java
package com.leadflow.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.config.SecurityProperties;
import org.junit.jupiter.api.Test;

class EncryptedStringConverterTest {

    private static final String MASTER_KEY = "gh/SD1Jp/HfWc/sB/BybtvbUqehzXmv0YIZ8uMwutME=";

    private final SecretCipher cipher = new SecretCipher(new SecurityProperties(MASTER_KEY));
    private final EncryptedStringConverter converter = new EncryptedStringConverter(cipher);

    @Test
    void laColonneNeContientPasLaValeurEnClair() {
        String colonne = converter.convertToDatabaseColumn("secret-partage");

        assertThat(colonne).isNotNull().doesNotContain("secret-partage");
    }

    @Test
    void relitLaValeurDOrigine() {
        String colonne = converter.convertToDatabaseColumn("secret-partage");

        assertThat(converter.convertToEntityAttribute(colonne)).isEqualTo("secret-partage");
    }

    @Test
    void laisseNullInchange() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
```

Créer `backend/src/test/java/com/leadflow/common/EncryptedJsonConverterTest.java` :

```java
package com.leadflow.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadflow.config.SecurityProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EncryptedJsonConverterTest {

    private static final String MASTER_KEY = "gh/SD1Jp/HfWc/sB/BybtvbUqehzXmv0YIZ8uMwutME=";

    private final SecretCipher cipher = new SecretCipher(new SecurityProperties(MASTER_KEY));
    private final EncryptedJsonConverter converter =
            new EncryptedJsonConverter(cipher, new ObjectMapper());

    @Test
    void relitLaMemeCarte() {
        Map<String, String> config = Map.of(
                "baseUrl", "http://localhost:8081/api/index.php",
                "apiKey", "cle-dolibarr");

        String colonne = converter.convertToDatabaseColumn(config);

        assertThat(converter.convertToEntityAttribute(colonne)).isEqualTo(config);
    }

    @Test
    void laColonneNeContientNiCleNiValeurEnClair() {
        Map<String, String> config = Map.of("apiKey", "cle-dolibarr");

        String colonne = converter.convertToDatabaseColumn(config);

        assertThat(colonne).doesNotContain("apiKey").doesNotContain("cle-dolibarr");
    }

    @Test
    void traiteNullCommeUneCarteVide() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }
}
```

- [ ] **Step 2: Lancer les tests et confirmer qu'ils échouent**

```bash
./mvnw test -Dtest='Encrypted*ConverterTest'
```

Attendu : échec de compilation, les converters n'existent pas.

- [ ] **Step 3: Implémenter `EncryptedStringConverter`**

Créer `backend/src/main/java/com/leadflow/common/EncryptedStringConverter.java` :

```java
package com.leadflow.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Chiffre une colonne texte de facon transparente pour l'entite.
 *
 * <p>Le bean est un {@code @Component} : Spring Boot installe {@code SpringBeanContainer}
 * dans Hibernate, ce qui permet a ce converter de recevoir {@link SecretCipher} par
 * injection plutot que d'etre instancie par un constructeur sans argument. Ce cablage est
 * implicite, d'ou le test d'integration dedie en tache 3.
 */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final SecretCipher cipher;

    public EncryptedStringConverter(SecretCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : cipher.decrypt(dbData);
    }
}
```

- [ ] **Step 4: Implémenter `EncryptedJsonConverter`**

Créer `backend/src/main/java/com/leadflow/common/EncryptedJsonConverter.java` :

```java
package com.leadflow.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Serialise une carte de reglages en JSON puis la chiffre.
 *
 * <p>Utilise pour {@code client.crm_config}, dont les cles dependent du fournisseur ERP :
 * les figer en colonnes casserait la promesse d'ajouter un ERP sans migration.
 */
@Converter
@Component
public class EncryptedJsonConverter implements AttributeConverter<Map<String, String>, String> {

    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {};

    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;

    public EncryptedJsonConverter(SecretCipher cipher, ObjectMapper objectMapper) {
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        Map<String, String> valeur = attribute == null ? Map.of() : attribute;
        try {
            return cipher.encrypt(objectMapper.writeValueAsString(valeur));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Echec de serialisation de la configuration CRM", e);
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new java.util.HashMap<>();
        }
        try {
            return objectMapper.readValue(cipher.decrypt(dbData), TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Echec de lecture de la configuration CRM", e);
        }
    }
}
```

- [ ] **Step 5: Lancer les tests et confirmer qu'ils passent**

```bash
./mvnw test -Dtest='Encrypted*ConverterTest'
```

Attendu : 6 tests passent.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/leadflow/common/EncryptedStringConverter.java \
        backend/src/main/java/com/leadflow/common/EncryptedJsonConverter.java \
        backend/src/test/java/com/leadflow/common/EncryptedStringConverterTest.java \
        backend/src/test/java/com/leadflow/common/EncryptedJsonConverterTest.java
git commit -m "feat: converters JPA de chiffrement des colonnes sensibles"
```

---

## Task 3: Migration V2 et entités du tenant

La plus grosse tâche du plan : elle crée les cinq tables d'un coup, puis mappe les deux premières entités. Les tables restantes sont mappées aux tâches 4 et 5. C'est sans risque : `ddl-auto: validate` vérifie que chaque entité correspond à une table, jamais que chaque table a une entité.

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__multi_tenant_schema.sql`
- Create: `backend/src/main/java/com/leadflow/common/BaseEntity.java`
- Create: `backend/src/main/java/com/leadflow/tenant/AssignmentStrategyType.java`
- Create: `backend/src/main/java/com/leadflow/tenant/Client.java`
- Create: `backend/src/main/java/com/leadflow/tenant/SalesRep.java`
- Create: `backend/src/main/java/com/leadflow/tenant/ClientRepository.java`
- Create: `backend/src/main/java/com/leadflow/tenant/SalesRepRepository.java`
- Create: `backend/src/main/java/com/leadflow/tenant/package-info.java`
- Test: `backend/src/test/java/com/leadflow/tenant/ClientPersistenceTest.java`
- Test: `backend/src/test/java/com/leadflow/tenant/SalesRepConstraintTest.java`

**Interfaces:**
- Consumes: `EncryptedStringConverter`, `EncryptedJsonConverter`.
- Produces: `BaseEntity` avec `getId(): UUID`, `getCreatedAt(): Instant`, `getUpdatedAt(): Instant` ; `Client` avec `getPublicKey/setPublicKey`, `getName/setName`, `getHmacSecret/setHmacSecret`, `getCrmProviderId/setCrmProviderId`, `getCrmConfig/setCrmConfig` (type `Map<String, String>`), `getAssignmentStrategy/setAssignmentStrategy`, `getScoringConfig/setScoringConfig` (type `Map<String, Object>`), `isActive/setActive` ; `SalesRep` avec `getClient/setClient` (type `Client`), `getFullName/setFullName`, `getEmail/setEmail`, `getCrmRef/setCrmRef`, `getSector/setSector`, `getZone/setZone`, `isActive/setActive` ; `ClientRepository.findByPublicKeyAndActiveTrue(String): Optional<Client>` ; `SalesRepRepository.findByClientIdAndActiveTrue(UUID): List<SalesRep>`.

- [ ] **Step 1: Écrire la migration**

Créer `backend/src/main/resources/db/migration/V2__multi_tenant_schema.sql` :

```sql
-- Schema metier multi-tenant. L'ordre de creation est impose par les cles etrangeres :
-- client, puis sales_rep, puis l'evolution de raw_lead_event, puis lead, puis les traces
-- de synchronisation.

CREATE TABLE client (
    id                  UUID         PRIMARY KEY,
    public_key          VARCHAR(64)  NOT NULL UNIQUE,
    name                VARCHAR(160) NOT NULL,
    -- Chiffre AES-256-GCM par l'application. Jamais lisible en SQL.
    hmac_secret         TEXT         NOT NULL,
    -- Cle du connecteur, resolue au runtime par CrmConnectorRegistry. Volontairement
    -- sans contrainte CHECK : ajouter un ERP ne doit demander aucune migration.
    crm_provider_id     VARCHAR(40)  NOT NULL,
    -- Document JSON chiffre : parametres de connexion a l'instance ERP de ce client.
    crm_config          TEXT         NOT NULL,
    assignment_strategy VARCHAR(32)  NOT NULL DEFAULT 'ROUND_ROBIN'
        CONSTRAINT ck_client_assignment_strategy
        CHECK (assignment_strategy IN ('ROUND_ROBIN', 'GEOGRAPHIC', 'SECTOR')),
    -- Regles de scoring propres au client. Non chiffre : ce n'est pas un secret.
    -- La forme du document est definie par F3.
    scoring_config      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sales_rep (
    id         UUID         PRIMARY KEY,
    client_id  UUID         NOT NULL REFERENCES client (id) ON DELETE CASCADE,
    full_name  VARCHAR(160) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    -- Reference du commercial dans l'ERP du client, inconnue tant qu'elle n'est pas
    -- resolue (F4/F5).
    crm_ref    VARCHAR(64),
    sector     VARCHAR(80),
    zone       VARCHAR(80),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_sales_rep_client_email UNIQUE (client_id, email)
);

CREATE INDEX idx_sales_rep_client_active ON sales_rep (client_id, active);

-- raw_lead_event existe depuis V1. On lui ajoute son tenant. La colonne est posee
-- directement NOT NULL : la table est vide et rien n'est deploye.
ALTER TABLE raw_lead_event
    ADD COLUMN client_id UUID NOT NULL REFERENCES client (id);

CREATE INDEX idx_raw_lead_event_client_received ON raw_lead_event (client_id, received_at DESC);

CREATE TABLE lead (
    id                    UUID         PRIMARY KEY,
    client_id             UUID         NOT NULL REFERENCES client (id),
    -- Unique : un evenement brut ne peut produire qu'un seul lead. C'est la base, et
    -- non le consommateur RabbitMQ, qui garantit l'idempotence de la qualification.
    raw_event_id          UUID         NOT NULL UNIQUE REFERENCES raw_lead_event (id),
    company_name          VARCHAR(160),
    first_name            VARCHAR(80),
    last_name             VARCHAR(80),
    email                 VARCHAR(255) NOT NULL,
    phone                 VARCHAR(32),
    message               TEXT,
    detected_intent       VARCHAR(64),
    -- Trace quel analyseur a repondu : rend le mode degrade observable.
    intent_source         VARCHAR(32)
        CONSTRAINT ck_lead_intent_source CHECK (intent_source IN ('RULES', 'GEMINI')),
    score                 INTEGER      NOT NULL DEFAULT 0,
    status                VARCHAR(32)  NOT NULL
        CONSTRAINT ck_lead_status
        CHECK (status IN ('QUALIFIED', 'ROUTED', 'SYNCED', 'REJECTED', 'FAILED')),
    assigned_sales_rep_id UUID         REFERENCES sales_rep (id),
    country_code          VARCHAR(2),
    sector                VARCHAR(80),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Deduplication de F3 : meme client, meme email, dans une fenetre temporelle.
CREATE INDEX idx_lead_client_email_created ON lead (client_id, email, created_at DESC);
-- Compteurs du dashboard de F6.
CREATE INDEX idx_lead_client_status ON lead (client_id, status);

CREATE TABLE crm_sync_attempt (
    id              UUID        PRIMARY KEY,
    lead_id         UUID        NOT NULL REFERENCES lead (id) ON DELETE CASCADE,
    -- Porte par la ligne et non deduit du client : si un client change d'ERP,
    -- l'historique reste lisible.
    provider_id     VARCHAR(40) NOT NULL,
    status          VARCHAR(32) NOT NULL
        CONSTRAINT ck_crm_sync_attempt_status CHECK (status IN ('SUCCESS', 'FAILED')),
    -- Typees en texte : Dolibarr et Odoo renvoient des entiers, d'autres ERP des UUID.
    account_ref     VARCHAR(64),
    contact_ref     VARCHAR(64),
    opportunity_ref VARCHAR(64),
    task_ref        VARCHAR(64),
    error_message   TEXT,
    attempted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_crm_sync_attempt_lead ON crm_sync_attempt (lead_id, attempted_at DESC);
```

- [ ] **Step 2: Écrire `BaseEntity`**

Créer `backend/src/main/java/com/leadflow/common/BaseEntity.java` :

```java
package com.leadflow.common;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Plomberie commune aux entites : identifiant et horodatages.
 *
 * <p>L'identifiant est un UUID ordonne dans le temps, genere cote application. Deux
 * consequences : les index ne se fragmentent pas comme avec un UUID aleatoire, et
 * l'identifiant existe avant l'insertion — ce dont la capture aura besoin en F2 pour
 * publier sur RabbitMQ sans aller-retour en base.
 */
@MappedSuperclass
@Getter
public abstract class BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

Noter l'usage de `@Getter` seul, jamais `@Data` ni `@EqualsAndHashCode` : sur une entité JPA, un `equals` généré sur tous les champs déclenche le chargement des associations paresseuses et casse le contrat de `hashCode` entre l'état transitoire et l'état persistant.

- [ ] **Step 3: Écrire l'énumération et les entités du tenant**

Créer `backend/src/main/java/com/leadflow/tenant/AssignmentStrategyType.java` :

```java
package com.leadflow.tenant;

/**
 * Strategie d'attribution des leads aux commerciaux. Choisie par client, consommee par
 * la couche routing en F4.
 */
public enum AssignmentStrategyType {
    ROUND_ROBIN,
    GEOGRAPHIC,
    SECTOR
}
```

Créer `backend/src/main/java/com/leadflow/tenant/Client.java` :

```java
package com.leadflow.tenant;

import com.leadflow.common.BaseEntity;
import com.leadflow.common.EncryptedJsonConverter;
import com.leadflow.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Le tenant : un site client de l'agence. Porte tout ce qui lui est propre — son secret
 * de signature, l'ERP qu'il vise et les parametres de connexion a SON instance.
 */
@Entity
@Table(name = "client")
@Getter
@Setter
public class Client extends BaseEntity {

    /** Cle publique figurant dans l'URL du webhook. Non secrete, revocable. */
    @Column(name = "public_key", nullable = false, unique = true, length = 64)
    private String publicKey;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "hmac_secret", nullable = false, columnDefinition = "text")
    private String hmacSecret;

    @Column(name = "crm_provider_id", nullable = false, length = 40)
    private String crmProviderId;

    /** Reglages de connexion a l'ERP du client. Les cles dependent du fournisseur. */
    @Convert(converter = EncryptedJsonConverter.class)
    @Column(name = "crm_config", nullable = false, columnDefinition = "text")
    private Map<String, String> crmConfig = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_strategy", nullable = false, length = 32)
    private AssignmentStrategyType assignmentStrategy = AssignmentStrategyType.ROUND_ROBIN;

    /** Regles de scoring. Forme definie par F3 ; volontairement non typee ici. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scoring_config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> scoringConfig = new HashMap<>();

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
```

Créer `backend/src/main/java/com/leadflow/tenant/SalesRep.java` :

```java
package com.leadflow.tenant;

import com.leadflow.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Commercial rattache a un client. L'association vers {@link Client} est autorisee car
 * les deux entites vivent dans le meme package ; entre packages, on porte l'identifiant.
 */
@Entity
@Table(name = "sales_rep",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sales_rep_client_email",
                columnNames = {"client_id", "email"}))
@Getter
@Setter
public class SalesRep extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** Reference du commercial dans l'ERP du client, nulle tant qu'elle n'est pas resolue. */
    @Column(name = "crm_ref", length = 64)
    private String crmRef;

    @Column(name = "sector", length = 80)
    private String sector;

    @Column(name = "zone", length = 80)
    private String zone;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
```

- [ ] **Step 4: Écrire les repositories et le `package-info`**

Créer `backend/src/main/java/com/leadflow/tenant/ClientRepository.java` :

```java
package com.leadflow.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, UUID> {

    /**
     * Resout le client emetteur d'un webhook depuis la cle publique de son URL. Un client
     * desactive est introuvable : c'est ainsi qu'on coupe un site sans supprimer ses donnees.
     */
    Optional<Client> findByPublicKeyAndActiveTrue(String publicKey);
}
```

Créer `backend/src/main/java/com/leadflow/tenant/SalesRepRepository.java` :

```java
package com.leadflow.tenant;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesRepRepository extends JpaRepository<SalesRep, UUID> {

    /** Commerciaux eligibles a une attribution, consomme par la couche routing en F4. */
    List<SalesRep> findByClientIdAndActiveTrue(UUID clientId);
}
```

Créer `backend/src/main/java/com/leadflow/tenant/package-info.java` :

```java
/**
 * Donnees de reference multi-tenant : le client de l'agence et ses commerciaux.
 *
 * <p>Ce package n'est pas une etape du pipeline. Il porte ce qui parametre toutes les
 * etapes : secret de signature verifie par {@code capture}, regles de scoring lues par
 * {@code qualification}, strategie d'attribution appliquee par {@code routing},
 * coordonnees de l'ERP utilisees par {@code crm}.
 */
package com.leadflow.tenant;
```

- [ ] **Step 5: Écrire les tests de persistance, qui doivent échouer**

Créer `backend/src/test/java/com/leadflow/tenant/ClientPersistenceTest.java` :

```java
package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Utilise {@code @SpringBootTest} et non {@code @DataJpaTest} : la tranche JPA n'inclut
 * pas les beans {@code @Component}, donc les converters de chiffrement ne seraient pas
 * cables et le test ne prouverait rien.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ClientPersistenceTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Client nouveauClient(String publicKey) {
        Client client = new Client();
        client.setPublicKey(publicKey);
        client.setName("Site de demonstration");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081", "apiKey", "cle-dolibarr"));
        client.setScoringConfig(Map.of("seuilChaud", 80));
        return client;
    }

    @Test
    void attribueUnIdentifiantEtDesHorodatages() {
        Client enregistre = clientRepository.saveAndFlush(nouveauClient("cle-publique-1"));

        assertThat(enregistre.getId()).isNotNull();
        assertThat(enregistre.getCreatedAt()).isNotNull();
        assertThat(enregistre.getUpdatedAt()).isNotNull();
    }

    @Test
    void relitLeSecretEtLaConfigurationEnClair() {
        Client enregistre = clientRepository.saveAndFlush(nouveauClient("cle-publique-2"));
        clientRepository.flush();

        Client relu = clientRepository.findById(enregistre.getId()).orElseThrow();

        assertThat(relu.getHmacSecret()).isEqualTo("secret-de-signature");
        assertThat(relu.getCrmConfig()).containsEntry("apiKey", "cle-dolibarr");
        assertThat(relu.getAssignmentStrategy()).isEqualTo(AssignmentStrategyType.ROUND_ROBIN);
    }

    @Test
    void neStockeAucunSecretEnClairDansLaBase() {
        Client enregistre = clientRepository.saveAndFlush(nouveauClient("cle-publique-3"));

        String secretEnBase = jdbcTemplate.queryForObject(
                "SELECT hmac_secret FROM client WHERE id = ?", String.class, enregistre.getId());
        String configEnBase = jdbcTemplate.queryForObject(
                "SELECT crm_config FROM client WHERE id = ?", String.class, enregistre.getId());

        assertThat(secretEnBase).doesNotContain("secret-de-signature");
        assertThat(configEnBase).doesNotContain("cle-dolibarr").doesNotContain("apiKey");
    }

    @Test
    void resoutUnClientParSaClePublique() {
        clientRepository.saveAndFlush(nouveauClient("cle-publique-4"));

        assertThat(clientRepository.findByPublicKeyAndActiveTrue("cle-publique-4")).isPresent();
    }

    @Test
    void ignoreUnClientDesactive() {
        Client desactive = nouveauClient("cle-publique-5");
        desactive.setActive(false);
        clientRepository.saveAndFlush(desactive);

        assertThat(clientRepository.findByPublicKeyAndActiveTrue("cle-publique-5")).isEmpty();
    }
}
```

Le test `neStockeAucunSecretEnClairDansLaBase` est celui qui couvre le risque de câblage `SpringBeanContainer` : si Hibernate instanciait le converter sans injection, l'écriture échouerait en `NullPointerException` avant même l'assertion.

Créer `backend/src/test/java/com/leadflow/tenant/SalesRepConstraintTest.java` :

```java
package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SalesRepConstraintTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private SalesRepRepository salesRepRepository;

    private Client clientEnregistre(String publicKey) {
        Client client = new Client();
        client.setPublicKey(publicKey);
        client.setName("Site de demonstration");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081"));
        return clientRepository.saveAndFlush(client);
    }

    private SalesRep commercial(Client client, String email) {
        SalesRep rep = new SalesRep();
        rep.setClient(client);
        rep.setFullName("Amina Bensalem");
        rep.setEmail(email);
        return rep;
    }

    @Test
    void refuseDeuxCommerciauxDeMemeEmailChezUnMemeClient() {
        Client client = clientEnregistre("cle-contrainte-1");
        salesRepRepository.saveAndFlush(commercial(client, "amina@exemple.test"));

        assertThatThrownBy(
                        () -> salesRepRepository.saveAndFlush(commercial(client, "amina@exemple.test")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accepteLeMemeEmailChezDeuxClientsDifferents() {
        Client premier = clientEnregistre("cle-contrainte-2");
        Client second = clientEnregistre("cle-contrainte-3");

        salesRepRepository.saveAndFlush(commercial(premier, "partage@exemple.test"));
        salesRepRepository.saveAndFlush(commercial(second, "partage@exemple.test"));

        assertThat(salesRepRepository.findByClientIdAndActiveTrue(second.getId())).hasSize(1);
    }

    @Test
    void ignoreUnCommercialDesactive() {
        Client client = clientEnregistre("cle-contrainte-4");
        SalesRep inactif = commercial(client, "inactif@exemple.test");
        inactif.setActive(false);
        salesRepRepository.saveAndFlush(inactif);

        assertThat(salesRepRepository.findByClientIdAndActiveTrue(client.getId())).isEmpty();
    }

    @Test
    void supprimerUnClientSupprimeSesCommerciaux() {
        Client client = clientEnregistre("cle-contrainte-5");
        salesRepRepository.saveAndFlush(commercial(client, "cascade@exemple.test"));
        UUID clientId = client.getId();

        clientRepository.delete(client);
        clientRepository.flush();

        assertThat(salesRepRepository.findByClientIdAndActiveTrue(clientId)).isEmpty();
    }
}
```

Le dernier test vérifie que la clé étrangère `ON DELETE CASCADE` de `V2` est bien posée. Sans lui, une erreur de migration laissant la contrainte en `NO ACTION` ne se manifesterait qu'au premier retrait de client en production.

Ajouter l'import correspondant en tête du fichier : `import java.util.UUID;`

- [ ] **Step 6: Lancer les tests et confirmer qu'ils échouent**

```bash
./mvnw test -Dtest='ClientPersistenceTest,SalesRepConstraintTest'
```

Attendu : échec. Si les classes existent déjà à ce stade — elles ont été créées aux étapes 2 à 4 — l'échec doit venir de la base, pas de la compilation.

- [ ] **Step 7: Lancer toute la suite et confirmer qu'elle passe**

```bash
./mvnw test
```

Attendu : succès complet. Deux échecs typiques à ce stade :

- `Schema-validation: missing column` → une annotation `@Column` ne correspond pas au SQL de `V2`. Corriger le mapping, jamais la migration déjà écrite dans le même commit — sauf si c'est bien le SQL qui est faux, auquel cas la corriger est légitime tant que la migration n'a pas été appliquée ailleurs.
- `NullPointerException` dans `EncryptedStringConverter` → le converter n'a pas reçu `SecretCipher`. Vérifier qu'il porte bien `@Component` **et** `@Converter`.

- [ ] **Step 8: Vérifier que les migrations s'appliquent sur une base vierge**

```bash
docker compose down -v
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Attendu : démarrage sans erreur, journal Flyway indiquant `Migrating schema "public" to version "2 - multi tenant schema"`. Arrêter l'application ensuite.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration/V2__multi_tenant_schema.sql \
        backend/src/main/java/com/leadflow/common/BaseEntity.java \
        backend/src/main/java/com/leadflow/tenant/ \
        backend/src/test/java/com/leadflow/tenant/
git commit -m "feat: schema multi-tenant et entites client et commercial"
```

---

## Task 4: Entités de capture et de qualification

**Files:**
- Create: `backend/src/main/java/com/leadflow/capture/RawLeadEventStatus.java`
- Create: `backend/src/main/java/com/leadflow/capture/RawLeadEvent.java`
- Create: `backend/src/main/java/com/leadflow/capture/RawLeadEventRepository.java`
- Create: `backend/src/main/java/com/leadflow/qualification/LeadStatus.java`
- Create: `backend/src/main/java/com/leadflow/qualification/IntentSource.java`
- Create: `backend/src/main/java/com/leadflow/qualification/Lead.java`
- Create: `backend/src/main/java/com/leadflow/qualification/LeadRepository.java`
- Test: `backend/src/test/java/com/leadflow/qualification/LeadPersistenceTest.java`

**Interfaces:**
- Consumes: `Client` et `ClientRepository` de la tâche 3.
- Produces: `RawLeadEvent` avec `getClientId/setClientId` (type `UUID`), `getSource/setSource`, `getPayload/setPayload` (type `Map<String, Object>`), `getSignature/setSignature`, `getReceivedAt/setReceivedAt`, `getPublishedAt/setPublishedAt`, `getStatus/setStatus`, `getFailureReason/setFailureReason` ; `Lead` avec `getClientId/setClientId`, `getRawEventId/setRawEventId`, `getEmail/setEmail`, `getStatus/setStatus`, `getIntentSource/setIntentSource`, `getScore/setScore`, `getAssignedSalesRepId/setAssignedSalesRepId` et les champs d'identité ; `LeadRepository.findByRawEventId(UUID): Optional<Lead>` ; `LeadRepository.existsByClientIdAndEmailAndCreatedAtAfter(UUID, String, Instant): boolean`.

- [ ] **Step 1: Écrire l'entité de capture**

`raw_lead_event` a été créée par `V1` **sans** `created_at` ni `updated_at`, donc elle n'hérite pas de `BaseEntity` : elle déclare son propre identifiant et son propre horodatage `received_at`.

Créer `backend/src/main/java/com/leadflow/capture/RawLeadEventStatus.java` :

```java
package com.leadflow.capture;

/** Cycle de vie d'un evenement brut, de sa reception a sa publication sur le broker. */
public enum RawLeadEventStatus {
    RECEIVED,
    PUBLISHED,
    FAILED
}
```

Créer `backend/src/main/java/com/leadflow/capture/RawLeadEvent.java` :

```java
package com.leadflow.capture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Journal d'entree du middleware : le payload webhook tel qu'il a ete recu, avant toute
 * qualification. C'est ce qui permet de rejouer un lead perdu ou mal traite.
 *
 * <p>N'herite pas de {@code BaseEntity} : la table vient de V1 et porte {@code received_at}
 * plutot que le couple {@code created_at} / {@code updated_at}.
 */
@Entity
@Table(name = "raw_lead_event")
@Getter
@Setter
public class RawLeadEvent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Tenant emetteur. Identifiant brut : {@code tenant} est un autre package. */
    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    /** Canal d'origine chez ce client : formulaire de contact, demande de devis... */
    @Column(name = "source", nullable = false, length = 120)
    private String source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "signature", nullable = false, length = 255)
    private String signature;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RawLeadEventStatus status = RawLeadEventStatus.RECEIVED;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @PrePersist
    void onCreate() {
        if (this.receivedAt == null) {
            this.receivedAt = Instant.now();
        }
    }
}
```

Créer `backend/src/main/java/com/leadflow/capture/RawLeadEventRepository.java` :

```java
package com.leadflow.capture;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawLeadEventRepository extends JpaRepository<RawLeadEvent, UUID> {

    /** Evenements en echec d'un client, pour l'ecran de rejeu du dashboard en F6. */
    List<RawLeadEvent> findByClientIdAndStatusOrderByReceivedAtDesc(
            UUID clientId, RawLeadEventStatus status);
}
```

- [ ] **Step 2: Écrire les énumérations et l'entité de qualification**

Créer `backend/src/main/java/com/leadflow/qualification/LeadStatus.java` :

```java
package com.leadflow.qualification;

/**
 * Cycle de vie d'un lead qualifie. Il nait {@code QUALIFIED} puisque c'est la
 * qualification qui l'ecrit ; {@code REJECTED} couvre le doublon et les donnees
 * invalides, {@code FAILED} un echec de traitement en aval.
 */
public enum LeadStatus {
    QUALIFIED,
    ROUTED,
    SYNCED,
    REJECTED,
    FAILED
}
```

Créer `backend/src/main/java/com/leadflow/qualification/IntentSource.java` :

```java
package com.leadflow.qualification;

/**
 * Analyseur ayant produit l'intention detectee. Rend le basculement en mode degrade
 * observable au lieu d'etre seulement affirme.
 */
public enum IntentSource {
    RULES,
    GEMINI
}
```

Créer `backend/src/main/java/com/leadflow/qualification/Lead.java` :

```java
package com.leadflow.qualification;

import com.leadflow.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Lead qualifie : l'evenement brut apres nettoyage, deduplication, analyse d'intention et
 * scoring.
 *
 * <p>Les references vers {@code client}, {@code raw_lead_event} et {@code sales_rep} sont
 * portees en identifiants bruts et non en associations : ces entites vivent dans d'autres
 * packages, et le typage direct rendrait {@code qualification} dependant de {@code tenant}
 * et de {@code capture}.
 */
@Entity
@Table(name = "lead")
@Getter
@Setter
public class Lead extends BaseEntity {

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    /** Unique en base : un evenement brut ne peut produire qu'un seul lead. */
    @Column(name = "raw_event_id", nullable = false, unique = true)
    private UUID rawEventId;

    @Column(name = "company_name", length = 160)
    private String companyName;

    @Column(name = "first_name", length = 80)
    private String firstName;

    @Column(name = "last_name", length = 80)
    private String lastName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @Column(name = "detected_intent", length = 64)
    private String detectedIntent;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent_source", length = 32)
    private IntentSource intentSource;

    @Column(name = "score", nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LeadStatus status = LeadStatus.QUALIFIED;

    @Column(name = "assigned_sales_rep_id")
    private UUID assignedSalesRepId;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "sector", length = 80)
    private String sector;
}
```

Créer `backend/src/main/java/com/leadflow/qualification/LeadRepository.java` :

```java
package com.leadflow.qualification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    /** Verifie qu'un evenement brut rejoue n'a pas deja produit son lead. */
    Optional<Lead> findByRawEventId(UUID rawEventId);

    /** Deduplication de F3 : meme client, meme email, dans une fenetre temporelle. */
    boolean existsByClientIdAndEmailAndCreatedAtAfter(UUID clientId, String email, Instant depuis);
}
```

- [ ] **Step 3: Écrire le test de persistance, qui doit échouer**

Créer `backend/src/test/java/com/leadflow/qualification/LeadPersistenceTest.java` :

```java
package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadPersistenceTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private RawLeadEventRepository rawLeadEventRepository;

    @Autowired
    private LeadRepository leadRepository;

    private Client clientEnregistre(String publicKey) {
        Client client = new Client();
        client.setPublicKey(publicKey);
        client.setName("Site de demonstration");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081"));
        return clientRepository.saveAndFlush(client);
    }

    private RawLeadEvent evenementEnregistre(UUID clientId) {
        RawLeadEvent event = new RawLeadEvent();
        event.setClientId(clientId);
        event.setSource("formulaire-contact");
        event.setPayload(Map.of("email", "prospect@exemple.test", "message", "Demande de devis"));
        event.setSignature("signature-hmac");
        return rawLeadEventRepository.saveAndFlush(event);
    }

    private Lead lead(UUID clientId, UUID rawEventId) {
        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail("prospect@exemple.test");
        lead.setDetectedIntent("DEMANDE_DEVIS");
        lead.setIntentSource(IntentSource.RULES);
        lead.setScore(72);
        return lead;
    }

    @Test
    void enregistreUnLeadAvecSonStatutParDefaut() {
        Client client = clientEnregistre("cle-lead-1");
        RawLeadEvent event = evenementEnregistre(client.getId());

        Lead enregistre = leadRepository.saveAndFlush(lead(client.getId(), event.getId()));

        assertThat(enregistre.getId()).isNotNull();
        assertThat(enregistre.getStatus()).isEqualTo(LeadStatus.QUALIFIED);
        assertThat(enregistre.getIntentSource()).isEqualTo(IntentSource.RULES);
    }

    @Test
    void conserveLePayloadJsonDeLEvenementBrut() {
        Client client = clientEnregistre("cle-lead-2");
        RawLeadEvent event = evenementEnregistre(client.getId());

        RawLeadEvent relu = rawLeadEventRepository.findById(event.getId()).orElseThrow();

        assertThat(relu.getPayload()).containsEntry("email", "prospect@exemple.test");
        assertThat(relu.getReceivedAt()).isNotNull();
    }

    @Test
    void refuseDeuxLeadsPourUnMemeEvenementBrut() {
        Client client = clientEnregistre("cle-lead-3");
        RawLeadEvent event = evenementEnregistre(client.getId());
        leadRepository.saveAndFlush(lead(client.getId(), event.getId()));

        assertThatThrownBy(
                        () -> leadRepository.saveAndFlush(lead(client.getId(), event.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void detecteUnDoublonDansLaFenetreDeDeduplication() {
        Client client = clientEnregistre("cle-lead-4");
        RawLeadEvent event = evenementEnregistre(client.getId());
        leadRepository.saveAndFlush(lead(client.getId(), event.getId()));

        boolean doublon = leadRepository.existsByClientIdAndEmailAndCreatedAtAfter(
                client.getId(), "prospect@exemple.test", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(doublon).isTrue();
    }

    @Test
    void retrouveUnLeadParSonEvenementBrut() {
        Client client = clientEnregistre("cle-lead-5");
        RawLeadEvent event = evenementEnregistre(client.getId());
        leadRepository.saveAndFlush(lead(client.getId(), event.getId()));

        assertThat(leadRepository.findByRawEventId(event.getId())).isPresent();
    }
}
```

- [ ] **Step 4: Lancer le test et confirmer qu'il passe**

```bash
./mvnw test -Dtest=LeadPersistenceTest
```

Attendu : 5 tests passent.

Piège connu : `lead` est un **mot réservé** dans certains dialectes SQL. Sur PostgreSQL il ne l'est pas, donc `@Table(name = "lead")` fonctionne sans échappement. Si une erreur de syntaxe apparaît autour du nom de table, la corriger par `@Table(name = "\"lead\"")` **et** par des guillemets dans `V2`, pas seulement d'un côté.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/capture/ \
        backend/src/main/java/com/leadflow/qualification/ \
        backend/src/test/java/com/leadflow/qualification/
git commit -m "feat: entites evenement brut et lead qualifie"
```

---

## Task 5: Trace de synchronisation ERP

**Files:**
- Create: `backend/src/main/java/com/leadflow/crm/CrmSyncAttemptStatus.java`
- Create: `backend/src/main/java/com/leadflow/crm/CrmSyncAttempt.java`
- Create: `backend/src/main/java/com/leadflow/crm/CrmSyncAttemptRepository.java`
- Test: `backend/src/test/java/com/leadflow/crm/CrmSyncAttemptPersistenceTest.java`

**Interfaces:**
- Consumes: `Lead`, `LeadRepository`, `RawLeadEvent`, `RawLeadEventRepository`, `Client`, `ClientRepository`.
- Produces: `CrmSyncAttempt` avec `getLeadId/setLeadId`, `getProviderId/setProviderId`, `getStatus/setStatus`, `getAccountRef/setAccountRef`, `getContactRef/setContactRef`, `getOpportunityRef/setOpportunityRef`, `getTaskRef/setTaskRef`, `getErrorMessage/setErrorMessage`, `getAttemptedAt` ; `CrmSyncAttemptRepository.findFirstByLeadIdAndStatusOrderByAttemptedAtDesc(UUID, CrmSyncAttemptStatus): Optional<CrmSyncAttempt>`.

- [ ] **Step 1: Écrire l'énumération, l'entité et le repository**

Créer `backend/src/main/java/com/leadflow/crm/CrmSyncAttemptStatus.java` :

```java
package com.leadflow.crm;

/** Issue d'une tentative de synchronisation vers un ERP. */
public enum CrmSyncAttemptStatus {
    SUCCESS,
    FAILED
}
```

Créer `backend/src/main/java/com/leadflow/crm/CrmSyncAttempt.java` :

```java
package com.leadflow.crm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Trace d'une tentative de synchronisation vers l'ERP d'un client.
 *
 * <p>Table append-only : chaque tentative ajoute une ligne, aucune n'est modifiee. C'est
 * le support de l'idempotence des adaptateurs en F5 — avant de creer quoi que ce soit,
 * l'adaptateur relit la derniere tentative reussie pour savoir ce qui existe deja.
 *
 * <p>N'herite pas de {@code BaseEntity} : une ligne n'etant jamais modifiee,
 * {@code updated_at} n'aurait aucun sens. Elle porte {@code attempted_at} a la place.
 */
@Entity
@Table(name = "crm_sync_attempt")
@Getter
@Setter
public class CrmSyncAttempt {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    /** Porte par la ligne : un client peut changer d'ERP sans rendre l'historique illisible. */
    @Column(name = "provider_id", nullable = false, length = 40)
    private String providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CrmSyncAttemptStatus status;

    @Column(name = "account_ref", length = 64)
    private String accountRef;

    @Column(name = "contact_ref", length = 64)
    private String contactRef;

    @Column(name = "opportunity_ref", length = 64)
    private String opportunityRef;

    @Column(name = "task_ref", length = 64)
    private String taskRef;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    @PrePersist
    void onCreate() {
        if (this.attemptedAt == null) {
            this.attemptedAt = Instant.now();
        }
    }
}
```

Créer `backend/src/main/java/com/leadflow/crm/CrmSyncAttemptRepository.java` :

```java
package com.leadflow.crm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrmSyncAttemptRepository extends JpaRepository<CrmSyncAttempt, UUID> {

    /**
     * Derniere tentative reussie d'un lead. C'est la source des references deja obtenues
     * dans l'ERP, que l'adaptateur consulte avant toute creation pour ne pas produire de
     * doublon lors d'un rejeu.
     */
    Optional<CrmSyncAttempt> findFirstByLeadIdAndStatusOrderByAttemptedAtDesc(
            UUID leadId, CrmSyncAttemptStatus status);

    /** Historique complet d'un lead, pour l'ecran de diagnostic du dashboard en F6. */
    List<CrmSyncAttempt> findByLeadIdOrderByAttemptedAtDesc(UUID leadId);
}
```

- [ ] **Step 2: Écrire le test, qui doit échouer**

Créer `backend/src/test/java/com/leadflow/crm/CrmSyncAttemptPersistenceTest.java` :

```java
package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CrmSyncAttemptPersistenceTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private RawLeadEventRepository rawLeadEventRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private CrmSyncAttemptRepository attemptRepository;

    private UUID leadEnregistre(String publicKey) {
        Client client = new Client();
        client.setPublicKey(publicKey);
        client.setName("Site de demonstration");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081"));
        Client enregistre = clientRepository.saveAndFlush(client);

        RawLeadEvent event = new RawLeadEvent();
        event.setClientId(enregistre.getId());
        event.setSource("formulaire-contact");
        event.setPayload(Map.of("email", "prospect@exemple.test"));
        event.setSignature("signature-hmac");
        RawLeadEvent evenement = rawLeadEventRepository.saveAndFlush(event);

        Lead lead = new Lead();
        lead.setClientId(enregistre.getId());
        lead.setRawEventId(evenement.getId());
        lead.setEmail("prospect@exemple.test");
        return leadRepository.saveAndFlush(lead).getId();
    }

    private CrmSyncAttempt tentative(UUID leadId, CrmSyncAttemptStatus statut, String accountRef) {
        CrmSyncAttempt attempt = new CrmSyncAttempt();
        attempt.setLeadId(leadId);
        attempt.setProviderId("dolibarr");
        attempt.setStatus(statut);
        attempt.setAccountRef(accountRef);
        return attempt;
    }

    @Test
    void enregistreUneTentativeAvecSonHorodatage() {
        UUID leadId = leadEnregistre("cle-sync-1");

        CrmSyncAttempt enregistree = attemptRepository.saveAndFlush(
                tentative(leadId, CrmSyncAttemptStatus.SUCCESS, "1042"));

        assertThat(enregistree.getId()).isNotNull();
        assertThat(enregistree.getAttemptedAt()).isNotNull();
    }

    @Test
    void retrouveLaDerniereTentativeReussieEtIgnoreLesEchecs() {
        UUID leadId = leadEnregistre("cle-sync-2");
        attemptRepository.saveAndFlush(tentative(leadId, CrmSyncAttemptStatus.SUCCESS, "1042"));
        attemptRepository.saveAndFlush(tentative(leadId, CrmSyncAttemptStatus.FAILED, null));

        CrmSyncAttempt derniere = attemptRepository
                .findFirstByLeadIdAndStatusOrderByAttemptedAtDesc(leadId, CrmSyncAttemptStatus.SUCCESS)
                .orElseThrow();

        assertThat(derniere.getAccountRef()).isEqualTo("1042");
    }

    @Test
    void conserveTouteLHistoriqueDUnLead() {
        UUID leadId = leadEnregistre("cle-sync-3");
        attemptRepository.saveAndFlush(tentative(leadId, CrmSyncAttemptStatus.FAILED, null));
        attemptRepository.saveAndFlush(tentative(leadId, CrmSyncAttemptStatus.SUCCESS, "1043"));

        assertThat(attemptRepository.findByLeadIdOrderByAttemptedAtDesc(leadId)).hasSize(2);
    }
}
```

- [ ] **Step 3: Lancer le test et confirmer qu'il passe**

```bash
./mvnw test -Dtest=CrmSyncAttemptPersistenceTest
```

Attendu : 3 tests passent.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/leadflow/crm/CrmSyncAttempt.java \
        backend/src/main/java/com/leadflow/crm/CrmSyncAttemptStatus.java \
        backend/src/main/java/com/leadflow/crm/CrmSyncAttemptRepository.java \
        backend/src/test/java/com/leadflow/crm/CrmSyncAttemptPersistenceTest.java
git commit -m "feat: trace append-only des synchronisations ERP"
```

---

## Task 6: Port `CrmConnector` multi-instance et nettoyage de la configuration

C'est la seule tâche qui touche du code déjà écrit. Elle est faisable sans risque **uniquement parce qu'aucun adaptateur n'existe encore** : en F5, elle coûterait la réécriture de deux connecteurs.

**Files:**
- Create: `backend/src/main/java/com/leadflow/crm/model/CrmTarget.java`
- Modify: `backend/src/main/java/com/leadflow/crm/CrmConnector.java`
- Modify: `backend/src/main/java/com/leadflow/crm/CrmConnectorRegistry.java`
- Modify: `backend/src/main/java/com/leadflow/config/CrmProperties.java`
- Modify: `backend/src/main/java/com/leadflow/config/WebhookProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/leadflow/crm/CrmConnectorRegistryTest.java`

**Interfaces:**
- Consumes: `Client.getCrmProviderId()` et `Client.getCrmConfig()` de la tâche 3.
- Produces: `CrmTarget(String providerId, Map<String, String> settings)` ; `CrmConnector.sync(CrmLead, CrmTarget): CrmSyncResult` ; `CrmConnectorRegistry.forProvider(String): CrmConnector` et `CrmConnectorRegistry.availableProviders(): Set<String>`.

- [ ] **Step 1: Écrire le test du registre, qui doit échouer**

Créer `backend/src/test/java/com/leadflow/crm/CrmConnectorRegistryTest.java` :

```java
package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmTarget;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrmConnectorRegistryTest {

    /** Connecteur factice : le registre ne doit dependre d'aucun adaptateur reel. */
    private static final class ConnecteurFactice implements CrmConnector {

        private final String providerId;

        private ConnecteurFactice(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public CrmSyncResult sync(CrmLead lead, CrmTarget target) {
            return new CrmSyncResult(providerId, "1", "2", "3", "4", Instant.now());
        }
    }

    private final CrmConnectorRegistry registry = new CrmConnectorRegistry(
            List.of(new ConnecteurFactice("dolibarr"), new ConnecteurFactice("odoo")));

    @Test
    void resoutUnConnecteurParSonIdentifiant() {
        assertThat(registry.forProvider("odoo").providerId()).isEqualTo("odoo");
    }

    @Test
    void listeLesFournisseursDisponibles() {
        assertThat(registry.availableProviders()).containsExactlyInAnyOrder("dolibarr", "odoo");
    }

    @Test
    void refuseUnFournisseurInconnuEnNommantLesDisponibles() {
        assertThatThrownBy(() -> registry.forProvider("salesforce"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("salesforce")
                .hasMessageContaining("dolibarr");
    }

    @Test
    void transmetLaCibleAuConnecteur() {
        CrmTarget cible = new CrmTarget("dolibarr", Map.of("baseUrl", "http://client-a:8081"));
        CrmLead lead = new CrmLead("Acme", "Amina", "Bensalem", "amina@exemple.test",
                "+212600000000", "Demande de devis", "DEMANDE_DEVIS", 72, "MA", "industrie", "7");

        CrmSyncResult resultat = registry.forProvider("dolibarr").sync(lead, cible);

        assertThat(resultat.providerId()).isEqualTo("dolibarr");
    }
}
```

- [ ] **Step 2: Lancer le test et confirmer qu'il échoue**

```bash
./mvnw test -Dtest=CrmConnectorRegistryTest
```

Attendu : échec de compilation — `CrmTarget` n'existe pas et le constructeur du registre prend encore deux arguments.

- [ ] **Step 3: Créer `CrmTarget`**

Créer `backend/src/main/java/com/leadflow/crm/model/CrmTarget.java` :

```java
package com.leadflow.crm.model;

import java.util.Map;

/**
 * Instance ERP visee par une synchronisation : le fournisseur et les reglages de
 * connexion propres au client concerne.
 *
 * <p>Volontairement sans champ {@code baseUrl} : rien ne garantit qu'un ERP futur
 * s'adresse par URL. Les cles de {@code settings} sont interpretees par l'adaptateur, qui
 * valide a son demarrage ce dont il a besoin — c'est ce qui permet d'ajouter un ERP sans
 * migration ni modification du modele pivot.
 */
public record CrmTarget(String providerId, Map<String, String> settings) {
}
```

- [ ] **Step 4: Changer la signature du port**

Dans `backend/src/main/java/com/leadflow/crm/CrmConnector.java`, ajouter l'import `com.leadflow.crm.model.CrmTarget` et remplacer la déclaration de `sync` :

```java
    /**
     * Cree ou met a jour le tiers, le contact, l'opportunite et la tache de rappel dans
     * l'instance ERP designee par {@code target}.
     *
     * @throws com.leadflow.crm.model.CrmSyncException si l'ERP refuse ou est injoignable
     */
    CrmSyncResult sync(CrmLead lead, CrmTarget target);
```

- [ ] **Step 5: Retirer `defaultConnector()` du registre**

Remplacer le contenu de `backend/src/main/java/com/leadflow/crm/CrmConnectorRegistry.java` par :

```java
package com.leadflow.crm;

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
 */
@Component
public class CrmConnectorRegistry {

    private final Map<String, CrmConnector> connectors;

    public CrmConnectorRegistry(List<CrmConnector> connectors) {
        this.connectors = connectors.stream()
                .collect(Collectors.toUnmodifiableMap(CrmConnector::providerId, Function.identity()));
    }

    public CrmConnector forProvider(String providerId) {
        CrmConnector connector = connectors.get(providerId);
        if (connector == null) {
            throw new IllegalArgumentException(
                    "Aucun connecteur CRM pour '" + providerId + "'. Disponibles : " + connectors.keySet());
        }
        return connector;
    }

    /** Fournisseurs effectivement disponibles au runtime, pour l'ecran Connecteurs. */
    public Set<String> availableProviders() {
        return connectors.keySet();
    }
}
```

- [ ] **Step 6: Alléger les propriétés typées**

Remplacer le contenu de `backend/src/main/java/com/leadflow/config/CrmProperties.java` par :

```java
package com.leadflow.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reglages techniques des adaptateurs ERP. Tout ce qui depend du client — URL de
 * l'instance, cle d'API, base, utilisateur — vit desormais sur la ligne {@code client},
 * dans son document {@code crm_config}. Ne restent ici que les reglages communs a toutes
 * les instances d'un meme fournisseur.
 */
@ConfigurationProperties(prefix = "leadflow.crm")
public record CrmProperties(Map<String, Provider> providers) {

    public record Provider(
            boolean enabled,
            Duration connectTimeout,
            Duration readTimeout) {
    }
}
```

Remplacer le contenu de `backend/src/main/java/com/leadflow/config/WebhookProperties.java` par :

```java
package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametres de verification des webhooks entrants. Le secret de signature n'est plus ici :
 * chaque client a le sien, porte par {@code client.hmac_secret}.
 */
@ConfigurationProperties(prefix = "leadflow.webhook")
public record WebhookProperties(
        String signatureHeader,
        Duration tolerance) {
}
```

- [ ] **Step 7: Nettoyer `application.yml`**

Dans `backend/src/main/resources/application.yml`, remplacer tout le bloc `leadflow:` par :

```yaml
leadflow:
  security:
    # Cle AES-256 en base64 sur 32 octets. Aucune valeur de repli : l'application
    # doit refuser de demarrer plutot que de chiffrer avec une cle devinable.
    master-key: ${LEADFLOW_MASTER_KEY:}
  webhook:
    signature-header: X-Leadflow-Signature
    # Fenetre de tolerance anti-rejeu sur l'horodatage de la requete.
    tolerance: 5m
  crm:
    # Reglages techniques uniquement. L'URL et les identifiants de chaque instance ERP
    # vivent sur la ligne client, chiffres.
    providers:
      dolibarr:
        enabled: true
        connect-timeout: 5s
        read-timeout: 15s
      odoo:
        enabled: false
        connect-timeout: 5s
        read-timeout: 15s
```

- [ ] **Step 8: Lancer toute la suite et confirmer qu'elle passe**

```bash
./mvnw test
```

Attendu : succès complet, y compris `CrmConnectorRegistryTest` avec 4 tests.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/leadflow/crm/ \
        backend/src/main/java/com/leadflow/config/CrmProperties.java \
        backend/src/main/java/com/leadflow/config/WebhookProperties.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/leadflow/crm/CrmConnectorRegistryTest.java
git commit -m "refactor: CrmConnector cible une instance ERP par client"
```

---

## Task 7: Jeu de données de démonstration

Sans cette tâche, F1 livre un schéma que rien ne peuple, et F2 démarrerait sans pouvoir signer la moindre requête de test.

Le secret du client de démonstration étant chiffré, il ne peut pas être écrit en clair dans du SQL. On produit donc les valeurs chiffrées avec la clé maître de dev, puis on les fige dans la migration. C'est un rituel manuel assumé : il est fait une fois, et il rend le jeu de démonstration reproductible sans code applicatif d'amorçage.

**Files:**
- Create: `backend/src/main/resources/db/dev/R__demo_data.sql`
- Modify: `backend/src/main/resources/application-dev.yml`
- Temporaire puis supprimé : `backend/src/test/java/com/leadflow/common/DemoSecretGenerator.java`

**Interfaces:**
- Consumes: `SecretCipher` de la tâche 1, schéma `V2` de la tâche 3.
- Produces: un client de démonstration de clé publique `demo-cd253966049ebd76243248e8`, dont le secret HMAC en clair est `c6702b700b1673ae027ce903ff753c4239522e71b21297259c396540b9e5ec19`. Ce couple sera utilisé par F2 pour signer les requêtes de test.

- [ ] **Step 1: Générer les valeurs chiffrées**

Créer temporairement `backend/src/test/java/com/leadflow/common/DemoSecretGenerator.java` :

```java
package com.leadflow.common;

import com.leadflow.config.SecurityProperties;
import org.junit.jupiter.api.Test;

/**
 * Utilitaire jetable : imprime les valeurs chiffrees a coller dans R__demo_data.sql.
 * Supprime a la fin de la tache 7.
 */
class DemoSecretGenerator {

    private static final String DEV_MASTER_KEY = "gmLN1lvFdN9MsO6NuKFrFHHVzFzWQF7bgrJYmNY8Rok=";

    @Test
    void imprimeLesValeursChiffrees() {
        SecretCipher cipher = new SecretCipher(new SecurityProperties(DEV_MASTER_KEY));

        System.out.println("hmac_secret = " + cipher.encrypt(
                "c6702b700b1673ae027ce903ff753c4239522e71b21297259c396540b9e5ec19"));
        System.out.println("crm_config  = " + cipher.encrypt(
                "{\"baseUrl\":\"http://localhost:8081/api/index.php\",\"apiKey\":\"cle-dolibarr-de-demo\"}"));
    }
}
```

Lancer :

```bash
./mvnw test -Dtest=DemoSecretGenerator
```

Relever les deux lignes imprimées dans la sortie. Elles seront collées à l'étape suivante.

- [ ] **Step 2: Écrire la migration de démonstration**

Créer `backend/src/main/resources/db/dev/R__demo_data.sql`, en remplaçant `<HMAC_CHIFFRE>` et `<CRM_CONFIG_CHIFFRE>` par les valeurs relevées à l'étape 1 :

```sql
-- Jeu de demonstration, charge uniquement sous le profil dev. La production ne voit
-- jamais ce dossier : il n'est ajoute a spring.flyway.locations que par application-dev.yml.
--
-- Migration repetable (R__) : elle se rejoue a chaque changement de son contenu, sans
-- occuper de numero de version et sans entrer en conflit avec les migrations V.
--
-- Les valeurs chiffrees ci-dessous ont ete produites avec la cle maitre de dev fixee dans
-- application-dev.yml. Changer cette cle rend ce jeu de donnees illisible : il faudrait
-- alors regenerer ces valeurs.
--
-- Secret HMAC en clair, pour signer les requetes de test en F2 :
--   c6702b700b1673ae027ce903ff753c4239522e71b21297259c396540b9e5ec19

INSERT INTO client (id, public_key, name, hmac_secret, crm_provider_id, crm_config,
                    assignment_strategy, scoring_config, active)
VALUES ('0198f3c2-0000-7000-8000-000000000001',
        'demo-cd253966049ebd76243248e8',
        'Boutique de demonstration',
        '<HMAC_CHIFFRE>',
        'dolibarr',
        '<CRM_CONFIG_CHIFFRE>',
        'ROUND_ROBIN',
        '{}'::jsonb,
        TRUE)
ON CONFLICT (id) DO UPDATE
    SET public_key      = EXCLUDED.public_key,
        name            = EXCLUDED.name,
        hmac_secret     = EXCLUDED.hmac_secret,
        crm_provider_id = EXCLUDED.crm_provider_id,
        crm_config      = EXCLUDED.crm_config,
        updated_at      = now();

INSERT INTO sales_rep (id, client_id, full_name, email, sector, zone, active)
VALUES ('0198f3c2-0000-7000-8000-000000000011',
        '0198f3c2-0000-7000-8000-000000000001',
        'Amina Bensalem', 'amina@demo.test', 'industrie', 'nord', TRUE),
       ('0198f3c2-0000-7000-8000-000000000012',
        '0198f3c2-0000-7000-8000-000000000001',
        'Karim Haddad', 'karim@demo.test', 'services', 'sud', TRUE)
ON CONFLICT (id) DO UPDATE
    SET full_name  = EXCLUDED.full_name,
        email      = EXCLUDED.email,
        sector     = EXCLUDED.sector,
        zone       = EXCLUDED.zone,
        updated_at = now();
```

L'usage de `ON CONFLICT DO UPDATE` est ce qui rend la migration réellement rejouable : sans lui, un second passage échouerait sur la clé primaire.

- [ ] **Step 3: Brancher le dossier dans le profil dev**

Ajouter dans `backend/src/main/resources/application-dev.yml` :

```yaml
spring:
  flyway:
    # Le jeu de demonstration s'ajoute aux migrations de schema, uniquement en dev.
    locations: classpath:db/migration,classpath:db/dev
```

- [ ] **Step 4: Vérifier sur une base vierge**

```bash
docker compose down -v
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Attendu : démarrage sans erreur. Puis, dans un autre terminal :

```bash
docker exec -i leadflow-postgres psql -U leadflow -d leadflow -c "SELECT public_key, name, crm_provider_id, hmac_secret FROM client;"
```

Attendu : une ligne, dont `hmac_secret` est une chaîne base64 illisible et ne contient nulle part `c6702b70`. Arrêter l'application ensuite.

- [ ] **Step 5: Supprimer l'utilitaire jetable**

```bash
rm backend/src/test/java/com/leadflow/common/DemoSecretGenerator.java
./mvnw test
```

Attendu : la suite passe toujours.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/dev/R__demo_data.sql \
        backend/src/main/resources/application-dev.yml
git commit -m "feat: jeu de donnees de demonstration charge sous le profil dev"
```

---

## Task 8: Mise à jour de `CLAUDE.md` et recette

`CLAUDE.md` est la mémoire du projet entre deux sessions. Tant qu'il annonce `sync(CrmLead)` et un secret HMAC global, la session F5 travaillera sur une information fausse. Cette tâche n'est pas de la documentation de confort : c'est le cinquième critère de recette de la spec.

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: tout ce qui précède.
- Produces: rien de logiciel.

- [ ] **Step 1: Mettre à jour la section « Base de donnees »**

Remplacer le paragraphe indiquant que seule `V1__raw_lead_event.sql` existe par :

```markdown
Deux migrations existent : `V1__raw_lead_event.sql` (journal de capture) et
`V2__multi_tenant_schema.sql` (schema metier complet — `client`, `sales_rep`, `lead`,
`crm_sync_attempt`, et l'ajout de `client_id` sur `raw_lead_event`). Le schema est
desormais complet : les features suivantes ne devraient plus avoir a le modifier.

Sous le profil `dev`, `spring.flyway.locations` inclut en plus `classpath:db/dev`, qui
contient `R__demo_data.sql` — un client de demonstration et ses commerciaux. La production
ne charge jamais ce dossier.
```

- [ ] **Step 2: Documenter le chiffrement et le multi-tenant**

Ajouter une sous-section après « Base de donnees » :

```markdown
### Multi-tenant et secrets

Chaque client (table `client`) porte son secret HMAC, l'identifiant de son connecteur ERP
et les parametres de connexion a **son** instance ERP. Rien de tout cela n'est dans
`application.yml` : ajouter un client est une insertion en base, pas un redeploiement.

Le webhook identifie le client par une cle publique placee dans l'URL
(`/api/webhooks/leads/{clientKey}`, colonne `client.public_key`). Cette cle n'est pas
secrete — c'est la signature qui authentifie — et elle est distincte de la cle primaire
pour pouvoir etre revoquee sans recreer la ligne.

`client.hmac_secret` et `client.crm_config` sont **chiffres au repos** en AES-256-GCM par
`common/SecretCipher` et deux `AttributeConverter` (`EncryptedStringConverter`,
`EncryptedJsonConverter`). La cle maitre vient de `LEADFLOW_MASTER_KEY` et n'est jamais en
base ; sa perte rend les secrets irrecuperables. Les converters sont des `@Component` :
ils recoivent `SecretCipher` par injection grace au `SpringBeanContainer` que Spring Boot
installe dans Hibernate. En consequence, **les tests de persistance utilisent
`@SpringBootTest` et non `@DataJpaTest`**, dont la tranche n'inclut pas les `@Component`.

`client.crm_config` est un document JSON chiffre plutot que des colonnes plates : ajouter
un ERP reclamant un reglage inedit ne doit demander ni migration ni modification d'entite.
Le prix assume est qu'il n'est pas requetable en SQL.
```

- [ ] **Step 3: Mettre à jour la section « Connecteurs ERP/CRM »**

Remplacer la description du port par :

```markdown
crm/
├── CrmConnector.java          port : providerId() + sync(CrmLead, CrmTarget)
├── CrmConnectorRegistry.java  resout l'adaptateur par providerId
├── CrmSyncAttempt.java        trace append-only des synchronisations
├── model/                     modele pivot : CrmLead, CrmTarget, CrmSyncResult, CrmSyncException
├── dolibarr/                  adaptateur REST
└── odoo/                      adaptateur JSON-RPC
```

Et ajouter :

```markdown
`sync` prend un `CrmTarget(providerId, settings)` decrivant **l'instance** ERP visee, car
deux clients sur le meme type d'ERP ont chacun leur serveur. Les cles de `settings`
viennent de `client.crm_config` et sont interpretees par l'adaptateur seul.

Il n'y a **pas de connecteur par defaut** : tout lead appartient a un client, et tout
client nomme son fournisseur. `CrmConnectorRegistry.defaultConnector()` et la propriete
`leadflow.crm.default-provider` ont ete supprimees.
```

- [ ] **Step 4: Mettre à jour la liste des packages et l'etat du projet**

Ajouter `tenant/` à la liste des sous-packages :

```markdown
- `tenant/` — donnees de reference multi-tenant : client et commerciaux. Seul package qui
  ne soit pas une etape du pipeline ; il porte ce qui parametre toutes les etapes.
```

Puis remplacer la section « Etat actuel » par :

```markdown
## Etat actuel

Le squelette compile de bout en bout et **le modele de donnees est complet** (F1 livree).

Ce qui existe : la configuration (Rabbit, Security, proprietes typees), le chiffrement des
secrets, les cinq entites et leurs repositories, les migrations `V1` et `V2`, le port
`CrmConnector` et son registre, le modele pivot.

Ce qui n'existe pas : **aucun comportement au runtime**. Pas d'endpoint webhook, pas de
consommateur RabbitMQ, aucun adaptateur ERP — `crm/dolibarr/` et `crm/odoo/` ne contiennent
que leur `package-info.java` — et les quatre composants de `features/` sont des
placeholders. Ne pas supposer l'existence d'un service ou d'un endpoint : verifier avant de
referencer.
```

- [ ] **Step 5: Recette complète**

```bash
docker compose down -v
docker compose up -d
cd backend && ./mvnw verify
```

Attendu : `BUILD SUCCESS`. Vérifier dans la sortie que les tests suivants sont passés : `SecretCipherTest`, `EncryptedStringConverterTest`, `EncryptedJsonConverterTest`, `ClientPersistenceTest`, `SalesRepConstraintTest`, `LeadPersistenceTest`, `CrmSyncAttemptPersistenceTest`, `CrmConnectorRegistryTest`, `BackendApplicationTests`.

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md reflete le socle multi-tenant de F1"
```

---

## Après la dernière tâche

Utiliser la compétence `superpowers:finishing-a-development-branch` pour vérifier l'état des tests, présenter les options d'intégration et fusionner `feature/f1-socle-multi-tenant` dans `main`.
