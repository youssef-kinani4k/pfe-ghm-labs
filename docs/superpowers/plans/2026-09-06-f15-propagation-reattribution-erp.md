# F15 — Propagation d'une réattribution jusqu'à l'ERP — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Quand un opérateur réattribue un lead déjà synchronisé, la correction atteint aussi Dolibarr ou Odoo, de façon asynchrone, tracée et rejouable.

**Architecture:** `ReattributionService` publie `lead.reassigned` après son commit ; une file dédiée l'apporte à `CrmReassignService`, qui reconstruit l'état antérieur et appelle la nouvelle méthode `reaffecte` du port `CrmConnector` — seulement si l'ERP connaît déjà ce lead. Le résultat s'écrit dans `crm_sync_attempt`, qui gagne une colonne `nature`, si bien qu'une resynchronisation ultérieure ne rétablit pas l'ancien responsable.

**Tech Stack:** Java 21, Spring Boot 3, Spring AMQP, JPA/Hibernate, Flyway, PostgreSQL, JUnit 5 + AssertJ, Testcontainers, `MockRestServiceServer` ; Angular 20 standalone + Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-09-06-f15-propagation-reattribution-erp-design.md`

## Global Constraints

- **Branche :** `feature/f15-propagation-reattribution-erp`, déjà créée. Fusion locale en `--no-ff` dans `main` à la fin, branche conservée.
- **Le daemon Docker doit tourner** pour `./mvnw test` : la suite démarre Postgres et RabbitMQ par Testcontainers.
- **Le backend écoute `:8090`**, pas `:8080`.
- **Hibernate ne crée jamais de table** (`ddl-auto: validate`) : toute évolution de schéma est une migration Flyway numérotée. La prochaine est `V13`.
- **Aucune entité JPA ne franchit la frontière HTTP** : toute réponse passe par un `record` de `monitoring/dto/`.
- **Aucun terme propre à un fournisseur dans `crm/model`** : ni « projet », ni « chef de projet », ni `fk_user_resp`, ni `crm.lead`, ni `user_id`.
- **Un nouveau contrat de file doit vivre dans un paquet de `RabbitMQConfig.PAQUETS_DE_CONFIANCE`** — `com.leadflow.routing` en fait déjà partie ; la correspondance est exacte, ni préfixe ni joker.
- **Un test qui écrit dans `client`, `raw_lead_event` ou `lead` ne doit effacer que ses propres lignes.** La base Testcontainers est partagée par toute la suite, et un `deleteAll()` non porté est ce qui a fait échouer la CI de F14 en dépendant de l'ordre d'exécution — invisible en lancement isolé. Retenir les identifiants créés et n'effacer que ceux-là, captures avant boutiques.
- **Copie de travail en LF** (`.gitattributes`), Prettier en 100 colonnes et guillemets simples.
- **Le crochet `pre-push`** joue ESLint, Prettier et les tests frontend ; `git config core.hooksPath .githooks` si ce n'est pas déjà fait.
- **Messages de commit en français**, sans accents, préfixés `feat(f15):`, `test(f15):`, `docs(f15):` ou `chore(f15):`, et terminés par :

```
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01TVp6r4t9wCdXraGqrgatKe
```

---

## Structure des fichiers

**Backend — créés**

| Fichier | Responsabilité |
| --- | --- |
| `routing/LeadReassignedMessage.java` | Contrat de la file `lead.reassigned` |
| `routing/LeadReassignedPublisher.java` | Publication après commit, échec avalé |
| `crm/CrmReassignListener.java` | Traduction du protocole, bean conditionnel |
| `crm/CrmReassignService.java` | Décide d'appeler l'ERP ou d'acquitter, et trace |
| `crm/CrmSyncAttemptNature.java` | `SYNCHRONISATION` / `REAFFECTATION` |
| `db/migration/V13__crm_sync_attempt_nature.sql` | La colonne et son défaut |

**Backend — modifiés**

| Fichier | Modification |
| --- | --- |
| `config/RabbitMQConfig.java` | Clé, file, binding |
| `routing/ReattributionService.java` | Publie après le journal |
| `crm/CrmConnector.java` | Méthode `reaffecte`, obligatoire |
| `crm/dolibarr/DolibarrConnector.java` | Implémente via `lieResponsable` |
| `crm/odoo/OdooClient.java` | Nouvelle méthode `ecrit` |
| `crm/odoo/OdooConnector.java` | Implémente via `ecrit` sur `crm.lead` |
| `crm/CrmSyncAttempt.java` | Champ `nature` |
| `crm/CrmSyncTraceWriter.java` | Deux méthodes qui ne touchent pas le statut du lead |
| `monitoring/LeadTimelineService.java` | Sépare les deux natures |
| `monitoring/dto/TimelineEventType.java` | `REAFFECTATION_ERP` |
| `monitoring/dto/SyncAttemptView.java` | `+nature`, `+assigneeRef`, `−taskRef` |
| `monitoring/LeadDetailService.java` | Câblage du DTO |
| `application.yml` | `leadflow.crm.reassign.listener.enabled` |
| `src/test/resources/application.properties` | Le même à `false` |

**Frontend — modifiés**

| Fichier | Modification |
| --- | --- |
| `core/models/lead.ts` | `SyncAttemptView`, `TimelineEventType` |
| `features/leads/lead-detail/lead-timeline/lead-timeline.ts` | Libellé et icône |
| `features/leads/lead-detail/lead-detail.html` | Références affichées |
| `features/leads/lead-detail/reattribution-dialog/reattribution-dialog.html` | La phrase d'aveu |

---

### Task 1 : La clé, la file et le contrat de message

**Files:**
- Create: `backend/src/main/java/com/leadflow/routing/LeadReassignedMessage.java`
- Modify: `backend/src/main/java/com/leadflow/config/RabbitMQConfig.java`
- Test: `backend/src/test/java/com/leadflow/crm/ReassignTopologieTest.java`

**Interfaces:**
- Consumes: rien.
- Produces: `RabbitMQConfig.REASSIGNED_QUEUE` (`"leadflow.leads.reassigned"`), `RabbitMQConfig.REASSIGNED_ROUTING_KEY` (`"lead.reassigned"`), et le record `LeadReassignedMessage(UUID leadId, UUID clientId, UUID previousSalesRepId, UUID newSalesRepId, Instant reassignedAt)`.

- [ ] **Step 1 : Écrire le test qui échoue**

Créer `backend/src/test/java/com/leadflow/crm/ReassignTopologieTest.java` :

```java
package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.routing.LeadReassignedMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * La file de reaffectation et son contrat, eprouves sur un vrai broker.
 *
 * <p>Le second test est celui qui compte : le convertisseur ne fait confiance qu'a une liste
 * blanche de paquets, et un contrat absent de PAQUETS_DE_CONFIANCE se refuse a la lecture
 * sans dire pourquoi.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReassignTopologieTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void videLaFile() {
        // Purge BLOQUANTE : la surcharge avec noWait rend la main avant la fin, et dans la
        // suite complete elle emporterait le message qu'on vient de publier.
        rabbitAdmin.purgeQueue(RabbitMQConfig.REASSIGNED_QUEUE);
    }

    @Test
    void laFileExisteEtPorteLeBonNom() {
        QueueInformation info = rabbitAdmin.getQueueInfo(RabbitMQConfig.REASSIGNED_QUEUE);

        assertThat(info).isNotNull();
        assertThat(info.getName()).isEqualTo("leadflow.leads.reassigned");
    }

    @Test
    void leMessageSeDeserialiseEnLeadReassignedMessage() {
        UUID leadId = UUID.randomUUID();
        UUID ancien = UUID.randomUUID();
        UUID nouveau = UUID.randomUUID();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.REASSIGNED_ROUTING_KEY,
                new LeadReassignedMessage(
                        leadId, UUID.randomUUID(), ancien, nouveau, Instant.now()));

        Object recu = rabbitTemplate.receiveAndConvert(RabbitMQConfig.REASSIGNED_QUEUE, 5000);

        assertThat(recu).isInstanceOf(LeadReassignedMessage.class);
        LeadReassignedMessage message = (LeadReassignedMessage) recu;
        assertThat(message.leadId()).isEqualTo(leadId);
        assertThat(message.previousSalesRepId()).isEqualTo(ancien);
        assertThat(message.newSalesRepId()).isEqualTo(nouveau);
    }
}
```

- [ ] **Step 2 : Lancer le test pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw test -Dtest=ReassignTopologieTest`
Expected: échec de compilation — `REASSIGNED_QUEUE`, `REASSIGNED_ROUTING_KEY` et `LeadReassignedMessage` n'existent pas.

- [ ] **Step 3 : Écrire le contrat de message**

Créer `backend/src/main/java/com/leadflow/routing/LeadReassignedMessage.java` :

```java
package com.leadflow.routing;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de file publie sur {@code lead.reassigned} : une reattribution manuelle vient
 * d'avoir lieu, et l'ERP ne le sait pas encore.
 *
 * <p>Une reference, pas un contenu, comme {@link RoutedLeadMessage} : le consommateur relit
 * la base, donc un rejeu depuis la DLQ travaille sur la donnee a jour.
 *
 * <p>{@code previousSalesRepId} voyage sans etre utilise pour decider quoi que ce soit :
 * il rend le message lisible dans le journal des morts, ou l'operateur voit ce que la
 * propagation devait corriger.
 *
 * <p><b>Le consommateur est idempotent par nature</b> : poser un responsable est une
 * ecriture, pas une rotation. C'est ce qui distingue cette cle de {@code lead.qualified},
 * dont le rejeu decale le tour de role.
 */
public record LeadReassignedMessage(
        UUID leadId,
        UUID clientId,
        UUID previousSalesRepId,
        UUID newSalesRepId,
        Instant reassignedAt) {
}
```

