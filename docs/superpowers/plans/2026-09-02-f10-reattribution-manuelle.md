# F10 — Reattribution manuelle et journal d'actions : plan d'implementation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permettre a l'operateur de corriger a la main le commercial d'un lead deja
attribue, et retenir ce geste — ainsi que les rejeux et ecarts de messages morts — dans un
journal d'actions affiche dans la chronologie du lead.

**Architecture:** L'ecriture vit dans `routing/`, responsable du choix du commercial depuis
F4 : un service non transactionnel orchestre les controles, delegue l'ecriture a
`RoutedLeadWriter` en `REQUIRES_NEW`, puis journalise apres le commit. Aucun message n'est
publie sur le broker — le tour de role n'est pas idempotent. `monitoring/` lit la nouvelle
table pour l'afficher dans la timeline de F9, sans jamais l'ecrire.

**Tech Stack:** Java 21, Spring Boot (Spring MVC, Spring Data JPA, Spring AMQP), Flyway,
PostgreSQL, JUnit 5 + AssertJ + MockMvc + Testcontainers ; Angular 20 standalone avec signals,
Angular Material, Karma + Jasmine.

**Spec:** `docs/superpowers/specs/2026-09-02-f10-reattribution-manuelle-design.md`

## Global Constraints

- **Branche** : `feature/f10-reattribution-manuelle`, deja creee. Fusion locale en `--no-ff`
  dans `main` apres recette de l'utilisateur ; la branche est **conservee**, jamais supprimee.
- **Migration** : `V8__lead_action.sql`. Ne jamais modifier une migration deja appliquee —
  Flyway echoue au demarrage sur le checksum.
- **`ddl-auto: validate`** : Hibernate ne cree aucune table. Toute colonne d'entite doit
  exister dans la migration, au nom pres.
- **Le backend ecoute sur `:8090`**, pas `:8080`.
- **Docker doit tourner** pour `./mvnw test` : `BackendApplicationTests` importe
  `TestcontainersConfiguration`. Verifier `docker info` avant de commencer.
- **Toute classe de test qui ecrit en base doit rendre la base comme elle l'a trouvee** — un
  `@AfterEach` supprimant dans l'ordre des cles etrangeres. C'est ce manquement qui a rendu la
  CI rouge apres F9.
- **Avant tout `git push`**, jouer les trois paquets dans un seul JVM :
  `cd backend && ./mvnw test -Dtest='com.leadflow.capture.**.*Test,com.leadflow.routing.**.*Test,com.leadflow.monitoring.**.*Test' -DfailIfNoSpecifiedTests=false`
  (une dizaine de minutes sur ce poste).
- **Le crochet `pre-push` prend plus de deux minutes** (ESLint, Prettier, 44 tests Karma). Lui
  laisser dix minutes ; un push coupe trop tot ressemble a un echec sans en etre un.
- **Copie de travail en LF.** Prettier compare avec `endOfLine: lf`.
- **Aucune entite JPA ne franchit la frontiere HTTP.** Toute reponse passe par un `record`.
- **Le nom de l'operateur vient du `Principal`**, jamais du corps de la requete.
- **Aucune decision visuelle sans les skills du plugin `ui-ux-pro-max`**, invoquees *avant*
  d'ecrire le code (tache 8).
- **Le francais du code et des commentaires est sans accents** dans le backend, comme
  l'ensemble du projet ; les templates Angular, eux, portent les accents.
- **Commits** : un par tache, message en francais, prefixe `feat:` / `test:` / `docs:`.

---

## Fichiers du chantier

**Crees — backend**

| Fichier | Responsabilite |
| --- | --- |
| `backend/src/main/resources/db/migration/V8__lead_action.sql` | la table `lead_action` et l'index unique partiel de `dead_letter` |
| `.../com/leadflow/routing/LeadAction.java` | l'entite |
| `.../com/leadflow/routing/LeadActionType.java` | `REATTRIBUTION`, `REJEU`, `ECART` |
| `.../com/leadflow/routing/LeadActionOutcome.java` | `SUCCES`, `ECHEC` |
| `.../com/leadflow/routing/LeadActionRepository.java` | le depot |
| `.../com/leadflow/routing/LeadActionJournal.java` | l'ecriture en `REQUIRES_NEW` |
| `.../com/leadflow/routing/ReattributionService.java` | les controles et l'orchestration |
| `.../com/leadflow/routing/ReattributionImpossibleException.java` | le refus unique, mappe sur `409` |
| `.../com/leadflow/routing/ReassignmentForm.java` | le corps de la requete |
| `.../com/leadflow/routing/LeadReassignmentController.java` | `POST /api/leads/{id}/reassign` |

**Modifies — backend**

| Fichier | Modification |
| --- | --- |
| `routing/RoutedLeadWriter.java` | methode `reattribue` |
| `monitoring/deadletter/DeadLetterListener.java` | rattrapage de la violation d'unicite |
| `monitoring/deadletter/DeadLetterRepository.java` | `findFirstByLeadIdAndStatus` |
| `monitoring/deadletter/DeadLetterReplayService.java` | motif obligatoire, ecriture au journal |
| `monitoring/deadletter/DeadLetterController.java` | le motif dans le corps |
| `monitoring/dto/TimelineEventType.java` | septieme valeur `REATTRIBUTION` |
| `monitoring/LeadTimelineService.java` | lecture de `lead_action`, deduplication des rejeux |
| `common/ApiExceptionHandler.java` | `ReattributionImpossibleException` → `409` |

**Crees — frontend**

| Fichier | Responsabilite |
| --- | --- |
| `frontend/src/app/features/leads/lead-detail/reattribution-dialog/reattribution-dialog.ts` (+ `.html`, `.scss`, `.spec.ts`) | le dialogue de reattribution |
| `frontend/src/app/shared/motif-dialog/motif-dialog.ts` (+ `.html`, `.scss`, `.spec.ts`) | la saisie d'un motif, partagee par le rejeu et l'ecart |

**Modifies — frontend**

| Fichier | Modification |
| --- | --- |
| `core/models/lead.ts` | `REATTRIBUTION` dans `TimelineEventType`, interface `ReassignmentForm` |
| `core/api/lead-api.ts` | `reattribue(id, formulaire)` |
| `core/api/dead-letter-api.ts` | motif sur `rejoue` et `ecarte` |
| `features/leads/lead-detail/lead-detail.ts` / `.html` | le bouton et le cablage |
| `features/leads/lead-detail/lead-timeline/lead-timeline.ts` | libelles du nouveau fait |
| `features/queue/queue.ts` | le dialogue de motif a la place de `confirm()` |

---

## Deux decisions d'architecture a connaitre avant de commencer

**`routing/` importe `monitoring/` pour rendre `LeadDetail`.** Le controleur de reattribution
rend la fiche rechargee, donc il injecte `LeadDetailService`. C'est le meme parti que F8, ou
`tenant/` importe `ScoringConfig` de `qualification/` : le cycle de packages est un fait
assume, et ce qui le rend sur est le test qui verrouille le contrat. L'alternative — un record
maigre duplique dans `routing/` — ferait deriver la forme du commercial affiche.

**Le rattrapage de la violation d'unicite vit dans le *listener*, pas dans le journal.** La
violation remonte au flush, a l'interieur de la transaction `REQUIRES_NEW` ; la transaction
est alors marquee rollback-only et toute lecture faite a l'interieur echouerait. C'est
exactement la raison pour laquelle `LeadCaptureService` rattrape autour de
`RawLeadEventWriter.insere` et non dedans. On reproduit ce placement.

---

### Task 1: La migration `V8`, l'entite et son depot

**Files:**

- Create: `backend/src/main/resources/db/migration/V8__lead_action.sql`
- Create: `backend/src/main/java/com/leadflow/routing/LeadActionType.java`
- Create: `backend/src/main/java/com/leadflow/routing/LeadActionOutcome.java`
- Create: `backend/src/main/java/com/leadflow/routing/LeadAction.java`
- Create: `backend/src/main/java/com/leadflow/routing/LeadActionRepository.java`
- Test: `backend/src/test/java/com/leadflow/routing/LeadActionRepositoryTest.java`

**Interfaces:**

- Consumes: rien.
- Produces: `LeadAction` (entite, getters/setters Lombok) ; `LeadActionType.REATTRIBUTION |
  REJEU | ECART` ; `LeadActionOutcome.SUCCES | ECHEC` ; `LeadActionRepository extends
  JpaRepository<LeadAction, UUID>` avec
  `List<LeadAction> findByLeadIdOrderByCreatedAtAsc(UUID leadId)`.

- [ ] **Step 1: Ecrire la migration**

`backend/src/main/resources/db/migration/V8__lead_action.sql` :

```sql
-- Journal des actions humaines portees par un lead : reattribution, rejeu, ecart.
-- Il repond a une question qu'aucune table ne sait tenir aujourd'hui — qui a change quoi,
-- quand, et pourquoi. dead_letter ne retient d'un rejeu que replayed_by et replayed_at,
-- jamais son motif ni son resultat.
CREATE TABLE lead_action (
    id                    UUID         PRIMARY KEY,
    -- Cle etrangere, contrairement a dead_letter.lead_id : l'action part toujours d'un lead
    -- qu'on vient de lire, jamais d'un message corrompu.
    lead_id               UUID         NOT NULL REFERENCES lead(id),
    action                VARCHAR(32)  NOT NULL
        CONSTRAINT ck_lead_action_action CHECK (action IN ('REATTRIBUTION','REJEU','ECART')),
    -- Sujet du jeton JWT : l'operateur unique du dashboard.
    actor                 VARCHAR(120) NOT NULL,
    reason                TEXT         NOT NULL,
    -- Sans cle etrangere, deliberement : un commercial supprime ne doit pas effacer
    -- l'histoire. Le journal affiche l'identifiant tel quel.
    previous_sales_rep_id UUID,
    new_sales_rep_id      UUID,
    -- Nul pour une reattribution. Sans lui, la timeline afficherait deux fois un meme
    -- rejeu : une fois derive de dead_letter.replayed_at, une fois lu ici.
    dead_letter_id        UUID,
    -- Une reattribution vaut toujours SUCCES : la ligne n'est ecrite qu'apres le commit de
    -- l'ecriture, donc un echec ne produit aucune ligne. ECHEC ne concerne que le rejeu.
    outcome               VARCHAR(16)  NOT NULL
        CONSTRAINT ck_lead_action_outcome CHECK (outcome IN ('SUCCES','ECHEC')),
    detail                TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_lead_action_lead ON lead_action (lead_id, created_at);

-- La dette de F6, payee ici parce qu'on ecrit la migration de toute facon. Partiel et non
-- global sur le contenu : la livraison etant at-least-once, une meme mort livree deux fois
-- ecrirait deux lignes — mais une seconde mort APRES un rejeu qui a de nouveau echoue est
-- un fait reel qu'il faut garder. Restreindre aux lignes PENDING distingue ces deux cas, et
-- aligne le schema sur le garde-fou deja ecrit en Java : QualifiedLeadRelay et
-- RoutedLeadRelay ignorent les leads portant une mort PENDING.
CREATE UNIQUE INDEX uq_dead_letter_lead_pending
    ON dead_letter (lead_id) WHERE lead_id IS NOT NULL AND status = 'PENDING';
```

