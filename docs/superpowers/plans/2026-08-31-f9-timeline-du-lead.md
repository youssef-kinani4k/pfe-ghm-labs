# F9 — Timeline dérivée du lead — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Donner à l'écran `leads/:id` une chronologie unique et ordonnée de tout ce qui est arrivé à un lead, de sa capture à son éventuel rejeu.

**Architecture:** Un endpoint dédié `GET /api/leads/{id}/timeline` sert une liste d'entrées typées, dérivée en lecture seule de quatre tables existantes (`raw_lead_event`, `lead`, `crm_sync_attempt`, `dead_letter`). Une seule donnée est réellement nouvelle : `lead.routed_at`, ajoutée par la migration `V7`, parce que l'attribution au commercial n'était datée nulle part. Le frontend consomme cet endpoint dans un composant local à l'écran de détail.

**Tech Stack:** Spring Boot 4 (Java 21), Flyway, Postgres 16, JPA/Hibernate, JUnit 5 + MockMvc + Testcontainers ; Angular 20 standalone avec signals, Angular Material, Karma + Jasmine.

**Spec:** `docs/superpowers/specs/2026-08-31-f9-timeline-du-lead-design.md`

## Global Constraints

- **Branche de travail : `feature/f9-timeline-du-lead`**, déjà créée. Ne jamais committer sur `main`.
- **`ddl-auto: validate`** — Hibernate ne crée aucune table. Toute évolution de schéma est un nouveau fichier `backend/src/main/resources/db/migration/V<n>__description.sql`. Ne jamais modifier une migration déjà appliquée.
- **F9 prend `V7`.** F10 prendra `V8` — la feuille de route dit encore `V7` pour F10, c'est faux.
- **`monitoring/` n'écrit que `dead_letter`.** Le nouveau service est en lecture seule ; toute écriture ailleurs casse l'invariant du package.
- **Aucune entité JPA ne franchit la frontière HTTP.** Toute réponse passe par un `record` de `monitoring/dto/`, et le test asserte le **corps JSON**, pas le DTO.
- **Tests de persistance : `@SpringBootTest`, jamais `@DataJpaTest`** — les `AttributeConverter` sont des `@Component` que la tranche JPA n'inclut pas.
- **Le daemon Docker doit tourner** pour `./mvnw test` (Testcontainers). Sur ce poste, **rejouer la suite backend par lots, jamais d'un bloc** : un run complet n'y a jamais survécu.
- **Le backend écoute sur `:8090`**, pas `:8080`.
- **Toute décision visuelle passe par le plugin `ui-ux-pro-max`, invoqué AVANT d'écrire le code**, jamais en relecture.
- **Le frontend est vérifié par trois contrôles bloquants en CI** : `npm run lint`, `npm run format:check`, et les tests Karma. Le crochet `pre-push` les rejoue.
- Commits en français, dans le style du dépôt : `type: sujet en minuscules`.

---

### Task 1 : `lead.routed_at` — la migration et son écriture

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__lead_routed_at.sql`
- Modify: `backend/src/main/java/com/leadflow/qualification/Lead.java`
- Modify: `backend/src/main/java/com/leadflow/routing/RoutedLeadWriter.java:41-48`
- Test: `backend/src/test/java/com/leadflow/routing/RoutedLeadWriterTest.java`

**Interfaces:**
- Consumes: rien.
- Produces: `Lead.getRoutedAt()` / `Lead.setRoutedAt(Instant)` — utilisés par la Task 3.

- [ ] **Step 1: Écrire le test qui échoue**

Créer `backend/src/test/java/com/leadflow/routing/RoutedLeadWriterTest.java` :

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
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * L'attribution est un seul fait : le statut, le commercial et la date sont ecrits
 * ensemble ou pas du tout.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoutedLeadWriterTest {

    @Autowired private RoutedLeadWriter writer;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;
    @Autowired private SalesRepRepository commerciaux;

    @Test
    void poseLaDateDAttributionEnMemeTempsQueLeStatut() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Nord");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        Client boutique = clients.saveAndFlush(client);
        UUID clientId = boutique.getId();

        // SalesRep porte une association vers Client, pas un clientId brut — contrairement
        // a Lead, qui reste en identifiants pour ne pas dependre du package tenant.
        SalesRep commercial = new SalesRep();
        commercial.setClient(boutique);
        commercial.setFullName("Amina Bensalem");
        commercial.setEmail("amina+" + UUID.randomUUID() + "@demo.test");
        commercial.setActive(true);
        UUID salesRepId = commerciaux.saveAndFlush(commercial).getId();

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
        UUID leadId = leads.saveAndFlush(lead).getId();

        Instant avant = Instant.now();
        writer.attribue(leadId, salesRepId);

        Lead relu = leads.findById(leadId).orElseThrow();
        assertThat(relu.getStatus()).isEqualTo(LeadStatus.ROUTED);
        assertThat(relu.getAssignedSalesRepId()).isEqualTo(salesRepId);
        assertThat(relu.getRoutedAt()).isNotNull();
        assertThat(relu.getRoutedAt()).isAfterOrEqualTo(avant);
    }

    @Test
    void unLeadNonAttribueNaPasDeDate() {
        // Le lead cree par le test precedent n'est pas relu ici : on eprouve le defaut
        // d'une ligne neuve, qui est ce que verra tout l'historique anterieur a V7.
        Lead lead = new Lead();
        assertThat(lead.getRoutedAt()).isNull();
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run : `cd backend && ./mvnw test -Dtest=RoutedLeadWriterTest`
Expected : **échec de compilation** — `cannot find symbol: method getRoutedAt()`.

- [ ] **Step 3: Écrire la migration**

Créer `backend/src/main/resources/db/migration/V7__lead_routed_at.sql` :

```sql
-- Date d'attribution au commercial. Elle n'existait nulle part : lead.updated_at bouge a
-- chaque ecriture, donc il vaut la date d'attribution pour un lead reste ROUTED mais celle
-- de la synchronisation pour un lead SYNCED. La timeline de F9 avait besoin d'une date qui
-- ne mente pas.
--
-- Nullable et SANS remplissage retroactif, deliberement : remplir l'historique depuis
-- updated_at inventerait une date pour tout lead deja synchronise. La timeline affiche
-- « date inconnue » pour les leads attribues avant cette migration, et c'est la seule chose
-- vraie qu'on puisse en dire.
ALTER TABLE lead ADD COLUMN routed_at timestamptz;
```

- [ ] **Step 4: Ajouter le champ à l'entité**

Dans `backend/src/main/java/com/leadflow/qualification/Lead.java`, ajouter l'import `java.time.Instant` s'il manque, puis le champ juste après `assignedSalesRepId` :

```java
    /**
     * Date d'attribution, posee par le routage en meme temps que le statut et le
     * commercial. Nulle pour les leads attribues avant la migration V7 : la timeline
     * affiche alors « date inconnue » plutot qu'une date deduite, qui serait fausse.
     */
    @Column(name = "routed_at")
    private Instant routedAt;