- [ ] **Step 4 : Déclarer la clé, la file et le binding**

Dans `backend/src/main/java/com/leadflow/config/RabbitMQConfig.java`, ajouter les constantes après le bloc `ROUTED_*` :

```java
    /** F15 : la correction manuelle d'un responsable, a pousser vers l'ERP. */
    public static final String REASSIGNED_QUEUE = "leadflow.leads.reassigned";
    public static final String REASSIGNED_ROUTING_KEY = "lead.reassigned";
```

Puis, aux côtés des autres beans de file, la file et son binding :

```java
    @Bean
    Queue reassignedQueue() {
        return QueueBuilder.durable(REASSIGNED_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    Binding reassignedBinding(Queue reassignedQueue, DirectExchange leadsExchange) {
        return BindingBuilder.bind(reassignedQueue).to(leadsExchange).with(REASSIGNED_ROUTING_KEY);
    }
```

Ne rien ajouter aux liaisons de `MONITORING_QUEUE` : la spec le décide explicitement — le flux temps réel montre les leads qui avancent dans le pipeline, pas les corrections manuelles.

- [ ] **Step 5 : Lancer le test pour vérifier qu'il passe**

Run: `cd backend && ./mvnw test -Dtest=ReassignTopologieTest`
Expected: PASS, deux tests.

- [ ] **Step 6 : Commit**

```bash
git add backend/src/main/java/com/leadflow/routing/LeadReassignedMessage.java \
        backend/src/main/java/com/leadflow/config/RabbitMQConfig.java \
        backend/src/test/java/com/leadflow/crm/ReassignTopologieTest.java
git commit -m "feat(f15): la cle lead.reassigned, sa file et son contrat"
```

---

### Task 2 : La publication depuis `routing/`

**Files:**
- Create: `backend/src/main/java/com/leadflow/routing/LeadReassignedPublisher.java`
- Modify: `backend/src/main/java/com/leadflow/routing/ReattributionService.java`
- Test: `backend/src/test/java/com/leadflow/routing/ReattributionServiceTest.java`

**Interfaces:**
- Consumes: `LeadReassignedMessage`, `RabbitMQConfig.REASSIGNED_*` (Task 1).
- Produces: `LeadReassignedPublisher.publie(UUID leadId, UUID clientId, UUID ancien, UUID nouveau)`.

- [ ] **Step 1 : Écrire le test qui échoue**

Ajouter à `ReattributionServiceTest` (le fichier existe ; garder ses tests) :

```java
    @Test
    void publieUnMessageDeReaffectationApresLeJournal() {
        rabbitAdmin.purgeQueue(RabbitMQConfig.REASSIGNED_QUEUE);
        Client boutique = uneBoutique("Agence Nord");
        UUID premier = unCommercial(boutique, "Sonia Merbah");
        UUID second = unCommercial(boutique, "Yanis Roux");
        UUID leadId = unLead(boutique, premier, LeadStatus.SYNCED);

        service.reattribue(leadId, second, "Secteur mal decoupe", "admin");

        Object recu = rabbitTemplate.receiveAndConvert(RabbitMQConfig.REASSIGNED_QUEUE, 5000);
        assertThat(recu).isInstanceOf(LeadReassignedMessage.class);
        LeadReassignedMessage message = (LeadReassignedMessage) recu;
        assertThat(message.leadId()).isEqualTo(leadId);
        assertThat(message.previousSalesRepId()).isEqualTo(premier);
        assertThat(message.newSalesRepId()).isEqualTo(second);
    }
```

Ajouter les deux injections en tête de classe, aux côtés des autres `@Autowired` :

```java
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;
```

et les imports `com.leadflow.config.RabbitMQConfig`, `org.springframework.amqp.rabbit.core.RabbitAdmin`, `org.springframework.amqp.rabbit.core.RabbitTemplate`.

- [ ] **Step 2 : Lancer le test pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw test -Dtest=ReattributionServiceTest#publieUnMessageDeReaffectationApresLeJournal`
Expected: FAIL — `recu` est `null`, aucune publication n'a lieu.

- [ ] **Step 3 : Écrire le publieur**

Créer `backend/src/main/java/com/leadflow/routing/LeadReassignedPublisher.java` :

```java
package com.leadflow.routing;

import com.leadflow.config.RabbitMQConfig;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Annonce a l'ERP qu'un lead a change de responsable.
 *
 * <p>Appel direct apres le commit, comme {@link RoutedLeadPublisher} et non
 * {@code @TransactionalEventListener} : {@link ReattributionService} n'etant pas
 * transactionnel, un tel listener serait silencieusement ignore.
 *
 * <p><b>L'echec de publication n'est jamais relance.</b> La reattribution a eu lieu et elle
 * est journalisee ; faire echouer l'appel HTTP apres coup dirait a l'operateur que son
 * geste a echoue alors qu'il a reussi.
 *
 * <p><b>Il n'y a pas de filet de republication</b>, contrairement a {@link RoutedLeadRelay} :
 * un lead reattribue ne porte aucun etat que le filet pourrait balayer — il reste
 * {@code SYNCED}, exactement comme un lead dont la propagation a reussi. Meme dette assumee
 * que sur {@code lead.qualified}, et elle se paierait pour les trois cles d'un coup.
 */
@Component
public class LeadReassignedPublisher {

    private static final Logger log = LoggerFactory.getLogger(LeadReassignedPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public LeadReassignedPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publie(UUID leadId, UUID clientId, UUID ancien, UUID nouveau) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.LEADS_EXCHANGE,
                    RabbitMQConfig.REASSIGNED_ROUTING_KEY,
                    new LeadReassignedMessage(leadId, clientId, ancien, nouveau, Instant.now()));
        } catch (AmqpException echec) {
            log.warn("Publication de la reaffectation du lead {} en echec", leadId, echec);
        }
    }
}
```

- [ ] **Step 4 : Brancher le publieur dans `ReattributionService`**

Ajouter le champ, le paramètre de constructeur et l'affectation, puis publier **après** `journal.enregistre(action)` et avant le `log.info` final :

```java
        // Apres le journal, jamais avant : l'ordre inverse annoncerait a l'ERP un changement
        // dont la trace peut encore manquer.
        publieur.publie(leadId, lead.getClientId(), ancien, salesRepId);
```

Compléter le Javadoc de classe : la phrase « **Aucun message n'est publié** » devient

```java
 * <p><b>Aucun message de routage n'est publie.</b> Le tour de role n'est pas idempotent —
 * rejouer une attribution decale la rotation — et republier sur {@code lead.routed}
 * renverrait vers l'ERP un lead deja synchronise en entier. Depuis F15, une cle distincte
 * part en revanche : {@code lead.reassigned} ne transporte qu'un changement de responsable,
 * et poser un responsable est idempotent.
```

- [ ] **Step 5 : Lancer les tests pour vérifier qu'ils passent**

Run: `cd backend && ./mvnw test -Dtest=ReattributionServiceTest`
Expected: PASS, tous les tests de la classe, anciens compris.

- [ ] **Step 6 : Commit**

```bash
git add backend/src/main/java/com/leadflow/routing/LeadReassignedPublisher.java \
        backend/src/main/java/com/leadflow/routing/ReattributionService.java \
        backend/src/test/java/com/leadflow/routing/ReattributionServiceTest.java
git commit -m "feat(f15): la reattribution annonce le changement de responsable"
```

---

### Task 3 : La migration `V13` et la nature d'une tentative

**Files:**
- Create: `backend/src/main/resources/db/migration/V13__crm_sync_attempt_nature.sql`
- Create: `backend/src/main/java/com/leadflow/crm/CrmSyncAttemptNature.java`
- Modify: `backend/src/main/java/com/leadflow/crm/CrmSyncAttempt.java`
- Modify: `backend/src/main/java/com/leadflow/crm/CrmSyncTraceWriter.java`
- Test: `backend/src/test/java/com/leadflow/crm/CrmSyncAttemptPersistenceTest.java`

**Interfaces:**
- Consumes: rien.
- Produces: l'énumération `CrmSyncAttemptNature { SYNCHRONISATION, REAFFECTATION }`, et `CrmSyncAttempt.getNature()` / `setNature(...)`.

- [ ] **Step 1 : Écrire le test qui échoue**

Ajouter à `CrmSyncAttemptPersistenceTest` :

```java
    @Test
    void uneTentativeEstUneSynchronisationParDefaut() {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId("dolibarr");
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setNature(CrmSyncAttemptNature.SYNCHRONISATION);
        tentative.setAttemptedAt(Instant.now());

        CrmSyncAttempt relue = repository.saveAndFlush(tentative);

        assertThat(relue.getNature()).isEqualTo(CrmSyncAttemptNature.SYNCHRONISATION);
    }

    @Test
    void uneReaffectationSeRelitCommeTelle() {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId("odoo");
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setNature(CrmSyncAttemptNature.REAFFECTATION);
        tentative.setAssigneeRef("12");
        tentative.setAttemptedAt(Instant.now());

        CrmSyncAttempt relue = repository.saveAndFlush(tentative);

        assertThat(relue.getNature()).isEqualTo(CrmSyncAttemptNature.REAFFECTATION);
        assertThat(relue.getAssigneeRef()).isEqualTo("12");
    }
```

Adapter les noms de champs (`repository`, `leadId`) à ceux déjà utilisés dans cette classe de test — la lire d'abord.

- [ ] **Step 2 : Lancer le test pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw test -Dtest=CrmSyncAttemptPersistenceTest`
Expected: échec de compilation — `CrmSyncAttemptNature` n'existe pas.

- [ ] **Step 3 : Écrire la migration**

Créer `backend/src/main/resources/db/migration/V13__crm_sync_attempt_nature.sql` :

```sql
-- F15 : une ligne de crm_sync_attempt ne raconte plus forcement une synchronisation. Depuis
-- la propagation d'une reattribution, elle peut aussi raconter la correction d'un
-- responsable chez l'ERP, qui ne cree rien et ne touche qu'une reference sur quatre.
--
-- Sans cette colonne, l'ecran de detail montrerait une tentative n'ayant obtenu qu'une
-- reference et ne saurait pas dire que c'est normal.
ALTER TABLE crm_sync_attempt
    ADD COLUMN nature VARCHAR(20) NOT NULL DEFAULT 'SYNCHRONISATION';

