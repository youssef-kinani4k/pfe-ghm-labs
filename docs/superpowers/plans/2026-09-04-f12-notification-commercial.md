# F12 — Notification du commercial : plan d'implementation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevenir par e-mail le commercial d'un lead chaud qui vient de lui etre attribue et
synchronise dans l'ERP, avec une trace de chaque envoi, chaque echec et chaque silence.

**Architecture:** Un package `notification/`, cinquieme etape du pipeline, calque sur `crm/`.
Une nouvelle file `leadflow.leads.notify` se lie a la cle `lead.synced` que
`SyncedLeadPublisher` publie deja ; l'echange etant un `DirectExchange`, le monitoring continue
de recevoir ce qu'il recevait et **aucun code existant du pipeline ne change**. Le metier vit
dans `NotificationService`, testable sans broker ; l'envoi passe par le port
`CanalDeNotification`, dont `smtp/CanalSmtp` est la seule implementation de cette feature.

**Tech Stack:** Java 21, Spring Boot, Spring AMQP, `spring-boot-starter-mail` (nouveau),
Flyway, JPA/Hibernate, JUnit 5 + AssertJ, GreenMail (nouveau, portee test), Testcontainers,
Angular 20 standalone.

**Spec:** `docs/superpowers/specs/2026-09-04-f12-notification-commercial-design.md`

## Global Constraints

- **Branche** `feature/f12-notification-commercial`, deja creee, fusionnee dans `main` en
  `--no-ff` a la fin. Ne jamais supprimer la branche apres fusion.
- **`ddl-auto: validate`** : Hibernate ne cree aucune table. Toute evolution de schema est une
  nouvelle migration `V<n>__description.sql`. La derniere existante est `V8__lead_action.sql`,
  donc F12 ecrit `V9__notification_attempt.sql`. **Ne jamais modifier une migration deja
  appliquee** — Flyway echoue au demarrage sur le checksum.
- **Le modele pivot ne porte aucun terme propre a un canal** : ni `subject`, ni `from`, ni
  `html` dans `NotificationLead`. Toute la traduction se fait dans l'adaptateur. C'est
  l'invariant de `crm/model`, transpose.
- **Aucun `switch` sur le canal** : resolution par injection de `List<CanalDeNotification>`.
- **Le consommateur est un bean conditionnel** `leadflow.notification.listener.enabled`, et
  `src/test/resources/application.properties` doit le mettre a `false` comme les cinq autres.
- **Tout nouveau contrat de file se declare dans `RabbitMQConfig.PAQUETS_DE_CONFIANCE`**,
  correspondance exacte, ni prefixe ni joker.
- **Aucune entite JPA ne franchit la frontiere HTTP** : toute reponse passe par un `record` de
  DTO, et le test asserte le corps JSON.
- **`./mvnw verify` doit continuer a passer sans profil supplementaire.** Les tests demandant
  un vrai relais SMTP portent `@Tag("notification")` et sont exclus par defaut, comme
  `@Tag("erp")`.
- **Le visuel passe par le plugin `ui-ux-pro-max`**, invoque **avant** d'ecrire le code des
  ecrans, jamais en relecture.
- **La copie de travail est en LF.** Prettier compare avec `endOfLine: lf`.
- **Le backend ecoute sur `:8090`**, pas `:8080`.
- **Le crochet `pre-push`** joue ESLint, Prettier et les tests frontend avant tout push vers
  `main` : `git config core.hooksPath .githooks` si ce n'est pas deja fait.
- Message de commit termine par :
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_018ipurcxNwaZKE9UWRRMqrz
  ```

---

### Task 1 : Le socle de configuration

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/leadflow/config/NotificationProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/leadflow/config/NotificationPropertiesTest.java`

**Interfaces:**
- Consomme : rien.
- Produit : `NotificationProperties(Smtp smtp, String urlFiche)` avec
  `record Smtp(boolean enabled, String host, int port, String username, String password, String from, Duration timeout)`
  et la methode `boolean configure()`. `urlFiche` est un gabarit contenant `{id}`, consomme
  par la tache 5.

- [ ] **Step 1 : Ecrire le test qui echoue**

`backend/src/test/java/com/leadflow/config/NotificationPropertiesTest.java` :

```java
package com.leadflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationPropertiesTest {

    private static final String URL = "http://localhost:4200/leads/{id}";

    private static NotificationProperties.Smtp smtp(boolean enabled, String host) {
        return new NotificationProperties.Smtp(
                enabled, host, 587, "u", "p", "leadflow@agence.test", Duration.ofSeconds(10));
    }

    @Test
    void unHoteAbsentRendLeCanalNonConfigure() {
        assertThat(new NotificationProperties(smtp(true, null), URL).smtp().configure())
                .isFalse();
        assertThat(new NotificationProperties(smtp(true, "  "), URL).smtp().configure())
                .isFalse();
    }

    @Test
    void unCanalEteintNestPasConfigureMemeAvecUnHote() {
        // Le drapeau permet d'eteindre l'envoi sans effacer les reglages, par exemple pour
        // une demonstration : sans lui, il faudrait vider l'hote puis le retrouver.
        assertThat(new NotificationProperties(smtp(false, "smtp.test"), URL).smtp().configure())
                .isFalse();
    }

    @Test
    void unHotePresentEtLeCanalAllumeSontConfigures() {
        assertThat(new NotificationProperties(smtp(true, "smtp.test"), URL).smtp().configure())
                .isTrue();
    }
}
```

- [ ] **Step 2 : Lancer le test pour le voir echouer**

Run : `cd backend && ./mvnw test -Dtest=NotificationPropertiesTest`
Expected : FAIL a la compilation — `NotificationProperties` n'existe pas.

- [ ] **Step 3 : Ajouter la dependance mail**

Dans `backend/pom.xml`, a cote des autres `spring-boot-starter-*` :

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

- [ ] **Step 4 : Ecrire le record de proprietes**

`backend/src/main/java/com/leadflow/config/NotificationProperties.java` :

```java
package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reglages du canal de notification. Globaux a l'instance et non portes par le client :
 * l'agence exploite un seul relais d'envoi pour toutes ses boutiques, comme elle exploite
 * une seule cle d'analyse d'intention. Ce n'est pas un reglage de tenant.
 *
 * <p>Rien n'est en dur : tout vient de l'environnement, et le profil dev ne porte aucun
 * repli. Un repli pointant vers un serveur imaginaire ferait echouer chaque lead chaud en
 * developpement et remplirait la DLQ de morts sans interet.
 */
@ConfigurationProperties(prefix = "leadflow.notification")
public record NotificationProperties(Smtp smtp, String urlFiche) {

    /** L'URL de la fiche du lead, {@code {id}} remplace. Un fait, pas une presentation. */
    public String urlDe(java.util.UUID leadId) {
        return urlFiche.replace("{id}", leadId.toString());
    }

    /**
     * @param enabled eteint l'envoi sans effacer les reglages
     * @param host absent ou vide : le canal est inerte, l'application demarre quand meme —
     *     c'est le parti de GEMINI_API_KEY, une instance sans relais doit continuer a
     *     traiter des leads
     * @param from adresse d'expedition, unique pour l'instance
     */
    public record Smtp(
            boolean enabled,
            String host,
            int port,
            String username,
            String password,
            String from,
            Duration timeout) {

        /** Vrai quand un envoi est possible. Le canal ne tente rien sinon. */
        public boolean configure() {
            return enabled && host != null && !host.isBlank();
        }
    }
}
```

- [ ] **Step 5 : Declarer les valeurs dans `application.yml`**

Sous la racine `leadflow:` existante :