```

- [ ] **Step 5: Écrire la date dans `RoutedLeadWriter`**

Dans `backend/src/main/java/com/leadflow/routing/RoutedLeadWriter.java`, ajouter l'import `java.time.Instant`, puis modifier la méthode `attribue` :

```java
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lead attribue(UUID leadId, UUID salesRepId) {
        Lead lead = leadRepository.findById(leadId).orElseThrow(
                () -> new IllegalStateException("Lead disparu en cours d'attribution : " + leadId));
        lead.setAssignedSalesRepId(salesRepId);
        lead.setStatus(LeadStatus.ROUTED);
        // Dans la meme transaction que les deux lignes ci-dessus : les trois sont un seul
        // fait, et un routed_at sans commercial serait un etat incoherent.
        lead.setRoutedAt(Instant.now());
        return leadRepository.saveAndFlush(lead);
    }
```

- [ ] **Step 6: Lancer le test pour vérifier qu'il passe**

Run : `cd backend && ./mvnw test -Dtest=RoutedLeadWriterTest`
Expected : PASS, 2 tests.

- [ ] **Step 7: Vérifier qu'on n'a rien cassé dans le routage**

Run : `cd backend && ./mvnw test -Dtest='LeadRoutingServiceTest,RoutedLeadRelayTest,RotationOrderTest'`
Expected : PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V7__lead_routed_at.sql \
        backend/src/main/java/com/leadflow/qualification/Lead.java \
        backend/src/main/java/com/leadflow/routing/RoutedLeadWriter.java \
        backend/src/test/java/com/leadflow/routing/RoutedLeadWriterTest.java
git commit -m "feat: lead.routed_at, la date que l attribution n avait pas"
```

---

### Task 2 : Le vocabulaire de la timeline

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/TimelineEventType.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/TimelineOutcome.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/TimelineEntry.java`

**Interfaces:**
- Consumes: rien.
- Produces: `TimelineEntry(TimelineEventType type, Instant at, TimelineOutcome outcome, Map<String,String> details)`, `TimelineEventType.{CAPTURE, QUALIFICATION, ATTRIBUTION, SYNC_ERP, MORT, REJEU}`, `TimelineOutcome.{SUCCES, ECHEC, NEUTRE}` — consommés par les Tasks 3, 4 et 5.

Pas de cycle TDD ici : trois déclarations sans comportement. Elles sont éprouvées par les tests des Tasks 3 et 4.

- [ ] **Step 1: Créer les deux énumérations**

`backend/src/main/java/com/leadflow/monitoring/dto/TimelineEventType.java` :

```java
package com.leadflow.monitoring.dto;

/**
 * Les six faits qu'un lead peut avoir vecus. L'ordre de declaration est l'ordre du
 * pipeline, et {@code LeadTimelineService} s'en sert pour placer une attribution non datee.
 */
public enum TimelineEventType {
    CAPTURE,
    QUALIFICATION,
    ATTRIBUTION,
    SYNC_ERP,
    MORT,
    REJEU
}
```

`backend/src/main/java/com/leadflow/monitoring/dto/TimelineOutcome.java` :

```java
package com.leadflow.monitoring.dto;

/**
 * Issue d'une entree, pour que l'ecran marque les echecs sans avoir a interpreter les
 * details. {@code NEUTRE} est le cas des faits qui ne reussissent ni n'echouent — une
 * capture, une attribution.
 */
public enum TimelineOutcome {
    SUCCES,
    ECHEC,
    NEUTRE
}
```

- [ ] **Step 2: Créer le record**

`backend/src/main/java/com/leadflow/monitoring/dto/TimelineEntry.java` :

```java
package com.leadflow.monitoring.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Un fait de la vie d'un lead.
 *
 * <p>Un record plat et typé plutot qu'une hierarchie scellee : les six types partagent la
 * meme forme, et une hierarchie couterait une deserialisation polymorphe cote Angular pour
 * aucun gain.
 *
 * <p><b>Aucune phrase n'est composee ici.</b> Le backend rend des faits typés, le template
 * Angular les met en francais. C'est deliberement la regle inverse de {@code replayWarning},
 * qui reste calcule cote serveur parce qu'il encode une <b>decision</b> du backend — la non
 * idempotence du tour de role — et non un libelle.
 *
 * @param at nul pour une attribution anterieure a la migration V7. L'absence est une
 *     information : la remplacer par une date deduite mentirait.
 */