- [ ] **Step 2: Ecrire les deux enumerations**

`LeadActionType.java` :

```java
package com.leadflow.routing;

/** Les trois gestes humains qu'un lead peut subir. Les valeurs sont celles du CHECK de V8. */
public enum LeadActionType {
    REATTRIBUTION,
    REJEU,
    ECART
}
```

`LeadActionOutcome.java` :

```java
package com.leadflow.routing;

/**
 * Issue d'une action. {@code ECHEC} ne concerne que le rejeu d'un message mort : une
 * reattribution n'ecrit sa ligne qu'apres le commit de l'ecriture, donc son echec ne
 * produit aucune ligne.
 */
public enum LeadActionOutcome {
    SUCCES,
    ECHEC
}
```

- [ ] **Step 3: Ecrire l'entite**

`LeadAction.java` — n'herite pas de `BaseEntity`, comme `DeadLetter` : une action a une date
de creation et rien a mettre a jour.

```java
package com.leadflow.routing;

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
 * Un geste humain porte par un lead, et ce qui le justifie.
 *
 * <p>N'herite pas de {@code BaseEntity} : une ligne de journal ne se met jamais a jour,
 * donc {@code updated_at} n'aurait aucun sens. Meme parti que {@code DeadLetter}.
 *
 * <p>Les deux references de commerciaux ne portent pas de cle etrangere : un commercial
 * supprime ne doit pas effacer l'histoire.
 */
@Entity
@Table(name = "lead_action")
@Getter
@Setter
public class LeadAction {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private LeadActionType action;

    @Column(name = "actor", nullable = false, length = 120)
    private String actor;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "previous_sales_rep_id")
    private UUID previousSalesRepId;

    @Column(name = "new_sales_rep_id")
    private UUID newSalesRepId;

    @Column(name = "dead_letter_id")
    private UUID deadLetterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private LeadActionOutcome outcome;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
```

- [ ] **Step 4: Ecrire le depot**

`LeadActionRepository.java` :

```java
package com.leadflow.routing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadActionRepository extends JpaRepository<LeadAction, UUID> {

    /** Ordre chronologique : c'est celui dans lequel la timeline les fusionne. */
    List<LeadAction> findByLeadIdOrderByCreatedAtAsc(UUID leadId);
}
```

- [ ] **Step 5: Ecrire le test**

`backend/src/test/java/com/leadflow/routing/LeadActionRepositoryTest.java`. Il eprouve deux
choses : la ligne fait l'aller-retour sans perdre de champ, et le schema accepte ce que
l'entite declare — c'est ce second point que `ddl-auto: validate` seul ne prouverait pas.

```java
package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadActionRepositoryTest {

    @Autowired private LeadActionRepository actions;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;

    /**
     * La base Testcontainers est partagee par toute la suite : un lead survivant empeche
     * {@code capture/} de vider {@code raw_lead_event}, et vingt-cinq tests tombent.
     */
    @AfterEach
    void nettoyage() {
        actions.deleteAll();
        leads.deleteAll();
        evenements.deleteAll();
        clients.deleteAll();
    }

    @Test
    void uneActionFaitLAllerRetourSansRienPerdre() {
        UUID leadId = unLead();
        UUID ancien = UUID.randomUUID();
        UUID nouveau = UUID.randomUUID();

        LeadAction action = new LeadAction();
        action.setLeadId(leadId);
        action.setAction(LeadActionType.REATTRIBUTION);
        action.setActor("admin");
        action.setReason("Depart en conge");
        action.setPreviousSalesRepId(ancien);
        action.setNewSalesRepId(nouveau);
        action.setOutcome(LeadActionOutcome.SUCCES);
        actions.saveAndFlush(action);

        List<LeadAction> relues = actions.findByLeadIdOrderByCreatedAtAsc(leadId);
        assertThat(relues).hasSize(1);
        LeadAction relue = relues.get(0);
        assertThat(relue.getAction()).isEqualTo(LeadActionType.REATTRIBUTION);
        assertThat(relue.getActor()).isEqualTo("admin");
        assertThat(relue.getReason()).isEqualTo("Depart en conge");
        assertThat(relue.getPreviousSalesRepId()).isEqualTo(ancien);
        assertThat(relue.getNewSalesRepId()).isEqualTo(nouveau);
        assertThat(relue.getDeadLetterId()).isNull();
        assertThat(relue.getOutcome()).isEqualTo(LeadActionOutcome.SUCCES);
        assertThat(relue.getCreatedAt()).isNotNull();
    }

    private UUID unLead() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Journal");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        UUID clientId = clients.saveAndFlush(client).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "a@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail("a@exemple.fr");
        lead.setScore(40);
        lead.setStatus(LeadStatus.QUALIFIED);
        return leads.saveAndFlush(lead).getId();
    }
}
```

- [ ] **Step 6: Lancer le test et le voir echouer**

```bash
cd backend && ./mvnw test -Dtest=LeadActionRepositoryTest
```

Attendu : **echec**. Si les fichiers Java n'existent pas encore au moment ou l'on lance, c'est
une erreur de compilation ; si la migration manque, c'est Hibernate qui refuse de valider le
schema (`Schema-validation: missing table [lead_action]`).

- [ ] **Step 7: Lancer le test et le voir passer**

```bash
cd backend && ./mvnw test -Dtest=LeadActionRepositoryTest
```

Attendu : **PASS**. Si Flyway se plaint d'un checksum, c'est qu'une migration anterieure a ete
touchee — la reverter, ne jamais la « reparer ».

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V8__lead_action.sql \
        backend/src/main/java/com/leadflow/routing/LeadAction.java \
        backend/src/main/java/com/leadflow/routing/LeadActionType.java \
        backend/src/main/java/com/leadflow/routing/LeadActionOutcome.java \
        backend/src/main/java/com/leadflow/routing/LeadActionRepository.java \
        backend/src/test/java/com/leadflow/routing/LeadActionRepositoryTest.java
git commit -m "feat: la table lead_action, et l index unique que dead_letter attendait"
```

---

### Task 2: Le journal, et le rattrapage de l'unicite des morts

**Files:**

- Create: `backend/src/main/java/com/leadflow/routing/LeadActionJournal.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterRepository.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterListener.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/deadletter/DeadLetterJournalTest.java`

**Interfaces:**

- Consumes: `LeadAction`, `LeadActionRepository` (tache 1).
- Produces: `LeadActionJournal.enregistre(LeadAction action) : LeadAction` en
  `REQUIRES_NEW` ; `DeadLetterRepository.findFirstByLeadIdAndStatus(UUID leadId,
  DeadLetterStatus status) : Optional<DeadLetter>`.

- [ ] **Step 1: Ecrire le journal**

`LeadActionJournal.java` :

```java
package com.leadflow.routing;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecrit une ligne du journal d'actions, en transaction propre.
 *
 * <p>Classe distincte pour la meme raison que {@code DeadLetterJournal} et
 * {@code LeadWriter} : l'appelant n'est pas transactionnel, et un {@code REQUIRES_NEW} rend
 * la frontiere explicite — quand cette methode rend la main, la ligne est commitee.
 */
@Component
public class LeadActionJournal {

    private final LeadActionRepository repository;

    public LeadActionJournal(LeadActionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LeadAction enregistre(LeadAction action) {
        return repository.saveAndFlush(action);
    }
}
```

- [ ] **Step 2: Ajouter la methode de relecture au depot des morts**

Dans `DeadLetterRepository.java`, ajouter :

```java
    /**
     * Sert le rattrapage de {@code uq_dead_letter_lead_pending} : quand l'insertion d'une
     * mort echoue sur l'index unique, c'est cette ligne-la qui a gagne.
     */
    Optional<DeadLetter> findFirstByLeadIdAndStatus(UUID leadId, DeadLetterStatus status);
```

Ajouter les imports `java.util.Optional` et `java.util.UUID` s'ils manquent.

- [ ] **Step 3: Ecrire le test qui echoue**

`backend/src/test/java/com/leadflow/monitoring/deadletter/DeadLetterJournalTest.java` :

```java
package com.leadflow.monitoring.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * L'index unique partiel de V8 : un lead n'a qu'une mort en attente d'action humaine.
 *
 * <p>Le test asserte le comportement de la <b>base</b>, pas celui du listener : c'est la
 * contrainte qui tranche, et le rattrapage applicatif n'a de sens que si elle leve.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeadLetterJournalTest {

    @Autowired private DeadLetterJournal journal;
    @Autowired private DeadLetterRepository morts;

    @AfterEach
    void nettoyage() {
        morts.deleteAll();
    }