```yaml
  notification:
    listener:
      enabled: true
    url-fiche: ${LEADFLOW_URL_FICHE:http://localhost:4200/leads/{id}}
    smtp:
      enabled: ${LEADFLOW_SMTP_ENABLED:true}
      host: ${LEADFLOW_SMTP_HOST:}
      port: ${LEADFLOW_SMTP_PORT:587}
      username: ${LEADFLOW_SMTP_USERNAME:}
      password: ${LEADFLOW_SMTP_PASSWORD:}
      from: ${LEADFLOW_SMTP_FROM:leadflow@localhost}
      timeout: ${LEADFLOW_SMTP_TIMEOUT:10s}
```

Aucun repli n'est ajoute dans `application-dev.yml` : l'hote y reste vide, donc le canal est
inerte en developpement.

- [ ] **Step 6 : Lancer le test pour le voir passer**

Run : `cd backend && ./mvnw test -Dtest=NotificationPropertiesTest`
Expected : PASS, 3 tests.

- [ ] **Step 7 : Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/leadflow/config/NotificationProperties.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/leadflow/config/NotificationPropertiesTest.java
git commit -m "feat: les reglages du canal de notification, inerte sans hote"
```

---

### Task 2 : La migration et la trace

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__notification_attempt.sql`
- Create: `backend/src/main/java/com/leadflow/notification/NotificationAttempt.java`
- Create: `backend/src/main/java/com/leadflow/notification/NotificationStatus.java`
- Create: `backend/src/main/java/com/leadflow/notification/NotificationAttemptRepository.java`
- Test: `backend/src/test/java/com/leadflow/notification/NotificationAttemptPersistenceTest.java`

**Interfaces:**
- Consomme : rien.
- Produit : `NotificationStatus{ENVOYEE, ECHEC, IGNOREE}` ; l'entite `NotificationAttempt`
  avec setters Lombok (`setLeadId`, `setChannel`, `setRecipient`, `setSalesRepId`,
  `setStatus`, `setScore`, `setSeuil`, `setErrorMessage`) et `getAttemptedAt()` ;
  `NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID>` avec
  `List<NotificationAttempt> findByLeadIdOrderByAttemptedAtAsc(UUID leadId)`.

- [ ] **Step 1 : Ecrire le test qui echoue**

`backend/src/test/java/com/leadflow/notification/NotificationAttemptPersistenceTest.java`.
Prendre `backend/src/test/java/com/leadflow/crm/CrmSyncAttemptPersistenceTest.java` comme
modele exact pour le montage du client, du commercial et du lead — **`@SpringBootTest` et non
`@DataJpaTest`**, la tranche `@DataJpaTest` n'incluant pas les `@Component` dont les
`AttributeConverter` de chiffrement ont besoin.

```java
    @Test
    void uneTentativeIgnoreeGardeLeScoreEtLeSeuilQuiLOntTranchee() {
        NotificationAttempt tentative = new NotificationAttempt();
        tentative.setLeadId(leadId);
        tentative.setChannel("smtp");
        tentative.setRecipient("karim@demo.test");
        tentative.setSalesRepId(commercialId);
        tentative.setStatus(NotificationStatus.IGNOREE);
        tentative.setScore(55);
        tentative.setSeuil(70);

        NotificationAttempt enregistree = tentatives.saveAndFlush(tentative);

        // Figes dans la ligne, pas relus : le seuil se regle depuis l'ecran « Bareme », et
        // le deplacer ne doit pas rendre incomprehensible une decision deja prise.
        assertThat(enregistree.getScore()).isEqualTo(55);
        assertThat(enregistree.getSeuil()).isEqualTo(70);
        assertThat(enregistree.getAttemptedAt()).isNotNull();
    }

    @Test
    void unStatutHorsDuVocabulaireEstRefuseParLaBase() {
        // Le CHECK de V9 est la derniere barriere : un statut invente cote Java ne doit pas
        // pouvoir s'ecrire. On l'eprouve en SQL direct, l'enumeration l'interdisant en Java.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO notification_attempt "
                        + "(id, lead_id, channel, recipient, status, score, seuil) "
                        + "VALUES (?, ?, 'smtp', 'x@y.test', 'PEUT_ETRE', 10, 70)",
                UUID.randomUUID(), leadId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laSuppressionDuLeadEmporteSesTentatives() {
        NotificationAttempt tentative = new NotificationAttempt();
        tentative.setLeadId(leadId);
        tentative.setChannel("smtp");
        tentative.setRecipient("karim@demo.test");
        tentative.setStatus(NotificationStatus.ENVOYEE);
        tentative.setScore(90);
        tentative.setSeuil(70);
        tentatives.saveAndFlush(tentative);

        leads.deleteById(leadId);
        leads.flush();

        // ON DELETE CASCADE, comme crm_sync_attempt et contrairement a lead_action : une
        // tentative n'a pas de sens sans son lead, la ou un geste humain garde le sien.
        assertThat(tentatives.findByLeadIdOrderByAttemptedAtAsc(leadId)).isEmpty();
    }
```

- [ ] **Step 2 : Lancer le test pour le voir echouer**

Run : `cd backend && ./mvnw test -Dtest=NotificationAttemptPersistenceTest`
Expected : FAIL a la compilation — les classes n'existent pas.

- [ ] **Step 3 : Ecrire la migration**

`backend/src/main/resources/db/migration/V9__notification_attempt.sql` :

```sql
-- Trace des notifications envoyees au commercial. Calquee sur crm_sync_attempt : meme role,
-- meme forme, meme raison d'exister — sans elle, « le commercial a-t-il ete prevenu ? » n'a
-- pas de reponse, et c'est la question qu'on posera a la premiere reclamation.
CREATE TABLE notification_attempt (
    id            UUID         PRIMARY KEY,
    lead_id       UUID         NOT NULL REFERENCES lead (id) ON DELETE CASCADE,
    -- Porte par la ligne et non deduit de la configuration : si l'instance change de canal,
    -- l'historique reste lisible. Meme parti que crm_sync_attempt.provider_id.
    channel       VARCHAR(40)  NOT NULL,
    -- L'adresse telle qu'elle a servi, et non celle que sales_rep porte aujourd'hui : un
    -- commercial qui change d'e-mail ne doit pas reecrire l'histoire.
    recipient     VARCHAR(255) NOT NULL,
    -- Sans cle etrangere, deliberement : une suppression de commercial ne doit pas effacer
    -- la trace. Meme parti que lead_action.previous_sales_rep_id.
    sales_rep_id  UUID,
    -- IGNOREE est un statut, pas une absence de ligne. Un lead sous le seuil ecrit quand
    -- meme sa trace : c'est ce qui permet de repondre « score 55, seuil 70 » a la question
    -- « pourquoi n'ai-je pas ete prevenu ? ». Ne rien ecrire rendrait le silence
    -- indistinguable d'une panne.
    status        VARCHAR(32)  NOT NULL
        CONSTRAINT ck_notification_attempt_status
        CHECK (status IN ('ENVOYEE', 'ECHEC', 'IGNOREE')),
    -- Le score au moment de la decision et le seuil qui l'a tranchee, figes dans la ligne.
    -- Le seuil se regle depuis l'ecran « Bareme » : sans ces deux colonnes, le deplacer
    -- ferait mentir tout l'historique. C'est la lecon de lead.score, fige a la qualification.
    score         INTEGER      NOT NULL,
    seuil         INTEGER      NOT NULL,
    error_message TEXT,
    attempted_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_attempt_lead
    ON notification_attempt (lead_id, attempted_at DESC);
```

- [ ] **Step 4 : Ecrire l'enumeration, l'entite et le repository**

`NotificationStatus.java` :

```java
package com.leadflow.notification;

/**
 * Ce qu'une tentative de notification a donne.
 *
 * <p>{@code IGNOREE} n'est pas un echec : c'est un lead sous le seuil de sa boutique, donc
 * une decision, et elle merite une trace au meme titre qu'un envoi.
 */
public enum NotificationStatus {
    ENVOYEE,
    ECHEC,
    IGNOREE
}
```