public record TimelineEntry(
        TimelineEventType type,
        Instant at,
        TimelineOutcome outcome,
        Map<String, String> details) {
}
```

- [ ] **Step 3: Vérifier que ça compile**

Run : `cd backend && ./mvnw -q compile`
Expected : succès, aucune sortie d'erreur.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/dto/TimelineEntry.java \
        backend/src/main/java/com/leadflow/monitoring/dto/TimelineEventType.java \
        backend/src/main/java/com/leadflow/monitoring/dto/TimelineOutcome.java
git commit -m "feat: le vocabulaire de la timeline — six types, trois issues"
```

---

### Task 3 : La dérivation, sans les morts

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/LeadTimelineService.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/LeadTimelineServiceTest.java`

**Interfaces:**
- Consumes: `TimelineEntry`, `TimelineEventType`, `TimelineOutcome` (Task 2) ; `Lead.getRoutedAt()` (Task 1).
- Produces: `LeadTimelineService.timeline(UUID leadId) → List<TimelineEntry>`, qui lève `RessourceIntrouvableException` sur lead inconnu — consommé par les Tasks 4 et 6.

Les morts et rejeux arrivent en Task 4 : cette tâche livre déjà une timeline utile, et la règle d'ordre — le point subtil — s'éprouve sans eux.

- [ ] **Step 1: Écrire le test qui échoue**

Créer `backend/src/test/java/com/leadflow/monitoring/LeadTimelineServiceTest.java` :

```java
package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.monitoring.dto.TimelineEntry;
import com.leadflow.monitoring.dto.TimelineEventType;
import com.leadflow.monitoring.dto.TimelineOutcome;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Eprouve la derivation et surtout la regle d'ordre, qui est le seul point subtil du
 * service : une attribution sans date se place a sa position connue dans le pipeline, et
 * non en tete ou en queue comme le ferait un comparateur ordinaire.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadTimelineServiceTest {

    @Autowired private LeadTimelineService service;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private CrmSyncAttemptRepository tentatives;
    @Autowired private ClientRepository clients;

    private UUID clientId;
    private final Instant base = Instant.parse("2026-08-31T10:00:00Z");

    @BeforeEach
    void boutique() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Est");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        clientId = clients.saveAndFlush(client).getId();
    }

    private UUID leadAvec(Instant recuA, Instant attribueA, LeadStatus statut) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire-devis");
        evenement.setPayload(new HashMap<>(Map.of("email", "b@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(recuA);
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail("b@exemple.fr");
        lead.setScore(80);
        lead.setDetectedIntent("DEVIS");
        lead.setStatus(statut);
        lead.setRoutedAt(attribueA);
        if (attribueA != null || statut != LeadStatus.QUALIFIED) {
            lead.setAssignedSalesRepId(UUID.randomUUID());
        }
        return leads.saveAndFlush(lead).getId();
    }

    @Test
    void leadInconnuLeve() {
        UUID inconnu = UUID.randomUUID();
        assertThatThrownBy(() -> service.timeline(inconnu))
                .isInstanceOf(RessourceIntrouvableException.class);
    }

    @Test
    void leadJamaisSynchroniseNaAucuneEntreeErp() {
        UUID leadId = leadAvec(base, base.plus(1, ChronoUnit.SECONDS), LeadStatus.ROUTED);

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).extracting(TimelineEntry::type).containsExactly(
                TimelineEventType.CAPTURE,
                TimelineEventType.QUALIFICATION,
                TimelineEventType.ATTRIBUTION);
        assertThat(timeline).noneMatch(e -> e.outcome() == TimelineOutcome.ECHEC);
    }

    @Test
    void lesEntreesDateesSeTrientChronologiquement() {
        UUID leadId = leadAvec(base, base.plus(2, ChronoUnit.SECONDS), LeadStatus.SYNCED);

        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId("dolibarr");
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setAttemptedAt(base.plus(5, ChronoUnit.SECONDS));
        tentatives.saveAndFlush(tentative);

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).extracting(TimelineEntry::type).containsExactly(
                TimelineEventType.CAPTURE,
                TimelineEventType.QUALIFICATION,
                TimelineEventType.ATTRIBUTION,
                TimelineEventType.SYNC_ERP);
        assertThat(timeline.get(3).outcome()).isEqualTo(TimelineOutcome.SUCCES);
    }

    @Test
    void uneAttributionSansDateSePlaceApresLaQualification() {
        // C'est tout l'historique anterieur a V7 : statut ROUTED, commercial pose,
        // routed_at nul.
        UUID leadId = leadAvec(base, null, LeadStatus.SYNCED);

        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId("dolibarr");
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setAttemptedAt(base.plus(5, ChronoUnit.SECONDS));
        tentatives.saveAndFlush(tentative);

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).extracting(TimelineEntry::type).containsExactly(
                TimelineEventType.CAPTURE,
                TimelineEventType.QUALIFICATION,
                TimelineEventType.ATTRIBUTION,
                TimelineEventType.SYNC_ERP);
        assertThat(timeline.get(2).at()).isNull();
    }

    @Test
    void chaqueTentativeErpDonneUneEntree() {
        UUID leadId = leadAvec(base, base.plus(1, ChronoUnit.SECONDS), LeadStatus.ROUTED);

        for (int i = 1; i <= 3; i++) {
            CrmSyncAttempt tentative = new CrmSyncAttempt();
            tentative.setLeadId(leadId);
            tentative.setProviderId("dolibarr");
            tentative.setStatus(CrmSyncAttemptStatus.FAILURE);
            tentative.setErrorMessage("Connection refused");
            tentative.setAttemptedAt(base.plus(2 + i, ChronoUnit.SECONDS));
            tentatives.saveAndFlush(tentative);
        }

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).filteredOn(e -> e.type() == TimelineEventType.SYNC_ERP)
                .hasSize(3)
                .allMatch(e -> e.outcome() == TimelineOutcome.ECHEC)
                .allMatch(e -> "Connection refused".equals(e.details().get("erreur")));
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run : `cd backend && ./mvnw test -Dtest=LeadTimelineServiceTest`
Expected : **échec de compilation** — `cannot find symbol: class LeadTimelineService`.