-- Le defaut remplit l'historique, et il dit vrai — contrairement au routed_at de V7, laisse
-- nullable parce qu'aucune valeur retroactive n'aurait ete honnete. Toutes les lignes
-- ecrites avant F15 sont bel et bien des synchronisations : la propagation n'existait pas.
--
-- Aucun index : la table se lit toujours par lead_id, ce que sert deja l'index de V2, et la
-- nature ne filtre jamais une requete a elle seule.
```

- [ ] **Step 4 : Écrire l'énumération et le champ**

Créer `backend/src/main/java/com/leadflow/crm/CrmSyncAttemptNature.java` :

```java
package com.leadflow.crm;

/**
 * Ce que raconte une ligne de {@code crm_sync_attempt}.
 *
 * <p>Une {@code REAFFECTATION} ne cree rien dans l'ERP : elle corrige le responsable d'un
 * lead qui s'y trouve deja, et ne renseigne donc que {@code assignee_ref}. La distinguer
 * evite que l'ecran presente une tentative normale comme une synchronisation qui se serait
 * arretee au premier appel.
 */
public enum CrmSyncAttemptNature {
    SYNCHRONISATION,
    REAFFECTATION
}
```

Dans `CrmSyncAttempt.java`, ajouter le champ après `status` avec ses accesseurs, sur le modèle exact de `status` :

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "nature", nullable = false, length = 20)
    private CrmSyncAttemptNature nature = CrmSyncAttemptNature.SYNCHRONISATION;
```

Le défaut Java double le défaut SQL : `ddl-auto: validate` ne remplit rien à l'insertion, et une colonne `NOT NULL` sans valeur ferait échouer chaque écriture existante.

- [ ] **Step 5 : Poser la nature dans les deux méthodes existantes du `CrmSyncTraceWriter`**

Dans `succes(...)` et dans `echec(...)`, ajouter juste après `setStatus(...)` :

```java
        tentative.setNature(CrmSyncAttemptNature.SYNCHRONISATION);
```

Explicite plutôt que laissé au défaut du champ : ces deux méthodes vont bientôt avoir deux voisines, et une nature implicite serait la première chose qu'un futur lecteur se tromperait à recopier.

- [ ] **Step 6 : Lancer les tests pour vérifier qu'ils passent**

Run: `cd backend && ./mvnw test -Dtest=CrmSyncAttemptPersistenceTest,CrmSyncServiceTest`
Expected: PASS. Si Flyway se plaint d'un checksum, c'est qu'une migration existante a été modifiée : ne pas la corriger, `docker compose down -v` en développement.

- [ ] **Step 7 : Commit**

```bash
git add backend/src/main/resources/db/migration/V13__crm_sync_attempt_nature.sql \
        backend/src/main/java/com/leadflow/crm/CrmSyncAttemptNature.java \
        backend/src/main/java/com/leadflow/crm/CrmSyncAttempt.java \
        backend/src/main/java/com/leadflow/crm/CrmSyncTraceWriter.java \
        backend/src/test/java/com/leadflow/crm/CrmSyncAttemptPersistenceTest.java
git commit -m "feat(f15): une tentative dit desormais ce qu elle raconte"
```

---

### Task 4 : La trace d'une réaffectation, qui ne touche pas le statut du lead

**Files:**
- Modify: `backend/src/main/java/com/leadflow/crm/CrmSyncTraceWriter.java`
- Test: `backend/src/test/java/com/leadflow/crm/CrmSyncServiceTest.java`

**Interfaces:**
- Consumes: `CrmSyncAttemptNature` (Task 3).
- Produces: `CrmSyncTraceWriter.reaffectationReussie(UUID leadId, String providerId, String assigneeRef)` et `CrmSyncTraceWriter.reaffectationEchouee(UUID leadId, String providerId, String message)`, toutes deux `@Transactional(REQUIRES_NEW)` et `void`.

**C'est la tâche la plus facile à faire de travers.** `succes(...)` passe le lead en `SYNCED` et `echec(...)` en `FAILED` : réutiliser l'une ou l'autre ferait régresser en `FAILED` un lead parfaitement synchronisé dont seule la correction de responsable a échoué. Les deux nouvelles méthodes n'appellent **jamais** `changeStatut`.

- [ ] **Step 1 : Écrire les tests qui échouent**

Ajouter à `CrmSyncServiceTest` :

```java
    @Test
    void uneReaffectationEnEchecNeFaitPasRegresserLeStatutDuLead() {
        UUID leadId = unLeadSynchronise();

        trace.reaffectationEchouee(leadId, "dolibarr", "ERP injoignable");

        assertThat(leads.findById(leadId).orElseThrow().getStatus())
                .as("une propagation ratee ne desynchronise pas le lead")
                .isEqualTo(LeadStatus.SYNCED);
        CrmSyncAttempt ligne = derniereTentative(leadId);
        assertThat(ligne.getNature()).isEqualTo(CrmSyncAttemptNature.REAFFECTATION);
        assertThat(ligne.getStatus()).isEqualTo(CrmSyncAttemptStatus.FAILED);
        assertThat(ligne.getErrorMessage()).isEqualTo("ERP injoignable");
    }

    @Test
    void etatAnterieurRendLeNouveauResponsableApresUneReaffectation() {
        UUID leadId = unLeadSynchronise();      // laisse une ligne SUCCESS avec assigneeRef "7"

        trace.reaffectationReussie(leadId, "dolibarr", "9");

        // Le coeur de F15 : sans cette assertion, une resynchronisation ulterieure relirait
        // "7" depuis la vieille ligne et retablirait chez l'ERP ce qu'on vient de corriger.
        assertThat(service.etatAnterieurPour(leadId, "dolibarr").assigneeRef()).isEqualTo("9");
    }
```

`etatAnterieur` est aujourd'hui `private`. Le rendre **package-private** (retirer `private`) plutôt que public, et le renommer `etatAnterieurPour` : `CrmReassignService` en aura besoin en Task 7, et il vit dans le même paquet. Ajouter au-dessus le commentaire :

```java
    /**
     * Package-private et non privee depuis F15 : {@link CrmReassignService} reconstruit le
     * meme etat, et le dupliquer ferait diverger les deux lectures a la premiere evolution.
     */
```

Écrire les deux méthodes utilitaires `unLeadSynchronise()` et `derniereTentative(UUID)` dans la classe de test si elles n'y sont pas, en réutilisant les fabriques déjà présentes ; ne pas dupliquer une fabrique existante.

- [ ] **Step 2 : Lancer les tests pour vérifier qu'ils échouent**

Run: `cd backend && ./mvnw test -Dtest=CrmSyncServiceTest`
Expected: échec de compilation — `reaffectationEchouee` et `reaffectationReussie` n'existent pas.

- [ ] **Step 3 : Écrire les deux méthodes**

Dans `CrmSyncTraceWriter` :

```java
    /**
     * Trace d'une correction de responsable reussie.
     *
     * <p><b>Ne touche pas le statut du lead</b>, contrairement a {@link #succes}. Un lead
     * reaffecte etait deja {@code SYNCED} et le reste ; le faire repasser par un changement
     * de statut n'apprendrait rien et ferait mentir {@code updated_at}.
     *
     * <p>La ligne ne porte que {@code assignee_ref}, et c'est suffisant : {@code
     * etatAnterieurPour} prend la valeur non nulle la plus recente champ par champ, donc les
     * trois autres references restent celles de la synchronisation d'origine.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reaffectationReussie(UUID leadId, String providerId, String assigneeRef) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId(providerId);
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setNature(CrmSyncAttemptNature.REAFFECTATION);
        tentative.setAssigneeRef(assigneeRef);
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.save(tentative);
    }

    /**
     * Trace d'une correction de responsable en echec, ecrite <b>avant</b> que l'exception ne
     * parte : en {@code REQUIRES_NEW}, elle survit au rollback du consommateur, comme la
     * ligne {@code FAILED} d'une synchronisation.
     *
     * <p>Ne touche pas davantage le statut : faire retomber en {@code FAILED} un lead
     * correctement synchronise parce que la correction de son responsable n'est pas passee
     * serait une regression visible sur toutes les listes du dashboard.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reaffectationEchouee(UUID leadId, String providerId, String message) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId(providerId);
        tentative.setStatus(CrmSyncAttemptStatus.FAILED);
        tentative.setNature(CrmSyncAttemptNature.REAFFECTATION);
        tentative.setErrorMessage(message);
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.save(tentative);
    }
```

- [ ] **Step 4 : Lancer les tests pour vérifier qu'ils passent**

Run: `cd backend && ./mvnw test -Dtest=CrmSyncServiceTest`
Expected: PASS.

- [ ] **Step 5 : Commit**

```bash
git add backend/src/main/java/com/leadflow/crm/CrmSyncTraceWriter.java \
        backend/src/main/java/com/leadflow/crm/CrmSyncService.java \
        backend/src/test/java/com/leadflow/crm/CrmSyncServiceTest.java
git commit -m "feat(f15): la trace d une reaffectation ne desynchronise pas le lead"
```

---

### Task 5 : `OdooClient.ecrit`, la mise à jour qui manquait

**Files:**
- Modify: `backend/src/main/java/com/leadflow/crm/odoo/OdooClient.java`
- Test: `backend/src/test/java/com/leadflow/crm/odoo/OdooClientTest.java`