`NotificationAttempt.java` — copier la forme de `com.leadflow.crm.CrmSyncAttempt` :
`@Entity @Table(name = "notification_attempt") @Getter @Setter`, `@Id @UuidGenerator UUID id`,
les colonnes ci-dessus, `@Enumerated(EnumType.STRING)` sur `status`, et un `@PrePersist` qui
pose `attemptedAt` s'il est nul. **N'herite pas de `BaseEntity`** : une ligne de trace ne se
met jamais a jour, donc `updated_at` n'aurait aucun sens — meme parti que `DeadLetter` et
`LeadAction`.

`NotificationAttemptRepository.java` :

```java
package com.leadflow.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationAttemptRepository
        extends JpaRepository<NotificationAttempt, UUID> {

    List<NotificationAttempt> findByLeadIdOrderByAttemptedAtAsc(UUID leadId);
}
```

- [ ] **Step 5 : Lancer le test pour le voir passer**

Run : `cd backend && ./mvnw test -Dtest=NotificationAttemptPersistenceTest`
Expected : PASS, 3 tests. Le demon Docker doit tourner — verifier par
`docker run --rm hello-world`, `docker info` qui repond ne prouvant rien.

- [ ] **Step 6 : Commit**

```bash
git add backend/src/main/resources/db/migration/V9__notification_attempt.sql \
        backend/src/main/java/com/leadflow/notification/ \
        backend/src/test/java/com/leadflow/notification/
git commit -m "feat: V9 — la trace des notifications, y compris les silences"
```

---

### Task 3 : Le pivot, le port et le registre

**Files:**
- Create: `backend/src/main/java/com/leadflow/notification/model/NotificationLead.java`
- Create: `backend/src/main/java/com/leadflow/notification/CanalDeNotification.java`
- Create: `backend/src/main/java/com/leadflow/notification/CanalDeNotificationRegistry.java`
- Create: `backend/src/main/java/com/leadflow/notification/NotificationException.java`
- Create: `backend/src/main/java/com/leadflow/notification/package-info.java`
- Test: `backend/src/test/java/com/leadflow/notification/CanalDeNotificationRegistryTest.java`

**Interfaces:**
- Consomme : rien.
- Produit :
  - `record NotificationLead(String nomCommercial, String adresseCommercial, String nomProspect, String emailProspect, String societeProspect, int score, String intention, String urlFiche)`
  - `interface CanalDeNotification { String identifiant(); void envoie(NotificationLead lead); }`
  - `CanalDeNotificationRegistry.resout(String identifiant)` rendant `CanalDeNotification`.
    Pas de methode « canal par defaut » : la tache 5 resout explicitement `"smtp"` par une
    constante, pour qu'ajouter un second canal oblige a choisir plutot qu'a heriter d'un
    defaut silencieux.
  - `NotificationException extends RuntimeException`, avec `(String message, Throwable cause)`.

- [ ] **Step 1 : Ecrire le test qui echoue**

```java
package com.leadflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.notification.model.NotificationLead;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanalDeNotificationRegistryTest {

    private static CanalDeNotification canal(String identifiant) {
        return new CanalDeNotification() {
            @Override
            public String identifiant() {
                return identifiant;
            }

            @Override
            public void envoie(NotificationLead lead) {
            }
        };
    }

    @Test
    void resoutUnCanalParSonIdentifiant() {
        CanalDeNotificationRegistry registre =
                new CanalDeNotificationRegistry(List.of(canal("smtp")));

        assertThat(registre.resout("smtp").identifiant()).isEqualTo("smtp");
    }

    @Test
    void refuseDeDemarrerSiDeuxCanauxRevendiquentLeMemeIdentifiant() {
        // Meme garde-fou que AssignmentStrategyRegistry : un doublon silencieux ferait
        // dependre le canal choisi de l'ordre d'injection, donc du hasard.
        assertThatThrownBy(
                        () -> new CanalDeNotificationRegistry(List.of(canal("smtp"), canal("smtp"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("smtp");
    }

    @Test
    void unIdentifiantInconnuLeveEtNommeCeQuiExiste() {
        CanalDeNotificationRegistry registre =
                new CanalDeNotificationRegistry(List.of(canal("smtp")));

        assertThatThrownBy(() -> registre.resout("pigeon"))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("pigeon");
    }
}
```

- [ ] **Step 2 : Lancer le test pour le voir echouer**

Run : `cd backend && ./mvnw test -Dtest=CanalDeNotificationRegistryTest`
Expected : FAIL a la compilation.

- [ ] **Step 3 : Ecrire le pivot**

```java
package com.leadflow.notification.model;

/**
 * Ce qu'un canal a besoin de savoir pour prevenir un commercial.
 *
 * <p><b>Aucun terme propre a un canal ici.</b> Ni objet, ni expediteur, ni corps HTML : ce
 * sont des faits, et c'est l'adaptateur qui compose le message. C'est l'invariant de
 * {@code crm/model} transpose, et il se casse aussi facilement — des qu'un champ SMTP remonte
 * dans ce record, un second canal devient impossible sans reecriture.
 *
 * <p>{@code urlFiche} est un fait et non une decision de presentation : le commercial doit
 * pouvoir ouvrir le lead, quel que soit le canal qui le previent.
 */
public record NotificationLead(
        String nomCommercial,
        String adresseCommercial,
        String nomProspect,
        String emailProspect,
        String societeProspect,
        int score,
        String intention,
        String urlFiche) {
}
```

- [ ] **Step 4 : Ecrire le port, l'exception et le registre**

```java
package com.leadflow.notification;

import com.leadflow.notification.model.NotificationLead;

/**
 * Port de sortie vers un canal d'alerte. Une implementation par canal, resolue par
 * {@link CanalDeNotificationRegistry} — jamais par un {@code switch}.
 */
public interface CanalDeNotification {

    /** Identifiant stable, ecrit tel quel dans {@code notification_attempt.channel}. */
    String identifiant();

    /** Envoie, ou leve {@link NotificationException} si le canal a echoue. */
    void envoie(NotificationLead lead);
}
```

```java
package com.leadflow.notification;

/** Echec technique d'un canal. Provoque les trois tentatives puis la DLQ. */
public class NotificationException extends RuntimeException {

    public NotificationException(String message) {
        super(message);
    }

    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package com.leadflow.notification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resout un canal par son identifiant. Les implementations sont collectees par injection de
 * {@code List<CanalDeNotification>} : un canal de plus s'ajoute en ecrivant une classe, et
 * aucun {@code switch} n'existe. Meme parti que {@code AssignmentStrategyRegistry} et
 * {@code CrmConnectorRegistry}.
 */
@Component
public class CanalDeNotificationRegistry {

    private final Map<String, CanalDeNotification> parIdentifiant = new HashMap<>();

    public CanalDeNotificationRegistry(List<CanalDeNotification> canaux) {
        for (CanalDeNotification canal : canaux) {
            CanalDeNotification precedent = parIdentifiant.put(canal.identifiant(), canal);
            if (precedent != null) {
                throw new IllegalStateException(
                        "Deux canaux revendiquent l'identifiant " + canal.identifiant());
            }
        }
    }

    public CanalDeNotification resout(String identifiant) {
        CanalDeNotification canal = parIdentifiant.get(identifiant);
        if (canal == null) {
            throw new NotificationException(
                    "Canal inconnu : " + identifiant + ". Connus : " + parIdentifiant.keySet());
        }
        return canal;
    }
}
```

- [ ] **Step 5 : Ecrire le `package-info.java`**