- [ ] **Step 3: Écrire le service**

Créer `backend/src/main/java/com/leadflow/monitoring/LeadTimelineService.java` :

```java
package com.leadflow.monitoring;

import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.monitoring.dto.TimelineEntry;
import com.leadflow.monitoring.dto.TimelineEventType;
import com.leadflow.monitoring.dto.TimelineOutcome;
import com.leadflow.qualification.Lead;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derive la chronologie d'un lead depuis les traces des etapes qui l'ont touche.
 *
 * <p><b>Ce service n'ecrit rien.</b> C'est la condition qui rend {@code monitoring/} sur :
 * le package lit les tables des autres etapes et n'ecrit que {@code dead_letter}.
 */
@Service
public class LeadTimelineService {

    private final LeadQueryRepository leads;
    private final RawLeadEventRepository evenements;
    private final CrmSyncAttemptRepository tentatives;

    public LeadTimelineService(
            LeadQueryRepository leads,
            RawLeadEventRepository evenements,
            CrmSyncAttemptRepository tentatives) {
        this.leads = leads;
        this.evenements = evenements;
        this.tentatives = tentatives;
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID leadId) {
        Lead lead = leads.findById(leadId).orElseThrow(
                () -> new RessourceIntrouvableException("Lead inconnu : " + leadId));

        List<TimelineEntry> entrees = new ArrayList<>();
        evenements.findById(lead.getRawEventId()).ifPresent(e -> entrees.add(capture(e)));
        entrees.add(qualification(lead));
        if (lead.getAssignedSalesRepId() != null) {
            entrees.add(attribution(lead));
        }
        tentatives.findByLeadIdOrderByAttemptedAtDesc(leadId)
                .forEach(tentative -> entrees.add(synchronisation(tentative)));

        return ordonne(entrees);
    }

    /**
     * Les entrees datees se trient chronologiquement ; une entree sans date reste a la
     * position que son type occupe dans le pipeline.
     *
     * <p>Un tri qui rejetterait les nuls en tete ou en queue — le comportement par defaut
     * de la plupart des comparateurs — mentirait sur toute la chronologie anterieure a V7.
     * L'ordre des etapes est connu meme quand leur date ne l'est pas.
     */
    private List<TimelineEntry> ordonne(List<TimelineEntry> entrees) {
        List<TimelineEntry> datees = new ArrayList<>(
                entrees.stream().filter(e -> e.at() != null).toList());
        datees.sort(Comparator.comparing(TimelineEntry::at));

        List<TimelineEntry> resultat = new ArrayList<>(datees);
        for (TimelineEntry sansDate : entrees.stream().filter(e -> e.at() == null).toList()) {
            resultat.add(positionDe(resultat, sansDate.type()), sansDate);
        }
        return List.copyOf(resultat);
    }

    /**
     * Position d'un type non date : juste apres la derniere entree dont le type le precede
     * dans le pipeline. L'ordre de declaration de {@link TimelineEventType} est l'ordre du
     * pipeline, donc {@code ordinal()} suffit a le lire.
     */
    private int positionDe(List<TimelineEntry> deja, TimelineEventType type) {
        int position = 0;
        for (int i = 0; i < deja.size(); i++) {
            if (deja.get(i).type().ordinal() <= type.ordinal()) {
                position = i + 1;
            }
        }
        return position;
    }

    private TimelineEntry capture(RawLeadEvent evenement) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("source", evenement.getSource());
        details.put("statut", String.valueOf(evenement.getStatus()));
        if (evenement.getFailureReason() != null) {
            details.put("erreur", evenement.getFailureReason());
        }
        return new TimelineEntry(
                TimelineEventType.CAPTURE,
                evenement.getReceivedAt(),
                evenement.getFailureReason() == null
                        ? TimelineOutcome.NEUTRE
                        : TimelineOutcome.ECHEC,
                Map.copyOf(details));
    }

    private TimelineEntry qualification(Lead lead) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("score", String.valueOf(lead.getScore()));
        if (lead.getDetectedIntent() != null) {
            details.put("intention", lead.getDetectedIntent());
        }
        if (lead.getIntentSource() != null) {
            details.put("sourceIntention", String.valueOf(lead.getIntentSource()));
        }
        return new TimelineEntry(
                TimelineEventType.QUALIFICATION,
                lead.getCreatedAt(),
                TimelineOutcome.NEUTRE,
                Map.copyOf(details));
    }

    private TimelineEntry attribution(Lead lead) {
        return new TimelineEntry(
                TimelineEventType.ATTRIBUTION,
                lead.getRoutedAt(),
                TimelineOutcome.NEUTRE,
                Map.of("commercialId", String.valueOf(lead.getAssignedSalesRepId())));
    }

    private TimelineEntry synchronisation(CrmSyncAttempt tentative) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("connecteur", tentative.getProviderId());
        details.put("statut", String.valueOf(tentative.getStatus()));
        if (tentative.getErrorMessage() != null) {
            details.put("erreur", tentative.getErrorMessage());
        }
        return new TimelineEntry(
                TimelineEventType.SYNC_ERP,
                tentative.getAttemptedAt(),
                tentative.getStatus() == CrmSyncAttemptStatus.SUCCESS
                        ? TimelineOutcome.SUCCES
                        : TimelineOutcome.ECHEC,
                Map.copyOf(details));
    }
}
```