**Interfaces:**
- Consumes: rien.
- Produces: `OdooClient.ecrit(CrmTarget target, int uid, String modele, String identifiant, Map<String, Object> champs)`, `void`, levant `CrmSyncException` si Odoo ne confirme pas.

Le client Odoo ne sait que créer : `authentifie`, `cree`, `chercheUtilisateurParEmail`, `verifieAcces`. C'est le seul vrai travail neuf de la feature, et il est isolé ici pour être éprouvé sans toucher au port.

- [ ] **Step 1 : Écrire le test qui échoue**

Ajouter à `OdooClientTest`, en suivant le montage `MockRestServiceServer` déjà utilisé dans cette classe (la lire d'abord pour reprendre ses fabriques) :

```java
    @Test
    void ecritEnvoieUnWriteAvecIdentifiantEtChamps() {
        serveur.expect(requestTo(URL_JSONRPC))
                .andExpect(method(HttpMethod.POST))
                // Le corps exact, pas seulement le code retour : c'est la forme de l'appel
                // qui casse quand Odoo change, et un 200 ne l'aurait pas vue.
                .andExpect(jsonPath("$.params.method").value("write"))
                .andExpect(jsonPath("$.params.model").value("crm.lead"))
                .andExpect(jsonPath("$.params.args[0][0]").value(31))
                .andExpect(jsonPath("$.params.args[1].user_id").value("9"))
                .andRespond(withSuccess("{\"result\": true}", MediaType.APPLICATION_JSON));

        client.ecrit(CIBLE, 2, "crm.lead", "31", Map.of("user_id", "9"));

        serveur.verify();
    }

    @Test
    void ecritRefuseUnResultatQuiNestPasVrai() {
        // Odoo repond 200 meme en cas de refus : l'echec vit dans le corps. Un « false »
        // avale silencieusement ferait croire a une correction qui n'a pas eu lieu.
        serveur.expect(requestTo(URL_JSONRPC))
                .andRespond(withSuccess("{\"result\": false}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.ecrit(CIBLE, 2, "crm.lead", "31", Map.of("user_id", "9")))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("crm.lead.write");
    }
```

- [ ] **Step 2 : Lancer les tests pour vérifier qu'ils échouent**

Run: `cd backend && ./mvnw test -Dtest=OdooClientTest`
Expected: échec de compilation — `ecrit` n'existe pas.

- [ ] **Step 3 : Écrire la méthode**

Dans `OdooClient`, juste après `cree(...)` :

```java
    /**
     * Met a jour un enregistrement existant. Symetrique de {@link #cree} : meme transport,
     * meme authentification, meme lecture du corps.
     *
     * <p>L'identifiant voyage en {@code String} comme partout dans le pivot, et redevient un
     * entier ici : Odoo n'accepte pas une chaine dans la liste d'identifiants d'un
     * {@code write}, et c'est exactement le genre de traduction qui appartient a
     * l'adaptateur.
     *
     * <p>Odoo rend {@code true} sur un write accepte. Tout le reste est un echec, y compris
     * le {@code false} d'un appel refuse et l'absence de {@code result} d'une reponse
     * tronquee — sans ce controle, une correction jamais appliquee passerait pour un succes.
     */
    public void ecrit(
            CrmTarget target, int uid, String modele, String identifiant,
            Map<String, Object> champs) {
        long id;
        try {
            id = Long.parseLong(identifiant);
        } catch (NumberFormatException erreur) {
            throw new CrmSyncException(
                    PROVIDER_ID, "Reference Odoo illisible sur " + modele + " : " + identifiant,
                    erreur);
        }
        Map<String, Object> reponse =
                executeKw(target, uid, modele, "write", List.of(List.of(id), champs));
        Object resultat = resultat(reponse, modele + ".write");
        if (!Boolean.TRUE.equals(resultat)) {
            throw new CrmSyncException(
                    PROVIDER_ID, "Odoo a refusé " + modele + ".write sur " + identifiant, null);
        }
    }
```

Vérifier la forme exacte du quatrième argument de `executeKw` en relisant `cree(...)` : le `List.of(List.of(champs))` qu'il passe est la liste d'arguments positionnels de `create`. Pour `write`, ce sont **deux** arguments — la liste d'identifiants puis la carte des champs — d'où `List.of(List.of(id), champs)`. Si la signature de `executeKw` diffère, l'adapter sans changer cette sémantique.

- [ ] **Step 4 : Lancer les tests pour vérifier qu'ils passent**

Run: `cd backend && ./mvnw test -Dtest=OdooClientTest`
Expected: PASS.

- [ ] **Step 5 : Commit**

```bash
git add backend/src/main/java/com/leadflow/crm/odoo/OdooClient.java \
        backend/src/test/java/com/leadflow/crm/odoo/OdooClientTest.java
git commit -m "feat(f15): le client Odoo sait desormais mettre a jour"
```

---

### Task 6 : Le port `reaffecte` et ses deux implémentations

**Files:**
- Modify: `backend/src/main/java/com/leadflow/crm/CrmConnector.java`
- Modify: `backend/src/main/java/com/leadflow/crm/dolibarr/DolibarrConnector.java`
- Modify: `backend/src/main/java/com/leadflow/crm/odoo/OdooConnector.java`
- Test: `backend/src/test/java/com/leadflow/crm/dolibarr/DolibarrConnectorTest.java`
- Test: `backend/src/test/java/com/leadflow/crm/odoo/OdooConnectorTest.java`

**Interfaces:**
- Consumes: `OdooClient.ecrit` (Task 5), `DolibarrClient.lieResponsable` (existant depuis F11.2).
- Produces: `void CrmConnector.reaffecte(CrmSyncState references, String assigneeRef, CrmTarget cible)`.

La méthode est ajoutée à l'interface **et** aux deux adaptateurs dans la même tâche : sans `default`, le projet ne compile pas entre les deux.

- [ ] **Step 1 : Écrire les tests qui échouent**

Dans `DolibarrConnectorTest`, en réutilisant le `TransportFactice` déjà défini dans la classe (il capture déjà `responsableLie`) :

```java
    @Test
    void reaffecteRelieLeResponsableAuProjetExistant() {
        TransportFactice transport = new TransportFactice();
        DolibarrConnector connecteur = new DolibarrConnector(transport);

        connecteur.reaffecte(new CrmSyncState("42", "77", "301", "7"), "9", CIBLE);

        assertThat(transport.responsableLie).isEqualTo("9");
        // Rien d'autre : une reaffectation ne cree pas, elle corrige.
        assertThat(transport.appels).isEmpty();
    }

    @Test
    void reaffecteRefuseUnLeadSansOpportunite() {
        TransportFactice transport = new TransportFactice();
        DolibarrConnector connecteur = new DolibarrConnector(transport);

        assertThatThrownBy(() ->
                        connecteur.reaffecte(CrmSyncState.VIERGE, "9", CIBLE))
                .isInstanceOf(CrmSyncException.class);
    }
```

Dans `OdooConnectorTest`, sur le même patron que son propre transport factice (le lire d'abord ; y ajouter la capture d'un `ecrit` si elle manque) :

```java
    @Test
    void reaffecteEcritUserIdSurLOpportunite() {
        TransportFactice transport = new TransportFactice();
        OdooConnector connecteur = new OdooConnector(transport);

        connecteur.reaffecte(new CrmSyncState("5", "6", "88", null), "9", CIBLE);

        assertThat(transport.modeleEcrit).isEqualTo("crm.lead");
        assertThat(transport.identifiantEcrit).isEqualTo("88");
        assertThat(transport.champsEcrits).containsEntry("user_id", "9");
    }
```

- [ ] **Step 2 : Lancer les tests pour vérifier qu'ils échouent**

Run: `cd backend && ./mvnw test -Dtest=DolibarrConnectorTest,OdooConnectorTest`
Expected: échec de compilation — `reaffecte` n'existe sur aucun des deux.

- [ ] **Step 3 : Ajouter la méthode au port**

Dans `CrmConnector.java`, après `sync(...)` :

```java
    /**
     * Corrige le responsable d'un lead deja present dans l'ERP.
     *
     * <p><b>Obligatoire, sans implementation par defaut</b>, comme {@link #verifieAcces} :
     * un {@code default} qui ne ferait rien laisserait un futur adaptateur degrader en
     * silence une promesse faite a l'ecran de reattribution. Un ERP incapable de reaffecter
     * doit le declarer en levant, pas l'omettre.
     *
     * <p>Ne recoit pas de {@link com.leadflow.crm.model.CrmLead} : la reaffectation ne
     * touche aucune donnee du prospect, et passer le lead entier inviterait un adaptateur a
     * en profiter pour mettre autre chose a jour.
     *
     * <p>Ne rend rien : le nouveau {@code assigneeRef} est celui qu'on vient de passer, il
     * n'y a rien a apprendre de l'ERP.
     *
     * @param references ce que l'ERP connait deja de ce lead ; l'adaptateur y prend la
     *     reference a laquelle il rattache le responsable — l'opportunite pour les deux
     *     adaptateurs actuels, mais rien ne l'impose
     * @throws com.leadflow.crm.model.CrmSyncException si l'ERP refuse, est injoignable, ou
     *     si {@code references} ne porte pas ce dont l'adaptateur a besoin
     */
    void reaffecte(CrmSyncState references, String assigneeRef, CrmTarget cible);
```

- [ ] **Step 4 : Implémenter côté Dolibarr**

Dans `DolibarrConnector`, après `sync(...)` :

```java
    /**
     * Dolibarr rattache le responsable au projet par un appel dedie — {@code fk_user_resp}
     * est ignore a la creation comme en modification, la sonde de F5 l'a verifie dans les
     * deux sens. C'est le meme appel que l'etape d'attribution de {@link #sync}, ce qui rend
     * la propagation gratuite en surface d'API.
     */
    @Override
    public void reaffecte(CrmSyncState references, String assigneeRef, CrmTarget cible) {
        if (references.opportunityRef() == null) {
            throw new CrmSyncException(
                    providerId(), "Aucune opportunite connue : rien a reaffecter", null);
        }
        client.lieResponsable(cible, references.opportunityRef(), assigneeRef);
    }
```

- [ ] **Step 5 : Implémenter côté Odoo**

Dans `OdooConnector`, après `sync(...)` :

```java
    /**
     * Odoo porte le responsable directement sur le {@code crm.lead}, par {@code user_id} :
     * un seul write suffit, la ou Dolibarr demande un appel de rattachement. C'est
     * exactement le genre de divergence qui se resout dans l'adaptateur et jamais en amont.
     */
    @Override
    public void reaffecte(CrmSyncState references, String assigneeRef, CrmTarget cible) {
        if (references.opportunityRef() == null) {
            throw new CrmSyncException(
                    providerId(), "Aucune opportunite connue : rien a reaffecter", null);
        }
        int uid = client.authentifie(cible);
        client.ecrit(cible, uid, OPPORTUNITE, references.opportunityRef(),
                Map.of("user_id", assigneeRef));
    }
```

`OPPORTUNITE` est la constante déjà déclarée dans cette classe et vaut `"crm.lead"` : ne pas réécrire la chaîne.

- [ ] **Step 6 : Lancer les tests pour vérifier qu'ils passent**

Run: `cd backend && ./mvnw test -Dtest=DolibarrConnectorTest,OdooConnectorTest,CrmConnectorRegistryTest`
Expected: PASS. Si une classe de test implémente `CrmConnector` en dur (faux connecteur), elle ne compile plus : lui ajouter `reaffecte` levant `UnsupportedOperationException`, ce qui est le comportement honnête d'un double de test.

- [ ] **Step 7 : Étendre l'étage d'intégration**

Ajouter à `backend/src/test/java/com/leadflow/crm/ErpIntegrationTest.java` — la classe `@Tag("erp")`, exclue de `./mvnw verify` et jouée seulement contre de vrais conteneurs — un cas par adaptateur : synchroniser un lead, puis appeler `reaffecte` avec un second utilisateur, et vérifier chez l'ERP que le responsable a changé. C'est le seul endroit où la fenêtre résiduelle documentée dans le Javadoc de `DolibarrConnector` — un `lieResponsable` rejoué sur un responsable déjà lié — devient éprouvable au lieu d'être supposée.

Run: `docker compose --profile dolibarr --profile odoo up -d`, puis `docs/erp-integration-setup.md` pour la clé d'API et la base Odoo, puis `./mvnw verify -Perp-it`.
Expected: PASS. Si Dolibarr refuse le second `lieResponsable` en doublon, ne pas contourner : le noter dans le document de fin de session — c'est précisément le comportement que le Javadoc dit qu'il faudra tolérer une fois observé.

- [ ] **Step 8 : Commit**

```bash
git add backend/src/main/java/com/leadflow/crm/CrmConnector.java \
        backend/src/main/java/com/leadflow/crm/dolibarr/DolibarrConnector.java \
        backend/src/main/java/com/leadflow/crm/odoo/OdooConnector.java \
        backend/src/test/java/com/leadflow/crm/
git commit -m "feat(f15): le port sait corriger le responsable d un lead"
```

---

### Task 7 : `CrmReassignService`, qui décide d'appeler ou d'acquitter

**Files:**
- Create: `backend/src/main/java/com/leadflow/crm/CrmReassignService.java`
- Test: `backend/src/test/java/com/leadflow/crm/CrmReassignServiceTest.java`

**Interfaces:**
- Consumes: `CrmSyncService.etatAnterieurPour` (Task 4), `CrmConnector.reaffecte` (Task 6), `CrmSyncTraceWriter.reaffectationReussie` / `reaffectationEchouee` (Task 4), `CrmConnectorRegistry.availableProviders()` et `forProvider(...)` (existants).
- Produces: `CrmReassignService.propage(UUID leadId)`, `void`.

Trois cas acquittent sans DLQ, un seul lève. Le service n'est **pas** transactionnel, comme `CrmSyncService` : la trace porte la sienne.

- [ ] **Step 1 : Écrire les tests qui échouent**

Créer `backend/src/test/java/com/leadflow/crm/CrmReassignServiceTest.java`. Reprendre le montage de `CrmSyncServiceTest` (`@SpringBootTest`, `@Import(TestcontainersConfiguration.class)`, fabriques de boutique/commercial/lead) et **n'effacer que les lignes créées par la classe** — retenir les identifiants, supprimer captures puis leads puis commerciaux puis boutiques.

```java
    @Test
    void nAppellePasLErpQuandLeLeadNyEstPasEncore() {
        UUID leadId = unLeadRouteJamaisSynchronise();

        service.propage(leadId);

        // Rien a corriger : sa synchronisation future portera deja le bon commercial,
        // versPivot lisant assignedSalesRepId au moment de l'appel.
        assertThat(connecteurFactice.appels).isEmpty();
        CrmSyncAttempt ligne = derniereTentative(leadId);
        assertThat(ligne.getNature()).isEqualTo(CrmSyncAttemptNature.REAFFECTATION);
        assertThat(ligne.getStatus()).isEqualTo(CrmSyncAttemptStatus.SUCCESS);
        assertThat(ligne.getErrorMessage()).contains("jamais synchronise");
    }

    @Test
    void nAppellePasLErpQuandIlNeConnaitPasLeCommercial() {
        UUID leadId = unLeadSynchronise();
        connecteurFactice.utilisateurInconnu = true;

        service.propage(leadId);

        assertThat(connecteurFactice.appels).doesNotContain("reaffecte");
        assertThat(derniereTentative(leadId).getErrorMessage()).contains("inconnu de l ERP");
    }

    @Test
    void corrigeLeResponsableEtTraceLaReference() {
        UUID leadId = unLeadSynchronise();

        service.propage(leadId);

        assertThat(connecteurFactice.appels).contains("reaffecte");
        CrmSyncAttempt ligne = derniereTentative(leadId);
        assertThat(ligne.getStatus()).isEqualTo(CrmSyncAttemptStatus.SUCCESS);
        assertThat(ligne.getAssigneeRef()).isEqualTo("9");
        assertThat(ligne.getNature()).isEqualTo(CrmSyncAttemptNature.REAFFECTATION);
    }

    @Test
    void laisseRemonterUnEchecTechniqueApresAvoirTrace() {
        UUID leadId = unLeadSynchronise();
        connecteurFactice.echoue = true;

        assertThatThrownBy(() -> service.propage(leadId))
                .isInstanceOf(CrmSyncException.class);

        // La trace est ecrite AVANT que l'exception ne parte, en REQUIRES_NEW : sans cela
        // elle disparaitrait avec le rollback du consommateur, au moment ou elle sert le
        // plus.
        CrmSyncAttempt ligne = derniereTentative(leadId);
        assertThat(ligne.getStatus()).isEqualTo(CrmSyncAttemptStatus.FAILED);
        assertThat(ligne.getNature()).isEqualTo(CrmSyncAttemptNature.REAFFECTATION);
    }

    @Test
    void uneReaffectationRateeNeDesynchronisePasLeLead() {
        UUID leadId = unLeadSynchronise();
        connecteurFactice.echoue = true;

        assertThatThrownBy(() -> service.propage(leadId)).isInstanceOf(CrmSyncException.class);

        assertThat(leads.findById(leadId).orElseThrow().getStatus()).isEqualTo(LeadStatus.SYNCED);
    }
```

Le connecteur factice, déclaré dans la classe de test et enregistré en `@Primary` par une `@TestConfiguration` importée pour cette classe seule :

```java
    /**
     * Double de test du port. Il remplace l'adaptateur Dolibarr pour que ce test porte sur
     * la decision — appeler ou acquitter — et non sur une traduction deja eprouvee ailleurs.
     */
    static final class ConnecteurFactice implements CrmConnector {

        final List<String> appels = new ArrayList<>();
        boolean utilisateurInconnu;
        boolean echoue;

        @Override
        public String providerId() {
            return "dolibarr";
        }

        @Override
        public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
            throw new UnsupportedOperationException("Hors sujet pour ce test");
        }

        @Override
        public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
            appels.add("resolveAssignee");
            return utilisateurInconnu ? null : "9";
        }

        @Override
        public List<CrmSettingSpec> reglagesAttendus() {
            return List.of();
        }

        @Override
        public CrmCheck verifieAcces(CrmTarget cible) {
            throw new UnsupportedOperationException("Hors sujet pour ce test");
        }

        @Override
        public void reaffecte(CrmSyncState references, String assigneeRef, CrmTarget cible) {
            appels.add("reaffecte");
            if (echoue) {
                throw new CrmSyncException("dolibarr", "ERP injoignable", null);
            }
        }
    }
```

Les leads de test doivent porter un commercial dont `crmRef` est **nul**, sans quoi `referenceDuCommercial` court-circuite `resolveAssignee` et le drapeau `utilisateurInconnu` n'aurait aucun effet.

- [ ] **Step 2 : Lancer les tests pour vérifier qu'ils échouent**

Run: `cd backend && ./mvnw test -Dtest=CrmReassignServiceTest`
Expected: échec de compilation — `CrmReassignService` n'existe pas.

- [ ] **Step 3 : Écrire le service**

Créer `backend/src/main/java/com/leadflow/crm/CrmReassignService.java` :

```java
package com.leadflow.crm;

import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pousse vers l'ERP une correction de responsable decidee a la main.
 *
 * <p><b>C'est ici, et non dans {@code routing/}, que se decide s'il y a quelque chose a
 * corriger.</b> Le routage publie toujours : lui faire consulter {@code crm_sync_attempt}
 * avant de publier le couplerait a l'etat CRM et lui ferait porter une connaissance qui
 * appartient a cette etape.
 *
 * <p><b>Trois cas acquittent sans partir en DLQ</b>, avec une trace explicite — meme parti
 * que {@code IGNOREE} en notification et {@code DISCARDED} en qualification, parce
 * qu'aucune repetition ne les repare : lead jamais synchronise, commercial inconnu de l'ERP,
 * connecteur desactive. Seul un echec technique merite les trois tentatives puis la DLQ.
 *
 * <p>Volontairement non transactionnel, comme {@link CrmSyncService} : la trace porte la
 * sienne, en {@code REQUIRES_NEW}, pour survivre a la remontee de l'exception.
 */
@Service
public class CrmReassignService {

    private static final Logger log = LoggerFactory.getLogger(CrmReassignService.class);

    private final LeadRepository leadRepository;
    private final ClientRepository clientRepository;
    private final SalesRepRepository salesRepRepository;
    private final CrmConnectorRegistry registry;
    private final CrmSyncService syncService;
    private final CrmSyncTraceWriter trace;

    public CrmReassignService(
            LeadRepository leadRepository,
            ClientRepository clientRepository,
            SalesRepRepository salesRepRepository,
            CrmConnectorRegistry registry,
            CrmSyncService syncService,
            CrmSyncTraceWriter trace) {
        this.leadRepository = leadRepository;
        this.clientRepository = clientRepository;
        this.salesRepRepository = salesRepRepository;
        this.registry = registry;
        this.syncService = syncService;
        this.trace = trace;
    }

    public void propage(UUID leadId) {
        Lead lead = leadRepository.findById(leadId).orElseThrow(
                () -> new IllegalArgumentException("Lead inconnu : " + leadId));
        Client client = clientRepository.findById(lead.getClientId()).orElseThrow(
                () -> new IllegalStateException(
                        "Le lead " + leadId + " reference un client inexistant"));
        String providerId = client.getCrmProviderId();

        // La liste plutot que le rattrapage d'exception : forProvider leve une
        // IllegalArgumentException sur un connecteur desactive, et une exception non
        // attrapee ferait partir en DLQ un cas qui doit acquitter.
        if (providerId == null || !registry.availableProviders().contains(providerId)) {
            abandonne(leadId, providerId, "Connecteur indisponible pour cette boutique");
            return;
        }

        CrmSyncState anterieur = syncService.etatAnterieurPour(leadId, providerId);
        if (anterieur.opportunityRef() == null) {
            abandonne(leadId, providerId,
                    "Lead jamais synchronise : rien a corriger chez l ERP");
            return;
        }

        CrmConnector connecteur = registry.forProvider(providerId);
        CrmTarget cible = new CrmTarget(providerId, client.getCrmConfig());

        try {
            String assigneeRef = referenceDuCommercial(lead, connecteur, cible);
            if (assigneeRef == null) {
                abandonne(leadId, providerId, "Commercial inconnu de l ERP : rien a poser");
                return;
            }
            connecteur.reaffecte(anterieur, assigneeRef, cible);
            trace.reaffectationReussie(leadId, providerId, assigneeRef);
            log.info("Responsable du lead {} corrige chez {}", leadId, providerId);
        } catch (CrmSyncException echec) {
            trace.reaffectationEchouee(leadId, providerId, echec.getMessage());
            throw echec;
        } catch (RuntimeException echec) {
            // Meme rattrapage qu'en synchronisation : un adaptateur n'enveloppe que ce qu'il
            // a prevu, et l'echec le plus banal ne doit pas partir en DLQ sans trace.
            trace.reaffectationEchouee(leadId, providerId,
                    echec.getClass().getSimpleName() + " : " + echec.getMessage());
            throw new CrmSyncException(providerId, echec.getMessage(), echec);
        }
    }

    /**
     * Acquitte en laissant une trace lisible. {@code SUCCESS} et non {@code FAILED} : rien
     * n'a echoue, il n'y avait rien a faire — et une ligne rouge apprendrait a l'operateur a
     * ignorer les rouges suivantes.
     */
    private void abandonne(UUID leadId, String providerId, String raison) {
        log.info("Propagation du lead {} sans effet : {}", leadId, raison);
        trace.reaffectationSansEffet(
                leadId, providerId == null ? "inconnu" : providerId, raison);
    }

    /** Resolu une seule fois par commercial et par instance, comme en synchronisation. */
    private String referenceDuCommercial(Lead lead, CrmConnector connecteur, CrmTarget cible) {
        if (lead.getAssignedSalesRepId() == null) {
            return null;
        }
        SalesRep commercial = salesRepRepository.findById(lead.getAssignedSalesRepId())
                .orElseThrow(() -> new IllegalStateException(
                        "Le lead " + lead.getId() + " reference un commercial inexistant"));
        if (commercial.getCrmRef() != null) {
            return commercial.getCrmRef();
        }
        String reference = connecteur.resolveAssignee(
                new CrmAssignee(commercial.getFullName(), commercial.getEmail()), cible);
        if (reference != null) {
            commercial.setCrmRef(reference);
            salesRepRepository.save(commercial);
        }
        return reference;
    }
}
```

- [ ] **Step 4 : Ajouter la troisième méthode de trace**

`abandonne` appelle `reaffectationSansEffet`, qui n'existe pas encore. Dans `CrmSyncTraceWriter` :

```java
    /**
     * Trace d'une propagation qui n'avait rien a faire — lead absent de l'ERP, commercial
     * inconnu, connecteur desactive.
     *
     * <p>{@code SUCCESS} et non {@code FAILED}, avec la raison dans {@code error_message} :
     * rien n'a echoue. C'est le pendant du statut {@code IGNOREE} de la notification, qui
     * ecrit une ligne plutot que de ne rien laisser — sans elle, la chronologie ne saurait
     * pas repondre a « pourquoi l'ERP n'a-t-il pas ete corrige ? ».
     *
     * <p>Aucune reference n'est posee : la ligne ne doit rien apprendre a
     * {@code etatAnterieurPour}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reaffectationSansEffet(UUID leadId, String providerId, String raison) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId(providerId);
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setNature(CrmSyncAttemptNature.REAFFECTATION);
        tentative.setErrorMessage(raison);
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.save(tentative);
    }
```

- [ ] **Step 5 : Lancer les tests pour vérifier qu'ils passent**

Run: `cd backend && ./mvnw test -Dtest=CrmReassignServiceTest`
Expected: PASS, cinq tests.

- [ ] **Step 6 : Commit**

```bash
git add backend/src/main/java/com/leadflow/crm/CrmReassignService.java \
        backend/src/main/java/com/leadflow/crm/CrmSyncTraceWriter.java \
        backend/src/test/java/com/leadflow/crm/CrmReassignServiceTest.java
git commit -m "feat(f15): le service qui decide d appeler l ERP ou d acquitter"
```

---

### Task 8 : Le consommateur et son interrupteur

**Files:**
- Create: `backend/src/main/java/com/leadflow/crm/CrmReassignListener.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application.properties`
- Test: `backend/src/test/java/com/leadflow/crm/CrmReassignListenerTest.java`

**Interfaces:**
- Consumes: `CrmReassignService.propage` (Task 7), `LeadReassignedMessage` (Task 1).
- Produces: le bean `CrmReassignListener`, conditionné par `leadflow.crm.reassign.listener.enabled` (défaut vrai).

- [ ] **Step 1 : Écrire le test qui échoue**

Créer `backend/src/test/java/com/leadflow/crm/CrmReassignListenerTest.java`, sur le patron de `CrmSyncListenerTest` (la lire) :

```java
    @Test
    void deleguerAuServiceEtRienDAutre() {
        UUID leadId = UUID.randomUUID();

        listener.recoit(new LeadReassignedMessage(
                leadId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.now()));

        assertThat(service.leadsPropages).containsExactly(leadId);
    }

    @Test
    void laisseRemonterLExceptionPourQueLaFileReessaie() {
        service.echoue = true;

        assertThatThrownBy(() -> listener.recoit(new LeadReassignedMessage(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), Instant.now())))
                .isInstanceOf(CrmSyncException.class);
    }
```

- [ ] **Step 2 : Lancer le test pour vérifier qu'il échoue**

Run: `cd backend && ./mvnw test -Dtest=CrmReassignListenerTest`
Expected: échec de compilation — `CrmReassignListener` n'existe pas.

- [ ] **Step 3 : Écrire le consommateur**

Créer `backend/src/main/java/com/leadflow/crm/CrmReassignListener.java` :

```java
package com.leadflow.crm;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.routing.LeadReassignedMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Porte la correction d'un responsable jusqu'a l'ERP.
 *
 * <p>File dediee plutot qu'un appel dans le {@code PUT} de reattribution : un ERP lent
 * ferait trainer un geste deja reussi cote LeadFlow, et un ERP eteint forcerait a choisir
 * entre echouer le geste entier ou rendre {@code 200} avec un avertissement.
 *
 * <p>Aucune exception n'est rattrapee : les trois tentatives puis la DLQ sont le
 * comportement voulu, et la ligne {@code crm_sync_attempt} en echec est deja ecrite par
 * {@link CrmReassignService} en transaction propre, donc elle survit.
 *
 * <p><b>Le rejeu depuis le journal des morts est inoffensif</b> : poser un responsable est
 * une ecriture idempotente, contrairement au tour de role que rejoue {@code lead.qualified}.
 *
 * <p>Bean conditionnel comme les autres consommateurs du projet : la suite de tests le
 * retire, et un contexte remis en marche par le cache de tests redemarrerait ses beans
 * {@code Lifecycle} en ignorant {@code auto-startup}.
 */
@Component
@ConditionalOnProperty(name = "leadflow.crm.reassign.listener.enabled", matchIfMissing = true)
public class CrmReassignListener {

    private final CrmReassignService service;

    public CrmReassignListener(CrmReassignService service) {
        this.service = service;
    }

    @RabbitListener(queues = RabbitMQConfig.REASSIGNED_QUEUE)
    public void recoit(LeadReassignedMessage message) {
        service.propage(message.leadId());
    }
}
```

- [ ] **Step 4 : Déclarer la propriété**

Dans `application.yml`, aux côtés de `leadflow.crm.listener.enabled` :

```yaml
        reassign:
          listener:
            enabled: true
```

Respecter l'imbrication réelle du fichier — le lire avant d'éditer.

Dans `src/test/resources/application.properties`, à la suite de la ligne 17 :

```properties
leadflow.crm.reassign.listener.enabled=false
```

- [ ] **Step 5 : Lancer les tests pour vérifier qu'ils passent**

Run: `cd backend && ./mvnw test -Dtest=CrmReassignListenerTest`
Expected: PASS.

- [ ] **Step 6 : Écrire le test de bout en bout**

C'est le seul test qui traverse la chaîne entière — réattribution, publication, consommation, trace — et donc le seul qui attraperait un binding oublié ou un contrat refusé par la liste blanche de paquets. Ajouter à `CrmReassignListenerTest`, en `@SpringBootTest` avec `@Import(TestcontainersConfiguration.class)` et la propriété du listener remise à vrai pour cette classe :

```java
    @Test
    void deLaReattributionJusquALaLigneDeTrace() {
        UUID leadId = unLeadSynchronise();      // boutique, commercial, lead SYNCED
        UUID second = unCommercial(boutique, "Yanis Roux");

        reattribution.reattribue(leadId, second, "Secteur mal decoupe", "admin");

        // Le consommateur travaille en parallele : on attend la ligne, on ne la suppose pas.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            CrmSyncAttempt ligne = derniereTentative(leadId);
            assertThat(ligne.getNature()).isEqualTo(CrmSyncAttemptNature.REAFFECTATION);
        });
    }
```

Si Awaitility n'est pas déjà une dépendance du projet — le vérifier par `grep -rn awaitility backend/pom.xml` —, ne pas l'ajouter pour ce seul test : boucler sur `derniereTentative` avec un délai borné et un compteur d'essais, comme le font les autres tests asynchrones de la suite.

- [ ] **Step 7 : Vérifier que le compte de files monte à six**

Run:
```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
Dans un autre terminal :
```bash
docker exec leadflow-rabbitmq rabbitmqctl list_queues name messages consumers
```
Expected: **six** files métier avec un consommateur chacune — les cinq d'avant plus `leadflow.leads.reassigned`. Arrêter le backend ensuite.

- [ ] **Step 8 : Commit**

```bash
git add backend/src/main/java/com/leadflow/crm/CrmReassignListener.java \
        backend/src/main/resources/application.yml \
        backend/src/test/resources/application.properties \
        backend/src/test/java/com/leadflow/crm/CrmReassignListenerTest.java
git commit -m "feat(f15): le consommateur de la file de reaffectation"
```

---

### Task 9 : La chronologie et le DTO de tentative

**Files:**
- Modify: `backend/src/main/java/com/leadflow/monitoring/dto/TimelineEventType.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadTimelineService.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/dto/SyncAttemptView.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadDetailService.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/LeadTimelineServiceTest.java`
- Test: la classe de test qui asserte le corps JSON du détail (la trouver par `grep -rln "syncAttempts" backend/src/test`)

**Interfaces:**
- Consumes: `CrmSyncAttemptNature` (Task 3).
- Produces: `TimelineEventType.REAFFECTATION_ERP`, et `SyncAttemptView(UUID id, String providerId, CrmSyncAttemptStatus status, CrmSyncAttemptNature nature, String accountRef, String contactRef, String opportunityRef, String assigneeRef, String errorMessage, Instant attemptedAt)`.

- [ ] **Step 1 : Écrire les tests qui échouent**

Dans `LeadTimelineServiceTest` :

```java
    @Test
    void uneReaffectationApparaitCommeTelleEtNonCommeUneSynchronisation() {
        UUID leadId = unLeadSynchronise();
        uneTentative(leadId, CrmSyncAttemptNature.REAFFECTATION, CrmSyncAttemptStatus.SUCCESS,
                "9", null);

        List<TimelineEntry> chronologie = service.pour(leadId);

        TimelineEntry entree = chronologie.stream()
                .filter(e -> e.type() == TimelineEventType.REAFFECTATION_ERP)
                .findFirst()
                .orElseThrow();
        assertThat(entree.outcome()).isEqualTo(TimelineOutcome.SUCCES);
        assertThat(entree.details()).containsEntry("responsable", "9");
    }

    @Test
    void uneReaffectationSansEffetSeLitDansSaRaison() {
        UUID leadId = unLeadSynchronise();
        uneTentative(leadId, CrmSyncAttemptNature.REAFFECTATION, CrmSyncAttemptStatus.SUCCESS,
                null, "Commercial inconnu de l ERP : rien a poser");

        TimelineEntry entree = service.pour(leadId).stream()
                .filter(e -> e.type() == TimelineEventType.REAFFECTATION_ERP)
                .findFirst()
                .orElseThrow();

        // La ligne repond a « pourquoi l'ERP n'a-t-il pas ete corrige ? », comme IGNOREE
        // repond a « pourquoi n'ai-je pas ete prevenu ? ».
        assertThat(entree.details()).containsEntry("raison", "Commercial inconnu de l ERP : rien a poser");
    }
```

Adapter `service.pour(...)`, `TimelineEntry.outcome()` et `details()` aux noms réels — lire `LeadTimelineService` et `TimelineEntry` d'abord.

- [ ] **Step 2 : Lancer les tests pour vérifier qu'ils échouent**

Run: `cd backend && ./mvnw test -Dtest=LeadTimelineServiceTest`
Expected: échec de compilation — `REAFFECTATION_ERP` n'existe pas.

- [ ] **Step 3 : Ajouter le type d'événement**

Dans `TimelineEventType.java`, **après** `SYNC_ERP` et avant `NOTIFICATION` — l'ordre de déclaration est l'ordre du pipeline et sert à placer une entrée non datée ; une correction de responsable suit toujours une synchronisation :

```java
    // Apres SYNC_ERP : on ne corrige le responsable que d'un lead deja present dans l'ERP.
    REAFFECTATION_ERP,
```

Mettre à jour le Javadoc de l'énumération : « les neuf faits » devient « les dix faits ».

- [ ] **Step 4 : Séparer les deux natures dans le service**

Dans `LeadTimelineService`, remplacer l'appel unique à `synchronisation(tentative)` par un aiguillage sur la nature, et ajouter la méthode voisine :

```java
    private TimelineEntry reaffectationErp(CrmSyncAttempt tentative) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("connecteur", tentative.getProviderId());
        if (tentative.getAssigneeRef() != null) {
            details.put("responsable", tentative.getAssigneeRef());
        }
        if (tentative.getErrorMessage() != null) {
            // Une ligne SUCCESS portant un message decrit une propagation sans effet ; une
            // ligne FAILED decrit un echec technique. Le mot change, la colonne non.
            details.put(
                    tentative.getStatus() == CrmSyncAttemptStatus.SUCCESS ? "raison" : "erreur",
                    tentative.getErrorMessage());
        }
        return new TimelineEntry(
                TimelineEventType.REAFFECTATION_ERP,
                tentative.getAttemptedAt(),
                tentative.getStatus() == CrmSyncAttemptStatus.SUCCESS
                        ? TimelineOutcome.SUCCES
                        : TimelineOutcome.ECHEC,
                Map.copyOf(details));
    }