    @Test
    void deuxMortsEnAttentePourUnMemeLeadSontRefusees() {
        UUID leadId = UUID.randomUUID();
        journal.enregistre(mort(leadId, DeadLetterStatus.PENDING));

        assertThatThrownBy(() -> journal.enregistre(mort(leadId, DeadLetterStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(morts.findFirstByLeadIdAndStatus(leadId, DeadLetterStatus.PENDING))
                .isPresent();
    }

    @Test
    void uneNouvelleMortEstAccepteeQuandLaPrecedenteEstRejouee() {
        UUID leadId = UUID.randomUUID();
        DeadLetter premiere = journal.enregistre(mort(leadId, DeadLetterStatus.PENDING));
        premiere.setStatus(DeadLetterStatus.REPLAYED);
        premiere.setReplayedAt(Instant.now());
        morts.saveAndFlush(premiere);

        // Une seconde mort apres un rejeu qui a de nouveau echoue est un fait reel : l'index
        // partiel doit la laisser passer.
        journal.enregistre(mort(leadId, DeadLetterStatus.PENDING));

        assertThat(morts.findAll()).hasSize(2);
    }

    @Test
    void deuxMortsSansLeadIdNeSeGenentPas() {
        // L'index est partiel sur lead_id IS NOT NULL : un message illisible, dont on n'a
        // pas su tirer d'identifiant, doit toujours pouvoir etre journalise.
        journal.enregistre(mort(null, DeadLetterStatus.PENDING));
        journal.enregistre(mort(null, DeadLetterStatus.PENDING));

        assertThat(morts.findAll()).hasSize(2);
    }

    private DeadLetter mort(UUID leadId, DeadLetterStatus statut) {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.routed");
        mort.setRoutingKey("lead.routed");
        mort.setPayload("{}");
        mort.setLeadId(leadId);
        mort.setStatus(statut);
        mort.setDeadAt(Instant.now());
        return mort;
    }
}
```

- [ ] **Step 4: Lancer le test et le voir echouer**

```bash
cd backend && ./mvnw test -Dtest=DeadLetterJournalTest
```

Attendu : `deuxMortsEnAttentePourUnMemeLeadSontRefusees` **echoue** si la tache 1 n'a pas ete
faite (aucune exception levee). Si la tache 1 est faite, ce test passe deja et seule la
compilation de `findFirstByLeadIdAndStatus` manque — c'est normal, l'index est le
comportement, le rattrapage vient au pas suivant.

- [ ] **Step 5: Rattraper la violation dans le listener**

Dans `DeadLetterListener.recoit`, remplacer la ligne
`DeadLetter ecrite = journal.enregistre(mort);` par :

```java
        DeadLetter ecrite;
        try {
            ecrite = journal.enregistre(mort);
        } catch (DataIntegrityViolationException doublon) {
            // uq_dead_letter_lead_pending : la meme mort a deja ete journalisee. La
            // livraison etant at-least-once, ce n'est pas une anomalie — on acquitte en
            // rendant la ligne gagnante, sans rien rediffuser.
            //
            // Le rattrapage est ICI et non dans le journal : la violation remonte au flush,
            // a l'interieur de la transaction REQUIRES_NEW, qui est alors marquee
            // rollback-only — toute lecture faite dedans echouerait. Meme placement que
            // LeadCaptureService autour de RawLeadEventWriter.insere.
            log.info("Mort deja journalisee pour le lead {} : doublon acquitte",
                    mort.getLeadId());
            morts.findFirstByLeadIdAndStatus(mort.getLeadId(), DeadLetterStatus.PENDING)
                    .orElseThrow(() -> doublon);
            return;
        }
```

Ajouter au constructeur et aux champs la dependance `DeadLetterRepository morts`, et les
imports `org.springframework.dao.DataIntegrityViolationException`.

- [ ] **Step 6: Lancer les tests du paquet et les voir passer**

```bash
cd backend && ./mvnw test -Dtest='com.leadflow.monitoring.deadletter.*Test' -DfailIfNoSpecifiedTests=false
```

Attendu : **PASS**, y compris les tests existants du journal des morts.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/leadflow/routing/LeadActionJournal.java \
        backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterRepository.java \
        backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterListener.java \
        backend/src/test/java/com/leadflow/monitoring/deadletter/DeadLetterJournalTest.java
git commit -m "feat: le journal d actions, et un doublon de mort qui ne casse plus rien"
```

---

### Task 3: `RoutedLeadWriter.reattribue`

**Files:**

- Modify: `backend/src/main/java/com/leadflow/routing/RoutedLeadWriter.java`
- Test: `backend/src/test/java/com/leadflow/routing/RoutedLeadWriterTest.java`

**Interfaces:**

- Consumes: rien de neuf.
- Produces: `RoutedLeadWriter.reattribue(UUID leadId, UUID salesRepId) : Lead` — pose le
  commercial **sans toucher au statut ni a `routedAt`**.

- [ ] **Step 1: Ecrire le test qui echoue**

Ajouter a `RoutedLeadWriterTest` (le jeu de donnees des tests existants est recopie ici : le
fichier n'a pas de fabrique partagee, et la tache ne doit pas en introduire une qui
reecrirait les tests existants) :

```java
    @Test
    void laReattributionNeToucheNiLeStatutNiLaDateDAttribution() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Sud");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        Client boutique = clients.saveAndFlush(client);
        UUID clientId = boutique.getId();

        SalesRep premier = new SalesRep();
        premier.setClient(boutique);
        premier.setFullName("Amina Bensalem");
        premier.setEmail("amina+" + UUID.randomUUID() + "@demo.test");
        premier.setActive(true);
        UUID premierId = commerciaux.saveAndFlush(premier).getId();

        SalesRep second = new SalesRep();
        second.setClient(boutique);
        second.setFullName("Karim Haddad");
        second.setEmail("karim+" + UUID.randomUUID() + "@demo.test");
        second.setActive(true);
        UUID secondId = commerciaux.saveAndFlush(second).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "b@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail("b@exemple.fr");
        lead.setScore(60);
        lead.setStatus(LeadStatus.QUALIFIED);
        UUID leadId = leads.saveAndFlush(lead).getId();

        writer.attribue(leadId, premierId);
        Instant dateDAttribution = leads.findById(leadId).orElseThrow().getRoutedAt();

        // Le lead passe SYNCED entre l'attribution et la reattribution : c'est le cas qui
        // compte, celui d'un lead deja pousse dans l'ERP.
        Lead synchronise = leads.findById(leadId).orElseThrow();
        synchronise.setStatus(LeadStatus.SYNCED);
        leads.saveAndFlush(synchronise);

        writer.reattribue(leadId, secondId);

        Lead relu = leads.findById(leadId).orElseThrow();
        assertThat(relu.getAssignedSalesRepId()).isEqualTo(secondId);
        // Les deux assertions qui font tout l'interet du test : la reattribution n'est pas
        // une attribution. routed_at date le passage automatique, qui a bien eu lieu ; le
        // statut appartient au pipeline, pas a l'operateur.
        assertThat(relu.getStatus()).isEqualTo(LeadStatus.SYNCED);
        assertThat(relu.getRoutedAt()).isEqualTo(dateDAttribution);
    }
```

- [ ] **Step 2: Lancer le test et le voir echouer**

```bash
cd backend && ./mvnw test -Dtest=RoutedLeadWriterTest
```

Attendu : erreur de compilation — `reattribue` n'existe pas.

- [ ] **Step 3: Ecrire la methode**

Dans `RoutedLeadWriter`, apres `attribue` :

```java
    /**
     * Reattribution manuelle : elle pose le commercial, et <b>rien d'autre</b>.
     *
     * <p>Le statut appartient au pipeline — un lead {@code SYNCED} reste {@code SYNCED},
     * puisqu'il est bien dans l'ERP. Et {@code routedAt} date l'attribution automatique, un
     * fait qui a eu lieu : la reattribution est un fait distinct, date par sa ligne de
     * {@code lead_action}. Reecrire l'un ou l'autre ferait mentir la chronologie.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lead reattribue(UUID leadId, UUID salesRepId) {
        Lead lead = leadRepository.findById(leadId).orElseThrow(
                () -> new IllegalStateException("Lead disparu en cours de reattribution : " + leadId));
        lead.setAssignedSalesRepId(salesRepId);
        return leadRepository.saveAndFlush(lead);
    }
```

- [ ] **Step 4: Lancer le test et le voir passer**

```bash
cd backend && ./mvnw test -Dtest=RoutedLeadWriterTest
```

Attendu : **PASS**, les trois tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/routing/RoutedLeadWriter.java \
        backend/src/test/java/com/leadflow/routing/RoutedLeadWriterTest.java
git commit -m "feat: reattribuer pose le commercial, et ne touche ni au statut ni a la date"
```

---

### Task 4: `ReattributionService` et ses quatre refus

**Files:**

- Create: `backend/src/main/java/com/leadflow/routing/ReattributionImpossibleException.java`
- Create: `backend/src/main/java/com/leadflow/routing/ReattributionService.java`
- Test: `backend/src/test/java/com/leadflow/routing/ReattributionServiceTest.java`

**Interfaces:**

- Consumes: `RoutedLeadWriter.reattribue` (tache 3), `LeadActionJournal.enregistre`
  (tache 2), `LeadRepository`, `SalesRepRepository`.
- Produces: `ReattributionService.reattribue(UUID leadId, UUID salesRepId, String motif,
  String operateur) : Lead` ; `ReattributionImpossibleException extends RuntimeException`.

- [ ] **Step 1: Ecrire l'exception**

```java
package com.leadflow.routing;

/**
 * Refus d'une reattribution pour une raison metier : lead sans commercial, commercial
 * inconnu, inactif, d'une autre boutique, ou deja en place.
 *
 * <p>Une seule exception pour les quatre cas, mappee sur {@code 409} : ce sont tous des
 * conflits avec l'etat courant, et les distinguer par des codes differents n'apprendrait
 * rien a l'ecran, qui affiche le message.
 */
public class ReattributionImpossibleException extends RuntimeException {

    public ReattributionImpossibleException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Ecrire le test qui echoue**

`backend/src/test/java/com/leadflow/routing/ReattributionServiceTest.java` :

```java
package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Les quatre refus, et le cas passant. Le journal fait partie du contrat : une
 * reattribution qui ne laisse pas de trace est un echec fonctionnel, pas un detail.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReattributionServiceTest {

    @Autowired private ReattributionService service;
    @Autowired private LeadActionRepository actions;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;
    @Autowired private SalesRepRepository commerciaux;

    @AfterEach
    void nettoyage() {
        actions.deleteAll();
        leads.deleteAll();
        evenements.deleteAll();
        commerciaux.deleteAll();
        clients.deleteAll();
    }

    @Test
    void reattribueEtJournalise() {
        Client boutique = uneBoutique("Agence Est");
        UUID premier = unCommercial(boutique, "Amina Bensalem");
        UUID second = unCommercial(boutique, "Karim Haddad");
        UUID leadId = unLead(boutique, premier, LeadStatus.ROUTED);

        Lead relu = service.reattribue(leadId, second, "Depart en conge", "admin");

        assertThat(relu.getAssignedSalesRepId()).isEqualTo(second);
        List<LeadAction> journal = actions.findByLeadIdOrderByCreatedAtAsc(leadId);
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0).getAction()).isEqualTo(LeadActionType.REATTRIBUTION);
        assertThat(journal.get(0).getActor()).isEqualTo("admin");
        assertThat(journal.get(0).getReason()).isEqualTo("Depart en conge");
        assertThat(journal.get(0).getPreviousSalesRepId()).isEqualTo(premier);
        assertThat(journal.get(0).getNewSalesRepId()).isEqualTo(second);
        assertThat(journal.get(0).getOutcome()).isEqualTo(LeadActionOutcome.SUCCES);
    }

    @Test
    void unLeadInconnuLeve() {
        assertThatThrownBy(() ->
                service.reattribue(UUID.randomUUID(), UUID.randomUUID(), "motif", "admin"))
                .isInstanceOf(RessourceIntrouvableException.class);
    }

    @Test
    void refuseUnLeadSansCommercial() {
        Client boutique = uneBoutique("Agence Sans");
        UUID cible = unCommercial(boutique, "Karim Haddad");
        UUID leadId = unLead(boutique, null, LeadStatus.QUALIFIED);

        assertThatThrownBy(() -> service.reattribue(leadId, cible, "motif", "admin"))
                .isInstanceOf(ReattributionImpossibleException.class);
        assertThat(actions.findByLeadIdOrderByCreatedAtAsc(leadId)).isEmpty();
    }

    @Test
    void refuseUnCommercialInactif() {
        Client boutique = uneBoutique("Agence Inactive");
        UUID premier = unCommercial(boutique, "Amina Bensalem");
        SalesRep dormant = new SalesRep();
        dormant.setClient(boutique);
        dormant.setFullName("Sofia Nadir");
        dormant.setEmail("sofia+" + UUID.randomUUID() + "@demo.test");
        dormant.setActive(false);
        UUID inactif = commerciaux.saveAndFlush(dormant).getId();
        UUID leadId = unLead(boutique, premier, LeadStatus.ROUTED);

        assertThatThrownBy(() -> service.reattribue(leadId, inactif, "motif", "admin"))
                .isInstanceOf(ReattributionImpossibleException.class);
    }

    @Test
    void refuseUnCommercialDUneAutreBoutique() {
        Client boutique = uneBoutique("Agence A");
        Client voisine = uneBoutique("Agence B");
        UUID premier = unCommercial(boutique, "Amina Bensalem");
        UUID etranger = unCommercial(voisine, "Youssef Alami");
        UUID leadId = unLead(boutique, premier, LeadStatus.ROUTED);

        assertThatThrownBy(() -> service.reattribue(leadId, etranger, "motif", "admin"))
                .isInstanceOf(ReattributionImpossibleException.class);
    }

    @Test
    void refuseLeCommercialDejaEnPlace() {
        Client boutique = uneBoutique("Agence Meme");
        UUID premier = unCommercial(boutique, "Amina Bensalem");
        UUID leadId = unLead(boutique, premier, LeadStatus.ROUTED);

        assertThatThrownBy(() -> service.reattribue(leadId, premier, "motif", "admin"))
                .isInstanceOf(ReattributionImpossibleException.class);
        assertThat(actions.findByLeadIdOrderByCreatedAtAsc(leadId)).isEmpty();
    }

    private Client uneBoutique(String nom) {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName(nom);
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        return clients.saveAndFlush(client);
    }

    private UUID unCommercial(Client boutique, String nom) {
        SalesRep commercial = new SalesRep();
        commercial.setClient(boutique);
        commercial.setFullName(nom);
        commercial.setEmail("c+" + UUID.randomUUID() + "@demo.test");
        commercial.setActive(true);
        return commerciaux.saveAndFlush(commercial).getId();
    }

    private UUID unLead(Client boutique, UUID salesRepId, LeadStatus statut) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(boutique.getId());
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "c@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(boutique.getId());
        lead.setRawEventId(rawEventId);
        lead.setEmail("c@exemple.fr");
        lead.setScore(50);
        lead.setStatus(statut);
        lead.setAssignedSalesRepId(salesRepId);
        if (salesRepId != null) {
            lead.setRoutedAt(Instant.now());
        }
        return leads.saveAndFlush(lead).getId();
    }
}
```

- [ ] **Step 3: Lancer le test et le voir echouer**

```bash
cd backend && ./mvnw test -Dtest=ReattributionServiceTest
```

Attendu : erreur de compilation — `ReattributionService` n'existe pas.

- [ ] **Step 4: Ecrire le service**

```java
package com.leadflow.routing;

import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reattribution manuelle d'un lead deja attribue.
 *
 * <p><b>Aucun message n'est publie.</b> Le tour de role n'est pas idempotent — rejouer une
 * attribution decale la rotation — et republier sur {@code lead.routed} renverrait vers
 * l'ERP un lead deja synchronise. La reattribution est une ecriture directe assumee par
 * l'operateur, tracee ; pas un rejeu de file.
 *
 * <p><b>Non transactionnel</b>, comme {@link LeadRoutingService} : l'ecriture porte sa
 * propre transaction ({@link RoutedLeadWriter}), et le journal la sienne. C'est ce qui
 * permet de journaliser <b>apres</b> le commit — l'ordre inverse laisserait une ligne
 * affirmant un changement qui n'a pas eu lieu.
 *
 * <p>Le risque assume est symetrique et moindre : une panne entre les deux etapes donne un
 * lead reattribue sans trace, que la timeline montre comme une attribution sans action.
 */
@Service
public class ReattributionService {

    private static final Logger log = LoggerFactory.getLogger(ReattributionService.class);

    private final LeadRepository leads;
    private final SalesRepRepository commerciaux;
    private final RoutedLeadWriter writer;
    private final LeadActionJournal journal;

    public ReattributionService(
            LeadRepository leads,
            SalesRepRepository commerciaux,
            RoutedLeadWriter writer,
            LeadActionJournal journal) {
        this.leads = leads;
        this.commerciaux = commerciaux;
        this.writer = writer;
        this.journal = journal;
    }

    public Lead reattribue(UUID leadId, UUID salesRepId, String motif, String operateur) {
        Lead lead = leads.findById(leadId).orElseThrow(
                () -> new RessourceIntrouvableException("Lead inconnu : " + leadId));

        UUID ancien = lead.getAssignedSalesRepId();
        if (ancien == null) {
            throw new ReattributionImpossibleException(
                    "Ce lead n a pas encore de commercial : il n y a rien a reattribuer");
        }
        if (ancien.equals(salesRepId)) {
            throw new ReattributionImpossibleException(
                    "Ce commercial est deja celui du lead");
        }

        SalesRep cible = commerciaux.findById(salesRepId).orElseThrow(
                () -> new ReattributionImpossibleException(
                        "Commercial inconnu : " + salesRepId));
        if (!cible.isActive()) {
            throw new ReattributionImpossibleException(
                    "Ce commercial est desactive : le reactiver d abord");
        }
        // Le controle qui n'est pas theorique : l'identifiant vient du client, et rien
        // d'autre n'empecherait d'attribuer le lead d'une boutique au commercial d'une autre.
        if (!cible.getClient().getId().equals(lead.getClientId())) {
            throw new ReattributionImpossibleException(
                    "Ce commercial appartient a une autre boutique");
        }

        Lead reattribue = writer.reattribue(leadId, salesRepId);

        LeadAction action = new LeadAction();
        action.setLeadId(leadId);
        action.setAction(LeadActionType.REATTRIBUTION);
        action.setActor(operateur);
        action.setReason(motif);
        action.setPreviousSalesRepId(ancien);
        action.setNewSalesRepId(salesRepId);
        action.setOutcome(LeadActionOutcome.SUCCES);
        journal.enregistre(action);

        log.info("Lead {} reattribue de {} a {} par {}", leadId, ancien, salesRepId, operateur);
        return reattribue;
    }
}
```

- [ ] **Step 5: Lancer le test et le voir passer**

```bash
cd backend && ./mvnw test -Dtest=ReattributionServiceTest
```

Attendu : **PASS**, les six tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/leadflow/routing/ReattributionService.java \
        backend/src/main/java/com/leadflow/routing/ReattributionImpossibleException.java \
        backend/src/test/java/com/leadflow/routing/ReattributionServiceTest.java
git commit -m "feat: la reattribution, ses quatre refus et sa trace"
```

---

### Task 5: L'endpoint `POST /api/leads/{id}/reassign`

**Files:**

- Create: `backend/src/main/java/com/leadflow/routing/ReassignmentForm.java`
- Create: `backend/src/main/java/com/leadflow/routing/LeadReassignmentController.java`
- Modify: `backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/leadflow/routing/LeadReassignmentControllerTest.java`

**Interfaces:**

- Consumes: `ReattributionService.reattribue` (tache 4),
  `com.leadflow.monitoring.LeadDetailService.detail(UUID) : LeadDetail`.
- Produces: `POST /api/leads/{id}/reassign`, corps
  `{"salesRepId": "<uuid>", "reason": "<texte>"}`, reponse `200` avec `LeadDetail`.

- [ ] **Step 1: Ecrire le formulaire**

```java
package com.leadflow.routing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Le corps de la reattribution. <b>L'operateur n'y figure pas</b> : il vient du
 * {@code Principal}, donc du jeton. Un acteur transmis par le client serait un journal
 * falsifiable.
 *
 * <p>Le motif est obligatoire : un journal dont la moitie des lignes n'ont pas de motif ne
 * sert a rien six mois plus tard, et le geste est assez rare pour que la phrase ne coute
 * pas cher.
 */
public record ReassignmentForm(
        @NotNull UUID salesRepId,
        @NotBlank @Size(max = 500) String reason) {
}
```

- [ ] **Step 2: Ecrire le controleur**

```java
package com.leadflow.routing;

import com.leadflow.monitoring.LeadDetailService;
import com.leadflow.monitoring.dto.LeadDetail;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reattribution manuelle d'un lead.
 *
 * <p>Le chemin suit la ressource, le package suit la responsabilite : l'URL commence par
 * {@code /api/leads}, mais le choix du commercial appartient a {@code routing/} depuis F4.
 *
 * <p><b>L'import de {@code monitoring/} est assume.</b> Rendre la fiche rechargee evite un
 * aller-retour et garantit que l'ecran affiche l'etat reellement enregistre. C'est le meme
 * parti qu'en F8, ou {@code tenant/} importe {@code qualification/} : le cycle de packages
 * est un fait, et ce qui compte est le test qui verrouille le contrat.
 */
@RestController
@RequestMapping("/api/leads")
public class LeadReassignmentController {

    private final ReattributionService service;
    private final LeadDetailService detail;

    public LeadReassignmentController(ReattributionService service, LeadDetailService detail) {
        this.service = service;
        this.detail = detail;
    }

    @PostMapping("/{id}/reassign")
    public LeadDetail reattribue(
            @PathVariable UUID id,
            @Valid @RequestBody ReassignmentForm formulaire,
            Principal operateur) {
        service.reattribue(id, formulaire.salesRepId(), formulaire.reason(),
                operateur.getName());
        return detail.detail(id);
    }
}
```

- [ ] **Step 3: Mapper le refus sur `409`**

Dans `ApiExceptionHandler`, a cote du gestionnaire de `DernierCommercialException` :

```java
    @ExceptionHandler(ReattributionImpossibleException.class)
    ProblemDetail reattributionImpossible(ReattributionImpossibleException echec) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, echec.getMessage());
    }
```

Ajouter l'import `com.leadflow.routing.ReattributionImpossibleException`.

- [ ] **Step 4: Ecrire le test qui echoue**

`backend/src/test/java/com/leadflow/routing/LeadReassignmentControllerTest.java` :

```java
package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Asserte le corps JSON, qui est le contrat, et non le record.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LeadReassignmentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LeadActionRepository actions;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;
    @Autowired private SalesRepRepository commerciaux;

    private UUID leadId;
    private UUID premier;
    private UUID second;

    @AfterEach
    void nettoyage() {
        actions.deleteAll();
        leads.deleteAll();
        evenements.deleteAll();
        commerciaux.deleteAll();
        clients.deleteAll();
    }

    @BeforeEach
    void jeuDeDonnees() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Controleur");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        Client boutique = clients.saveAndFlush(client);

        SalesRep un = new SalesRep();
        un.setClient(boutique);
        un.setFullName("Amina Bensalem");
        un.setEmail("amina+" + UUID.randomUUID() + "@demo.test");
        un.setActive(true);
        premier = commerciaux.saveAndFlush(un).getId();

        SalesRep deux = new SalesRep();
        deux.setClient(boutique);
        deux.setFullName("Karim Haddad");
        deux.setEmail("karim+" + UUID.randomUUID() + "@demo.test");
        deux.setActive(true);
        second = commerciaux.saveAndFlush(deux).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(boutique.getId());
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "d@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(boutique.getId());
        lead.setRawEventId(rawEventId);
        lead.setEmail("d@exemple.fr");
        lead.setScore(70);
        lead.setStatus(LeadStatus.ROUTED);
        lead.setAssignedSalesRepId(premier);
        lead.setRoutedAt(Instant.now());
        leadId = leads.saveAndFlush(lead).getId();
    }