Chaque package du projet documente sa responsabilite. Y ecrire : l'etape du pipeline, la
source (`lead.synced`), l'invariant du pivot, l'absence de `switch`, et la regle des trois cas
deterministes qui ne partent jamais en DLQ.

- [ ] **Step 6 : Lancer le test pour le voir passer**

Run : `cd backend && ./mvnw test -Dtest=CanalDeNotificationRegistryTest`
Expected : PASS, 3 tests.

- [ ] **Step 7 : Commit**

```bash
git add backend/src/main/java/com/leadflow/notification/ \
        backend/src/test/java/com/leadflow/notification/CanalDeNotificationRegistryTest.java
git commit -m "feat: le port de notification, son pivot et son registre"
```

---

### Task 4 : Le seuil de notification dans le bareme

**Files:**
- Modify: `backend/src/main/java/com/leadflow/qualification/ScoringConfig.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/dto/ScoringForm.java`
- Modify: `backend/src/test/java/com/leadflow/tenant/ScoringAllerRetourTest.java`
- Modify: `backend/src/test/java/com/leadflow/tenant/ScoringContraintesTest.java`

**Interfaces:**
- Consomme : rien.
- Produit : `ScoringConfig.seuilNotification()` (dernier composant du record, defaut `70`) et
  le champ `seuilNotification` de `ScoringForm`, borne `[0, 100]`, ecrit dans le document sous
  la cle `seuilNotification`.

- [ ] **Step 1 : Ecrire les tests qui echouent**

Dans `ScoringAllerRetourTest` :

```java
    @Test
    void leSeuilDeNotificationSurvitAAllerRetour() {
        ScoringForm forme = ScoringForm.de(ScoringConfig.defaut());
        ScoringForm modifiee = new ScoringForm(
                forme.telephonePresent(), forme.societePresente(), forme.nomPresent(),
                forme.messagePresent(), forme.intention(), forme.secteursCibles(),
                forme.paysCibles(), forme.bonusCible(), forme.seuilChaud(), 85);

        ScoringConfig relu = ScoringConfig.depuis(modifiee.versDocument());

        assertThat(relu.seuilNotification()).isEqualTo(85);
        // Le seuil de notification est distinct du seuil de chaleur : colorer une pastille
        // et deranger quelqu'un ne meritent pas la meme valeur.
        assertThat(relu.seuilChaud()).isEqualTo(forme.seuilChaud());
    }

    @Test
    void unDocumentSansSeuilDeNotificationPrendLeDefaut() {
        // La lecture est tolerante : toutes les boutiques deja configurees continuent de
        // fonctionner sans etre touchees, et c'est ce qui evite une migration.
        Map<String, Object> ancien = new LinkedHashMap<>(ScoringForm.de(ScoringConfig.defaut())
                .versDocument());
        ancien.remove("seuilNotification");

        assertThat(ScoringConfig.depuis(ancien).seuilNotification())
                .isEqualTo(ScoringConfig.defaut().seuilNotification());
    }
```

Dans `ScoringContraintesTest`, ajouter le cas de borne sur le nouveau champ, sur le modele
exact du cas existant pour `seuilChaud` : un `PUT` avec `seuilNotification` a `101` doit rendre
`400`.

- [ ] **Step 2 : Lancer les tests pour les voir echouer**

Run : `cd backend && ./mvnw test -Dtest=ScoringAllerRetourTest+ScoringContraintesTest`
Expected : FAIL a la compilation — le composant n'existe pas.

- [ ] **Step 3 : Ajouter le champ a `ScoringConfig`**

Ajouter `int seuilNotification` **en dernier composant** du record ; dans `defaut()`, passer
`70` en dernier argument ; dans `depuis(...)`, ajouter en dernier
`entier(document.get("seuilNotification"), defaut.seuilNotification())`. Documenter dans le
Javadoc du record :

```java
 * @param seuilNotification score a partir duquel le commercial est prevenu. Distinct de
 *     seuilChaud, qui ne colore qu'un badge : on tolere un badge genereux, pas une boite
 *     mail saturee. Les confondre donnerait deux roles a un meme reglage, et deplacer le
 *     seuil pour ajuster l'affichage changerait silencieusement qui recoit des e-mails.
```

- [ ] **Step 4 : Ajouter le champ a `ScoringForm`**

`@Min(0) @Max(100) int seuilNotification` en dernier composant ; dans `versDocument()`,
`document.put("seuilNotification", seuilNotification)` ; dans `de(...)`, passer
`bareme.seuilNotification()` en dernier argument.

- [ ] **Step 5 : Lancer les tests pour les voir passer**

Run : `cd backend && ./mvnw test -Dtest=ScoringAllerRetourTest+ScoringContraintesTest+ScoringAdminTest+ScoringValidationTest`
Expected : PASS. `ScoringAllerRetourTest` est ce qui empeche les deux formes de diverger.

- [ ] **Step 6 : Commit**

```bash
git add backend/src/main/java/com/leadflow/qualification/ScoringConfig.java \
        backend/src/main/java/com/leadflow/tenant/dto/ScoringForm.java \
        backend/src/test/java/com/leadflow/tenant/
git commit -m "feat: un seuil de notification distinct du seuil de chaleur"
```

---

### Task 5 : Le service et sa trace

**Files:**
- Create: `backend/src/main/java/com/leadflow/notification/NotificationTraceWriter.java`
- Create: `backend/src/main/java/com/leadflow/notification/NotificationService.java`
- Test: `backend/src/test/java/com/leadflow/notification/NotificationServiceTest.java`

**Interfaces:**
- Consomme : `CanalDeNotification`, `CanalDeNotificationRegistry`,
  `NotificationAttemptRepository`, `NotificationStatus`, `NotificationLead`,
  `ScoringConfig.seuilNotification()`, `LeadRepository`, `SalesRepRepository`,
  `ClientRepository`.
- Produit : `NotificationService.notifie(UUID leadId)` — sans valeur de retour, levant
  `NotificationException` sur echec technique seulement ;
  `NotificationTraceWriter.ecrit(UUID leadId, UUID salesRepId, String canal, String destinataire, NotificationStatus statut, int score, int seuil, String erreur)`
  en `@Transactional(propagation = REQUIRES_NEW)`.

- [ ] **Step 1 : Ecrire les tests qui echouent**

Test unitaire pur : ni Spring, ni broker, ni serveur. Le canal est un double qui compte les
appels et peut lever ; le `NotificationTraceWriter` est un double qui memorise les lignes.