```

- [ ] **Step 5 : Corriger `SyncAttemptView` et son câblage**

Remplacer le record par :

```java
/**
 * Une ligne de la trace append-only de F5. Depuis F15 elle porte sa {@code nature} et la
 * reference du responsable.
 *
 * <p>{@code taskRef} n'y figure plus : la colonne existe depuis {@code V2}, aucun adaptateur
 * ne l'a jamais remplie, et l'ecran affichait « tache — » sur chaque tentative de chaque lead
 * depuis F5 tout en taisant {@code assigneeRef}, la seule reference qui ait bouge depuis. La
 * colonne de base reste : elle ne coute rien, et un adaptateur pourra la remplir.
 */
public record SyncAttemptView(
        UUID id,
        String providerId,
        CrmSyncAttemptStatus status,
        CrmSyncAttemptNature nature,
        String accountRef,
        String contactRef,
        String opportunityRef,
        String assigneeRef,
        String errorMessage,
        Instant attemptedAt) {
}
```

Corriger la construction dans `LeadDetailService` en conséquence. Le compilateur signale l'emplacement exact.

- [ ] **Step 6 : Lancer les tests pour vérifier qu'ils passent**

Run: `cd backend && ./mvnw test -Dtest=LeadTimelineServiceTest,LeadDetailServiceTest`
Expected: PASS. Le test qui asserte le corps JSON du détail échouera sur `taskRef` : le corriger pour attendre `assigneeRef` et `nature` — c'est l'assertion sur le JSON, pas sur le DTO, qui prouve quelque chose.

- [ ] **Step 7 : Lancer toute la suite backend**

Run: `cd backend && ./mvnw verify`
Expected: PASS, sans `-Perp-it`.

- [ ] **Step 8 : Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/ backend/src/test/java/com/leadflow/monitoring/
git commit -m "feat(f15): la chronologie distingue la correction du responsable"
```

