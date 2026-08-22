# F4 — Routage et attribution : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Attribuer chaque lead qualifié à un commercial, puis le pousser dans l'ERP du client — le pipeline devient complet de bout en bout.

**Architecture:** Deux étapes asynchrones. Un consommateur de `leadflow.leads.qualified` choisit le commercial selon la stratégie du client, écrit `assigned_sales_rep_id` + `ROUTED` et publie sur `leadflow.leads.routed` ; un second consommateur appelle le `CrmSyncService` de F5 et passe le lead à `SYNCED`. Les trois stratégies sont des `@Component` résolus par un registre, sans `switch` nulle part.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AMQP, Spring Data JPA, Postgres, JUnit 5 + AssertJ, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-22-f4-routage-attribution-design.md`

## Global Constraints

- Toutes les commandes s'exécutent depuis `backend/`.
- **Aucune migration Flyway.** Tout le schéma nécessaire vient de `V2`. Si une tâche semble en réclamer une, c'est qu'elle a dévié du plan.
- Code, commentaires et Javadoc **en français, sans accents** (le reste du dépôt est ainsi). Les documents Markdown, eux, portent leurs accents.
- Javadoc qui explique **pourquoi**, jamais quoi. C'est la norme du dépôt : chaque classe dit la décision qu'elle incarne et l'alternative écartée.
- Lignes ~100 colonnes, indentation 4 espaces.
- Les sous-packages sont des **étapes du pipeline**, pas des couches techniques : pas de `service/`, `repository/` ni `controller/`.
- Le modèle pivot `crm/model` ne doit recevoir **aucun** terme propre à un ERP.
- Le daemon Docker doit tourner : la plupart des tests utilisent Testcontainers.
- Commits en français, une tâche = un commit, message expliquant la décision et non le diff.

---

## Écarts assumés par rapport à la spec

**La rotation est appliquée avant l'appel à la stratégie, pas dedans.** La spec décrit
`choisit(Lead, List<SalesRep>)` et dit que les trois stratégies partagent le départage « le
moins récemment servi ». Plutôt que de passer une carte d'horodatages à chaque stratégie,
c'est `LeadRoutingService` qui trie la liste des éligibles par ancienneté avant de la
transmettre : les stratégies filtrent puis prennent le premier. Signature de la spec
inchangée, stratégies restées pures, et la seule classe qui touche la base est
`RotationOrder`.

---

## Structure des fichiers

| Fichier | Responsabilité |
| --- | --- |
| `routing/AttributionRecente.java` | Projection Spring Data : commercial + date de sa dernière attribution |
| `routing/RotationOrder.java` | Ordonne les commerciaux du moins récemment servi au plus récemment servi |
| `routing/AssignmentStrategy.java` | Port : `type()` + `choisit(Lead, List<SalesRep>)` |
| `routing/RoundRobinStrategy.java` | Prend le premier de la liste déjà ordonnée |
| `routing/GeographicStrategy.java` | Filtre sur `zone` contre `country_code`, repli round-robin |
| `routing/SectorStrategy.java` | Filtre sur `sector`, repli round-robin |
| `routing/AssignmentStrategyRegistry.java` | Résout la stratégie par `AssignmentStrategyType`, valide le câblage au démarrage |
| `routing/AssignmentException.java` | Aucun commercial éligible — le seul échec qui mérite la DLQ |
| `routing/RoutedLeadWriter.java` | Écrit `assigned_sales_rep_id` + `ROUTED` en transaction propre |
| `routing/RoutedLeadMessage.java` | Contrat de file `lead.routed` |
| `routing/RoutedLeadPublisher.java` | Publication, sans relance en cas d'échec |
| `routing/LeadRoutingService.java` | Orchestration : idempotence, stratégie, écriture, publication |
| `routing/LeadRoutingListener.java` | Traduction AMQP, bean conditionnel |
| `crm/CrmSyncListener.java` | Consomme `lead.routed`, appelle `CrmSyncService`, bean conditionnel |
| `crm/SyncedLeadWriter.java` | Passe le lead à `SYNCED` après une synchronisation réussie |

Fichiers modifiés hors de ces packages : `config/RabbitMQConfig.java`,
`qualification/LeadRepository.java`, `application.yml`,
`src/test/resources/application.properties`, `routing/package-info.java`, `CLAUDE.md`.

---

## Task 1: Ordre de rotation lu dans la table `lead`

**Files:**
- Create: `backend/src/main/java/com/leadflow/routing/AttributionRecente.java`
- Create: `backend/src/main/java/com/leadflow/routing/RotationOrder.java`
- Modify: `backend/src/main/java/com/leadflow/qualification/LeadRepository.java`
- Test: `backend/src/test/java/com/leadflow/routing/RotationOrderTest.java`

**Interfaces:**
- Consomme : `Lead`, `LeadRepository`, `SalesRep` (tous existants).
- Produit : `RotationOrder.parAnciennete(UUID clientId, List<SalesRep> commerciaux) -> List<SalesRep>`, consommé par T4. `LeadRepository.derniereAttributionParCommercial(UUID clientId) -> List<AttributionRecente>`.

**Testcontainers requis.**

- [ ] **Step 1: Écrire le test d'intégration**

Créer `backend/src/test/java/com/leadflow/routing/RotationOrderTest.java` :

```java
package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L'ordre de rotation est deduit des donnees, pas d'un compteur : ce test le verifie sur
 * une vraie base, seul endroit ou l'agregation a un sens.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RotationOrderTest {

    @Autowired private RotationOrder rotation;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Client client;

    @BeforeEach
    void preparation() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
        client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost", "apiKey", "x"));
        client = clientRepository.saveAndFlush(client);
    }

    private SalesRep commercial(String email) {
        SalesRep commercial = new SalesRep();
        commercial.setClient(client);
        commercial.setFullName("Commercial " + email);
        commercial.setEmail(email);
        return salesRepRepository.saveAndFlush(commercial);
    }

    /** Insere un lead attribue et vieilli de {@code ageHeures}. */
    private void leadAttribue(SalesRep commercial, int ageHeures) {
        RawLeadEvent brut = new RawLeadEvent();
        brut.setClientId(client.getId());
        brut.setSource("formulaire-devis");
        brut.setPayload(Map.of("email", "prospect@acme.test"));
        brut.setSignature("t=1,v1=" + UUID.randomUUID());
        brut.setStatus(RawLeadEventStatus.PUBLISHED);
        UUID eventId = rawLeadEventRepository.saveAndFlush(brut).getId();

        Lead lead = new Lead();
        lead.setClientId(client.getId());
        lead.setRawEventId(eventId);
        lead.setEmail("prospect+" + UUID.randomUUID() + "@acme.test");
        lead.setScore(50);
        lead.setStatus(LeadStatus.ROUTED);
        lead.setAssignedSalesRepId(commercial.getId());
        UUID leadId = leadRepository.saveAndFlush(lead).getId();

        // cast explicite : sans lui Postgres ne sait pas typer le parametre de l'intervalle.
        jdbcTemplate.update(
                "update lead set created_at = created_at - (cast(? as int) * interval '1 hour')"
                        + " where id = ?",
                ageHeures, leadId);
    }

    @Test
    void placeEnTeteLeCommercialQuiNAJamaisRienRecu() {
        SalesRep servi = commercial("servi@demo.test");
        SalesRep jamaisServi = commercial("neuf@demo.test");
        leadAttribue(servi, 1);

        List<SalesRep> ordre = rotation.parAnciennete(client.getId(), List.of(servi, jamaisServi));

        assertThat(ordre).extracting(SalesRep::getEmail)
                .containsExactly("neuf@demo.test", "servi@demo.test");
    }

    @Test
    void ordonneDuMoinsRecemmentServiAuPlusRecent() {
        SalesRep ancien = commercial("ancien@demo.test");
        SalesRep recent = commercial("recent@demo.test");
        leadAttribue(ancien, 48);
        leadAttribue(recent, 1);

        List<SalesRep> ordre = rotation.parAnciennete(client.getId(), List.of(recent, ancien));

        assertThat(ordre).extracting(SalesRep::getEmail)
                .containsExactly("ancien@demo.test", "recent@demo.test");
    }

    @Test
    void neRegardeQueLesLeadsDuClientConcerne() {
        SalesRep commercial = commercial("unique@demo.test");
        leadAttribue(commercial, 1);

        List<SalesRep> ordre = rotation.parAnciennete(UUID.randomUUID(), List.of(commercial));

        // Aucune attribution connue pour cet autre client : le commercial reste eligible.
        assertThat(ordre).containsExactly(commercial);
    }

    @Test
    void rendUneListeVideSansCommercial() {
        assertThat(rotation.parAnciennete(client.getId(), List.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Lancer le test et vérifier qu'il échoue**

Run: `./mvnw test -Dtest=RotationOrderTest`
Expected: échec de compilation — `RotationOrder` n'existe pas.

- [ ] **Step 3: Écrire la projection**

Créer `backend/src/main/java/com/leadflow/routing/AttributionRecente.java` :

```java
package com.leadflow.routing;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection Spring Data : un commercial et la date de sa derniere attribution.
 *
 * <p>Interface et non {@code record} : Spring Data sait construire une projection fermee
 * a partir des alias de la requete, sans constructeur a faire correspondre.
 */
public interface AttributionRecente {

    UUID getSalesRepId();

    Instant getDerniereAttribution();
}
```

- [ ] **Step 4: Ajouter la requête au repository**

Dans `backend/src/main/java/com/leadflow/qualification/LeadRepository.java`, ajouter les
imports puis la méthode :

```java
import com.leadflow.routing.AttributionRecente;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
```

```java
    /**
     * Etat du tour de rotation pour F4, deduit des attributions passees. Un commercial
     * absent du resultat n'a jamais rien recu : c'est a lui que revient le prochain lead.
     */
    @Query("""
            select l.assignedSalesRepId as salesRepId, max(l.createdAt) as derniereAttribution
            from Lead l
            where l.clientId = :clientId and l.assignedSalesRepId is not null
            group by l.assignedSalesRepId
            """)
    List<AttributionRecente> derniereAttributionParCommercial(@Param("clientId") UUID clientId);
```

- [ ] **Step 5: Écrire `RotationOrder`**

Créer `backend/src/main/java/com/leadflow/routing/RotationOrder.java` :

```java
package com.leadflow.routing;

import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.SalesRep;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Ordonne les commerciaux du moins recemment servi au plus recemment servi.
 *
 * <p><b>Aucun compteur.</b> Le tour se lit dans la table {@code lead} : un curseur persiste
 * demanderait une migration, un verrou pour deux livraisons concurrentes, et pourrait
 * pointer vers un commercial desactive entre-temps. L'ordre deduit ne peut pas diverger de
 * la realite — il <i>est</i> la realite. Un commercial ajoute aujourd'hui n'a aucune
 * attribution, donc il passe en tete, ce qui est le comportement voulu.
 *
 * <p>Seule classe du package a toucher la base : les strategies restent des fonctions pures
 * sur la liste qu'elle rend, et se testent sans Spring.
 */
@Component
public class RotationOrder {

    private final LeadRepository leadRepository;

    public RotationOrder(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    public List<SalesRep> parAnciennete(UUID clientId, List<SalesRep> commerciaux) {
        if (commerciaux.isEmpty()) {
            return List.of();
        }
        Map<UUID, Instant> dernieres =
                leadRepository.derniereAttributionParCommercial(clientId).stream()
                        .collect(Collectors.toMap(
                                AttributionRecente::getSalesRepId,
                                AttributionRecente::getDerniereAttribution));

        // nullsFirst : jamais servi passe avant tout le monde. Le departage par
        // identifiant rend l'ordre total, donc l'attribution reproductible a donnees egales.
        return commerciaux.stream()
                .sorted(Comparator
                        .comparing((SalesRep commercial) -> dernieres.get(commercial.getId()),
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(SalesRep::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
```

- [ ] **Step 6: Lancer le test et vérifier qu'il passe**

Run: `./mvnw test -Dtest=RotationOrderTest`
Expected: 4 tests, 0 échec.

- [ ] **Step 7: Commiter**

```bash
git add backend/src/main/java/com/leadflow/routing/AttributionRecente.java \
        backend/src/main/java/com/leadflow/routing/RotationOrder.java \
        backend/src/main/java/com/leadflow/qualification/LeadRepository.java \
        backend/src/test/java/com/leadflow/routing/RotationOrderTest.java
git commit -m "feat: ordre de rotation deduit des attributions passees"
```

---

## Task 2: Port `AssignmentStrategy`, round-robin et registre

**Files:**
- Create: `backend/src/main/java/com/leadflow/routing/AssignmentStrategy.java`
- Create: `backend/src/main/java/com/leadflow/routing/RoundRobinStrategy.java`
- Create: `backend/src/main/java/com/leadflow/routing/AssignmentStrategyRegistry.java`
- Test: `backend/src/test/java/com/leadflow/routing/AssignmentStrategyRegistryTest.java`
- Test: `backend/src/test/java/com/leadflow/routing/RoundRobinStrategyTest.java`

**Interfaces:**
- Consomme : `Lead`, `SalesRep`, `AssignmentStrategyType` (existants), `RotationOrder` (T1, seulement en amont — la stratégie ne l'appelle pas).
- Produit : `AssignmentStrategy.type() -> AssignmentStrategyType`, `AssignmentStrategy.choisit(Lead, List<SalesRep>) -> Optional<SalesRep>`, `AssignmentStrategyRegistry.pour(AssignmentStrategyType) -> AssignmentStrategy`. Consommés par T3 et T4.

**Sans Spring ni base.**

- [ ] **Step 1: Écrire les tests du round-robin**

Créer `backend/src/test/java/com/leadflow/routing/RoundRobinStrategyTest.java` :

```java
package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoundRobinStrategyTest {

    private final RoundRobinStrategy strategie = new RoundRobinStrategy();

    static SalesRep commercial(String email, String secteur, String zone) {
        SalesRep commercial = new SalesRep();
        commercial.setFullName("Commercial " + email);
        commercial.setEmail(email);
        commercial.setSector(secteur);
        commercial.setZone(zone);
        return commercial;
    }

    static Lead lead(String pays, String secteur) {
        Lead lead = new Lead();
        lead.setEmail("prospect@acme.test");
        lead.setCountryCode(pays);
        lead.setSector(secteur);
        return lead;
    }

    @Test
    void seDeclareSousLeTypeRoundRobin() {
        assertThat(strategie.type()).isEqualTo(AssignmentStrategyType.ROUND_ROBIN);
    }

    /**
     * La liste arrive deja triee du moins recemment servi au plus recemment servi : la
     * strategie n'a qu'a prendre la tete. C'est ce qui la rend testable sans base.
     */
    @Test
    void prendLePremierDeLaListeOrdonnee() {
        SalesRep premier = commercial("premier@demo.test", null, null);
        SalesRep second = commercial("second@demo.test", null, null);

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(premier, second)))
                .contains(premier);
    }

    @Test
    void neChoisitPersonneQuandLaListeEstVide() {
        assertThat(strategie.choisit(lead("MA", "industrie"), List.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Écrire le test du registre**

Créer `backend/src/test/java/com/leadflow/routing/AssignmentStrategyRegistryTest.java` :

```java
package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AssignmentStrategyRegistryTest {

    /** Strategie factice : ce test porte sur la resolution, pas sur le choix. */
    private record StrategieFactice(AssignmentStrategyType type) implements AssignmentStrategy {

        @Override
        public Optional<SalesRep> choisit(Lead lead, List<SalesRep> eligibles) {
            return Optional.empty();
        }
    }

    private static List<AssignmentStrategy> completes() {
        return List.of(
                new StrategieFactice(AssignmentStrategyType.ROUND_ROBIN),
                new StrategieFactice(AssignmentStrategyType.GEOGRAPHIC),
                new StrategieFactice(AssignmentStrategyType.SECTOR));
    }

    @Test
    void rendLaStrategieDuTypeDemande() {
        AssignmentStrategyRegistry registre = new AssignmentStrategyRegistry(completes());

        assertThat(registre.pour(AssignmentStrategyType.SECTOR).type())
                .isEqualTo(AssignmentStrategyType.SECTOR);
    }

    /** Une erreur de cablage doit se voir au demarrage, pas au premier lead. */
    @Test
    void refuseDeDemarrerSiUnTypeNAAucuneStrategie() {
        List<AssignmentStrategy> incompletes =
                List.of(new StrategieFactice(AssignmentStrategyType.ROUND_ROBIN));

        assertThatThrownBy(() -> new AssignmentStrategyRegistry(incompletes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEOGRAPHIC");
    }

    @Test
    void refuseDeDemarrerSiDeuxStrategiesRevendiquentLeMemeType() {
        List<AssignmentStrategy> doublon = List.of(
                new StrategieFactice(AssignmentStrategyType.ROUND_ROBIN),
                new StrategieFactice(AssignmentStrategyType.ROUND_ROBIN),
                new StrategieFactice(AssignmentStrategyType.GEOGRAPHIC),
                new StrategieFactice(AssignmentStrategyType.SECTOR));

        assertThatThrownBy(() -> new AssignmentStrategyRegistry(doublon))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROUND_ROBIN");
    }
}
```

- [ ] **Step 3: Lancer les tests et vérifier qu'ils échouent**

Run: `./mvnw test -Dtest='RoundRobinStrategyTest,AssignmentStrategyRegistryTest'`
Expected: échec de compilation — `AssignmentStrategy`, `RoundRobinStrategy` et `AssignmentStrategyRegistry` n'existent pas.

- [ ] **Step 4: Écrire le port**

Créer `backend/src/main/java/com/leadflow/routing/AssignmentStrategy.java` :

```java
package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import java.util.Optional;

/**
 * Choix du commercial destinataire.
 *
 * <p>Une implementation par valeur de {@link AssignmentStrategyType}, chacune un
 * {@code @Component} decouvert par {@link AssignmentStrategyRegistry} : ajouter une
 * strategie, c'est ecrire une classe, jamais completer un {@code switch}.
 *
 * <p><b>Fonction pure.</b> {@code eligibles} arrive deja filtree sur les commerciaux actifs
 * du client et <b>ordonnee du moins recemment servi au plus recemment servi</b> par
 * {@link RotationOrder}. Une strategie ne lit donc jamais la base : elle filtre selon son
 * critere et prend la tete de ce qui reste. C'est ce qui rend le departage equitable dans
 * les trois strategies sans le reecrire trois fois.
 */
public interface AssignmentStrategy {

    AssignmentStrategyType type();

    /** @return vide si aucun commercial ne convient ; c'est a l'appelant de lever. */
    Optional<SalesRep> choisit(Lead lead, List<SalesRep> eligibles);
}
```

- [ ] **Step 5: Écrire le round-robin**

Créer `backend/src/main/java/com/leadflow/routing/RoundRobinStrategy.java` :

```java
package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Tour de role pur : le commercial servi le plus anciennement prend le lead suivant.
 *
 * <p>Le corps tient en une ligne parce que tout le travail est fait en amont par
 * {@link RotationOrder}. Les deux autres strategies s'appuient sur celle-ci pour departager
 * leurs candidats, ce qui evite que le premier commercial d'un secteur prenne tout.
 */
@Component
public class RoundRobinStrategy implements AssignmentStrategy {

    @Override
    public AssignmentStrategyType type() {
        return AssignmentStrategyType.ROUND_ROBIN;
    }

    @Override
    public Optional<SalesRep> choisit(Lead lead, List<SalesRep> eligibles) {
        return eligibles.stream().findFirst();
    }
}
```

- [ ] **Step 6: Écrire le registre**

Créer `backend/src/main/java/com/leadflow/routing/AssignmentStrategyRegistry.java` :

```java
package com.leadflow.routing;

import com.leadflow.tenant.AssignmentStrategyType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resout la strategie d'attribution declaree par le client.
 *
 * <p>Meme motif que {@code CrmConnectorRegistry} : les strategies sont collectees par
 * injection de {@code List<AssignmentStrategy>}, donc il n'y a aucune liste a maintenir a
 * la main et aucun {@code switch} sur le type quelque part.
 *
 * <p>Le constructeur echoue si un type est revendique deux fois ou n'a aucun titulaire. Une
 * erreur de cablage doit arreter le demarrage, pas surgir au premier lead d'un client dont
 * la strategie n'a jamais ete implementee.
 */
@Component
public class AssignmentStrategyRegistry {

    private final Map<AssignmentStrategyType, AssignmentStrategy> parType =
            new EnumMap<>(AssignmentStrategyType.class);

    public AssignmentStrategyRegistry(List<AssignmentStrategy> strategies) {
        for (AssignmentStrategy strategie : strategies) {
            AssignmentStrategy ancienne = parType.put(strategie.type(), strategie);
            if (ancienne != null) {
                throw new IllegalStateException(
                        "Deux strategies revendiquent le type " + strategie.type());
            }
        }
        for (AssignmentStrategyType type : AssignmentStrategyType.values()) {
            if (!parType.containsKey(type)) {
                throw new IllegalStateException("Aucune strategie pour le type " + type);
            }
        }
    }

    public AssignmentStrategy pour(AssignmentStrategyType type) {
        AssignmentStrategy strategie = parType.get(type);
        if (strategie == null) {
            throw new IllegalStateException("Aucune strategie pour le type " + type);
        }
        return strategie;
    }
}
```

**Attention :** le registre exige une implémentation pour `GEOGRAPHIC` et `SECTOR`, qui
n'existent qu'en T3. Tant que T3 n'est pas faite, **tout `@SpringBootTest` échouera au
démarrage**. C'est voulu — le registre fait précisément ce pour quoi il est écrit — mais
cela implique d'enchaîner T2 et T3 sans lancer la suite complète entre les deux.

- [ ] **Step 7: Lancer les tests et vérifier qu'ils passent**

Run: `./mvnw test -Dtest='RoundRobinStrategyTest,AssignmentStrategyRegistryTest'`
Expected: 6 tests, 0 échec.

- [ ] **Step 8: Commiter**

```bash
git add backend/src/main/java/com/leadflow/routing/AssignmentStrategy.java \
        backend/src/main/java/com/leadflow/routing/RoundRobinStrategy.java \
        backend/src/main/java/com/leadflow/routing/AssignmentStrategyRegistry.java \
        backend/src/test/java/com/leadflow/routing/RoundRobinStrategyTest.java \
        backend/src/test/java/com/leadflow/routing/AssignmentStrategyRegistryTest.java
git commit -m "feat: port de strategie d'attribution, round-robin et registre"
```

---

## Task 3: Stratégies géographique et sectorielle

**Files:**
- Create: `backend/src/main/java/com/leadflow/routing/GeographicStrategy.java`
- Create: `backend/src/main/java/com/leadflow/routing/SectorStrategy.java`
- Test: `backend/src/test/java/com/leadflow/routing/GeographicStrategyTest.java`
- Test: `backend/src/test/java/com/leadflow/routing/SectorStrategyTest.java`

**Interfaces:**
- Consomme : `AssignmentStrategy` et `RoundRobinStrategy` (T2).
- Produit : deux `@Component` de plus pour le registre. Rien de nouveau à appeler.

**Sans Spring ni base.**

- [ ] **Step 1: Écrire le test de la stratégie géographique**

Créer `backend/src/test/java/com/leadflow/routing/GeographicStrategyTest.java` :

```java
package com.leadflow.routing;

import static com.leadflow.routing.RoundRobinStrategyTest.commercial;
import static com.leadflow.routing.RoundRobinStrategyTest.lead;
import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeographicStrategyTest {

    private final GeographicStrategy strategie = new GeographicStrategy(new RoundRobinStrategy());

    @Test
    void seDeclareSousLeTypeGeographique() {
        assertThat(strategie.type()).isEqualTo(AssignmentStrategyType.GEOGRAPHIC);
    }

    @Test
    void retientLeCommercialDeLaZoneDuLead() {
        SalesRep maroc = commercial("ma@demo.test", null, "MA");
        SalesRep france = commercial("fr@demo.test", null, "FR");

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(france, maroc)))
                .contains(maroc);
    }

    /** La zone est saisie a la main : « ma » et « MA » designent le meme territoire. */
    @Test
    void ignoreLaCasseEtLesEspacesDeLaZone() {
        SalesRep maroc = commercial("ma@demo.test", null, "  ma ");

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(maroc))).contains(maroc);
    }

    /**
     * Parmi plusieurs commerciaux de la meme zone, c'est l'ordre de rotation qui tranche :
     * la liste arrive triee, on prend la tete.
     */
    @Test
    void departageDeuxCommerciauxDeLaMemeZoneParLaRotation() {
        SalesRep premier = commercial("premier@demo.test", null, "MA");
        SalesRep second = commercial("second@demo.test", null, "MA");

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(premier, second)))
                .contains(premier);
    }

    /**
     * Repli : un prospect qui attend coute plus cher qu'une attribution imparfaite. Le
     * service logue le repli pour que la configuration incomplete du client se voie.
     */
    @Test
    void repliSurLaRotationQuandAucuneZoneNeCorrespond() {
        SalesRep france = commercial("fr@demo.test", null, "FR");
        SalesRep espagne = commercial("es@demo.test", null, "ES");

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(france, espagne)))
                .contains(france);
    }

    @Test
    void repliSurLaRotationQuandLeLeadNAPasDePays() {
        SalesRep france = commercial("fr@demo.test", null, "FR");

        assertThat(strategie.choisit(lead(null, "industrie"), List.of(france))).contains(france);
    }

    @Test
    void neChoisitPersonneQuandLaListeEstVide() {
        assertThat(strategie.choisit(lead("MA", "industrie"), List.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Écrire le test de la stratégie sectorielle**

Créer `backend/src/test/java/com/leadflow/routing/SectorStrategyTest.java` :

```java
package com.leadflow.routing;

import static com.leadflow.routing.RoundRobinStrategyTest.commercial;
import static com.leadflow.routing.RoundRobinStrategyTest.lead;
import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import org.junit.jupiter.api.Test;

class SectorStrategyTest {

    private final SectorStrategy strategie = new SectorStrategy(new RoundRobinStrategy());

    @Test
    void seDeclareSousLeTypeSectoriel() {
        assertThat(strategie.type()).isEqualTo(AssignmentStrategyType.SECTOR);
    }

    @Test
    void retientLeCommercialDuSecteurDuLead() {
        SalesRep industrie = commercial("indus@demo.test", "industrie", null);
        SalesRep services = commercial("services@demo.test", "services", null);

        assertThat(strategie.choisit(lead("MA", "Industrie"), List.of(services, industrie)))
                .contains(industrie);
    }

    /**
     * Correspondance exacte, jamais partielle : « industrie » ne reconnait pas « industrie
     * du textile ». Un client qui veut les deux enumere deux commerciaux — une comparaison
     * par prefixe ferait de « industrie » un piege silencieux.
     */
    @Test
    void neReconnaitPasUnSecteurSeulementPrefixe() {
        SalesRep industrie = commercial("indus@demo.test", "industrie", null);
        SalesRep autre = commercial("autre@demo.test", "services", null);

        // Aucun secteur ne correspond exactement : repli sur la rotation, donc le premier.
        assertThat(strategie.choisit(lead("MA", "industrie du textile"), List.of(autre, industrie)))
                .contains(autre);
    }

    @Test
    void repliSurLaRotationQuandAucunSecteurNeCorrespond() {
        SalesRep services = commercial("services@demo.test", "services", null);

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(services)))
                .contains(services);
    }

    @Test
    void repliSurLaRotationQuandLeLeadNAPasDeSecteur() {
        SalesRep services = commercial("services@demo.test", "services", null);

        assertThat(strategie.choisit(lead("MA", null), List.of(services))).contains(services);
    }

    @Test
    void neChoisitPersonneQuandLaListeEstVide() {
        assertThat(strategie.choisit(lead("MA", "industrie"), List.of())).isEmpty();
    }
}
```

- [ ] **Step 3: Lancer les tests et vérifier qu'ils échouent**

Run: `./mvnw test -Dtest='GeographicStrategyTest,SectorStrategyTest'`
Expected: échec de compilation — `GeographicStrategy` et `SectorStrategy` n'existent pas.

- [ ] **Step 4: Écrire la stratégie géographique**

Créer `backend/src/main/java/com/leadflow/routing/GeographicStrategy.java` :

```java
package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Attribution par territoire : {@code sales_rep.zone} contre {@code lead.country_code}.
 *
 * <p><b>Ce que compare vraiment cette strategie.</b> {@code zone} est un texte libre saisi
 * par l'agence, {@code country_code} un code ISO a deux lettres produit par la
 * normalisation de F3. La correspondance n'a donc lieu que si l'agence remplit {@code zone}
 * avec des codes pays : en pratique, c'est une strategie « par code pays ». Le repli couvre
 * le cas contraire, et renommer la colonne demanderait une migration.
 *
 * <p>Le departage entre plusieurs commerciaux du meme territoire est delegue au round-robin
 * — sans quoi le premier de la liste prendrait tout.
 */
@Component
public class GeographicStrategy implements AssignmentStrategy {

    private final RoundRobinStrategy rotation;

    public GeographicStrategy(RoundRobinStrategy rotation) {
        this.rotation = rotation;
    }

    @Override
    public AssignmentStrategyType type() {
        return AssignmentStrategyType.GEOGRAPHIC;
    }

    @Override
    public Optional<SalesRep> choisit(Lead lead, List<SalesRep> eligibles) {
        List<SalesRep> duTerritoire = eligibles.stream()
                .filter(commercial -> correspond(commercial.getZone(), lead.getCountryCode()))
                .toList();
        // Filtre vide : on rend la main au tour de role plutot que de laisser le lead en
        // souffrance. Le service logue ce repli.
        return rotation.choisit(lead, duTerritoire.isEmpty() ? eligibles : duTerritoire);
    }

    /** @return {@code false} des qu'une des deux valeurs manque : rien ne peut correspondre */
    static boolean correspond(String valeur, String attendue) {
        if (valeur == null || attendue == null) {
            return false;
        }
        return valeur.trim().toLowerCase(Locale.ROOT)
                .equals(attendue.trim().toLowerCase(Locale.ROOT));
    }
}
```

- [ ] **Step 5: Écrire la stratégie sectorielle**

Créer `backend/src/main/java/com/leadflow/routing/SectorStrategy.java` :

```java
package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Attribution par secteur d'activite : {@code sales_rep.sector} contre {@code lead.sector}.
 *
 * <p>La correspondance est <b>exacte</b>, casse et espaces mis a part : « industrie » ne
 * reconnait pas « industrie du textile ». Une comparaison par prefixe ferait d'un secteur
 * court un piege silencieux qui capterait tout ce qui commence pareil. Meme regle que le
 * ciblage sectoriel du bareme de scoring de F3.
 *
 * <p>Le departage entre plusieurs commerciaux du meme secteur est delegue au round-robin.
 */
@Component
public class SectorStrategy implements AssignmentStrategy {

    private final RoundRobinStrategy rotation;

    public SectorStrategy(RoundRobinStrategy rotation) {
        this.rotation = rotation;
    }

    @Override
    public AssignmentStrategyType type() {
        return AssignmentStrategyType.SECTOR;
    }

    @Override
    public Optional<SalesRep> choisit(Lead lead, List<SalesRep> eligibles) {
        List<SalesRep> duSecteur = eligibles.stream()
                .filter(commercial ->
                        GeographicStrategy.correspond(commercial.getSector(), lead.getSector()))
                .toList();
        return rotation.choisit(lead, duSecteur.isEmpty() ? eligibles : duSecteur);
    }
}
```

- [ ] **Step 6: Lancer les tests et vérifier qu'ils passent**

Run: `./mvnw test -Dtest='GeographicStrategyTest,SectorStrategyTest,AssignmentStrategyRegistryTest,RoundRobinStrategyTest'`
Expected: 19 tests, 0 échec.

- [ ] **Step 7: Lancer la suite complète**

Run: `./mvnw test`
Expected: toute la suite verte — le registre trouve maintenant ses trois titulaires, donc
les contextes Spring redémarrent.

- [ ] **Step 8: Commiter**

```bash
git add backend/src/main/java/com/leadflow/routing/GeographicStrategy.java \
        backend/src/main/java/com/leadflow/routing/SectorStrategy.java \
        backend/src/test/java/com/leadflow/routing/GeographicStrategyTest.java \
        backend/src/test/java/com/leadflow/routing/SectorStrategyTest.java
git commit -m "feat: strategies geographique et sectorielle avec repli sur la rotation"
```

---

## Task 4: Écriture isolée et orchestration de l'attribution

**Files:**
- Create: `backend/src/main/java/com/leadflow/routing/AssignmentException.java`
- Create: `backend/src/main/java/com/leadflow/routing/RoutedLeadWriter.java`
- Create: `backend/src/main/java/com/leadflow/routing/LeadRoutingService.java`
- Test: `backend/src/test/java/com/leadflow/routing/LeadRoutingServiceTest.java`

**Interfaces:**
- Consomme : `RotationOrder` (T1), `AssignmentStrategyRegistry` (T2), `LeadRepository`, `ClientRepository`, `SalesRepRepository`.
- Produit : `LeadRoutingService.route(UUID leadId) -> Optional<Lead>`, consommé par T6. `AssignmentException`, `RoutedLeadWriter.attribue(UUID leadId, UUID salesRepId) -> Lead`.

**Testcontainers requis.** La publication n'existe pas encore : elle est ajoutée en T5.

- [ ] **Step 1: Écrire le test d'intégration**

Créer `backend/src/test/java/com/leadflow/routing/LeadRoutingServiceTest.java` :

```java
package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadRoutingServiceTest {

    @Autowired private LeadRoutingService service;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;

    private Client client;

    @BeforeEach
    void preparation() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
        client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost", "apiKey", "x"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client = clientRepository.saveAndFlush(client);
    }

    /** La base est partagee : ne rien laisser derriere soi. */
    @AfterEach
    void nettoyage() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
    }

    private SalesRep commercial(String email, boolean actif) {
        SalesRep commercial = new SalesRep();
        commercial.setClient(client);
        commercial.setFullName("Commercial " + email);
        commercial.setEmail(email);
        commercial.setActive(actif);
        return salesRepRepository.saveAndFlush(commercial);
    }

    private UUID leadQualifie() {
        RawLeadEvent brut = new RawLeadEvent();
        brut.setClientId(client.getId());
        brut.setSource("formulaire-devis");
        brut.setPayload(Map.of("email", "prospect@acme.test"));
        brut.setSignature("t=1,v1=" + UUID.randomUUID());
        brut.setStatus(RawLeadEventStatus.PUBLISHED);
        UUID eventId = rawLeadEventRepository.saveAndFlush(brut).getId();

        Lead lead = new Lead();
        lead.setClientId(client.getId());
        lead.setRawEventId(eventId);
        lead.setEmail("prospect+" + UUID.randomUUID() + "@acme.test");
        lead.setScore(60);
        lead.setStatus(LeadStatus.QUALIFIED);
        return leadRepository.saveAndFlush(lead).getId();
    }

    @Test
    void attribueLeLeadEtLeMarqueRoute() {
        SalesRep amina = commercial("amina@demo.test", true);

        Lead route = service.route(leadQualifie()).orElseThrow();

        assertThat(route.getAssignedSalesRepId()).isEqualTo(amina.getId());
        assertThat(route.getStatus()).isEqualTo(LeadStatus.ROUTED);
    }

    @Test
    void repartitTroisLeadsSurDeuxCommerciauxSansEnOublierUn() {
        commercial("amina@demo.test", true);
        commercial("karim@demo.test", true);

        UUID premier = service.route(leadQualifie()).orElseThrow().getAssignedSalesRepId();
        UUID second = service.route(leadQualifie()).orElseThrow().getAssignedSalesRepId();
        UUID troisieme = service.route(leadQualifie()).orElseThrow().getAssignedSalesRepId();

        assertThat(premier).isNotEqualTo(second);
        assertThat(troisieme).isEqualTo(premier);
    }

    @Test
    void neRetientJamaisUnCommercialInactif() {
        commercial("inactif@demo.test", false);
        SalesRep actif = commercial("actif@demo.test", true);

        Lead route = service.route(leadQualifie()).orElseThrow();

        assertThat(route.getAssignedSalesRepId()).isEqualTo(actif.getId());
    }

    /** Reparable par un humain — activer un commercial — donc le rejeu depuis la DLQ a un sens. */
    @Test
    void leveQuandAucunCommercialNEstActif() {
        commercial("inactif@demo.test", false);
        UUID leadId = leadQualifie();

        assertThatThrownBy(() -> service.route(leadId))
                .isInstanceOf(AssignmentException.class)
                .hasMessageContaining(client.getId().toString());
    }

    /** La livraison est at-least-once : un rejeu ne doit pas decaler la rotation. */
    @Test
    void neReattribuePasUnLeadDejaRoute() {
        commercial("amina@demo.test", true);
        commercial("karim@demo.test", true);
        UUID leadId = leadQualifie();
        UUID premier = service.route(leadId).orElseThrow().getAssignedSalesRepId();

        UUID second = service.route(leadId).orElseThrow().getAssignedSalesRepId();

        assertThat(second).isEqualTo(premier);
    }

    @Test
    void acquitteUnLeadIntrouvableSansLever() {
        assertThat(service.route(UUID.randomUUID())).isEmpty();
    }
}
```

- [ ] **Step 2: Lancer le test et vérifier qu'il échoue**

Run: `./mvnw test -Dtest=LeadRoutingServiceTest`
Expected: échec de compilation — `LeadRoutingService` et `AssignmentException` n'existent pas.

- [ ] **Step 3: Écrire l'exception**

Créer `backend/src/main/java/com/leadflow/routing/AssignmentException.java` :

```java
package com.leadflow.routing;

import java.util.UUID;

/**
 * Aucun commercial ne peut recevoir ce lead.
 *
 * <p>Le seul echec du routage qui merite la DLQ, et il la merite parce qu'un humain peut le
 * reparer : activer un commercial, puis rejouer le message. Cela le distingue de l'evenement
 * sans email exploitable de F3, qui echouerait eternellement a l'identique et recoit pour
 * cela un statut terminal.
 */
public class AssignmentException extends RuntimeException {

    public AssignmentException(UUID clientId, UUID leadId) {
        super("Aucun commercial actif pour le client " + clientId
                + " : le lead " + leadId + " ne peut pas etre attribue");
    }
}
```

- [ ] **Step 4: Écrire l'écrivain transactionnel**

Créer `backend/src/main/java/com/leadflow/routing/RoutedLeadWriter.java` :

```java
package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecrit l'attribution dans sa <b>propre</b> transaction.
 *
 * <p>Meme raison qu'en F3 pour {@code LeadWriter} : quand cette methode rend la main, la
 * ligne est <b>commitee</b>. C'est ce qui autorise l'orchestrateur a publier vers le broker
 * par un appel direct — un message parti plus tot designerait un lead que le consommateur
 * suivant lirait encore {@code QUALIFIED}, sans commercial.
 *
 * <p>Bean distinct et non methode privee : Spring ne proxie pas l'auto-invocation, la
 * propagation serait silencieusement ignoree.
 */
@Component
public class RoutedLeadWriter {

    private final LeadRepository leadRepository;

    public RoutedLeadWriter(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lead attribue(UUID leadId, UUID salesRepId) {
        Lead lead = leadRepository.findById(leadId).orElseThrow(
                () -> new IllegalStateException("Lead disparu en cours d'attribution : " + leadId));
        lead.setAssignedSalesRepId(salesRepId);
        lead.setStatus(LeadStatus.ROUTED);
        return leadRepository.saveAndFlush(lead);
    }
}
```

- [ ] **Step 5: Écrire l'orchestrateur**

Créer `backend/src/main/java/com/leadflow/routing/LeadRoutingService.java` :

```java
package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Choisit le commercial destinataire d'un lead qualifie.
 *
 * <p><b>Volontairement non transactionnel</b>, comme {@code LeadQualificationService} :
 * l'ecriture a sa propre transaction, portee par {@link RoutedLeadWriter}, et la suite du
 * traitement part apres son commit.
 *
 * <p>C'est ici, et pas dans les strategies, que vivent les trois decisions qui ne sont pas
 * du calcul : ne pas reattribuer un lead qui porte deja un commercial, lever quand aucun
 * commercial n'est actif, et journaliser le repli d'une strategie qui n'a trouve personne
 * sur son critere.
 */
@Service
public class LeadRoutingService {

    private static final Logger log = LoggerFactory.getLogger(LeadRoutingService.class);

    private final LeadRepository leadRepository;
    private final ClientRepository clientRepository;
    private final SalesRepRepository salesRepRepository;
    private final RotationOrder rotation;
    private final AssignmentStrategyRegistry registre;
    private final RoutedLeadWriter writer;

    public LeadRoutingService(
            LeadRepository leadRepository,
            ClientRepository clientRepository,
            SalesRepRepository salesRepRepository,
            RotationOrder rotation,
            AssignmentStrategyRegistry registre,
            RoutedLeadWriter writer) {
        this.leadRepository = leadRepository;
        this.clientRepository = clientRepository;
        this.salesRepRepository = salesRepRepository;
        this.rotation = rotation;
        this.registre = registre;
        this.writer = writer;
    }

    /**
     * @return le lead attribue ; vide quand il n'y a rien a router — lead introuvable. Le
     *     message est alors acquitte : cet echec est deterministe et n'a rien a faire en DLQ.
     * @throws AssignmentException si le client n'a aucun commercial actif
     */
    public Optional<Lead> route(UUID leadId) {
        Lead lead = leadRepository.findById(leadId).orElse(null);
        if (lead == null) {
            log.warn("Lead {} introuvable : rien a router", leadId);
            return Optional.empty();
        }
        if (lead.getAssignedSalesRepId() != null) {
            // Rejeu : ne pas reattribuer, sous peine de decaler la rotation.
            return Optional.of(lead);
        }

        Client client = clientRepository.findById(lead.getClientId())
                .orElseThrow(() -> new IllegalStateException(
                        "Le lead " + leadId + " reference un client inexistant"));

        List<SalesRep> actifs = salesRepRepository.findByClientIdAndActiveTrue(lead.getClientId());
        if (actifs.isEmpty()) {
            throw new AssignmentException(lead.getClientId(), leadId);
        }

        List<SalesRep> ordonnes = rotation.parAnciennete(lead.getClientId(), actifs);
        SalesRep choisi = registre.pour(client.getAssignmentStrategy())
                .choisit(lead, ordonnes)
                .orElseThrow(() -> new AssignmentException(lead.getClientId(), leadId));
        journaliseLeRepli(client, lead, choisi);

        return Optional.of(writer.attribue(leadId, choisi.getId()));
    }

    /**
     * Le repli est silencieux pour le prospect, il ne doit pas l'etre pour l'agence : sans
     * cette trace, un client dont aucun commercial ne porte de zone verrait toutes ses
     * attributions passer par le tour de role sans que rien ne signale la configuration
     * incomplete.
     */
    private void journaliseLeRepli(Client client, Lead lead, SalesRep choisi) {
        boolean repli = switch (client.getAssignmentStrategy()) {
            case GEOGRAPHIC -> !GeographicStrategy.correspond(choisi.getZone(), lead.getCountryCode());
            case SECTOR -> !GeographicStrategy.correspond(choisi.getSector(), lead.getSector());
            case ROUND_ROBIN -> false;
        };
        if (repli) {
            log.info("Strategie {} sans correspondance pour le lead {} : repli sur le tour de role",
                    client.getAssignmentStrategy(), lead.getId());
        }
    }
}
```

- [ ] **Step 6: Lancer le test et vérifier qu'il passe**

Run: `./mvnw test -Dtest=LeadRoutingServiceTest`
Expected: 6 tests, 0 échec.

- [ ] **Step 7: Commiter**

```bash
git add backend/src/main/java/com/leadflow/routing/AssignmentException.java \
        backend/src/main/java/com/leadflow/routing/RoutedLeadWriter.java \
        backend/src/main/java/com/leadflow/routing/LeadRoutingService.java \
        backend/src/test/java/com/leadflow/routing/LeadRoutingServiceTest.java
git commit -m "feat: orchestration de l'attribution et ecriture du lead route"
```

---

## Task 5: Publication sur `leadflow.leads.routed`

**Files:**
- Create: `backend/src/main/java/com/leadflow/routing/RoutedLeadMessage.java`
- Create: `backend/src/main/java/com/leadflow/routing/RoutedLeadPublisher.java`
- Modify: `backend/src/main/java/com/leadflow/config/RabbitMQConfig.java`
- Modify: `backend/src/main/java/com/leadflow/routing/LeadRoutingService.java`
- Test: `backend/src/test/java/com/leadflow/routing/LeadRoutingServiceTest.java` (étendu)

**Interfaces:**
- Consomme : `Lead`, `RabbitMQConfig` (F2), `LeadRoutingService` (T4).
- Produit : `RabbitMQConfig.ROUTED_QUEUE`, `RabbitMQConfig.ROUTED_ROUTING_KEY`,
  `RoutedLeadMessage(UUID leadId, UUID clientId, UUID salesRepId, Instant routedAt)`,
  `RoutedLeadPublisher.publie(Lead)`. Consommés par T7.

**Testcontainers requis.**

- [ ] **Step 1: Étendre le test d'intégration**

Dans `LeadRoutingServiceTest`, ajouter les imports :

```java
import com.leadflow.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
```

le champ :

```java
    @Autowired private RabbitTemplate rabbitTemplate;
```

la purge en première ligne de `preparation()` :

```java
        while (rabbitTemplate.receive(RabbitMQConfig.ROUTED_QUEUE) != null) {
            // vide la file avant chaque test : sans quoi un test lit la publication du precedent.
        }
```

puis les deux tests :

```java
    @Test
    void publieLeLeadRouteSurLaFileDeSortie() {
        SalesRep amina = commercial("amina@demo.test", true);

        Lead route = service.route(leadQualifie()).orElseThrow();

        Object recu = rabbitTemplate.receiveAndConvert(RabbitMQConfig.ROUTED_QUEUE, 5000);
        assertThat(recu).isInstanceOf(RoutedLeadMessage.class);
        RoutedLeadMessage message = (RoutedLeadMessage) recu;
        assertThat(message.leadId()).isEqualTo(route.getId());
        assertThat(message.clientId()).isEqualTo(client.getId());
        assertThat(message.salesRepId()).isEqualTo(amina.getId());
        assertThat(message.routedAt()).isNotNull();
    }

    /**
     * Republier un lead deja attribue est sans danger — CrmSyncService rejoue via
     * CrmSyncState — alors que ne pas republier laisserait un lead ROUTED que plus rien ne
     * synchroniserait.
     */
    @Test
    void republieUnLeadDejaRouteSansLeReattribuer() {
        commercial("amina@demo.test", true);
        UUID leadId = leadQualifie();
        service.route(leadId);
        rabbitTemplate.receiveAndConvert(RabbitMQConfig.ROUTED_QUEUE, 5000);

        service.route(leadId);

        assertThat(rabbitTemplate.receiveAndConvert(RabbitMQConfig.ROUTED_QUEUE, 5000))
                .isInstanceOf(RoutedLeadMessage.class);
    }
```

- [ ] **Step 2: Lancer le test et vérifier qu'il échoue**

Run: `./mvnw test -Dtest=LeadRoutingServiceTest`
Expected: échec de compilation — `RoutedLeadMessage` et `RabbitMQConfig.ROUTED_QUEUE` n'existent pas.

- [ ] **Step 3: Écrire le contrat de file**

Créer `backend/src/main/java/com/leadflow/routing/RoutedLeadMessage.java` :

```java
package com.leadflow.routing;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de file publie sur {@code lead.routed}. C'est la frontiere publique du routage.
 *
 * <p>Une reference, pas un contenu — meme raison qu'en F2 et F3. La base reste l'unique
 * source de verite et le consommateur relit la ligne {@code lead} : un rejeu depuis la DLQ
 * travaille donc forcement sur la donnee a jour.
 *
 * <p>{@code salesRepId} voyage parce que c'est la decision que cette etape vient de prendre :
 * un consommateur qui recoit le message sait ce qui a ete decide sans relire la ligne.
 *
 * <p><b>Le consommateur doit etre idempotent sur {@code leadId}.</b> Un lead deja attribue
 * est republie sans etre reattribue : c'est voulu, et {@code CrmSyncService} absorbe ce
 * rejeu grace a {@code CrmSyncState}.
 */
public record RoutedLeadMessage(
        UUID leadId,
        UUID clientId,
        UUID salesRepId,
        Instant routedAt) {
}
```

- [ ] **Step 4: Déclarer la file dans la topologie**

Dans `backend/src/main/java/com/leadflow/config/RabbitMQConfig.java`, après les constantes
`QUALIFIED_*` :

```java
    /** Sortie du routage, consommee par la synchronisation ERP. */
    public static final String ROUTED_QUEUE = "leadflow.leads.routed";
    public static final String ROUTED_ROUTING_KEY = "lead.routed";
```

après le bean `qualifiedLeadsQueue()` :

```java
    @Bean
    Queue routedLeadsQueue() {
        return QueueBuilder.durable(ROUTED_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }
```

après le bean `qualifiedLeadsBinding(...)` :

```java
    @Bean
    Binding routedLeadsBinding(Queue routedLeadsQueue, DirectExchange leadsExchange) {
        return BindingBuilder.bind(routedLeadsQueue)
                .to(leadsExchange)
                .with(ROUTED_ROUTING_KEY);
    }
```

et enfin la liste blanche — **sans quoi le consommateur de T7 refusera de désérialiser** :

```java
    private static final String[] PAQUETS_DE_CONFIANCE =
            {"com.leadflow.capture", "com.leadflow.qualification", "com.leadflow.routing"};
```

- [ ] **Step 5: Écrire le publieur**

Créer `backend/src/main/java/com/leadflow/routing/RoutedLeadPublisher.java` :

```java
package com.leadflow.routing;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.Lead;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publie le lead attribue vers la synchronisation ERP.
 *
 * <p>Appel direct apres le retour de {@link RoutedLeadWriter#attribue}, donc apres le
 * commit — et non {@code @TransactionalEventListener(AFTER_COMMIT)} comme en F2 :
 * l'orchestrateur n'etant pas transactionnel, un tel listener serait <b>silencieusement
 * ignore</b> et le message ne partirait jamais.
 *
 * <p><b>Dette assumee : aucun filet de republication.</b> Si l'envoi echoue, le lead reste
 * {@code ROUTED} — rien n'est perdu, mais rien ne le republie. Le balayage symetrique
 * ({@code findByStatusAndCreatedAtBefore(ROUTED, seuil)}) est desormais bornable puisque le
 * lead quitte cet etat pour {@code SYNCED} ; il est reporte a F6, ou il rejoint le rejeu
 * manuel depuis la DLQ.
 */
@Component
public class RoutedLeadPublisher {

    private static final Logger log = LoggerFactory.getLogger(RoutedLeadPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public RoutedLeadPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publie(Lead lead) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.LEADS_EXCHANGE,
                    RabbitMQConfig.ROUTED_ROUTING_KEY,
                    new RoutedLeadMessage(
                            lead.getId(),
                            lead.getClientId(),
                            lead.getAssignedSalesRepId(),
                            Instant.now()));
        } catch (AmqpException echec) {
            // Ne jamais relancer : le lead est attribue, et faire echouer le consommateur
            // renverrait en DLQ un message deja traite avec succes.
            log.warn("Publication du lead route {} en echec", lead.getId(), echec);
        }
    }
}
```

- [ ] **Step 6: Brancher le publieur dans l'orchestrateur**

Dans `LeadRoutingService`, ajouter le champ :

```java
    private final RoutedLeadPublisher publisher;
```

l'ajouter en dernier paramètre du constructeur et l'affecter :

```java
            RoutedLeadWriter writer,
            RoutedLeadPublisher publisher) {
        ...
        this.writer = writer;
        this.publisher = publisher;
    }
```

puis publier sur les **deux** chemins de sortie. Remplacer le retour anticipé du rejeu :

```java
        if (lead.getAssignedSalesRepId() != null) {
            // Rejeu : ne pas reattribuer, sous peine de decaler la rotation. Republier, en
            // revanche, est necessaire — un message perdu entre les deux etapes laisserait
            // un lead ROUTED que plus rien ne synchroniserait.
            publisher.publie(lead);
            return Optional.of(lead);
        }
```

et le retour final :

```java
        Lead route = writer.attribue(leadId, choisi.getId());
        publisher.publie(route);
        return Optional.of(route);
```

Ajouter enfin cette phrase au Javadoc de la classe, après le paragraphe sur la
transactionnalité :

```java
 * <p>La publication part apres le retour de {@link RoutedLeadWriter#attribue}, donc apres le
 * commit : un message parti plus tot designerait un lead que l'etape suivante lirait encore
 * sans commercial.
```

- [ ] **Step 7: Lancer le test et vérifier qu'il passe**

Run: `./mvnw test -Dtest=LeadRoutingServiceTest`
Expected: 8 tests, 0 échec.

- [ ] **Step 8: Commiter**

```bash
git add backend/src/main/java/com/leadflow/routing/RoutedLeadMessage.java \
        backend/src/main/java/com/leadflow/routing/RoutedLeadPublisher.java \
        backend/src/main/java/com/leadflow/routing/LeadRoutingService.java \
        backend/src/main/java/com/leadflow/config/RabbitMQConfig.java \
        backend/src/test/java/com/leadflow/routing/LeadRoutingServiceTest.java
git commit -m "feat: publication du lead attribue sur leadflow.leads.routed"
```

---

## Task 6: Consommation de `leadflow.leads.qualified`

**Files:**
- Create: `backend/src/main/java/com/leadflow/routing/LeadRoutingListener.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application.properties`
- Test: `backend/src/test/java/com/leadflow/routing/LeadRoutingListenerTest.java`

**Interfaces:**
- Consomme : `QualifiedLeadMessage` (F3), `LeadRoutingService` (T4), `RabbitMQConfig.QUALIFIED_QUEUE`.
- Produit : le câblage AMQP de l'étape. Rien que T7 appelle directement.

**Testcontainers requis.**

- [ ] **Step 1: Écrire le listener**

Créer `backend/src/main/java/com/leadflow/routing/LeadRoutingListener.java` :

```java
package com.leadflow.routing;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.QualifiedLeadMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Traduit le protocole AMQP, et rien d'autre.
 *
 * <p>Classe distincte de {@link LeadRoutingService} a dessein : le metier ne connait pas
 * RabbitMQ, ce qui permet de tester tout le routage sans broker.
 *
 * <p>Le bean est conditionnel plutot que simplement arrete par
 * {@code spring.rabbitmq.listener.simple.auto-startup} : le cache de contextes de test met
 * un contexte en pause puis le redemarre, et {@code ApplicationContext.start()} demarre tous
 * les beans {@code Lifecycle} sans regarder {@code autoStartup}. Un consommateur ainsi
 * ressuscite volerait aux tests de la qualification le message qu'ils viennent de publier.
 *
 * <p>Aucune exception n'est rattrapee ici. {@code AssignmentException} doit provoquer les
 * trois tentatives puis la DLQ : un humain peut activer un commercial et rejouer. Les echecs
 * deterministes, eux, sont absorbes par le service qui rend un {@code Optional} vide.
 */
@Component
@ConditionalOnProperty(name = "leadflow.routing.listener.enabled", matchIfMissing = true)
public class LeadRoutingListener {

    private final LeadRoutingService service;

    public LeadRoutingListener(LeadRoutingService service) {
        this.service = service;
    }

    @RabbitListener(queues = RabbitMQConfig.QUALIFIED_QUEUE)
    public void recoit(QualifiedLeadMessage message) {
        service.route(message.leadId());
    }
}
```

- [ ] **Step 2: Déclarer le commutateur**

Dans `backend/src/main/resources/application.yml`, ajouter sous `leadflow:`, après le bloc
`qualification:` :

```yaml
  routing:
    listener:
      # Presence du consommateur de leadflow.leads.qualified. Toujours vrai en production ;
      # la suite de tests le retire pour lire elle-meme ce que la qualification a publie.
      enabled: true
```

Dans `backend/src/test/resources/application.properties`, ajouter :

```properties
# Meme raison que pour la qualification : un consommateur actif volerait aux tests des
# etapes amont les messages qu'ils viennent de publier. C'est le bean qui est retire, et
# non son demarrage qui est desactive.
leadflow.routing.listener.enabled=false
```

- [ ] **Step 3: Écrire le test de câblage**

Créer `backend/src/test/java/com/leadflow/routing/LeadRoutingListenerTest.java` :

```java
package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.qualification.QualifiedLeadMessage;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * La suite retire le consommateur du routage — sans quoi il volerait aux tests de la
 * qualification les messages qu'ils viennent de publier — donc cette classe le rallume par
 * propriete, dans son propre contexte. Le consommateur de synchronisation reste eteint : ce
 * qui est verifie ici est le cablage du routage, pas la chaine complete.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.routing.listener.enabled=true",
        "leadflow.crm.listener.enabled=false"})
class LeadRoutingListenerTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;

    @AfterEach
    void nettoyage() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
    }

    @Test
    void routeUnLeadRecuParLaFile() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost", "apiKey", "x"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        Client enregistre = clientRepository.saveAndFlush(client);

        SalesRep commercial = new SalesRep();
        commercial.setClient(enregistre);
        commercial.setFullName("Amina Bensalem");
        commercial.setEmail("amina@demo.test");
        SalesRep amina = salesRepRepository.saveAndFlush(commercial);

        RawLeadEvent brut = new RawLeadEvent();
        brut.setClientId(enregistre.getId());
        brut.setSource("formulaire-devis");
        brut.setPayload(Map.of("email", "prospect@acme.test"));
        brut.setSignature("t=1,v1=" + UUID.randomUUID());
        brut.setStatus(RawLeadEventStatus.PUBLISHED);
        UUID eventId = rawLeadEventRepository.saveAndFlush(brut).getId();

        Lead lead = new Lead();
        lead.setClientId(enregistre.getId());
        lead.setRawEventId(eventId);
        lead.setEmail("prospect@acme.test");
        lead.setScore(80);
        lead.setStatus(LeadStatus.QUALIFIED);
        UUID leadId = leadRepository.saveAndFlush(lead).getId();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.QUALIFIED_ROUTING_KEY,
                new QualifiedLeadMessage(leadId, enregistre.getId(), 80, Instant.now()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Lead route = leadRepository.findById(leadId).orElseThrow();
            assertThat(route.getStatus()).isEqualTo(LeadStatus.ROUTED);
            assertThat(route.getAssignedSalesRepId()).isEqualTo(amina.getId());
        });
    }
}
```

- [ ] **Step 4: Lancer le test et vérifier qu'il passe**

Run: `./mvnw test -Dtest=LeadRoutingListenerTest`
Expected: 1 test, 0 échec.

**Note :** `leadflow.crm.listener.enabled` n'a pas encore de titulaire — la propriété est
simplement inconnue, ce qui est sans effet. T7 crée le bean qu'elle commande.

- [ ] **Step 5: Lancer la suite complète**

Run: `./mvnw test`
Expected: toute la suite verte.

- [ ] **Step 6: Commiter**

```bash
git add backend/src/main/java/com/leadflow/routing/LeadRoutingListener.java \
        backend/src/main/resources/application.yml \
        backend/src/test/resources/application.properties \
        backend/src/test/java/com/leadflow/routing/LeadRoutingListenerTest.java
git commit -m "feat: consommation de leadflow.leads.qualified par le routage"
```

---

## Task 7: Synchronisation ERP déclenchée par la file

**Files:**
- Create: `backend/src/main/java/com/leadflow/crm/SyncedLeadWriter.java`
- Create: `backend/src/main/java/com/leadflow/crm/CrmSyncListener.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application.properties`
- Test: `backend/src/test/java/com/leadflow/crm/CrmSyncListenerTest.java`

**Interfaces:**
- Consomme : `RoutedLeadMessage` (T5), `CrmSyncService.synchronise(UUID)` (F5), `RabbitMQConfig.ROUTED_QUEUE`.
- Produit : la fin de la chaîne. `SyncedLeadWriter.marqueSynchronise(UUID leadId)`.

**Testcontainers requis.**

- [ ] **Step 1: Écrire le test de bout en bout**

Créer `backend/src/test/java/com/leadflow/crm/CrmSyncListenerTest.java` :

```java
package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.qualification.QualifiedLeadMessage;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Le pipeline complet, listeners rallumes : un message de qualification entre, un lead
 * SYNCED et sa trace sortent. Le connecteur est un espion — aucun test de F4 ne doit
 * dependre d'un vrai Dolibarr.
 */
@SpringBootTest(properties = "leadflow.crm.providers.espion.enabled=true")
@Import({TestcontainersConfiguration.class, CrmSyncListenerTest.ConnecteurDeTest.class})
@TestPropertySource(properties = {
        "leadflow.routing.listener.enabled=true",
        "leadflow.crm.listener.enabled=true"})
class CrmSyncListenerTest {

    /** Connecteur qui n'existe que pour ce test : il valide la chaine, pas un protocole. */
    @TestConfiguration
    static class ConnecteurDeTest {

        @Bean
        CrmConnector connecteurEspion() {
            return new CrmConnector() {

                @Override
                public String providerId() {
                    return "espion";
                }

                @Override
                public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
                    return new CrmSyncResult("espion", "A-1", "C-1", "O-1", null, Instant.now());
                }

                @Override
                public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
                    return "U-1";
                }
            };
        }
    }

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;
    @Autowired private CrmSyncAttemptRepository attemptRepository;

    @AfterEach
    void nettoyage() {
        attemptRepository.deleteAll();
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
    }

    @Test
    void routePuisSynchroniseUnLeadVenantDeLaFile() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("espion");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost", "apiKey", "x"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        Client enregistre = clientRepository.saveAndFlush(client);

        SalesRep commercial = new SalesRep();
        commercial.setClient(enregistre);
        commercial.setFullName("Amina Bensalem");
        commercial.setEmail("amina@demo.test");
        salesRepRepository.saveAndFlush(commercial);

        RawLeadEvent brut = new RawLeadEvent();
        brut.setClientId(enregistre.getId());
        brut.setSource("formulaire-devis");
        brut.setPayload(Map.of("email", "prospect@acme.test"));
        brut.setSignature("t=1,v1=" + UUID.randomUUID());
        brut.setStatus(RawLeadEventStatus.PUBLISHED);
        UUID eventId = rawLeadEventRepository.saveAndFlush(brut).getId();

        Lead lead = new Lead();
        lead.setClientId(enregistre.getId());
        lead.setRawEventId(eventId);
        lead.setEmail("prospect@acme.test");
        lead.setScore(80);
        lead.setStatus(LeadStatus.QUALIFIED);
        UUID leadId = leadRepository.saveAndFlush(lead).getId();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.QUALIFIED_ROUTING_KEY,
                new QualifiedLeadMessage(leadId, enregistre.getId(), 80, Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Lead synchronise = leadRepository.findById(leadId).orElseThrow();
            assertThat(synchronise.getStatus()).isEqualTo(LeadStatus.SYNCED);
            assertThat(synchronise.getAssignedSalesRepId()).isNotNull();
            assertThat(attemptRepository.findByLeadIdAndProviderIdOrderByAttemptedAtDesc(
                    leadId, "espion")).isNotEmpty();
        });
    }
}
```

- [ ] **Step 2: Lancer le test et vérifier qu'il échoue**

Run: `./mvnw test -Dtest=CrmSyncListenerTest`
Expected: le lead reste `ROUTED` et le test expire — aucun consommateur de
`leadflow.leads.routed` n'existe.

- [ ] **Step 3: Écrire l'écrivain du statut**

Créer `backend/src/main/java/com/leadflow/crm/SyncedLeadWriter.java` :

```java
package com.leadflow.crm;

import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fait passer le lead a {@code SYNCED} apres une synchronisation reussie.
 *
 * <p>Classe a part plutot qu'une ligne dans {@code CrmSyncService} : celui-ci ne connait pas
 * le cycle de vie du lead, il synchronise et trace. Lui confier le statut ferait dependre
 * l'adaptation vers l'ERP d'une notion qui appartient au pipeline.
 *
 * <p>Transaction propre, pour la meme raison que les autres ecrivains du projet : l'appelant
 * n'est pas transactionnel.
 */
@Component
public class SyncedLeadWriter {

    private final LeadRepository leadRepository;

    public SyncedLeadWriter(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void marqueSynchronise(UUID leadId) {
        Lead lead = leadRepository.findById(leadId).orElseThrow(
                () -> new IllegalStateException("Lead disparu apres synchronisation : " + leadId));
        lead.setStatus(LeadStatus.SYNCED);
        leadRepository.saveAndFlush(lead);
    }
}
```

- [ ] **Step 4: Écrire le listener**

Créer `backend/src/main/java/com/leadflow/crm/CrmSyncListener.java` :

```java
package com.leadflow.crm;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.routing.RoutedLeadMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Derniere etape du pipeline : pousse le lead attribue dans l'ERP du client.
 *
 * <p>Etape distincte du routage, avec sa propre file, parce qu'un ERP injoignable est le
 * cas le plus frequent et le moins evitable. Avec un seul consommateur, il renverrait en DLQ
 * un message dont le rejeu reattribuerait le lead — or le tour de role n'est pas idempotent.
 * Ici, la DLQ ne contient que ce qui a reellement echoue, et le rejeu s'appuie sur
 * {@code CrmSyncState}, concu pour cela depuis F5.
 *
 * <p>Aucune exception n'est rattrapee : {@code CrmSyncException} doit provoquer les trois
 * tentatives puis la DLQ. La ligne {@code crm_sync_attempt} en echec est deja ecrite par
 * {@link CrmSyncService}, en transaction propre, donc elle survit.
 *
 * <p>Bean conditionnel pour la meme raison que les autres consommateurs du projet : la suite
 * de tests le retire, et un contexte remis en marche par le cache de tests redemarrerait ses
 * beans {@code Lifecycle} en ignorant {@code auto-startup}.
 */
@Component
@ConditionalOnProperty(name = "leadflow.crm.listener.enabled", matchIfMissing = true)
public class CrmSyncListener {

    private final CrmSyncService service;
    private final SyncedLeadWriter writer;

    public CrmSyncListener(CrmSyncService service, SyncedLeadWriter writer) {
        this.service = service;
        this.writer = writer;
    }

    @RabbitListener(queues = RabbitMQConfig.ROUTED_QUEUE)
    public void recoit(RoutedLeadMessage message) {
        service.synchronise(message.leadId());
        writer.marqueSynchronise(message.leadId());
    }
}
```

- [ ] **Step 5: Déclarer le commutateur**

Dans `backend/src/main/resources/application.yml`, sous `leadflow.crm:`, avant `providers:` :

```yaml
    listener:
      # Presence du consommateur de leadflow.leads.routed. Toujours vrai en production ;
      # la suite de tests le retire pour ne pas appeler d'ERP sans le vouloir.
      enabled: true
```

Dans `backend/src/test/resources/application.properties`, ajouter :

```properties
leadflow.crm.listener.enabled=false
```

- [ ] **Step 6: Lancer le test et vérifier qu'il passe**

Run: `./mvnw test -Dtest=CrmSyncListenerTest`
Expected: 1 test, 0 échec.

- [ ] **Step 7: Lancer la suite complète**

Run: `./mvnw test`
Expected: toute la suite verte.

- [ ] **Step 8: Commiter**

```bash
git add backend/src/main/java/com/leadflow/crm/SyncedLeadWriter.java \
        backend/src/main/java/com/leadflow/crm/CrmSyncListener.java \
        backend/src/main/resources/application.yml \
        backend/src/test/resources/application.properties \
        backend/src/test/java/com/leadflow/crm/CrmSyncListenerTest.java
git commit -m "feat: synchronisation ERP declenchee par la file, lead marque SYNCED"
```

---

## Task 8: Documentation

**Files:**
- Modify: `backend/src/main/java/com/leadflow/routing/package-info.java`
- Modify: `CLAUDE.md`

**Interfaces:** aucune — tâche documentaire.

- [ ] **Step 1: Enrichir le `package-info`**

Remplacer `backend/src/main/java/com/leadflow/routing/package-info.java` :

```java
/**
 * Etape 3a - Routage et attribution.
 *
 * <p>Consomme {@code leadflow.leads.qualified}, choisit le commercial destinataire selon la
 * strategie declaree par le client, ecrit {@code assigned_sales_rep_id} et le statut
 * {@code ROUTED}, puis publie une reference sur {@code leadflow.leads.routed} a destination
 * de la synchronisation ERP.
 *
 * <p><b>Trois invariants a ne pas casser.</b>
 *
 * <p>Aucun {@code switch} sur la strategie. Les implementations de
 * {@link com.leadflow.routing.AssignmentStrategy} sont des {@code @Component} collectes par
 * {@link com.leadflow.routing.AssignmentStrategyRegistry} : une quatrieme strategie s'ajoute
 * en ecrivant une classe.
 *
 * <p>Les strategies sont des fonctions pures. La liste des eligibles leur arrive filtree sur
 * les commerciaux actifs et ordonnee du moins recemment servi au plus recemment servi ;
 * {@link com.leadflow.routing.RotationOrder} est la seule classe du package a lire la base.
 * C'est ce qui rend le departage equitable dans les trois strategies sans le reecrire.
 *
 * <p>Un lead deja attribue n'est jamais reattribue, mais son message est republie. Le tour
 * de role n'est pas idempotent — le rejouer decalerait la rotation — alors que republier est
 * sans danger, {@code CrmSyncService} rejouant via {@code CrmSyncState}.
 */
package com.leadflow.routing;
```

- [ ] **Step 2: Ajouter la section « Routage » à `CLAUDE.md`**

Insérer après la section « Qualification — ce qui sort de la file » :

```markdown
### Routage — le dernier maillon

Deux etapes, deux files : `routing` consomme `leadflow.leads.qualified`, attribue et publie
sur `leadflow.leads.routed` ; `crm/CrmSyncListener` consomme cette file et appelle
`CrmSyncService`. Les deux consommateurs sont des beans conditionnels
(`leadflow.routing.listener.enabled`, `leadflow.crm.listener.enabled`), retires dans la
suite de tests.

**Le decoupage en deux etapes n'est pas cosmetique.** Le tour de role n'est pas idempotent :
rejouer une attribution decale la rotation. Un ERP injoignable — le cas le plus frequent —
ne doit donc jamais renvoyer l'attribution au consommateur. Avec deux files, la DLQ ne
contient que ce qui a reellement echoue.

**Aucun `switch` sur la strategie.** `AssignmentStrategyRegistry` collecte les
implementations par injection de `List<AssignmentStrategy>` et refuse de demarrer si une
valeur de `AssignmentStrategyType` n'a pas de titulaire, ou si deux la revendiquent.

**Le tour de role se lit dans la table `lead`**, pas dans un compteur : le commercial dont
`max(created_at)` est le plus ancien prend le lead suivant, et celui qui n'a jamais rien recu
passe devant. Aucun etat a maintenir, donc rien qui puisse diverger de la realite apres un
redemarrage ou une desactivation.

**Les strategies geographique et sectorielle filtrent puis retombent sur le tour de role**
quand leur critere ne trouve personne, et le repli est logue. Un prospect qui attend coute
plus cher qu'une attribution imparfaite ; le log existe pour que la configuration incomplete
du client se voie.

**Un client sans aucun commercial actif fait lever `AssignmentException`** — trois tentatives
puis DLQ. C'est le seul echec du routage qui merite la DLQ, parce qu'un humain peut le
reparer : activer un commercial, puis rejouer.
```

- [ ] **Step 3: Mettre à jour la section « État actuel » de `CLAUDE.md`**

Remplacer la section entière :

```markdown
## Etat actuel

Le pipeline est **complet de bout en bout** : capture (F2), qualification (F3), routage et
synchronisation ERP (F4), sur le socle multi-tenant de F1 et les adaptateurs de F5.

Ce qui existe : la configuration, le chiffrement des secrets, les cinq entites et leurs
repositories, les migrations `V1` a `V3`, le port `CrmConnector` et son registre, les
adaptateurs Dolibarr et Odoo, `CrmSyncService`, la couche `capture`, la couche
`qualification`, et la couche `routing` — trois strategies d'attribution, publication sur
`leadflow.leads.routed`, et la synchronisation ERP enfin declenchee par la file. Un lead
traverse desormais `QUALIFIED` -> `ROUTED` -> `SYNCED` sans intervention.

Ce qui n'existe pas : **l'observabilite**. Pas d'API de monitoring ni de dashboard (F6) : les
quatre composants de `features/` sont des placeholders, la DLQ ne se rejoue qu'a la main
depuis la console RabbitMQ, et les filets de republication de `lead.qualified` et
`lead.routed` restent a ecrire. Aucune notification n'est envoyee au commercial : ni tache
d'agenda dans l'ERP, ni alerte pour les leads chauds. Ne pas supposer l'existence d'un
service ou d'un endpoint : verifier avant de referencer.
```

- [ ] **Step 4: Vérifier et commiter**

Run: `./mvnw test`
Expected: toute la suite verte — le `package-info` est compilé.

```bash
git add backend/src/main/java/com/leadflow/routing/package-info.java CLAUDE.md
git commit -m "docs: contrat du routage et etat du pipeline apres F4"
```

---

## Fin de feature

Une fois les huit tâches terminées :

1. `./mvnw verify` depuis `backend/` — toute la suite plus le packaging.
2. Vérification manuelle de bout en bout, avec l'infrastructure réelle :
   ```bash
   docker compose --profile dolibarr up -d
   cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```
   Envoyer une soumission signée avec le `curl` de `docs/webhook-integration.md`, puis
   vérifier en base que la ligne `lead` porte un `assigned_sales_rep_id`, le statut `SYNCED`
   et une ligne `crm_sync_attempt` en `SUCCESS` — et dans Dolibarr que le tiers, le contact
   et l'opportunité existent. **Le port 8080 est occupe sur la machine de developpement** :
   lancer le backend avec `-Dspring-boot.run.arguments=--server.port=8085`.
3. Revue de branche via la skill `superpowers:requesting-code-review`.
4. Fusion dans `main` via la skill `superpowers:finishing-a-development-branch`.
   **Ne pas supprimer la branche** : elle sert d'historique de la feature.