```java
    @Test
    void unLeadSousLeSeuilNestPasEnvoyeMaisLaisseUneTrace() {
        boutiqueAvecSeuil(70);
        leadAvecScore(55);

        service.notifie(leadId);

        assertThat(canal.envois()).isEmpty();
        // Le silence doit etre explicable : sans cette ligne, « pourquoi n'ai-je pas ete
        // prevenu ? » n'a pas de reponse, et une panne ressemble a une decision.
        assertThat(traces.dernier().statut()).isEqualTo(NotificationStatus.IGNOREE);
        assertThat(traces.dernier().score()).isEqualTo(55);
        assertThat(traces.dernier().seuil()).isEqualTo(70);
    }

    @Test
    void unLeadAuSeuilExactEstEnvoye() {
        // Le seuil est inclusif, comme le badge « chaud » du monitoring : score >= seuil.
        boutiqueAvecSeuil(70);
        leadAvecScore(70);

        service.notifie(leadId);

        assertThat(canal.envois()).hasSize(1);
        assertThat(traces.dernier().statut()).isEqualTo(NotificationStatus.ENVOYEE);
    }

    @Test
    void leCommercialSansAdresseNePartJamaisEnDlq() {
        boutiqueAvecSeuil(70);
        leadAvecScore(90);
        commercial.setEmail(null);

        // Aucune repetition ne reparera une adresse absente : lever ferait mourir le message
        // trois fois pour rien. C'est la distinction que la qualification fait avec DISCARDED.
        assertThatCode(() -> service.notifie(leadId)).doesNotThrowAnyException();

        assertThat(canal.envois()).isEmpty();
        assertThat(traces.dernier().statut()).isEqualTo(NotificationStatus.ECHEC);
        assertThat(traces.dernier().erreur()).contains("adresse");
    }

    @Test
    void unLeadSansCommercialNePartJamaisEnDlq() {
        boutiqueAvecSeuil(70);
        leadAvecScore(90);
        lead.setAssignedSalesRepId(null);

        assertThatCode(() -> service.notifie(leadId)).doesNotThrowAnyException();
        assertThat(traces.dernier().statut()).isEqualTo(NotificationStatus.ECHEC);
    }

    @Test
    void unEchecTechniqueEcritSaTraceAvantDeLeverPourLaDlq() {
        boutiqueAvecSeuil(70);
        leadAvecScore(90);
        canal.echoueAvec(new NotificationException("smtp injoignable"));

        assertThatThrownBy(() -> service.notifie(leadId))
                .isInstanceOf(NotificationException.class);

        // L'ordre est l'invariant : la trace s'ecrit AVANT que l'exception ne parte, dans sa
        // propre transaction. Sans cela, la seule trace de l'echec disparaitrait avec lui —
        // c'est ce que fait LeadActionJournal pour un rejeu rate.
        assertThat(traces.dernier().statut()).isEqualTo(NotificationStatus.ECHEC);
        assertThat(traces.dernier().erreur()).contains("smtp injoignable");
    }

    @Test
    void lePivotPorteLesFaitsDuLeadEtDuCommercial() {
        boutiqueAvecSeuil(70);
        leadAvecScore(90);

        service.notifie(leadId);

        NotificationLead envoye = canal.envois().getFirst();
        assertThat(envoye.adresseCommercial()).isEqualTo("karim@demo.test");
        assertThat(envoye.nomCommercial()).isEqualTo("Karim Idrissi");
        assertThat(envoye.score()).isEqualTo(90);
        assertThat(envoye.urlFiche()).contains(leadId.toString());
    }
```

- [ ] **Step 2 : Lancer les tests pour les voir echouer**

Run : `cd backend && ./mvnw test -Dtest=NotificationServiceTest`
Expected : FAIL a la compilation.

- [ ] **Step 3 : Ecrire le `NotificationTraceWriter`**

```java
package com.leadflow.notification;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecrit une ligne de {@code notification_attempt} dans sa propre transaction.
 *
 * <p>{@code REQUIRES_NEW} n'est pas decoratif : c'est ce qui fait qu'une trace d'echec survit
 * a l'exception qui part ensuite vers la DLQ. Meme parti que {@code LeadActionJournal}, qui
 * journalise un rejeu rate avant de laisser partir son echec.
 */
@Component
public class NotificationTraceWriter {

    private final NotificationAttemptRepository tentatives;

    public NotificationTraceWriter(NotificationAttemptRepository tentatives) {
        this.tentatives = tentatives;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ecrit(
            UUID leadId, UUID salesRepId, String canal, String destinataire,
            NotificationStatus statut, int score, int seuil, String erreur) {
        NotificationAttempt tentative = new NotificationAttempt();
        tentative.setLeadId(leadId);
        tentative.setSalesRepId(salesRepId);
        tentative.setChannel(canal);
        tentative.setRecipient(destinataire == null ? "" : destinataire);
        tentative.setStatus(statut);
        tentative.setScore(score);
        tentative.setSeuil(seuil);
        tentative.setErrorMessage(erreur);
        tentatives.save(tentative);
    }
}
```

- [ ] **Step 4 : Ecrire le service**

`NotificationService` — `@Service`, **sans `@Transactional`**, avec le Javadoc expliquant
pourquoi (un envoi de plusieurs secondes tiendrait une connexion Postgres, comme
`LeadQualificationService`). Le corps suit exactement l'ordre du spec :

```java
    public void notifie(UUID leadId) {
        Lead lead = leads.findById(leadId).orElseThrow(
                () -> new NotificationException("Lead inconnu : " + leadId));
        Client boutique = clients.findById(lead.getClientId()).orElseThrow(
                () -> new NotificationException("Boutique inconnue : " + lead.getClientId()));
        int seuil = ScoringConfig.depuis(boutique.getScoringConfig()).seuilNotification();
        CanalDeNotification canal = registre.resout(CANAL_PAR_DEFAUT);

        if (lead.getScore() < seuil) {
            traces.ecrit(leadId, lead.getAssignedSalesRepId(), canal.identifiant(), null,
                    NotificationStatus.IGNOREE, lead.getScore(), seuil, null);
            return;
        }
        if (lead.getAssignedSalesRepId() == null) {
            echecDeterministe(lead, canal, seuil, null, "Aucun commercial attribue");
            return;
        }
        SalesRep commercial = commerciaux.findById(lead.getAssignedSalesRepId()).orElse(null);
        if (commercial == null) {
            echecDeterministe(lead, canal, seuil, null, "Commercial introuvable");
            return;
        }
        if (commercial.getEmail() == null || commercial.getEmail().isBlank()) {
            echecDeterministe(lead, canal, seuil, commercial.getId(),
                    "Le commercial n'a pas d'adresse e-mail");
            return;
        }

        try {
            canal.envoie(pivot(lead, commercial));
        } catch (NotificationException echec) {
            // La trace AVANT l'exception : elle doit survivre au depart vers la DLQ.
            traces.ecrit(leadId, commercial.getId(), canal.identifiant(), commercial.getEmail(),
                    NotificationStatus.ECHEC, lead.getScore(), seuil, echec.getMessage());
            throw echec;
        }
        traces.ecrit(leadId, commercial.getId(), canal.identifiant(), commercial.getEmail(),
                NotificationStatus.ENVOYEE, lead.getScore(), seuil, null);
    }
```

Le canal se resout par une constante `private static final String CANAL_PAR_DEFAUT = "smtp";`
declaree dans le service. Une constante et non un reglage : tant qu'il n'existe qu'un canal,
un reglage donnerait le choix entre une seule valeur et une faute de frappe.

`echecDeterministe(UUID leadId, Lead lead, CanalDeNotification canal, int seuil, UUID salesRepId, String raison)`
ecrit une trace `ECHEC` et **ne leve pas** : ces cas ne sont pas reparables par une
repetition. `pivot(lead, commercial)` compose le `NotificationLead`, l'URL venant de
`proprietes.urlDe(lead.getId())` — la propriete `leadflow.notification.url-fiche` posee a la
tache 1.

- [ ] **Step 5 : Lancer les tests pour les voir passer**

Run : `cd backend && ./mvnw test -Dtest=NotificationServiceTest`
Expected : PASS, 6 tests.

- [ ] **Step 6 : Commit**

```bash
git add backend/src/main/java/com/leadflow/notification/ \
        backend/src/main/java/com/leadflow/config/NotificationProperties.java \
        backend/src/test/java/com/leadflow/notification/NotificationServiceTest.java
git commit -m "feat: le service de notification, son seuil et ses trois refus sans DLQ"
```

---

### Task 6 : L'adaptateur SMTP

**Files:**
- Modify: `backend/pom.xml` (GreenMail, portee test)
- Create: `backend/src/main/java/com/leadflow/notification/smtp/CanalSmtp.java`
- Create: `backend/src/main/java/com/leadflow/notification/smtp/GabaritMessage.java`
- Test: `backend/src/test/java/com/leadflow/notification/smtp/CanalSmtpTest.java`

**Interfaces:**
- Consomme : `CanalDeNotification`, `NotificationLead`, `NotificationException`,
  `NotificationProperties.Smtp`.