---

### Task 10 : Le frontend

**Files:**
- Modify: `frontend/src/app/core/models/lead.ts`
- Modify: `frontend/src/app/features/leads/lead-detail/lead-timeline/lead-timeline.ts`
- Modify: `frontend/src/app/features/leads/lead-detail/lead-detail.html:126-128`
- Modify: `frontend/src/app/features/leads/lead-detail/reattribution-dialog/reattribution-dialog.html:4-14`
- Test: `frontend/src/app/features/leads/lead-detail/lead-timeline/lead-timeline.spec.ts`

**Interfaces:**
- Consumes: le JSON produit en Task 9.
- Produces: rien pour les tâches suivantes.

- [ ] **Step 1 : Écrire le test qui échoue**

Dans `lead-timeline.spec.ts`, sur le patron du test `SYNC_ERP` déjà présent :

```typescript
  it('nomme une reaffectation autrement qu une synchronisation', async () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/leads/abc/timeline').flush([
      {
        type: 'REAFFECTATION_ERP',
        at: '2026-09-06T10:00:05Z',
        outcome: 'SUCCES',
        details: { connecteur: 'dolibarr', responsable: '9' },
      },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();

    const entree = fixture.nativeElement.querySelector('[data-entree]');
    // Corriger un responsable n'est pas synchroniser : confondre les deux ferait lire
    // « Synchronisation ERP » la ou rien n'a ete pousse.
    expect(entree.textContent).toContain('Responsable corrigé');
    expect(entree.textContent).not.toContain('Synchronisation ERP');
  });

  it('explique une propagation restee sans effet', async () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/leads/abc/timeline').flush([
      {
        type: 'REAFFECTATION_ERP',
        at: '2026-09-06T10:00:05Z',
        outcome: 'SUCCES',
        details: { connecteur: 'dolibarr', raison: 'Lead jamais synchronise' },
      },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Lead jamais synchronise');
  });
```