L'import `java.time.Instant` n'est pas nécessaire dans cette classe : aucune date n'y est construite, elles sont toutes relues des entités. Ne pas l'ajouter.

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run : `cd backend && ./mvnw test -Dtest=LeadTimelineServiceTest`
Expected : PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/LeadTimelineService.java \
        backend/src/test/java/com/leadflow/monitoring/LeadTimelineServiceTest.java
git commit -m "feat: la derivation de la timeline, et sa regle d ordre"
```

---

### Task 4 : Les morts et les rejeux

**Files:**
- Modify: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterRepository.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadTimelineService.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/LeadTimelineServiceTest.java` (ajout)

**Interfaces:**
- Consumes: `LeadTimelineService.timeline(UUID)` (Task 3).
- Produces: `DeadLetterRepository.findByLeadIdOrderByDeadAtAsc(UUID) → List<DeadLetter>`.

- [ ] **Step 1: Écrire le test qui échoue**

Ajouter dans `LeadTimelineServiceTest`, avec les imports `com.leadflow.monitoring.deadletter.DeadLetter`, `com.leadflow.monitoring.deadletter.DeadLetterRepository` et `com.leadflow.monitoring.deadletter.DeadLetterStatus`, plus le champ `@Autowired private DeadLetterRepository morts;` :

```java
    @Test
    void uneMortEtSonRejeuDonnentDeuxEntrees() {
        UUID leadId = leadAvec(base, base.plus(1, ChronoUnit.SECONDS), LeadStatus.ROUTED);

        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.routed");
        mort.setRoutingKey("lead.routed");
        mort.setPayload("{}");
        mort.setClientId(clientId);
        mort.setLeadId(leadId);
        mort.setFailureReason("Connection refused");
        mort.setDeadAt(base.plus(10, ChronoUnit.SECONDS));
        mort.setStatus(DeadLetterStatus.REPLAYED);
        mort.setReplayedAt(base.plus(60, ChronoUnit.SECONDS));
        mort.setReplayedBy("admin");
        morts.saveAndFlush(mort);

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).extracting(TimelineEntry::type).containsExactly(
                TimelineEventType.CAPTURE,
                TimelineEventType.QUALIFICATION,
                TimelineEventType.ATTRIBUTION,
                TimelineEventType.MORT,
                TimelineEventType.REJEU);
        assertThat(timeline.get(3).outcome()).isEqualTo(TimelineOutcome.ECHEC);
        assertThat(timeline.get(3).details().get("file")).isEqualTo("leadflow.leads.routed");
        assertThat(timeline.get(4).details().get("par")).isEqualTo("admin");
    }

    @Test
    void uneMortNonRejoueeNeDonneQuUneEntree() {
        UUID leadId = leadAvec(base, base.plus(1, ChronoUnit.SECONDS), LeadStatus.ROUTED);

        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.routed");
        mort.setRoutingKey("lead.routed");
        mort.setPayload("{}");
        mort.setClientId(clientId);
        mort.setLeadId(leadId);
        mort.setFailureReason("Connection refused");
        mort.setDeadAt(base.plus(10, ChronoUnit.SECONDS));
        mort.setStatus(DeadLetterStatus.PENDING);
        morts.saveAndFlush(mort);

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).filteredOn(e -> e.type() == TimelineEventType.REJEU).isEmpty();
        assertThat(timeline).filteredOn(e -> e.type() == TimelineEventType.MORT).hasSize(1);
    }
```

Renommer la seconde méthode en `uneMortNonRejoueeNeDonneQuUneEntree` — les espaces ci-dessus sont une faute de frappe à ne pas recopier.

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run : `cd backend && ./mvnw test -Dtest=LeadTimelineServiceTest`
Expected : les deux nouveaux tests échouent — la timeline ne contient que trois entrées.

- [ ] **Step 3: Ajouter le finder au repository**

Dans `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterRepository.java`, ajouter les imports `java.util.List` et `java.util.UUID` s'ils manquent, puis :

```java
    /** Les morts d'un lead, du plus ancien au plus recent : la timeline les lit dans l'ordre. */
    List<DeadLetter> findByLeadIdOrderByDeadAtAsc(UUID leadId);
```

- [ ] **Step 4: Étendre le service**

Dans `LeadTimelineService`, ajouter les imports `com.leadflow.monitoring.deadletter.DeadLetter`, `com.leadflow.monitoring.deadletter.DeadLetterRepository`, injecter le dépôt dans le constructeur (champ `private final DeadLetterRepository morts;`), puis ajouter avant `return ordonne(entrees);` :

```java
        morts.findByLeadIdOrderByDeadAtAsc(leadId).forEach(mort -> {
            entrees.add(mort(mort));
            if (mort.getReplayedAt() != null) {
                entrees.add(rejeu(mort));
            }
        });
```

et les deux fabriques :

```java
    private TimelineEntry mort(DeadLetter mort) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("file", mort.getOriginQueue());
        if (mort.getFailureReason() != null) {
            details.put("erreur", mort.getFailureReason());
        }
        return new TimelineEntry(
                TimelineEventType.MORT, mort.getDeadAt(), TimelineOutcome.ECHEC,
                Map.copyOf(details));
    }

    /**
     * Le rejeu n'existe que si la date est posee : un message mort et non rejoue n'a pas
     * d'entree de rejeu, meme si la colonne {@code replayed_by} porte une valeur.
     */
    private TimelineEntry rejeu(DeadLetter mort) {
        Map<String, String> details = new LinkedHashMap<>();
        if (mort.getReplayedBy() != null) {
            details.put("par", mort.getReplayedBy());
        }
        return new TimelineEntry(
                TimelineEventType.REJEU, mort.getReplayedAt(), TimelineOutcome.NEUTRE,
                Map.copyOf(details));
    }
```