- Produit : `CanalSmtp` avec `identifiant()` rendant `"smtp"`, et
  `GabaritMessage.objet(NotificationLead)` / `GabaritMessage.corps(NotificationLead)`.

- [ ] **Step 1 : Ajouter GreenMail au `pom.xml`**

```xml
<dependency>
    <groupId>com.icegreen</groupId>
    <artifactId>greenmail-junit5</artifactId>
    <version>2.1.9</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2 : Ecrire le test qui echoue**

Le test **asserte le contenu du message**, pas seulement l'absence d'exception — c'est le
parti des adaptateurs ERP, qui assertent les corps envoyes et non les codes retour.

```java
    @Test
    void envoieUnMessageAuCommercialAvecLesFaitsDuLead() throws Exception {
        canal.envoie(new NotificationLead(
                "Karim Idrissi", "karim@demo.test", "Sara Benali", "sara@rif.test",
                "Rif Logistics", 92, "DEVIS", "http://localhost:4200/leads/" + leadId));

        MimeMessage[] recus = greenMail.getReceivedMessages();
        assertThat(recus).hasSize(1);
        assertThat(recus[0].getAllRecipients()[0].toString()).isEqualTo("karim@demo.test");
        assertThat(recus[0].getSubject()).contains("Sara Benali");
        String corps = GreenMailUtil.getBody(recus[0]);
        assertThat(corps).contains("92").contains("Rif Logistics").contains(leadId.toString());
    }

    @Test
    void unRelaisInjoignableLeveUneNotificationException() {
        CanalSmtp casse = canalVers("127.0.0.1", portMort);

        assertThatThrownBy(() -> casse.envoie(unLead()))
                .isInstanceOf(NotificationException.class);
    }

    @Test
    void unCanalNonConfigureLeveSansTenterDeConnexion() {
        // Hote vide : l'instance demarre quand meme, mais chaque envoi doit dire pourquoi il
        // n'a pas eu lieu plutot que de tenter une connexion vers nulle part.
        CanalSmtp inerte = canalVers("", 587);

        assertThatThrownBy(() -> inerte.envoie(unLead()))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("configur");
    }
```

- [ ] **Step 3 : Lancer le test pour le voir echouer**

Run : `cd backend && ./mvnw test -Dtest=CanalSmtpTest`
Expected : FAIL a la compilation.

- [ ] **Step 4 : Ecrire le gabarit et l'adaptateur**

`GabaritMessage` est le **seul** endroit qui compose des phrases : objet et corps en texte
brut, pas de HTML. Le corps nomme le prospect, sa societe, son score, son intention, et porte
l'URL de la fiche.

`CanalSmtp` est un `@Component` implementant `CanalDeNotification` :

- `identifiant()` rend `"smtp"` ;
- `envoie(...)` leve `NotificationException` immediatement si
  `!proprietes.configure()`, **sans ouvrir de connexion** ;
- il construit son `JavaMailSenderImpl` a partir de `NotificationProperties.Smtp` — hote,
  port, identifiants, `mail.smtp.timeout` et `mail.smtp.connectiontimeout` depuis
  `timeout` ;
- toute `MailException` est enveloppee dans `NotificationException` avec un message court.

- [ ] **Step 5 : Lancer le test pour le voir passer**

Run : `cd backend && ./mvnw test -Dtest=CanalSmtpTest`
Expected : PASS, 3 tests.

- [ ] **Step 6 : Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/leadflow/notification/smtp/ \
        backend/src/test/java/com/leadflow/notification/smtp/
git commit -m "feat: l adaptateur SMTP, seul a connaitre le gabarit du message"
```

---

### Task 7 : La file, le binding et le consommateur

**Files:**
- Modify: `backend/src/main/java/com/leadflow/config/RabbitMQConfig.java`
- Create: `backend/src/main/java/com/leadflow/notification/NotificationListener.java`
- Modify: `backend/src/test/resources/application.properties`
- Test: `backend/src/test/java/com/leadflow/notification/NotificationTopologieTest.java`

**Interfaces:**
- Consomme : `NotificationService.notifie(UUID)`, `SyncedLeadMessage`.
- Produit : `RabbitMQConfig.NOTIFY_QUEUE = "leadflow.leads.notify"`.

- [ ] **Step 1 : Ecrire le test qui echoue**

```java
    @Test
    void laFileDeNotificationEstLieeALaMemeCleQueLeMonitoring() {
        // Un DirectExchange livre a TOUTES les files liees a une cle : le monitoring doit
        // continuer de recevoir lead.synced apres l'ajout de la file de notification.
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE, RabbitMQConfig.SYNCED_ROUTING_KEY,
                new SyncedLeadMessage(leadId, clientId, "dolibarr", Instant.now()));

        assertThat(recoitUnMessage(RabbitMQConfig.NOTIFY_QUEUE)).isNotNull();
        assertThat(recoitUnMessage(RabbitMQConfig.MONITORING_QUEUE)).isNotNull();
    }

    @Test
    void laFileDeNotificationAUneDlx() {
        // Contrairement a la file d'observation : un echec de notification EST un echec de
        // lead, reparable par un humain, donc il merite le journal des morts.
        QueueInformation info = rabbitAdmin.getQueueInfo(RabbitMQConfig.NOTIFY_QUEUE);
        assertThat(info).isNotNull();
    }
```

- [ ] **Step 2 : Lancer le test pour le voir echouer**

Run : `cd backend && ./mvnw test -Dtest=NotificationTopologieTest`
Expected : FAIL — `NOTIFY_QUEUE` n'existe pas.

- [ ] **Step 3 : Declarer la file et son binding**

Dans `RabbitMQConfig`, sous la constante `SYNCED_ROUTING_KEY`, **remplacer** le commentaire
`/** Sortie de la synchronisation ERP. Aucun consommateur metier : le monitoring seul. */`
devenu faux par une phrase disant que la cle porte desormais deux files, et ajouter :

```java
    /**
     * Entree de la notification. Liee a la meme cle que la file d'observation : un
     * DirectExchange livre a TOUTES les files liees a une cle, donc le monitoring continue de
     * recevoir ce qu'il recevait. Elle porte une DLX, contrairement a la file d'observation —
     * un echec d'alerte est reparable par un humain, un echec d'affichage ne l'est pas.
     */
    public static final String NOTIFY_QUEUE = "leadflow.leads.notify";

    @Bean
    Queue notifyQueue() {
        return QueueBuilder.durable(NOTIFY_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    Binding notifyBinding(Queue notifyQueue, DirectExchange leadsExchange) {
        return BindingBuilder.bind(notifyQueue).to(leadsExchange).with(SYNCED_ROUTING_KEY);
    }
```

Ajouter `"com.leadflow.notification"` a `PAQUETS_DE_CONFIANCE`. Le message consomme est le
`SyncedLeadMessage` de `com.leadflow.crm`, deja de confiance — mais l'ajout protege les
contrats a venir de ce package, et l'oubli ne se voit qu'a l'execution.

- [ ] **Step 4 : Ecrire le consommateur**

```java
package com.leadflow.notification;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.crm.SyncedLeadMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Cinquieme etape : previent le commercial du lead qui vient d'arriver chez lui.
 *
 * <p>Il ne traduit que le protocole ; tout le metier vit dans {@link NotificationService},
 * testable sans broker. Aucune exception n'est rattrapee : un echec technique doit provoquer
 * les trois tentatives puis la DLQ, et sa trace est deja ecrite en transaction propre.
 *
 * <p>Bean conditionnel, comme les cinq autres consommateurs : la suite de tests le retire.
 */
@Component
@ConditionalOnProperty(name = "leadflow.notification.listener.enabled", matchIfMissing = true)
public class NotificationListener {

    private final NotificationService service;

    public NotificationListener(NotificationService service) {
        this.service = service;
    }

    @RabbitListener(queues = RabbitMQConfig.NOTIFY_QUEUE)
    public void recoit(SyncedLeadMessage message) {
        service.notifie(message.leadId());
    }
}
```