    @Test
    @WithMockUser(username = "operateur-du-jeton")
    void rendLaFicheRechargeeEtJournaliseLOperateurDuJeton() throws Exception {
        mockMvc.perform(post("/api/leads/{id}/reassign", leadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesRepId\":\"" + second + "\",\"reason\":\"Conge\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesRep.id").value(second.toString()))
                .andExpect(jsonPath("$.salesRep.fullName").value("Karim Haddad"));

        List<LeadAction> journal = actions.findByLeadIdOrderByCreatedAtAsc(leadId);
        assertThat(journal).hasSize(1);
        // Le corps ne portait aucun acteur : il ne peut venir que du jeton.
        assertThat(journal.get(0).getActor()).isEqualTo("operateur-du-jeton");
    }

    @Test
    @WithMockUser
    void unLeadInconnuRend404() throws Exception {
        mockMvc.perform(post("/api/leads/{id}/reassign", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesRepId\":\"" + second + "\",\"reason\":\"Conge\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void leCommercialDejaEnPlaceRend409() throws Exception {
        mockMvc.perform(post("/api/leads/{id}/reassign", leadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesRepId\":\"" + premier + "\",\"reason\":\"Conge\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser
    void unMotifVideRend400() throws Exception {
        mockMvc.perform(post("/api/leads/{id}/reassign", leadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesRepId\":\"" + second + "\",\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
        assertThat(actions.findByLeadIdOrderByCreatedAtAsc(leadId)).isEmpty();
    }
}
```

- [ ] **Step 5: Lancer le test et le voir passer**

```bash
cd backend && ./mvnw test -Dtest=LeadReassignmentControllerTest
```

Attendu : **PASS**, les quatre tests. Si `unMotifVideRend400` rend `500`, c'est que
`@Valid` manque sur le parametre du controleur.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/leadflow/routing/ReassignmentForm.java \
        backend/src/main/java/com/leadflow/routing/LeadReassignmentController.java \
        backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java \
        backend/src/test/java/com/leadflow/routing/LeadReassignmentControllerTest.java
git commit -m "feat: POST /api/leads/{id}/reassign, et son refus en 409"
```

---

### Task 6: Le motif sur le rejeu et l'ecart d'un message mort

**Files:**

- Modify: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterReplayService.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterController.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/MotifForm.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/deadletter/DeadLetterReplayServiceTest.java` (etendre s'il existe, creer sinon)

**Interfaces:**

- Consumes: `LeadActionJournal.enregistre` (tache 2), `LeadAction`, `LeadActionType.REJEU |
  ECART`, `LeadActionOutcome` (tache 1).
- Produces: `DeadLetterReplayService.rejoue(UUID id, String operateur, String motif)` et
  `.ecarte(UUID id, String operateur, String motif)` ; `MotifForm(String reason)` ;
  `POST /api/dead-letters/{id}/replay` et `/discard` acceptent desormais un corps JSON
  `{"reason": "<texte>"}`.

- [ ] **Step 1: Ecrire le formulaire**

```java
package com.leadflow.monitoring.deadletter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Le motif d'un rejeu ou d'un ecart. Obligatoire pour la meme raison que celui de la
 * reattribution : {@code replayed_by} seul ne dit pas pourquoi.
 */
public record MotifForm(@NotBlank @Size(max = 500) String reason) {
}
```

- [ ] **Step 2: Ecrire le test qui echoue**

Dans `DeadLetterReplayServiceTest` (le creer sur le modele de `DeadLetterJournalTest` s'il
n'existe pas : memes `@SpringBootTest`, `@Import(TestcontainersConfiguration.class)` et
`@AfterEach` vidant `actions` puis `morts`) :

```java
    @Test
    void unEcartEcritSaLigneDeJournal() {
        UUID leadId = unLeadEnBase();
        DeadLetter mort = journal.enregistre(morteFor(leadId));

        service.ecarte(mort.getId(), "admin", "Doublon d un formulaire de test");

        List<LeadAction> lignes = actions.findByLeadIdOrderByCreatedAtAsc(leadId);
        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0).getAction()).isEqualTo(LeadActionType.ECART);
        assertThat(lignes.get(0).getActor()).isEqualTo("admin");
        assertThat(lignes.get(0).getReason()).isEqualTo("Doublon d un formulaire de test");
        assertThat(lignes.get(0).getDeadLetterId()).isEqualTo(mort.getId());
        assertThat(lignes.get(0).getOutcome()).isEqualTo(LeadActionOutcome.SUCCES);
    }

    @Test
    void uneMortSansLeadNEcritAucuneLigne() {
        // lead_action.lead_id porte une cle etrangere NOT NULL : une mort dont on n'a pas su
        // tirer d'identifiant de lead n'a nulle part ou etre journalisee, et c'est normal.
        DeadLetter mort = journal.enregistre(morteFor(null));

        service.ecarte(mort.getId(), "admin", "Message illisible");

        assertThat(actions.findAll()).isEmpty();
    }
```

Les deux fabriques de la classe :

```java
    private UUID unLeadEnBase() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Rejeu");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        UUID clientId = clients.saveAndFlush(client).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "e@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail("e@exemple.fr");
        lead.setScore(30);
        lead.setStatus(LeadStatus.QUALIFIED);
        return leads.saveAndFlush(lead).getId();
    }

    private DeadLetter morteFor(UUID leadId) {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.routed");
        mort.setRoutingKey("lead.routed");
        mort.setPayload("{}");
        mort.setLeadId(leadId);
        mort.setStatus(DeadLetterStatus.PENDING);
        mort.setDeadAt(Instant.now());
        return mort;
    }
```

- [ ] **Step 3: Lancer le test et le voir echouer**

```bash
cd backend && ./mvnw test -Dtest=DeadLetterReplayServiceTest
```

Attendu : erreur de compilation — `ecarte` ne prend que deux arguments.

- [ ] **Step 4: Faire porter le motif au service**

Dans `DeadLetterReplayService` : injecter `LeadActionJournal journal` (constructeur + champ),
ajouter le troisieme parametre `String motif` a `rejoue` et `ecarte`, et journaliser apres la
sauvegarde de la mort.

```java
    /**
     * Une mort dont on n'a pas su tirer de {@code leadId} n'est pas journalisee :
     * {@code lead_action.lead_id} porte une cle etrangere non nulle, et inventer une
     * reference ferait echouer l'ecriture exactement quand le message est le plus abime.
     */
    private void journalise(
            DeadLetter mort,
            LeadActionType type,
            String operateur,
            String motif,
            LeadActionOutcome issue,
            String detail) {
        if (mort.getLeadId() == null) {
            return;
        }
        LeadAction action = new LeadAction();
        action.setLeadId(mort.getLeadId());
        action.setAction(type);
        action.setActor(operateur);
        action.setReason(motif);
        action.setDeadLetterId(mort.getId());
        action.setOutcome(issue);
        action.setDetail(detail);
        journal.enregistre(action);
    }
```

Dans `rejoue`, l'appel se place apres `repository.saveAndFlush(mort)` :

```java
        journalise(mort, LeadActionType.REJEU, operateur, motif,
                LeadActionOutcome.SUCCES, null);
```

et dans le `catch (AmqpException echec)`, **avant** de lever :

```java
            journalise(mort, LeadActionType.REJEU, operateur, motif,
                    LeadActionOutcome.ECHEC, echec.getMessage());
```

Dans `ecarte`, apres `repository.saveAndFlush(mort)` :

```java
        journalise(mort, LeadActionType.ECART, operateur, motif,
                LeadActionOutcome.SUCCES, null);
```

- [ ] **Step 5: Faire porter le motif au controleur**

Dans `DeadLetterController` :

```java
    @PostMapping("/{id}/replay")
    public void rejoue(
            @PathVariable UUID id, @Valid @RequestBody MotifForm formulaire,
            Principal operateur) {
        rejeu.rejoue(id, operateur.getName(), formulaire.reason());
    }

    @PostMapping("/{id}/discard")
    public void ecarte(
            @PathVariable UUID id, @Valid @RequestBody MotifForm formulaire,
            Principal operateur) {
        rejeu.ecarte(id, operateur.getName(), formulaire.reason());
    }
```

Ajouter les imports `jakarta.validation.Valid` et
`org.springframework.web.bind.annotation.RequestBody`.

- [ ] **Step 6: Lancer les tests du paquet et les voir passer**

```bash
cd backend && ./mvnw test -Dtest='com.leadflow.monitoring.deadletter.*Test' -DfailIfNoSpecifiedTests=false
```

Attendu : **PASS**. Les tests de controleur existants qui appelaient `/replay` sans corps
echoueront en `400` — leur ajouter `.contentType(MediaType.APPLICATION_JSON)` et
`.content("{\"reason\":\"…\"}")`. C'est une rupture de contrat voulue, et le frontend suit en
tache 9.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/deadletter/ \
        backend/src/test/java/com/leadflow/monitoring/deadletter/
git commit -m "feat: un rejeu et un ecart disent desormais pourquoi, et ce qu ils ont donne"
```

---

### Task 7: La timeline — septieme fait, et deduplication des rejeux

**Files:**

- Modify: `backend/src/main/java/com/leadflow/monitoring/dto/TimelineEventType.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadTimelineService.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/LeadTimelineServiceTest.java`

**Interfaces:**

- Consumes: `LeadActionRepository.findByLeadIdOrderByCreatedAtAsc` (tache 1), `LeadAction`,
  `LeadActionType`, `LeadActionOutcome`.
- Produces: `TimelineEventType.REATTRIBUTION` ; les entrees de type `REATTRIBUTION` portent
  `details` avec les cles `ancienCommercial`, `nouveauCommercial`, `par`, `motif` ; les
  entrees `REJEU` issues du journal portent `par` et `motif`.

- [ ] **Step 1: Ajouter la valeur a l'enumeration**

```java
public enum TimelineEventType {
    CAPTURE,
    QUALIFICATION,
    ATTRIBUTION,
    // Apres ATTRIBUTION : l'ordre de declaration est l'ordre du pipeline, et il sert a
    // placer une entree non datee. Une reattribution suit toujours une attribution.
    REATTRIBUTION,
    SYNC_ERP,
    MORT,
    REJEU
}
```

- [ ] **Step 2: Ecrire le test qui echoue**

Ajouter a `LeadTimelineServiceTest` :

```java
    @Test
    void laReattributionApparaitApresLAttribution() {
        // Le lead et son attribution sont poses par le jeu de donnees de la classe.
        LeadAction action = new LeadAction();
        action.setLeadId(leadId);
        action.setAction(LeadActionType.REATTRIBUTION);
        action.setActor("admin");
        action.setReason("Depart en conge");
        action.setPreviousSalesRepId(UUID.randomUUID());
        action.setNewSalesRepId(UUID.randomUUID());
        action.setOutcome(LeadActionOutcome.SUCCES);
        actions.saveAndFlush(action);

        List<TimelineEntry> entrees = service.timeline(leadId);

        List<TimelineEventType> types = entrees.stream().map(TimelineEntry::type).toList();
        assertThat(types).contains(TimelineEventType.REATTRIBUTION);
        assertThat(types.indexOf(TimelineEventType.REATTRIBUTION))
                .isGreaterThan(types.indexOf(TimelineEventType.ATTRIBUTION));

        TimelineEntry reattribution = entrees.stream()
                .filter(e -> e.type() == TimelineEventType.REATTRIBUTION)
                .findFirst()
                .orElseThrow();
        assertThat(reattribution.details()).containsEntry("par", "admin");
        assertThat(reattribution.details()).containsEntry("motif", "Depart en conge");
    }

    @Test
    void unRejeuJournaliseNApparaitQuUneFois() {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.routed");
        mort.setRoutingKey("lead.routed");
        mort.setPayload("{}");
        mort.setLeadId(leadId);
        mort.setStatus(DeadLetterStatus.REPLAYED);
        mort.setDeadAt(Instant.now());
        mort.setReplayedAt(Instant.now());
        mort.setReplayedBy("admin");
        DeadLetter enregistree = morts.saveAndFlush(mort);

        LeadAction action = new LeadAction();
        action.setLeadId(leadId);
        action.setAction(LeadActionType.REJEU);
        action.setActor("admin");
        action.setReason("Dolibarr etait tombe");
        action.setDeadLetterId(enregistree.getId());
        action.setOutcome(LeadActionOutcome.SUCCES);
        actions.saveAndFlush(action);

        List<TimelineEntry> entrees = service.timeline(leadId);

        assertThat(entrees.stream().filter(e -> e.type() == TimelineEventType.REJEU))
                .hasSize(1);
        assertThat(entrees.stream()
                        .filter(e -> e.type() == TimelineEventType.REJEU)
                        .findFirst()
                        .orElseThrow()
                        .details())
                .containsEntry("motif", "Dolibarr etait tombe");
    }

    @Test
    void unRejeuAnterieurAV8GardeSonEntreeDerivee() {
        // Aucune ligne de lead_action : c'est tout l'historique d'avant la migration. Le
        // rejeu doit rester visible, avec ce qu'on sait de lui — qui, et quand.
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.routed");
        mort.setRoutingKey("lead.routed");
        mort.setPayload("{}");
        mort.setLeadId(leadId);
        mort.setStatus(DeadLetterStatus.REPLAYED);
        mort.setDeadAt(Instant.now());
        mort.setReplayedAt(Instant.now());
        mort.setReplayedBy("admin");
        morts.saveAndFlush(mort);

        List<TimelineEntry> entrees = service.timeline(leadId);

        assertThat(entrees.stream().filter(e -> e.type() == TimelineEventType.REJEU))
                .hasSize(1);
    }
```

Ajouter les champs `@Autowired private LeadActionRepository actions;` et, s'il manque,
`@Autowired private DeadLetterRepository morts;`, ainsi que `actions.deleteAll();` **en tete**
du `@AfterEach` existant.

- [ ] **Step 3: Lancer le test et le voir echouer**

```bash
cd backend && ./mvnw test -Dtest=LeadTimelineServiceTest
```

Attendu : `laReattributionApparaitApresLAttribution` echoue — aucune entree `REATTRIBUTION` ;
`unRejeuJournaliseNApparaitQuUneFois` echoue avec deux entrees `REJEU`.

- [ ] **Step 4: Lire le journal dans le service**

Dans `LeadTimelineService` : injecter `LeadActionRepository actions`, puis remplacer le bloc
des morts par :

```java
        List<LeadAction> journal = actions.findByLeadIdOrderByCreatedAtAsc(leadId);
        Set<UUID> mortsDejaJournalisees = journal.stream()
                .map(LeadAction::getDeadLetterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        morts.findByLeadIdOrderByDeadAtAsc(leadId).forEach(mort -> {
            entrees.add(mort(mort));
            // Le rejeu derive de dead_letter ne sert plus que l'historique anterieur a V8 :
            // des qu'une ligne de journal reference cette mort, c'est elle qui parle, et
            // elle en dit plus — le motif, et l'issue.
            if (mort.getReplayedAt() != null
                    && !mortsDejaJournalisees.contains(mort.getId())) {
                entrees.add(rejeu(mort));
            }
        });

        journal.forEach(action -> entrees.add(action(action)));
```

et ajouter la conversion :

```java
    /**
     * Un geste humain. Les cles de {@code details} sont des faits, jamais des phrases : la
     * mise en francais appartient au template Angular.
     */
    private TimelineEntry action(LeadAction action) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("par", action.getActor());
        details.put("motif", action.getReason());
        if (action.getPreviousSalesRepId() != null) {
            details.put("ancienCommercial", String.valueOf(action.getPreviousSalesRepId()));
        }
        if (action.getNewSalesRepId() != null) {
            details.put("nouveauCommercial", String.valueOf(action.getNewSalesRepId()));
        }
        if (action.getDetail() != null) {
            details.put("erreur", action.getDetail());
        }
        return new TimelineEntry(
                typeDe(action.getAction()),
                action.getCreatedAt(),
                action.getOutcome() == LeadActionOutcome.SUCCES
                        ? TimelineOutcome.NEUTRE
                        : TimelineOutcome.ECHEC,
                Map.copyOf(details));
    }

    /**
     * Un ecart se montre comme un rejeu : c'est la meme decision humaine prise sur la meme
     * mort, et distinguer les deux dans la chronologie n'apprendrait rien de plus que le
     * motif, deja affiche.
     */
    private TimelineEventType typeDe(LeadActionType type) {
        return type == LeadActionType.REATTRIBUTION
                ? TimelineEventType.REATTRIBUTION
                : TimelineEventType.REJEU;
    }
```

Ajouter les imports `java.util.Objects`, `java.util.Set`,
`java.util.stream.Collectors`, `com.leadflow.routing.LeadAction`,
`com.leadflow.routing.LeadActionOutcome`, `com.leadflow.routing.LeadActionRepository`,
`com.leadflow.routing.LeadActionType`.

- [ ] **Step 5: Lancer le test et le voir passer**

```bash
cd backend && ./mvnw test -Dtest=LeadTimelineServiceTest
```

Attendu : **PASS**, tous les tests de la classe, y compris ceux de F9.

- [ ] **Step 6: Jouer les trois paquets dans un seul JVM**

```bash
cd backend && ./mvnw test -Dtest='com.leadflow.capture.**.*Test,com.leadflow.routing.**.*Test,com.leadflow.monitoring.**.*Test' -DfailIfNoSpecifiedTests=false
```

Attendu : **0 echec**. C'est le controle qui manquait apres F9 ; ne pas le sauter sous
pretexte que chaque classe passe seule.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/dto/TimelineEventType.java \
        backend/src/main/java/com/leadflow/monitoring/LeadTimelineService.java \
        backend/src/test/java/com/leadflow/monitoring/LeadTimelineServiceTest.java
git commit -m "feat: la chronologie montre les gestes humains, sans les compter deux fois"
```

---

### Task 8: L'ecran — le dialogue de reattribution

**Files:**

- Modify: `frontend/src/app/core/models/lead.ts`
- Modify: `frontend/src/app/core/api/lead-api.ts`
- Create: `frontend/src/app/features/leads/lead-detail/reattribution-dialog/reattribution-dialog.ts`
- Create: `frontend/src/app/features/leads/lead-detail/reattribution-dialog/reattribution-dialog.html`
- Create: `frontend/src/app/features/leads/lead-detail/reattribution-dialog/reattribution-dialog.scss`
- Modify: `frontend/src/app/features/leads/lead-detail/lead-detail.ts`
- Modify: `frontend/src/app/features/leads/lead-detail/lead-detail.html`
- Modify: `frontend/src/app/features/leads/lead-detail/lead-timeline/lead-timeline.ts`
- Test: `frontend/src/app/features/leads/lead-detail/reattribution-dialog/reattribution-dialog.spec.ts`

**Interfaces:**

- Consumes: `POST /api/leads/{id}/reassign` (tache 5) ;
  `ClientApi.commerciaux(clientId) : Observable<SalesRepSummary[]>` — **existe deja**, sur
  `/api/clients/{id}/sales-reps`, et `SalesRepSummary` porte `active` ;
  `LeadTimeline.recharge()` — publique depuis F9.
- Produces: `LeadApi.reattribue(id, formulaire) : Observable<LeadDetail>` ;
  `ReattributionDialog` avec `MAT_DIALOG_DATA` de type
  `{ leadId: string; clientId: string; salesRepId: string; statut: LeadStatus }`, fermant sur
  `LeadDetail | undefined`.

- [ ] **Step 1: Invoquer les skills de design**

**Avant d'ecrire la moindre ligne de template.** Le dialogue est le premier de l'application :
la liste des commerciaux, le champ de motif et l'avertissement `SYNCED` sont des decisions
visuelles.

```
Skill: ui-ux-pro-max:ui-ux-pro-max   (entree : styles, palette, typographie)
Skill: ui-ux-pro-max:ui-styling      (le composant concret et sa mise en page)
```

Les jetons de design existants du dashboard sont la reference ; le dialogue s'y conforme, il
n'en invente pas.

- [ ] **Step 2: Etendre les modeles**

Dans `core/models/lead.ts` :

```ts
export type TimelineEventType =
  | 'CAPTURE'
  | 'QUALIFICATION'
  | 'ATTRIBUTION'
  | 'REATTRIBUTION'
  | 'SYNC_ERP'
  | 'MORT'
  | 'REJEU';

/** Le corps de `POST /api/leads/{id}/reassign`. L'operateur vient du jeton, pas d'ici. */
export interface ReassignmentForm {
  salesRepId: string;
  reason: string;
}
```

- [ ] **Step 3: Etendre l'API**

Dans `core/api/lead-api.ts`, en important `ReassignmentForm` :

```ts
  /**
   * Rend la fiche rechargee : le backend renvoie le `LeadDetail` a jour, ce qui evite un
   * aller-retour et garantit que l'ecran montre l'etat reellement enregistre.
   */
  reattribue(id: string, formulaire: ReassignmentForm) {
    return this.http.post<LeadDetail>(`/api/leads/${id}/reassign`, formulaire);
  }
```

- [ ] **Step 4: Ecrire le test qui echoue**

`reattribution-dialog.spec.ts` :

```ts
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ReattributionDialog } from './reattribution-dialog';

describe('ReattributionDialog', () => {
  let http: HttpTestingController;
  const fermeture = { close: jasmine.createSpy('close') };

  function monte(statut: string) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ReattributionDialog],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: MatDialogRef, useValue: fermeture },
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            leadId: 'lead-1',
            clientId: 'client-1',
            salesRepId: 'rep-1',
            statut,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(ReattributionDialog);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/clients/client-1/sales-reps').flush([
      { id: 'rep-1', fullName: 'Amina', email: 'a@x.fr', active: true },
      { id: 'rep-2', fullName: 'Karim', email: 'k@x.fr', active: true },
      { id: 'rep-3', fullName: 'Sofia', email: 's@x.fr', active: false },
    ]);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => http.verify());

  it('n offre ni le commercial en place ni les desactives', () => {
    const dialogue = monte('ROUTED').componentInstance;
    expect(dialogue.candidats().map((c: { id: string }) => c.id)).toEqual(['rep-2']);
  });

  it('avertit seulement quand le lead est deja synchronise', () => {
    expect(monte('ROUTED').componentInstance.avertitSurERP()).toBeFalse();
    expect(monte('SYNCED').componentInstance.avertitSurERP()).toBeTrue();
  });

  it('refuse de valider sans motif', () => {
    const dialogue = monte('ROUTED').componentInstance;
    dialogue.commercialChoisi.set('rep-2');
    dialogue.motif.set('   ');
    expect(dialogue.valide()).toBeFalse();

    dialogue.confirme();
    http.expectNone('/api/leads/lead-1/reassign');
  });

  it('ferme en rendant la fiche rechargee', () => {
    const dialogue = monte('ROUTED').componentInstance;
    dialogue.commercialChoisi.set('rep-2');
    dialogue.motif.set('Depart en conge');
    dialogue.confirme();

    const requete = http.expectOne('/api/leads/lead-1/reassign');
    expect(requete.request.body).toEqual({
      salesRepId: 'rep-2',
      reason: 'Depart en conge',
    });
    requete.flush({ id: 'lead-1' });
    expect(fermeture.close).toHaveBeenCalledWith({ id: 'lead-1' });
  });
});
```

- [ ] **Step 5: Lancer le test et le voir echouer**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Attendu : echec de compilation — `ReattributionDialog` n'existe pas.

- [ ] **Step 6: Ecrire le composant**

`reattribution-dialog.ts` :

```ts
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  MAT_DIALOG_DATA,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ClientApi } from '../../../../core/api/client-api';
import { LeadApi } from '../../../../core/api/lead-api';
import { LeadDetail } from '../../../../core/models/lead';
import { LeadStatus, SalesRepSummary } from '../../../../core/models/monitoring';