- [ ] **Step 5: Lancer le test pour vérifier qu'il passe**

Run : `cd backend && ./mvnw test -Dtest=LeadTimelineServiceTest`
Expected : PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterRepository.java \
        backend/src/main/java/com/leadflow/monitoring/LeadTimelineService.java \
        backend/src/test/java/com/leadflow/monitoring/LeadTimelineServiceTest.java
git commit -m "feat: les morts et les rejeux entrent dans la timeline"
```

---

### Task 5 : L'endpoint et son contrat JSON

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/LeadTimelineController.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/LeadTimelineControllerTest.java`

**Interfaces:**
- Consumes: `LeadTimelineService.timeline(UUID)` (Tasks 3-4).
- Produces: `GET /api/leads/{id}/timeline` → tableau JSON de `TimelineEntry` — consommé par la Task 6.

- [ ] **Step 1: Écrire le test qui échoue**

Créer `backend/src/test/java/com/leadflow/monitoring/LeadTimelineControllerTest.java` :

```java
package com.leadflow.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Asserte le <b>corps JSON</b> et non le DTO : c'est le corps qui est le contrat, et un
 * test sur le record ne prouverait rien de ce que voit le frontend.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LeadTimelineControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;

    private UUID leadId;

    @BeforeEach
    void jeuDeDonnees() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Ouest");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        UUID clientId = clients.saveAndFlush(client).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire-devis");
        evenement.setPayload(new HashMap<>(Map.of("email", "c@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.parse("2026-08-31T10:00:00Z"));
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail("c@exemple.fr");
        lead.setScore(80);
        lead.setDetectedIntent("DEVIS");
        lead.setStatus(LeadStatus.QUALIFIED);
        leadId = leads.saveAndFlush(lead).getId();
    }

    @Test
    @WithMockUser
    void rendLaChronologieEnJson() throws Exception {
        mockMvc.perform(get("/api/leads/{id}/timeline", leadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CAPTURE"))
                .andExpect(jsonPath("$[0].outcome").value("NEUTRE"))
                .andExpect(jsonPath("$[0].details.source").value("formulaire-devis"))
                .andExpect(jsonPath("$[1].type").value("QUALIFICATION"))
                .andExpect(jsonPath("$[1].details.score").value("80"))
                .andExpect(jsonPath("$[1].details.intention").value("DEVIS"));
    }

    @Test
    @WithMockUser
    void leadInconnuRend404() throws Exception {
        mockMvc.perform(get("/api/leads/{id}/timeline", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sansJetonLEndpointRefuse() throws Exception {
        mockMvc.perform(get("/api/leads/{id}/timeline", leadId))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run : `cd backend && ./mvnw test -Dtest=LeadTimelineControllerTest`
Expected : `rendLaChronologieEnJson` échoue en `404` — la route n'existe pas.

- [ ] **Step 3: Écrire le contrôleur**

Créer `backend/src/main/java/com/leadflow/monitoring/LeadTimelineController.java` :

```java
package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.TimelineEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controleur a part et non methode de plus sur {@code LeadQueryController} : une classe,
 * une raison de changer.
 *
 * <p>Pas de pagination : le volume est borne par construction — une capture, une
 * qualification, une attribution, au plus trois tentatives ERP avant la DLQ, une mort et un
 * rejeu.
 */
@RestController
@RequestMapping("/api/leads")
public class LeadTimelineController {

    private final LeadTimelineService service;