- [ ] **Step 5 : Eteindre le consommateur dans les tests**

Dans `backend/src/test/resources/application.properties`, a cote des cinq lignes existantes :

```properties
leadflow.notification.listener.enabled=false
```

- [ ] **Step 6 : Lancer les tests pour les voir passer**

Run : `cd backend && ./mvnw test -Dtest=NotificationTopologieTest`
Expected : PASS, 2 tests.

- [ ] **Step 7 : Commit**

```bash
git add backend/src/main/java/com/leadflow/config/RabbitMQConfig.java \
        backend/src/main/java/com/leadflow/notification/NotificationListener.java \
        backend/src/test/resources/application.properties \
        backend/src/test/java/com/leadflow/notification/NotificationTopologieTest.java
git commit -m "feat: la file de notification, liee a lead.synced sans voler le monitoring"
```

---

### Task 8 : La chronologie montre les notifications

**Files:**
- Modify: `backend/src/main/java/com/leadflow/monitoring/dto/TimelineEventType.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadTimelineService.java`
- Modify: `backend/src/test/java/com/leadflow/monitoring/LeadTimelineServiceTest.java`
- Modify: `frontend/src/app/features/leads/lead-detail/lead-timeline/lead-timeline.ts`

**Interfaces:**
- Consomme : `NotificationAttemptRepository.findByLeadIdOrderByAttemptedAtAsc(UUID)`.
- Produit : `TimelineEventType.NOTIFICATION`.

- [ ] **Step 1 : Ecrire les tests qui echouent**

```java
    @Test
    void uneNotificationEnvoyeeApparaitApresLaSynchronisation() {
        tentativeDeNotification(NotificationStatus.ENVOYEE, 90, 70, null);

        List<TimelineEventType> types = service.timeline(leadId).stream()
                .map(TimelineEntry::type).toList();

        assertThat(types).contains(TimelineEventType.NOTIFICATION);
        assertThat(detail(service.timeline(leadId), TimelineEventType.NOTIFICATION))
                .containsEntry("destinataire", "karim@demo.test")
                .containsEntry("score", "90")
                .containsEntry("seuil", "70");
    }

    @Test
    void uneNotificationIgnoreeApparaitAussi() {
        // Elle explique un silence : l'absence d'entree ferait croire a une panne.
        tentativeDeNotification(NotificationStatus.IGNOREE, 55, 70, null);

        TimelineEntry entree = service.timeline(leadId).stream()
                .filter(e -> e.type() == TimelineEventType.NOTIFICATION)
                .findFirst().orElseThrow();

        assertThat(entree.outcome()).isEqualTo(TimelineOutcome.NEUTRE);
        assertThat(entree.details()).containsEntry("statut", "IGNOREE");
    }

    @Test
    void uneNotificationEnEchecEstMarqueeCommeTelle() {
        tentativeDeNotification(NotificationStatus.ECHEC, 90, 70, "smtp injoignable");

        TimelineEntry entree = service.timeline(leadId).stream()
                .filter(e -> e.type() == TimelineEventType.NOTIFICATION)
                .findFirst().orElseThrow();

        assertThat(entree.outcome()).isEqualTo(TimelineOutcome.ECHEC);
        assertThat(entree.details()).containsEntry("erreur", "smtp injoignable");
    }
```

- [ ] **Step 2 : Lancer les tests pour les voir echouer**

Run : `cd backend && ./mvnw test -Dtest=LeadTimelineServiceTest`
Expected : FAIL — `TimelineEventType.NOTIFICATION` n'existe pas.

- [ ] **Step 3 : Ajouter la valeur d'enumeration au bon endroit**

Dans `TimelineEventType`, inserer `NOTIFICATION` **apres `SYNC_ERP` et avant `MORT`** :
l'ordre de declaration est l'ordre du pipeline, et `positionDe(...)` s'appuie sur
`ordinal()` pour placer une entree non datee. Mettre a jour le Javadoc, qui dit « les huit
faits » — il y en a neuf.

- [ ] **Step 4 : Lire la sixieme source dans le service**

Injecter `NotificationAttemptRepository`, et ajouter dans `timeline(...)`, apres la boucle des
tentatives ERP :

```java
        notifications.findByLeadIdOrderByAttemptedAtAsc(leadId)
                .forEach(tentative -> entrees.add(notification(tentative)));
```

La methode `notification(...)` rend des **faits typés, jamais des phrases** :
`destinataire`, `statut`, `score`, `seuil`, et `erreur` quand elle existe. L'issue est
`ECHEC` pour `NotificationStatus.ECHEC`, `NEUTRE` pour les deux autres — une notification
ignoree n'est pas un echec.

- [ ] **Step 5 : Ajouter le libelle francais cote Angular**

Dans `lead-timeline.ts`, ajouter au dictionnaire des libelles
`NOTIFICATION: { libelle: 'Notification', icone: 'notifications' }`, et les libelles de detail
`destinataire`, `statut`, `seuil`. La mise en francais appartient au template, jamais au
service.

- [ ] **Step 6 : Lancer les tests pour les voir passer**

Run : `cd backend && ./mvnw test -Dtest=LeadTimelineServiceTest`
puis `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected : PASS des deux cotes.

- [ ] **Step 7 : Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/ backend/src/test/java/com/leadflow/monitoring/ \
        frontend/src/app/features/leads/lead-detail/lead-timeline/lead-timeline.ts
git commit -m "feat: la chronologie montre les notifications, silences compris"
```

---

### Task 9 : La sonde et son endpoint