export interface ReattributionData {
  leadId: string;
  clientId: string;
  /** Le commercial en place : il ne figure pas parmi les candidats. */
  salesRepId: string;
  statut: LeadStatus;
}

/**
 * Reattribution manuelle d'un lead.
 *
 * Le premier dialogue de l'application : le motif etant obligatoire, un `confirm()` du
 * navigateur — la solution retenue par l'ecran du journal des morts — ne suffisait plus.
 *
 * L'avertissement sur un lead `SYNCED` n'est pas cosmetique : la reattribution ne touche pas
 * l'ERP, ou le lead reste rattache a l'ancien responsable. Le taire ferait croire a une
 * correction qui n'a pas lieu.
 */
@Component({
  selector: 'app-reattribution-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './reattribution-dialog.html',
  styleUrl: './reattribution-dialog.scss',
})
export class ReattributionDialog {
  private readonly clients = inject(ClientApi);
  private readonly leads = inject(LeadApi);
  private readonly fermeture = inject(MatDialogRef<ReattributionDialog, LeadDetail>);
  readonly data = inject<ReattributionData>(MAT_DIALOG_DATA);

  readonly commerciaux = signal<SalesRepSummary[]>([]);
  readonly commercialChoisi = signal<string | null>(null);
  readonly motif = signal('');
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);

  /**
   * Ni le commercial en place — le backend refuse le geste en 409 — ni les desactives, qu'il
   * refuse aussi. Filtrer ici evite de proposer un choix voue au refus.
   */
  readonly candidats = computed(() =>
    this.commerciaux().filter((c) => c.active && c.id !== this.data.salesRepId),
  );

  readonly avertitSurERP = computed(() => this.data.statut === 'SYNCED');

  readonly valide = computed(
    () => this.commercialChoisi() !== null && this.motif().trim().length > 0,
  );

  constructor() {
    this.clients.commerciaux(this.data.clientId).subscribe({
      next: (liste) => this.commerciaux.set(liste),
      error: () => this.erreur.set('La liste des commerciaux n a pas pu etre chargee.'),
    });
  }

  confirme(): void {
    if (!this.valide() || this.enCours()) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    this.leads
      .reattribue(this.data.leadId, {
        salesRepId: this.commercialChoisi()!,
        reason: this.motif().trim(),
      })
      .subscribe({
        next: (fiche) => this.fermeture.close(fiche),
        error: (echec) => {
          this.enCours.set(false);
          // Le backend rend un ProblemDetail dont `detail` porte la phrase utile ; le corps
          // brut ne s'affiche jamais tel quel.
          this.erreur.set(
            echec?.error?.detail ?? 'La reattribution n a pas pu etre enregistree.',
          );
        },
      });
  }

  annule(): void {
    this.fermeture.close();
  }
}
```

`reattribution-dialog.html` — la mise en forme suit ce qu'ont donne les skills de l'etape 1 ;
la structure attendue :

```html
<h2 mat-dialog-title>Réattribuer ce lead</h2>