- [ ] **Step 2 : Lancer le test pour vérifier qu'il échoue**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — TypeScript refuse `'REAFFECTATION_ERP'`, absent de l'union `TimelineEventType`.

- [ ] **Step 3 : Étendre le modèle**

Dans `core/models/lead.ts`, ajouter `| 'REAFFECTATION_ERP'` à `TimelineEventType` après `'SYNC_ERP'`, et corriger `SyncAttemptView` :

```typescript
export type SyncAttemptNature = 'SYNCHRONISATION' | 'REAFFECTATION';

/** Une tentative de synchronisation ERP : la trace append-only de `crm_sync_attempt`. */
export interface SyncAttemptView {
  id: string;
  providerId: string;
  status: SyncAttemptStatus;
  nature: SyncAttemptNature;
  accountRef: string | null;
  contactRef: string | null;
  opportunityRef: string | null;
  assigneeRef: string | null;
  errorMessage: string | null;
  attemptedAt: string;
}
```

- [ ] **Step 4 : Ajouter le libellé et l'icône**

Dans `lead-timeline.ts`, dans `FAITS`, après `SYNC_ERP` :

```typescript
  // Corriger n'est pas synchroniser : l'icone dit la personne changee, pas le transfert.
  REAFFECTATION_ERP: { libelle: 'Responsable corrigé', icone: 'manage_accounts' },
```