    public LeadTimelineController(LeadTimelineService service) {
        this.service = service;
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineEntry> timeline(@PathVariable UUID id) {
        return service.timeline(id);
    }
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run : `cd backend && ./mvnw test -Dtest=LeadTimelineControllerTest`
Expected : PASS, 3 tests.

- [ ] **Step 5: Rejouer les tests de monitoring par lot**

Run : `cd backend && ./mvnw test -Dtest='Lead*Test'`
Expected : PASS. (Par lots, jamais la suite entière : elle ne survit pas sur ce poste.)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/LeadTimelineController.java \
        backend/src/test/java/com/leadflow/monitoring/LeadTimelineControllerTest.java
git commit -m "feat: GET /api/leads/{id}/timeline"
```

---

### Task 6 : Le modèle et l'appel côté Angular

**Files:**
- Modify: `frontend/src/app/core/models/lead.ts`
- Modify: `frontend/src/app/core/api/lead-api.ts`
- Test: `frontend/src/app/core/api/lead-api.spec.ts`

**Interfaces:**
- Consumes: `GET /api/leads/{id}/timeline` (Task 5).
- Produces: `LeadApi.timeline(id: string) → Observable<TimelineEntry[]>` et les types `TimelineEntry`, `TimelineEventType`, `TimelineOutcome` — consommés par la Task 7.

- [ ] **Step 1: Écrire le test qui échoue**

Ajouter dans `frontend/src/app/core/api/lead-api.spec.ts`, à l'intérieur du `describe` existant :

```typescript
  it('appelle la timeline sur le chemin relatif du lead', () => {
    let recu: TimelineEntry[] | undefined;
    api.timeline('abc').subscribe((entrees) => (recu = entrees));

    const requete = httpMock.expectOne('/api/leads/abc/timeline');
    expect(requete.request.method).toBe('GET');
    requete.flush([
      { type: 'CAPTURE', at: '2026-08-31T10:00:00Z', outcome: 'NEUTRE', details: {} },
      { type: 'ATTRIBUTION', at: null, outcome: 'NEUTRE', details: {} },
    ]);

    expect(recu?.length).toBe(2);
    expect(recu?.[1].at).toBeNull();
  });
```

Importer `TimelineEntry` depuis `../models/lead` en tête du fichier.

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run : `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected : échec de compilation TypeScript — `Property 'timeline' does not exist on type 'LeadApi'`.

- [ ] **Step 3: Ajouter les types**

Dans `frontend/src/app/core/models/lead.ts` :

```typescript
export type TimelineEventType =
  | 'CAPTURE'
  | 'QUALIFICATION'
  | 'ATTRIBUTION'
  | 'SYNC_ERP'
  | 'MORT'
  | 'REJEU';

export type TimelineOutcome = 'SUCCES' | 'ECHEC' | 'NEUTRE';

export interface TimelineEntry {
  type: TimelineEventType;
  /**
   * `null` pour une attribution anterieure a la migration V7. L'absence est une
   * information : l'ecran affiche « date inconnue » plutot qu'une date deduite.
   */
  at: string | null;
  outcome: TimelineOutcome;
  details: Record<string, string>;
}
```

- [ ] **Step 4: Ajouter la méthode d'appel**

Dans `frontend/src/app/core/api/lead-api.ts`, importer `TimelineEntry` depuis `../models/lead` et ajouter après `detail` :

```typescript
  /**
   * Endpoint separe du detail : F10 devra rafraichir la seule chronologie apres une
   * reattribution, sans refaire tout le detail.
   */
  timeline(id: string) {
    return this.http.get<TimelineEntry[]>(`/api/leads/${id}/timeline`);
  }
```

- [ ] **Step 5: Lancer le test pour vérifier qu'il passe**

Run : `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected : PASS, 45 tests.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/core/models/lead.ts \
        frontend/src/app/core/api/lead-api.ts \
        frontend/src/app/core/api/lead-api.spec.ts
git commit -m "feat: le modele et l appel de la timeline cote Angular"
```

---

### Task 7 : Le composant `lead-timeline` et son intégration

**Files:**
- Create: `frontend/src/app/features/leads/lead-detail/lead-timeline/lead-timeline.ts`
- Create: `frontend/src/app/features/leads/lead-detail/lead-timeline/lead-timeline.html`
- Create: `frontend/src/app/features/leads/lead-detail/lead-timeline/lead-timeline.scss`
- Create: `frontend/src/app/features/leads/lead-detail/lead-timeline/lead-timeline.spec.ts`
- Modify: `frontend/src/app/features/leads/lead-detail/lead-detail.ts`
- Modify: `frontend/src/app/features/leads/lead-detail/lead-detail.html`

**Interfaces:**
- Consumes: `LeadApi.timeline(id)`, `TimelineEntry`, `TimelineEventType`, `TimelineOutcome` (Task 6).
- Produces: le composant `LeadTimeline`, avec une entrée `leadId: InputSignal<string>` — c'est cette signature que F10 réutilisera.

- [ ] **Step 1: Concevoir le visuel AVANT d'écrire le composant**

**Obligatoire, et avant tout code** : invoquer `ui-ux-pro-max:ui-ux-pro-max` puis `ui-ux-pro-max:ui-styling` pour arrêter le traitement visuel de la chronologie. Trois questions doivent être tranchées, et notées dans le message de commit :

1. La marque d'un échec (`outcome === 'ECHEC'`) — couleur, icône, contraste en clair **et** en sombre.
2. Le rendu de la case « date inconnue », qui doit se lire comme une **absence assumée**, jamais comme une erreur d'affichage.
3. La densité de la liste, sachant qu'un lead en échec porte jusqu'à huit entrées.

Les icônes viennent de Material Symbols Outlined, servie localement — ne jamais référencer `fonts.googleapis.com` : la CSP de production dit `font-src 'self'` et une police bloquée fait **disparaître** les icônes.

- [ ] **Step 2: Écrire le test qui échoue**

Créer `frontend/src/app/features/leads/lead-detail/lead-timeline/lead-timeline.spec.ts` :

```typescript
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { LeadTimeline } from './lead-timeline';

describe('LeadTimeline', () => {
  let fixture: ComponentFixture<LeadTimeline>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LeadTimeline],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LeadTimeline);
    httpMock = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('leadId', 'abc');
  });

  afterEach(() => httpMock.verify());

  it('rend une entree par fait', async () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/leads/abc/timeline').flush([
      { type: 'CAPTURE', at: '2026-08-31T10:00:00Z', outcome: 'NEUTRE', details: {} },
      { type: 'QUALIFICATION', at: '2026-08-31T10:00:01Z', outcome: 'NEUTRE', details: {} },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();

    const entrees = fixture.nativeElement.querySelectorAll('[data-entree]');
    expect(entrees.length).toBe(2);
  });

  it('marque une date absente comme inconnue et non comme vide', async () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/leads/abc/timeline').flush([
      { type: 'ATTRIBUTION', at: null, outcome: 'NEUTRE', details: {} },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('date inconnue');
  });
});
```

Si le projet ne fournit pas `provideZonelessChangeDetection` dans ses autres specs, recopier à la place la configuration de `frontend/src/app/features/parametres/parametres.spec.ts` — le but est le même : un `TestBed` qui compile le composant standalone avec un `HttpClient` de test.

- [ ] **Step 3: Lancer le test pour vérifier qu'il échoue**

Run : `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected : échec — `Cannot find module './lead-timeline'`.

- [ ] **Step 4: Écrire le composant**

`frontend/src/app/features/leads/lead-detail/lead-timeline/lead-timeline.ts` :

```typescript
import { Component, OnInit, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { LeadApi } from '../../../../core/api/lead-api';
import { TimelineEntry } from '../../../../core/models/lead';

/**
 * Chronologie d'un lead, de sa capture a son eventuel rejeu.
 *
 * Local a l'ecran de detail et non dans `shared/` : rien d'autre ne le consomme. C'est ce
 * composant que F10 rouvrira pour y accrocher la reattribution, d'ou l'entree `leadId`
 * plutot qu'une liste passee toute faite — il sait recharger sa propre matiere.
 */
@Component({
  selector: 'app-lead-timeline',
  imports: [DatePipe, MatIconModule],
  templateUrl: './lead-timeline.html',
  styleUrl: './lead-timeline.scss',
})
export class LeadTimeline implements OnInit {
  private readonly api = inject(LeadApi);

  readonly leadId = input.required<string>();

  readonly entrees = signal<TimelineEntry[]>([]);
  readonly enCours = signal(true);
  readonly erreur = signal<string | null>(null);

  ngOnInit(): void {
    this.recharge();
  }

  /** Publique : F10 l'appellera apres une reattribution. */
  recharge(): void {
    this.enCours.set(true);
    this.api.timeline(this.leadId()).subscribe({
      next: (entrees) => {
        this.entrees.set(entrees);
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set("La chronologie n'a pas pu etre chargee.");
        this.enCours.set(false);
      },
    });
  }
}
```

`lead-timeline.html` — la structure ci-dessous est le **squelette fonctionnel** ; les classes, icônes et couleurs sont celles arrêtées à l'étape 1 :

```html
@if (enCours()) {
  <p>Chargement de la chronologie…</p>
} @else if (erreur()) {
  <p>{{ erreur() }}</p>
} @else {
  <ol class="timeline">
    @for (entree of entrees(); track $index) {
      <li data-entree [attr.data-issue]="entree.outcome">
        <span class="date">
          @if (entree.at) {
            {{ entree.at | date: 'dd/MM/yyyy HH:mm:ss' }}
          } @else {
            date inconnue
          }
        </span>
        <span class="type">{{ entree.type }}</span>
        @for (detail of entree.details | keyvalue; track detail.key) {
          <span class="detail">{{ detail.key }} : {{ detail.value }}</span>
        }
      </li>
    }
  </ol>
}
```

Le `| keyvalue` demande d'ajouter `KeyValuePipe` aux imports du composant. Les libellés français des six types se posent à l'étape 1 avec le reste du visuel — `{{ entree.type }}` affiche l'énumération brute et n'est pas un rendu acceptable en l'état.

- [ ] **Step 5: Lancer le test pour vérifier qu'il passe**

Run : `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected : PASS, 47 tests.

- [ ] **Step 6: Brancher le composant dans l'écran de détail**

Dans `lead-detail.ts`, ajouter `LeadTimeline` aux `imports` du décorateur. Dans `lead-detail.html`, insérer le composant **après** le bloc du lead et de son commercial, et avant l'historique des synchronisations :

```html
@if (lead(); as detail) {
  <app-lead-timeline [leadId]="detail.id" />
}
```

- [ ] **Step 7: Vérifier les trois contrôles bloquants**

Run :
```bash
cd frontend
npm run lint
npm run format:check
npm test -- --watch=false --browsers=ChromeHeadless
```
Expected : les trois passent. Si `format:check` signale des fichiers, lancer `npm run format` puis recommencer.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/features/leads/lead-detail/
git commit -m "feat: la chronologie du lead dans l ecran de detail"
```

---

### Task 8 : Documenter et clore

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/monitoring-api.md`

- [ ] **Step 1: Documenter l'endpoint**

Dans `docs/monitoring-api.md`, ajouter une section `GET /api/leads/{id}/timeline` sur le modèle des sections existantes : un exemple `curl` avec jeton, une réponse JSON complète montrant les six types, et la phrase qui explique le `at` nul.

- [ ] **Step 2: Mettre à jour `CLAUDE.md`**

Deux endroits, et rien d'autre :

- Section « Base de donnees » : passer de « Six migrations existent » à sept, en décrivant `V7__lead_routed_at.sql` et **pourquoi elle est nullable sans remplissage rétroactif**.
- Section « Monitoring » : ajouter que la timeline est dérivée et servie par un endpoint séparé, et pourquoi (F10 rafraîchira la seule chronologie).

Ne pas toucher la liste « Ce qui n'existe pas » : F9 ne livre ni réattribution, ni notification, ni graphique.

- [ ] **Step 3: Vérifier le backend par lots**

Run : `cd backend && ./mvnw test -Dtest='Lead*Test,Routed*Test'`
Expected : PASS.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/monitoring-api.md
git commit -m "docs: la timeline du lead, et la septieme migration"
```

- [ ] **Step 5: Pousser la branche**

```bash
git push -u origin feature/f9-timeline-du-lead
```

Le crochet `pre-push` rejoue ESLint, Prettier et les tests Karma. S'il refuse, corriger avant d'insister — il refuse exactement ce que la CI refuserait.

- [ ] **Step 6: Rendre la main pour la recette**

**Ne pas fusionner.** La recette à l'écran est faite par l'utilisateur, pas par l'assistant. Lui présenter :

- l'écran `leads/:id` d'un lead récent, qui doit montrer les entrées datées dans l'ordre ;
- l'écran d'un lead **antérieur à `V7`**, dont l'attribution doit afficher « date inconnue » à sa place dans la chronologie, et non en tête ;
- un lead en échec de synchronisation, qui doit montrer ses trois tentatives puis sa mort.

Pour fabriquer les leads de démonstration, `docker compose up -d` puis `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`, **jamais `spring-boot:test-run`** — voir la section « Backend » de `CLAUDE.md`. Le secret HMAC de la boutique de démonstration se refait tourner depuis l'écran « Boutiques » ; celui écrit dans `R__demo_data.sql` est faux.