<mat-dialog-content>
  @if (avertitSurERP()) {
    <p class="avertissement">
      <mat-icon>warning</mat-icon>
      Ce lead est déjà synchronisé. L'ERP conservera l'ancien responsable : seule LeadFlow
      est corrigée.
    </p>
  }

  <mat-form-field appearance="outline">
    <mat-label>Nouveau commercial</mat-label>
    <mat-select [ngModel]="commercialChoisi()" (ngModelChange)="commercialChoisi.set($event)">
      @for (candidat of candidats(); track candidat.id) {
        <mat-option [value]="candidat.id">{{ candidat.fullName }}</mat-option>
      }
    </mat-select>
  </mat-form-field>

  <mat-form-field appearance="outline">
    <mat-label>Motif</mat-label>
    <textarea
      matInput
      rows="3"
      [ngModel]="motif()"
      (ngModelChange)="motif.set($event)"
      maxlength="500"
    ></textarea>
    <mat-hint>Obligatoire : il est conservé dans la chronologie du lead.</mat-hint>
  </mat-form-field>

  @if (erreur(); as message) {
    <p class="erreur">{{ message }}</p>
  }
</mat-dialog-content>

<mat-dialog-actions align="end">
  <button mat-button type="button" (click)="annule()">Annuler</button>
  <button
    mat-flat-button
    type="button"
    [disabled]="!valide() || enCours()"
    (click)="confirme()"
  >
    Réattribuer
  </button>