Mettre à jour le commentaire de tête : « chacun des huit faits » devient « chacun des dix faits ». Ajouter dans `DETAILS` les clés `responsable` et `raison` si leur rendu brut ne convient pas — `libelleDetail` rend la clé telle quelle quand la table n'en dit rien, et `raison` est déjà français.

- [ ] **Step 5 : Corriger les références affichées**

Dans `lead-detail.html`, remplacer les lignes 126-128 :

```html
                  tiers {{ tentative.accountRef || '—' }} · contact
                  {{ tentative.contactRef || '—' }} · opportunité
                  {{ tentative.opportunityRef || '—' }} · responsable
                  {{ tentative.assigneeRef || '—' }}
```

Le mot « tiers » est du vocabulaire Dolibarr et ne veut rien dire pour un client Odoo ; le corriger dépasse le périmètre strict de F15 et n'est pas fait ici — c'est une dette à nommer dans le document de fin de session, pas à régler dans une tâche de propagation.

- [ ] **Step 6 : Corriger la phrase du dialogue**

Dans `reattribution-dialog.html`, remplacer le contenu du bandeau (lignes 4-14) :

```html
  @if (avertitSurERP()) {
    <!-- L'icone double le mot et la couleur ne porte jamais seule l'information : ce
         message doit rester lisible sur un ecran imprime en noir et blanc. -->
    <p class="lf-bandeau lf-bandeau--info">
      <mat-icon aria-hidden="true">info</mat-icon>
      <span>
        Ce lead est déjà synchronisé : la correction sera transmise à l'ERP dans la foulée.
        En cas d'échec, elle apparaîtra dans le journal des messages morts.
      </span>
    </p>
  }
```

L'avertissement devient une information : ce n'est plus une limitation qu'on avoue, c'est un traitement qu'on annonce. Vérifier que la classe `lf-bandeau--info` existe dans les styles partagés ; sinon, garder `lf-bandeau--attente` plutôt qu'en inventer une.

- [ ] **Step 7 : Lancer les tests et les contrôles de qualité**

Run:
```bash
cd frontend
npm test -- --watch=false --browsers=ChromeHeadless
npx eslint .
npx prettier --check .
npm run build
```
Expected: tests PASS, ESLint sans constat, Prettier sans fichier signalé, build réussi. `qualite` bloque en CI : un seul constat fait échouer l'exécution.

- [ ] **Step 8 : Commit**

```bash
git add frontend/src/
git commit -m "feat(f15): la fiche annonce la correction et la montre"
```

---

### Task 11 : La documentation

**Files:**
- Modify: `CLAUDE.md` (racine)
- Modify: `backend/src/main/java/com/leadflow/crm/CLAUDE.md`
- Modify: `docs/monitoring-api.md`

**Interfaces:**
- Consumes: tout ce qui précède.
- Produces: rien.

- [ ] **Step 1 : Mettre à jour le `CLAUDE.md` de `crm/`**

Corriger la phrase devenue fausse : « Une carte de références par étape, plutôt qu'un quatrième champ, ne redeviendra la bonne réponse que si un ERP apporte un jour une cinquième étape » reste vraie et doit rester — c'est elle qui a fait écarter la refonte. Ajouter en revanche `reaffecte` à la description du port en tête de fichier, et un paragraphe :

```
**Le port porte une troisieme methode obligatoire depuis F15.** `reaffecte(references,
assigneeRef, cible)` corrige le responsable d'un lead deja present dans l'ERP, sans rien
creer. Sans `default`, pour la meme raison que `verifieAcces` : un ERP incapable de
reaffecter doit le declarer en levant, pas l'omettre. Dolibarr y rattache le responsable au
projet par `lieResponsable`, Odoo y ecrit `user_id` sur le `crm.lead` — la divergence se
resout dans l'adaptateur, comme celle du Tiers et du Contact.
```

- [ ] **Step 2 : Mettre à jour le `CLAUDE.md` racine**

Trois endroits :

1. Section « Routage — le dernier maillon », paragraphe sur `ReattributionService` : « **et elle ne publie aucun message** » devient « et elle ne publie aucun message **de routage** », suivi d'une phrase sur `lead.reassigned`.
2. Section « Base de données » : treize migrations, avec la description de `V13`.
3. Section « État actuel » : F15 livrée ; retirer de « Ce qui n'existe pas » la puce « Une réattribution ne remonte pas jusqu'à l'ERP » et la remplacer par ce qui reste vrai — la propagation ne concerne que le responsable, et rien ne rattrape rétroactivement les leads réattribués avant F15.

- [ ] **Step 3 : Mettre à jour `docs/monitoring-api.md`**

La réponse de `GET /api/leads/{id}` porte `syncAttempts` : corriger l'exemple JSON pour y mettre `nature` et `assigneeRef` à la place de `taskRef`, et ajouter `REAFFECTATION_ERP` à la liste des types de la chronologie.

- [ ] **Step 4 : Vérifier que rien d'autre ne ment**

Run: `grep -rn "taskRef\|ne corrige pas\|conservera l'ancien" --include=*.md --include=*.html --include=*.ts . | grep -v node_modules`
Expected: aucune occurrence décrivant l'ancien comportement, hors le Javadoc de `CrmSyncResult` où `taskRef` existe toujours légitimement.

- [ ] **Step 5 : Commit**

```bash
git add CLAUDE.md backend/src/main/java/com/leadflow/crm/CLAUDE.md docs/monitoring-api.md
git commit -m "docs(f15): la propagation vers l ERP existe, et la doc le dit"
```

---

## Recette et fusion

- [ ] **Suite complète** : `cd backend && ./mvnw verify` puis `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless && npx eslint . && npx prettier --check . && npm run build`.
- [ ] **Pile de production** : `docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build` puis `SMOKE_PASSWORD='...' ./scripts/smoke-prod.sh` — c'est la seule preuve que `V13` s'applique sur une base vierge, la base de développement étant persistante.
- [ ] **Recette à l'écran** — elle revient à l'utilisateur, sauf demande explicite du contraire. Les points à éprouver : le dialogue annonce la transmission sur un lead `SYNCED` et ne promet rien sur un lead `ROUTED` ; la chronologie montre « Responsable corrigé » après quelques secondes ; l'ERP éteint fait apparaître la propagation dans le journal des morts, et le rejeu la répare une fois l'ERP relevé ; la fiche affiche la référence du responsable là où elle affichait « tâche — ».
- [ ] **Push** : `git push -u origin feature/f15-propagation-reattribution-erp`, et attendre les quatre jobs de la CI — le job `fumee` écoute les branches `feature/**`.
- [ ] **Fusion** : `git checkout main && git merge --no-ff feature/f15-propagation-reattribution-erp && git push`. **Ne pas supprimer la branche.**
- [ ] **État de fin de session** dans `docs/superpowers/plans/`, avec les dettes nommées en chemin : le vocabulaire Dolibarr dans `lead-detail.html`, l'absence de filet de republication pour `lead.reassigned`, et les quatre dettes de F14 toujours ouvertes.