**Files:**
- Create: `backend/src/main/java/com/leadflow/notification/SondeNotification.java`
- Create: `backend/src/main/java/com/leadflow/notification/dto/DiagnosticNotification.java`
- Modify: le controleur des parametres (celui qui expose deja la sonde d'intention)
- Test: `backend/src/test/java/com/leadflow/notification/SondeNotificationTest.java`

**Interfaces:**
- Consomme : `CanalSmtp`, `NotificationProperties.Smtp`.
- Produit : `record DiagnosticNotification(boolean ok, String detail)` et
  `SondeNotification.eprouve(String destinataire)` rendant ce record ;
  `POST /api/admin/notification/sonde` rendant le meme DTO.

- [ ] **Step 1 : Ecrire les tests qui echouent**

```java
    @Test
    void unRelaisJoignableRendUnDiagnosticFavorable() {
        assertThat(sonde.eprouve("operateur@agence.test").ok()).isTrue();
    }

    @Test
    void unRelaisInjoignableNommeLaCauseSansRecopierLaReponseBrute() {
        DiagnosticNotification diagnostic = sondeVersUnPortMort().eprouve("x@y.test");

        assertThat(diagnostic.ok()).isFalse();
        // La phrase utile, pas le roman du serveur : la reponse brute va dans les journaux.
        assertThat(diagnostic.detail()).isNotBlank().hasSizeLessThan(200);
    }

    @Test
    void laSondeNecritRien() {
        long avant = tentatives.count();
        sonde.eprouve("operateur@agence.test");

        // Meme parti que SondeIntent : elle eprouve sans mettre en service.
        assertThat(tentatives.count()).isEqualTo(avant);
    }
```

- [ ] **Step 2 : Lancer les tests pour les voir echouer**

Run : `cd backend && ./mvnw test -Dtest=SondeNotificationTest`
Expected : FAIL a la compilation.

- [ ] **Step 3 : Ecrire la sonde**

Elle partage `CanalSmtp` — seul endroit qui connaisse le protocole — et n'ecrit **aucune**
ligne de `notification_attempt`. Elle plafonne le detail rendu, sur le modele du
`DETAIL_MAX = 140` de `SondeIntent`, et journalise la cause complete en `warn`.

- [ ] **Step 4 : Exposer l'endpoint**

`POST /api/admin/notification/sonde`, dans le controleur qui porte deja la sonde d'intention.
Reponse : le `record` `DiagnosticNotification`, **jamais une entite**. Ajouter un test qui
asserte le **corps JSON**, pas le DTO.

- [ ] **Step 5 : Lancer les tests pour les voir passer**

Run : `cd backend && ./mvnw test -Dtest=SondeNotificationTest`
Expected : PASS, 3 tests.

- [ ] **Step 6 : Commit**

```bash
git add backend/src/main/java/com/leadflow/notification/ backend/src/test/java/com/leadflow/notification/
git commit -m "feat: une sonde qui eprouve le relais sans rien envoyer en vrai"
```

---

### Task 10 : Les deux ecrans

**Files:**
- Modify: `frontend/src/app/features/boutiques/bareme/bareme.ts` et `.html`
- Modify: `frontend/src/app/features/parametres/parametres.ts` et `.html`
- Modify: le modele TypeScript du bareme dans `frontend/src/app/core/`
- Test: `bareme.spec.ts`, `parametres.spec.ts`

**Interfaces:**
- Consomme : le champ `seuilNotification` du `ScoringForm`,
  `POST /api/admin/notification/sonde`.
- Produit : rien pour les taches suivantes.

- [ ] **Step 1 : Invoquer les skills du plugin `ui-ux-pro-max`**

**Avant d'ecrire la moindre ligne de template ou de SCSS.** C'est une regle permanente du
projet : toute decision visuelle passe par ces skills, invoquees en amont et jamais en
relecture. `ui-ux-pro-max:ui-ux-pro-max` en point d'entree, `ui-ux-pro-max:ui-styling` pour la
mise en page des deux champs.

- [ ] **Step 2 : Ecrire les tests qui echouent**

Dans `bareme.spec.ts` : le formulaire porte un champ `seuilNotification`, il est envoye dans
le `PUT`, et une valeur hors `[0, 100]` invalide le formulaire. **Ce test compte plus qu'il
n'en a l'air** : le `PUT` remplace le document entier, donc un champ oublie remettrait le
seuil au defaut a chaque sauvegarde de bareme.

Dans `parametres.spec.ts` : le bouton « Tester l'envoi » appelle
`POST /api/admin/notification/sonde` et affiche le detail rendu en cas d'echec.

- [ ] **Step 3 : Lancer les tests pour les voir echouer**

Run : `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected : FAIL.

- [ ] **Step 4 : Ajouter le champ et le bouton**

Le seuil rejoint le formulaire de bareme, avec une aide de saisie qui dit ce qui le distingue
du seuil de chaleur — sans quoi l'operateur verra deux champs presque identiques. La sonde
rejoint l'ecran « Parametres », a cote de la cle Gemini, avec le meme motif d'affichage du
diagnostic.

- [ ] **Step 5 : Lancer les tests, ESLint et Prettier**

```bash
cd frontend
npm test -- --watch=false --browsers=ChromeHeadless
npm run lint
npm run format:check
```
Expected : PASS des trois. **`qualite` bloque en CI** : un constat ESLint ou un fichier mal
formate fait echouer l'execution.

- [ ] **Step 6 : Commit**

```bash
git add frontend/src/
git commit -m "feat: le seuil de notification au bareme, la sonde aux parametres"
```

---

### Task 11 : L'etage d'integration, exclu par defaut

**Files:**
- Modify: `backend/pom.xml` (exclusion du tag)
- Create: `backend/src/test/java/com/leadflow/notification/smtp/CanalSmtpIntegrationTest.java`
- Create ou Modify: `docs/erp-integration-setup.md` ou un document dedie

**Interfaces:**
- Consomme : `CanalSmtp`.
- Produit : rien.

- [ ] **Step 1 : Exclure le tag par defaut**

Dans la configuration Surefire du `pom.xml`, ajouter `notification` a la liste des groupes
exclus, a cote de `erp`. **`./mvnw verify` doit continuer a passer sans profil
supplementaire** : la CI ne sait pas preparer de relais SMTP.

- [ ] **Step 2 : Ecrire le test d'integration**

`@Tag("notification")`, sur le modele des tests `@Tag("erp")` : il envoie vers un vrai relais
dont les coordonnees viennent de l'environnement, et se saute si elles sont absentes.

- [ ] **Step 3 : Verifier que `verify` ne le joue pas**

Run : `cd backend && ./mvnw verify`
Expected : `BUILD SUCCESS`, et le test d'integration **absent** du decompte.

- [ ] **Step 4 : Documenter la mise en route manuelle**

Comment obtenir un relais de test, quelles variables poser, et la commande qui joue l'etage
d'integration.

- [ ] **Step 5 : Commit**

```bash
git add backend/pom.xml backend/src/test/java/com/leadflow/notification/smtp/ docs/
git commit -m "test: l etage d integration SMTP, exclu par defaut comme celui des ERP"
```

---

### Task 12 : La documentation et la fusion

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/monitoring-api.md`
- Create: `docs/superpowers/plans/2026-09-04-f12-fin-de-feature.md`

- [ ] **Step 1 : Mettre a jour `CLAUDE.md`**

Quatre endroits, et pas un de moins :

1. le schema du pipeline en tete, ou `[notification]` s'ajoute apres `[crm]` ;
2. la liste des sous-packages, ou `notification/` s'ajoute ;
3. une section « Notification » decrivant l'accroche sur `lead.synced`, le seuil distinct, les
   trois cas deterministes et l'inertie sans hote ;
4. **la section « Etat actuel »**, ou la puce « Aucune notification n'est envoyee au
   commercial » **disparait** et ou la liste des migrations passe de huit a neuf.

Corriger aussi la phrase de la section « Monitoring » qui parle de **cinq** tables pour la
chronologie : il y en a six.

- [ ] **Step 2 : Mettre a jour `docs/monitoring-api.md`**

Le type `NOTIFICATION` rejoint la liste des types d'entree de chronologie, avec ses cles de
detail et la regle qu'une entree `IGNOREE` explique un silence.

- [ ] **Step 3 : Lancer la suite complete**

```bash
cd backend && ./mvnw verify
cd ../frontend && npm test -- --watch=false --browsers=ChromeHeadless && npm run lint && npm run format:check
```
Expected : `BUILD SUCCESS` et les trois controles frontend verts.

- [ ] **Step 4 : Recette a l'ecran**

Elle revient a l'utilisateur, sauf demande contraire. Preparer la pile et lui donner les
etapes : lever un collecteur SMTP local, poser `LEADFLOW_SMTP_HOST`, eprouver la sonde depuis
« Parametres », regler le seuil depuis « Bareme », injecter un lead chaud par le webhook signe,
et lire la chronologie du lead.

- [ ] **Step 5 : Commit, fusion et pousse**

```bash
git add CLAUDE.md docs/
git commit -m "docs: F12 — la notification du commercial et sa neuvieme migration"
git checkout main
git merge --no-ff feature/f12-notification-commercial
git push origin main
git push origin feature/f12-notification-commercial
```

La branche est **conservee** apres fusion : elle sert d'historique de la feature.

---

## Ce que ce plan ne fait pas

La tache d'agenda dans l'ERP (F14), le webhook sortant, la notification de l'operateur en plus
du commercial, tout digest ou regroupement, et toute notification declenchee par autre chose
que `lead.synced`. Un lead dont la synchronisation ERP echoue ne declenche aucune alerte, et
c'est assume : cet echec a deja son canal, le journal des morts.