</mat-dialog-actions>
```

- [ ] **Step 7: Cabler la fiche du lead**

Dans `lead-detail.ts` : ajouter `private readonly dialogue = inject(MatDialog);`, une
`viewChild` sur la chronologie, et la methode d'ouverture.

```ts
  readonly chronologie = viewChild.required(LeadTimeline);

  /** Seuls les leads deja attribues peuvent l'etre a nouveau ; le backend le refuse sinon. */
  readonly reattribuable = computed(() => this.lead()?.salesRep != null);

  ouvreLaReattribution(): void {
    const lead = this.lead();
    if (!lead?.salesRep) {
      return;
    }
    this.dialogue
      .open(ReattributionDialog, {
        width: '32rem',
        data: {
          leadId: lead.id,
          clientId: lead.clientId,
          salesRepId: lead.salesRep.id,
          statut: lead.status,
        },
      })
      .afterClosed()
      .subscribe((fiche) => {
        if (!fiche) {
          return;
        }
        this.lead.set(fiche);
        // La chronologie se recharge seule : c'est ce pour quoi `recharge()` a ete rendue
        // publique en F9, et pourquoi son endpoint est separe du detail.
        this.chronologie().recharge();
      });
  }
```

Imports a ajouter : `viewChild`, `computed`, `MatDialog` depuis `@angular/material/dialog`,
`ReattributionDialog`, `LeadTimeline`.

Dans `lead-detail.html`, dans le bloc « Commercial » :

```html
            <dd>
              @if (lead.salesRep; as commercial) {
                {{ commercial.fullName }} — {{ commercial.email }}
                <button
                  mat-button
                  type="button"
                  class="champs__action"
                  (click)="ouvreLaReattribution()"
                >
                  <mat-icon>swap_horiz</mat-icon>
                  Réattribuer
                </button>
              } @else {
                Non attribué
              }
            </dd>
```

- [ ] **Step 8: Nommer le nouveau fait dans la chronologie**

Dans `lead-timeline.ts`, ajouter a `FAITS` :

```ts
  REATTRIBUTION: { libelle: 'Réattribution', icone: 'swap_horiz' },
```

et a `DETAILS` :

```ts
  motif: 'motif',
  ancienCommercial: 'ancien commercial',
  nouveauCommercial: 'nouveau commercial',
```

- [ ] **Step 9: Lancer les tests et les voir passer**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
npm run lint
npm run format:check
```

Attendu : **PASS** pour les trois. Si `format:check` signale des dizaines de fichiers
inchanges, la copie de travail est en CRLF — verifier `.gitattributes` et re-extraire, ne pas
reformater.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/app/core/models/lead.ts \
        frontend/src/app/core/api/lead-api.ts \
        frontend/src/app/features/leads/lead-detail/
git commit -m "feat: reattribuer un lead depuis sa fiche, chronologie rechargee"
```

---

### Task 9: Le motif a l'ecran du journal des morts

**Files:**

- Create: `frontend/src/app/shared/motif-dialog/motif-dialog.ts` (+ `.html`, `.scss`)
- Modify: `frontend/src/app/core/api/dead-letter-api.ts`
- Modify: `frontend/src/app/features/queue/queue.ts`
- Test: `frontend/src/app/shared/motif-dialog/motif-dialog.spec.ts`
- Test: `frontend/src/app/core/api/dead-letter-api.spec.ts`

**Interfaces:**

- Consumes: `POST /api/dead-letters/{id}/replay` et `/discard` avec corps
  `{"reason": "<texte>"}` (tache 6).
- Produces: `MotifDialog` avec `MAT_DIALOG_DATA` de type
  `{ titre: string; avertissement?: string }`, fermant sur `string | undefined` ;
  `DeadLetterApi.rejoue(id, motif)` et `.ecarte(id, motif)`.

- [ ] **Step 1: Ecrire le test du dialogue**

`motif-dialog.spec.ts` :

```ts
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MotifDialog } from './motif-dialog';

describe('MotifDialog', () => {
  const fermeture = { close: jasmine.createSpy('close') };

  function monte(donnees: { titre: string; avertissement?: string }) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [MotifDialog],
      providers: [
        provideNoopAnimations(),
        { provide: MatDialogRef, useValue: fermeture },
        { provide: MAT_DIALOG_DATA, useValue: donnees },
      ],
    });
    const fixture = TestBed.createComponent(MotifDialog);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('refuse de valider sans motif', () => {
    const dialogue = monte({ titre: 'Rejouer ce message' });
    dialogue.motif.set('  ');
    expect(dialogue.valide()).toBeFalse();
  });

  it('rend le motif nettoye', () => {
    const dialogue = monte({ titre: 'Ecarter ce message' });
    dialogue.motif.set('  Doublon  ');
    dialogue.confirme();
    expect(fermeture.close).toHaveBeenCalledWith('Doublon');
  });
});
```

- [ ] **Step 2: Lancer le test et le voir echouer**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Attendu : echec de compilation — `MotifDialog` n'existe pas.

- [ ] **Step 3: Ecrire le dialogue**

`motif-dialog.ts` :

```ts
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface MotifData {
  titre: string;
  /** Phrase calculee par le backend, comme `replayWarning` — affichee telle quelle. */
  avertissement?: string;
}

/**
 * Saisie d'un motif obligatoire, partagee par le rejeu et l'ecart d'un message mort.
 *
 * Dans `shared/` et non dans l'ecran du journal : le geste est le meme des deux cotes, et
 * un motif obligatoire ne se demande pas avec un `confirm()`, qui ne sait pas lire du texte.
 */
@Component({
  selector: 'app-motif-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  templateUrl: './motif-dialog.html',
  styleUrl: './motif-dialog.scss',
})
export class MotifDialog {
  private readonly fermeture = inject(MatDialogRef<MotifDialog, string>);
  readonly data = inject<MotifData>(MAT_DIALOG_DATA);

  readonly motif = signal('');
  readonly valide = computed(() => this.motif().trim().length > 0);

  confirme(): void {
    if (!this.valide()) {
      return;
    }
    this.fermeture.close(this.motif().trim());
  }

  annule(): void {
    this.fermeture.close();
  }
}
```

`motif-dialog.html` :

```html
<h2 mat-dialog-title>{{ data.titre }}</h2>

<mat-dialog-content>
  @if (data.avertissement; as phrase) {
    <p class="avertissement">{{ phrase }}</p>
  }

  <mat-form-field appearance="outline">
    <mat-label>Motif</mat-label>
    <textarea
      matInput
      rows="3"
      [ngModel]="motif()"
      (ngModelChange)="motif.set($event)"
      maxlength="500"
    ></textarea>
    <mat-hint>Obligatoire : il est conservé dans la chronologie du lead.</mat-hint>
  </mat-form-field>
</mat-dialog-content>

<mat-dialog-actions align="end">
  <button mat-button type="button" (click)="annule()">Annuler</button>
  <button mat-flat-button type="button" [disabled]="!valide()" (click)="confirme()">
    Confirmer
  </button>
</mat-dialog-actions>
```

- [ ] **Step 4: Faire porter le motif a l'API**

Dans `core/api/dead-letter-api.ts`, les deux methodes prennent un second argument et
l'envoient dans le corps :

```ts
  rejoue(id: string, motif: string) {
    return this.http.post<void>(`/api/dead-letters/${id}/replay`, { reason: motif });
  }

  ecarte(id: string, motif: string) {
    return this.http.post<void>(`/api/dead-letters/${id}/discard`, { reason: motif });
  }
```

Mettre a jour `dead-letter-api.spec.ts` : les tests existants doivent passer un motif et
asserter `requete.request.body` — `{ reason: '…' }` et non `{}`.

- [ ] **Step 5: Remplacer les `confirm()` de l'ecran**

Dans `queue.ts`, `rejoue` et `ecarte` ouvrent le dialogue au lieu d'appeler `confirm()` :

```ts
  rejoue(mort: DeadLetterView): void {
    this.dialogue
      .open(MotifDialog, {
        width: '30rem',
        data: { titre: 'Rejouer ce message', avertissement: mort.replayWarning ?? undefined },
      })
      .afterClosed()
      .subscribe((motif) => {
        if (!motif) {
          return;
        }
        this.appelle(this.api.rejoue(mort.id, motif), 'Message rejoue.');
      });
  }

  ecarte(mort: DeadLetterView): void {
    this.dialogue
      .open(MotifDialog, {
        width: '30rem',
        data: {
          titre: 'Ecarter ce message',
          avertissement: 'Il ne sera plus rejouable.',
        },
      })
      .afterClosed()
      .subscribe((motif) => {
        if (!motif) {
          return;
        }
        this.appelle(this.api.ecarte(mort.id, motif), 'Message ecarte.');
      });
  }
```

Ajouter `private readonly dialogue = inject(MatDialog);` et les imports.

**Le rejeu de la selection** (`rejoueLaSelection`) demande **un seul motif pour tout le lot** :
ouvrir le dialogue une fois, puis boucler avec ce motif. Ne pas demander un motif par ligne.

- [ ] **Step 6: Lancer les tests et les voir passer**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
npm run lint
npm run format:check
```

Attendu : **PASS** pour les trois.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/shared/motif-dialog/ \
        frontend/src/app/core/api/dead-letter-api.ts \
        frontend/src/app/core/api/dead-letter-api.spec.ts \
        frontend/src/app/features/queue/queue.ts
git commit -m "feat: le journal des morts demande un motif au lieu d un confirm"
```

---

### Task 10: La documentation, et la verification complete

**Files:**

- Modify: `CLAUDE.md`
- Modify: `docs/monitoring-api.md`

- [ ] **Step 1: Mettre a jour `CLAUDE.md`**

Trois endroits, et rien d'autre :

- **Base de donnees** : « Sept migrations existent » devient huit, avec la phrase sur `V8` —
  la table `lead_action`, et l'index unique partiel de `dead_letter` qu'elle porte aussi.
- **Routage** : un paragraphe sur la reattribution manuelle — elle vit dans `routing/`, elle
  ne publie aucun message, elle ne touche ni le statut ni `routed_at`, et elle deplace le tour
  de role.
- **Etat actuel** : retirer « Aucune reattribution manuelle » de la liste des manques, et
  ajouter que la propagation vers l'ERP reste absente — un lead `SYNCED` reattribue garde son
  ancien responsable chez Dolibarr.

- [ ] **Step 2: Documenter les endpoints**

Dans `docs/monitoring-api.md`, ajouter `POST /api/leads/{id}/reassign` avec son `curl`, son
corps, sa reponse et ses trois codes d'echec ; et signaler que `/replay` et `/discard`
**exigent desormais un corps** `{"reason": "…"}`.

- [ ] **Step 3: Jouer la suite complete**

```bash
cd backend && ./mvnw verify
cd ../frontend && npm test -- --watch=false --browsers=ChromeHeadless && npm run lint && npm run format:check
```

Attendu : **0 echec**. `./mvnw verify` sans `-Perp-it` — le profil ferait echouer la CI sur des
conteneurs Dolibarr et Odoo qu'elle ne sait pas preparer.

- [ ] **Step 4: Commit et push**

```bash
git add CLAUDE.md docs/monitoring-api.md
git commit -m "docs: F10 — la reattribution manuelle et la huitieme migration"
git push -u origin feature/f10-reattribution-manuelle
```

Le crochet `pre-push` joue ESLint, Prettier et les 44 tests Karma : **lui laisser dix
minutes**. La CI se declenche sur la branche (`fumee` ecoute `feature/**`) ; attendre ses
quatre jobs verts avant de proposer la recette.

- [ ] **Step 5: Rendre la main pour la recette**

La recette manuelle a l'ecran est faite par l'utilisateur. Lui indiquer le chemin :
`docker compose up -d`, puis
`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`, puis `npm start` — et lui rappeler de
tourner le secret HMAC depuis l'ecran « Boutiques » si la base a ete remontee, la valeur en
clair de `R__demo_data.sql` etant fausse depuis F7.2.

Ne pas fusionner dans `main` avant son accord.
