# F13 — Analytics et graphiques : plan d'implementation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ajouter au dashboard un ecran « Analyse » portant trois series quotidiennes — volume
capture, delai capture -> ERP, part Gemini/lexique — servies par un endpoint unique et dessinees
par Chart.js.

**Architecture:** Le monitoring reste un observateur pur : un `SeriesRepository` natif en
lecture seule lit `raw_lead_event`, `lead` et `crm_sync_attempt`, un `SeriesService` comble les
trous de la serie et borne la fenetre, `StatsController` expose `GET /api/stats/series`. Cote
frontend, une route `/analyse` en `loadComponent` isole Chart.js dans son propre chunk, et un
seul composant (`graphique-ligne`) connait la bibliotheque.

**Tech Stack:** Java 21, Spring Boot 3, Postgres 16 (requetes natives, `percentile_cont`,
index partiel), Flyway, Testcontainers, Angular 20 standalone + signals, Chart.js 4, Karma.

**Spec:** `docs/superpowers/specs/2026-09-04-f13-analytics-graphiques-design.md`

## Global Constraints

- **Le monitoring lit et n'ecrit que `dead_letter`.** `SeriesRepository` etend
  `org.springframework.data.repository.Repository` nu — jamais `JpaRepository`.
- **Aucune entite JPA ne franchit la frontiere HTTP.** Toute reponse passe par un `record` de
  `monitoring/dto/`, et le test l'asserte sur le corps JSON.
- **Le tenant est un filtre de requete** (`?clientId=`), jamais une donnee portee par le jeton.
- **Le fuseau de regroupement est `Europe/Paris`**, lu depuis `leadflow.analytics.fuseau` et
  jamais ecrit en dur dans une requete.
- **La fenetre est bornee a {7, 30, 90} jours**, `400` sinon.
- **`hibernate.jdbc.time_zone` n'est pas touche** — le regler changerait la lecture de tous les
  `Instant` du projet.
- **Chart.js est empaquete par le build, jamais charge d'un CDN** : la CSP de production dit
  `script-src 'self'`.
- **Chart.js n'entre pas dans le chunk d'accueil** : la feature est en `loadComponent`.
- **Aucun `switch` sans branche par defaut ne doit exister sur une enumeration** dont une valeur
  serait ajoutee plus tard sans erreur de compilation.
- **Tout le visuel passe par le plugin `ui-ux-pro-max`**, invoque **avant** d'ecrire le code
  d'un ecran, avec le skill `dataviz` en complement pour les couleurs des series.
- **Le backend ecoute sur `:8090`**, pas `:8080`.
- **Le daemon Docker doit tourner** pour tout `./mvnw test` : les tests importent
  `TestcontainersConfiguration`.
- **La suite backend se rejoue par lots**, jamais d'un bloc : un run complet n'a jamais survecu
  sur cette machine.
- **La copie de travail est en LF.** Prettier compare avec `endOfLine: lf`.
- **Branche de travail : `feature/f13-analytics-graphiques`**, deja creee, portant le commit de
  la spec. Ne pas la supprimer apres fusion.

---

### Task 1 : la migration `V10` et ses deux index

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__analytics_index.sql`
- Test: `backend/src/test/java/com/leadflow/monitoring/AnalyticsIndexTest.java`

**Interfaces:**
- Consumes: rien.
- Produces: les index `idx_lead_client_created` et `idx_crm_sync_attempt_success_at`, dont les
  requetes des taches 2 a 4 dependent pour ne pas balayer la table entiere.

**Contexte que l'implementeur n'a pas :** `ddl-auto: validate` — Hibernate ne cree jamais de
table. Toute evolution de schema est un fichier `V<n>__description.sql`. Modifier une migration
deja appliquee fait echouer Flyway au demarrage sur un ecart de checksum ; en developpement, la
sortie est `docker compose down -v`. La derniere migration existante est `V9`.

- [ ] **Step 1: Ecrire le test qui echoue**

Ce test interroge `pg_indexes`, la vue systeme de Postgres qui liste les index reellement
crees. Il ne teste pas Flyway — il teste que les index que les requetes de F13 supposent
existent bel et bien.

```java
package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Les deux index de V10 existent reellement.
 *
 * <p>Les requetes de F13 sont natives : Hibernate ne les valide pas au demarrage, et un index
 * absent ne se verrait qu'en production, sous la forme d'un ecran lent. Ce test le dit tout de
 * suite.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional(readOnly = true)
class AnalyticsIndexTest {

    @Autowired private EntityManager em;

    @Test
    void lesDeuxIndexDeV10Existent() {
        @SuppressWarnings("unchecked")
        List<String> noms = em.createNativeQuery(
                        "select indexname from pg_indexes where schemaname = 'public'")
                .getResultList();

        assertThat(noms)
                .contains("idx_lead_client_created", "idx_crm_sync_attempt_success_at");
    }

    @Test
    void lIndexDesSuccesEstPartiel() {
        String definition = (String) em.createNativeQuery(
                        """
                        select indexdef from pg_indexes
                        where schemaname = 'public'
                          and indexname = 'idx_crm_sync_attempt_success_at'
                        """)
                .getSingleResult();

        // Partiel et non global : la requete des delais ne lit que les succes, et un index
        // sur toutes les tentatives ferait payer les echecs, qui sont l'essentiel du volume
        // quand un ERP tombe.
        assertThat(definition).contains("WHERE").contains("SUCCESS");
    }
}
```

- [ ] **Step 2: Lancer le test pour verifier qu'il echoue**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsIndexTest`
Expected: FAIL — `lesDeuxIndexDeV10Existent` echoue sur une liste qui ne contient ni
`idx_lead_client_created` ni `idx_crm_sync_attempt_success_at`, et `lIndexDesSuccesEstPartiel`
echoue sur `NoResultException`.

- [ ] **Step 3: Ecrire la migration**

```sql
-- V10 : les deux index dont les series quotidiennes de F13 ont besoin.
--
-- Aucune colonne, aucune table : F13 ne fait que lire ce qui existe, comme la chronologie
-- de F9 qui recompose six tables sans en creer aucune.
--
-- Un troisieme index aurait ete necessaire pour la courbe de volume, mais
-- idx_raw_lead_event_client_received (client_id, received_at DESC) existe depuis V2 et la
-- sert deja.

-- La serie de volume et celle des intentions balaient `lead` par date. L'index existant est
-- (client_id, email, created_at) : la colonne `email` au milieu le rend inutilisable pour un
-- regroupement par jour, Postgres ne pouvant sauter une colonne de tete.
CREATE INDEX idx_lead_client_created ON lead (client_id, created_at DESC);

-- La serie des delais balaie les succes sur une plage de dates, toutes boutiques confondues.
-- L'index existant est (lead_id, attempted_at) : il sert la fiche d'un lead, pas un balayage
-- par date.
--
-- Partiel, comme uq_dead_letter_lead_pending de V8 : la requete ne lit que les succes, et un
-- index global ferait payer les echecs, qui sont l'essentiel du volume quand un ERP tombe.
CREATE INDEX idx_crm_sync_attempt_success_at ON crm_sync_attempt (attempted_at DESC)
    WHERE status = 'SUCCESS';
```

- [ ] **Step 4: Lancer le test pour verifier qu'il passe**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsIndexTest`
Expected: PASS, 2 tests.

Si le test echoue encore avec les index bien ecrits, la base de `docker compose` porte
peut-etre un etat anterieur. Les tests tournent sous Testcontainers et repartent d'une base
vierge, donc ce cas ne devrait pas se produire ; s'il se produit, verifier que le fichier est
bien sous `src/main/resources/db/migration/` et non ailleurs.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V10__analytics_index.sql \
        backend/src/test/java/com/leadflow/monitoring/AnalyticsIndexTest.java
git commit -m "feat: V10, les deux index des series quotidiennes

L'un des trois index supposes par F13 existait deja depuis V2. Celui des
succes de synchronisation est partiel, comme celui de V8 : la requete des
delais ne lit que les succes, et un index global ferait payer les echecs."
```

---

### Task 2 : le fuseau de regroupement, reglable et jamais en dur

**Files:**
- Create: `backend/src/main/java/com/leadflow/config/AnalyticsProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/leadflow/config/AnalyticsPropertiesTest.java`

**Interfaces:**
- Consumes: rien.
- Produces: `AnalyticsProperties.fuseau()` rendant une `String` (« Europe/Paris » par defaut) et
  `AnalyticsProperties.zone()` rendant un `java.time.ZoneId`. Les taches 3 a 6 les injectent.

**Contexte que l'implementeur n'a pas :** les nouveaux reglages metier vont sous le prefixe
`leadflow.*` et se lisent via un `record` `@ConfigurationProperties` place dans `config/`.
`@ConfigurationPropertiesScan` est actif sur `BackendApplication` — **aucun enregistrement
manuel n'est necessaire**, et en ajouter un creerait un second bean.

- [ ] **Step 1: Ecrire le test qui echoue**

```java
package com.leadflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class AnalyticsPropertiesTest {

    @Test
    void leFuseauParDefautEstCeluiDeLAgence() {
        // Un fuseau absent ne doit pas retomber sur UTC : un lead recu a 00 h 30 heure
        // locale tomberait dans la journee de la veille sur le graphique, decalage
        // silencieux que personne ne rattache jamais a sa cause.
        assertThat(new AnalyticsProperties(null).fuseau()).isEqualTo("Europe/Paris");
        assertThat(new AnalyticsProperties("  ").fuseau()).isEqualTo("Europe/Paris");
    }

    @Test
    void leFuseauSurchargeEstRespecte() {
        AnalyticsProperties props = new AnalyticsProperties("Africa/Casablanca");

        assertThat(props.fuseau()).isEqualTo("Africa/Casablanca");
        assertThat(props.zone()).isEqualTo(ZoneId.of("Africa/Casablanca"));
    }

    @Test
    void unFuseauInconnuEchoueAuDemarrageEtNonALaPremiereRequete() {
        // Construire le ZoneId dans le record fait echouer le demarrage. Le construire a
        // chaque requete ferait echouer le premier chargement de l'ecran, plusieurs jours
        // apres le deploiement fautif.
        assertThatThrownBy(() -> new AnalyticsProperties("Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mars/Olympus");
    }
}
```

- [ ] **Step 2: Lancer le test pour verifier qu'il echoue**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsPropertiesTest`
Expected: FAIL a la compilation — `AnalyticsProperties` n'existe pas.

- [ ] **Step 3: Ecrire le record**

```java
package com.leadflow.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reglages des series quotidiennes du monitoring.
 *
 * <p>Le fuseau n'est pas un detail de presentation : {@code date_trunc} decoupe selon le
 * fuseau de la session — UTC dans le conteneur — si bien qu'un lead recu a 00 h 30 heure
 * locale tomberait dans la journee de la veille. Le regroupement le nomme donc
 * explicitement.
 *
 * <p>Il est <strong>global a l'instance</strong> et non porte par la boutique, comme les
 * reglages du relais SMTP de F12 : l'agence lit ses graphiques depuis un seul endroit.
 *
 * <p>Le {@link ZoneId} est construit ici, dans le constructeur compact, et non a chaque
 * requete : un fuseau mal orthographie doit empecher le demarrage, pas attendre le premier
 * chargement de l'ecran d'analyse.
 */
@ConfigurationProperties(prefix = "leadflow.analytics")
public record AnalyticsProperties(String fuseau) {

    private static final String DEFAUT = "Europe/Paris";

    public AnalyticsProperties {
        if (fuseau == null || fuseau.isBlank()) {
            fuseau = DEFAUT;
        }
        ZoneId.of(fuseau);
    }

    public ZoneId zone() {
        return ZoneId.of(fuseau);
    }
}
```

Puis, dans `application.yml`, sous la racine `leadflow:` deja presente — **verifier
l'indentation existante avant d'inserer**, le fichier est en deux espaces :

```yaml
  analytics:
    # Fuseau de regroupement des series quotidiennes. Global a l'instance : l'agence lit ses
    # graphiques depuis un seul endroit.
    fuseau: ${LEADFLOW_ANALYTICS_FUSEAU:Europe/Paris}
```

- [ ] **Step 4: Lancer le test pour verifier qu'il passe**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsPropertiesTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/config/AnalyticsProperties.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/leadflow/config/AnalyticsPropertiesTest.java
git commit -m "feat: le fuseau de regroupement des series, nomme et non deduit

date_trunc decoupe selon le fuseau de la session, UTC dans le conteneur :
un lead recu a 00 h 30 locale tomberait la veille. Le ZoneId est construit
au demarrage pour qu'un fuseau mal orthographie ne se decouvre pas au
premier chargement de l'ecran."
```

---

### Task 3 : la serie du volume capture

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/SeriesRepository.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/PointVolumeBrut.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/SeriesRepositoryTest.java`

**Interfaces:**
- Consumes: `AnalyticsProperties.fuseau()` (tache 2).
- Produces: `SeriesRepository.volumeParJour(String clientId, Instant depuis, String fuseau)`
  rendant `List<PointVolumeBrut>`, ou `PointVolumeBrut` expose `LocalDate getJour()`,
  `long getCaptures()`, `long getEcartes()`. Les taches 4, 5 et 6 s'y ajoutent et s'en servent.

**Contexte que l'implementeur n'a pas :**

- `RawLeadEventStatus` a quatre valeurs : `RECEIVED`, `PUBLISHED`, `FAILED`, `DISCARDED`. Seul
  `DISCARDED` est terminal — c'est l'evenement qui ne produira jamais de lead.
- `clientId` est passe en **`String` et non en `UUID`**. Un parametre `UUID` a `null` dans une
  requete native n'a aucun type deductible pour Postgres, qui refuse alors la requete entiere
  avec « could not determine data type of parameter ». Le `cast(:clientId as uuid)` lui donne ce
  type ; c'est le meme reflexe que les `cast(:param as ...)` de `StatsRepository`, sous une
  autre forme.
- `Repository` nu, jamais `JpaRepository` : le monitoring n'ecrit pas, et l'interface le montre.

- [ ] **Step 1: Ecrire le test qui echoue**

```java
package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeriesRepositoryTest {

    private static final String PARIS = "Europe/Paris";

    @Autowired private SeriesRepository series;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;

    @AfterEach
    void nettoie() {
        evenements.deleteAll();
        clients.deleteAll();
    }

    @Test
    void compteLesCapturesEtLaPartEcarteeParJour() {
        Client boutique = boutique("volume");
        Instant lundi = instantParisien(2026, 3, 2, 10);
        Instant mardi = instantParisien(2026, 3, 3, 10);

        evenement(boutique, lundi, RawLeadEventStatus.PUBLISHED);
        evenement(boutique, lundi, RawLeadEventStatus.DISCARDED);
        evenement(boutique, mardi, RawLeadEventStatus.PUBLISHED);

        List<PointVolumeBrut> points = series.volumeParJour(
                boutique.getId().toString(), lundi.minusSeconds(3600), PARIS);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).getJour()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(points.get(0).getCaptures()).isEqualTo(2L);
        assertThat(points.get(0).getEcartes()).isEqualTo(1L);
        assertThat(points.get(1).getJour()).isEqualTo(LocalDate.of(2026, 3, 3));
        assertThat(points.get(1).getCaptures()).isEqualTo(1L);
        assertThat(points.get(1).getEcartes()).isZero();
    }

    @Test
    void leDecoupageTombeDansLeFuseauDemande() {
        // 00 h 30 a Paris le 3 mars, soit 23 h 30 UTC le 2 mars. En UTC ce lead compterait
        // pour la veille ; c'est exactement le decalage que le fuseau explicite corrige.
        Client boutique = boutique("minuit");
        Instant justeApresMinuitAParis = instantParisien(2026, 3, 3, 0).plusSeconds(1800);
        evenement(boutique, justeApresMinuitAParis, RawLeadEventStatus.PUBLISHED);

        List<PointVolumeBrut> points = series.volumeParJour(
                boutique.getId().toString(),
                justeApresMinuitAParis.minusSeconds(86_400),
                PARIS);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).getJour()).isEqualTo(LocalDate.of(2026, 3, 3));
    }

    @Test
    void leFiltreDeBoutiqueIsoleLesInstances() {
        Client mienne = boutique("mienne");
        Client autre = boutique("autre");
        Instant quand = instantParisien(2026, 3, 2, 10);
        evenement(mienne, quand, RawLeadEventStatus.PUBLISHED);
        evenement(autre, quand, RawLeadEventStatus.PUBLISHED);

        assertThat(series.volumeParJour(
                        mienne.getId().toString(), quand.minusSeconds(3600), PARIS))
                .singleElement()
                .satisfies(p -> assertThat(p.getCaptures()).isEqualTo(1L));

        // clientId nul = toutes les boutiques. C'est la vue de l'agence.
        assertThat(series.volumeParJour(null, quand.minusSeconds(3600), PARIS))
                .singleElement()
                .satisfies(p -> assertThat(p.getCaptures()).isEqualTo(2L));
    }

    private Client boutique(String suffixe) {
        Client c = new Client();
        c.setName("Boutique " + suffixe);
        c.setPublicKey("pk-" + suffixe + "-" + UUID.randomUUID());
        c.setHmacSecret("secret-" + suffixe);
        c.setActive(true);
        return clients.save(c);
    }

    private void evenement(Client boutique, Instant quand, RawLeadEventStatus statut) {
        RawLeadEvent e = new RawLeadEvent();
        e.setClientId(boutique.getId());
        e.setPayload("{}");
        e.setSignature("sig-" + UUID.randomUUID());
        e.setReceivedAt(quand);
        e.setStatus(statut);
        evenements.save(e);
    }

    private static Instant instantParisien(int annee, int mois, int jour, int heure) {
        return ZonedDateTime.of(annee, mois, jour, heure, 0, 0, 0, ZoneId.of(PARIS))
                .toInstant();
    }
}
```

**Avertissement pour l'implementeur :** les setters de `Client` et `RawLeadEvent` ci-dessus sont
ceux que les tests existants utilisent. Ouvrir
`backend/src/test/java/com/leadflow/monitoring/StatsServiceTest.java` et **recopier ses methodes
de fabrication** plutot que d'inventer : si un champ obligatoire manque, l'insertion echouera
sur une contrainte et non sur la requete qu'on veut tester.

- [ ] **Step 2: Lancer le test pour verifier qu'il echoue**

Run: `cd backend && ./mvnw test -Dtest=SeriesRepositoryTest`
Expected: FAIL a la compilation — `SeriesRepository` et `PointVolumeBrut` n'existent pas.

- [ ] **Step 3: Ecrire la projection et le repository**

```java
package com.leadflow.monitoring;

import java.time.LocalDate;

/**
 * Une journee de capture, telle que Postgres la rend.
 *
 * <p>Projection d'interface, comme {@link Comptage} : Spring Data la remplit sans passer par
 * l'entite, ce qui garantit qu'aucune ligne n'est chargee pour compter des lignes.
 *
 * <p>« Brut » parce que la serie qui sort d'ici est trouee — une journee sans capture n'a pas
 * de ligne. C'est {@code SeriesService} qui la comble.
 */
public interface PointVolumeBrut {

    LocalDate getJour();

    long getCaptures();

    long getEcartes();
}
```

```java
package com.leadflow.monitoring;

import com.leadflow.qualification.Lead;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Les series quotidiennes du monitoring. {@code Repository} nu, comme {@link StatsRepository}
 * et {@link LeadQueryRepository} : le monitoring n'ecrit pas, et l'interface le montre.
 *
 * <p><strong>Premier repository natif du projet</strong>, et deux contraintes independantes
 * l'imposent. Le regroupement par jour dans un fuseau ne s'exprime pas en JPQL — ni
 * {@code date_trunc}, ni {@code AT TIME ZONE} — et la seule alternative, poser
 * {@code hibernate.jdbc.time_zone}, changerait la lecture de tous les {@code Instant} du
 * projet pour resoudre le besoin de trois requetes. Et {@code percentile_cont} n'existe pas
 * davantage en JPQL.
 *
 * <p>Le prix du natif : <strong>Hibernate ne valide plus ces requetes au demarrage</strong>.
 * Une colonne renommee ne se verrait qu'a l'execution, et c'est pour cela que les tests de ce
 * repository sont des {@code @SpringBootTest} contre un vrai Postgres.
 *
 * <p>{@code clientId} est une {@code String} et non un {@code UUID} : un parametre {@code UUID}
 * a {@code null} n'a aucun type deductible pour Postgres, qui refuse alors la requete entiere
 * avec « could not determine data type of parameter ». Le {@code cast} le lui donne.
 */
public interface SeriesRepository extends Repository<Lead, UUID> {

    @Query(
            value =
                    """
                    select (e.received_at at time zone :fuseau)::date as jour,
                           count(*)                                     as captures,
                           count(*) filter (where e.status = 'DISCARDED') as ecartes
                    from raw_lead_event e
                    where e.received_at >= :depuis
                      and (cast(:clientId as uuid) is null
                           or e.client_id = cast(:clientId as uuid))
                    group by jour
                    order by jour
                    """,
            nativeQuery = true)
    List<PointVolumeBrut> volumeParJour(
            @Param("clientId") String clientId,
            @Param("depuis") Instant depuis,
            @Param("fuseau") String fuseau);
}
```

- [ ] **Step 4: Lancer le test pour verifier qu'il passe**

Run: `cd backend && ./mvnw test -Dtest=SeriesRepositoryTest`
Expected: PASS, 3 tests.

Si Postgres se plaint de « column "jour" does not exist » dans le `group by`, c'est que la
version de Postgres n'accepte pas le regroupement par alias de sortie ; remplacer
`group by jour` par `group by 1` et `order by jour` par `order by 1`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/SeriesRepository.java \
        backend/src/main/java/com/leadflow/monitoring/PointVolumeBrut.java \
        backend/src/test/java/com/leadflow/monitoring/SeriesRepositoryTest.java
git commit -m "feat: la serie du volume capture, premiere requete native du projet

JPQL n'a ni date_trunc ni AT TIME ZONE, et le seul contournement global
changerait la lecture de tous les Instant du projet. Le clientId passe en
String : un UUID a null n'a aucun type deductible pour Postgres."
```

---

### Task 4 : la serie de la part Gemini / lexique

**Files:**
- Modify: `backend/src/main/java/com/leadflow/monitoring/SeriesRepository.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/PointIntentionBrut.java`
- Modify: `backend/src/test/java/com/leadflow/monitoring/SeriesRepositoryTest.java`

**Interfaces:**
- Consumes: `SeriesRepository` (tache 3).
- Produces: `SeriesRepository.intentionsParJour(String clientId, Instant depuis, String fuseau)`
  rendant `List<PointIntentionBrut>`, ou `PointIntentionBrut` expose `LocalDate getJour()`,
  `long getGemini()`, `long getLexique()`.

**Contexte que l'implementeur n'a pas :** `IntentSource` a exactement deux valeurs, `RULES` et
`GEMINI`, et la colonne est `lead.intent_source`. Elle est **nullable** : un lead qualifie avant
F7.2, ou un doublon rejete, n'a pas de source. Ces leads ne comptent dans aucune des deux
colonnes — c'est voulu, la figure repond « quelle part de l'analyse a ete faite par le modele »,
pas « combien de leads y a-t-il ».

- [ ] **Step 1: Ecrire le test qui echoue**

A ajouter dans `SeriesRepositoryTest`, avec l'injection et le nettoyage correspondants.
Ajouter en tete de classe :

```java
    @Autowired private com.leadflow.qualification.LeadRepository leads;
```

et, dans `nettoie()`, `leads.deleteAll();` **avant** `evenements.deleteAll();` — `lead`
reference `raw_lead_event`, donc l'ordre inverse violerait la cle etrangere.

```java
    @Test
    void comptePourChaqueJourLaPartDuModeleEtCelleDuLexique() {
        Client boutique = boutique("intentions");
        Instant lundi = instantParisien(2026, 3, 2, 10);

        lead(boutique, lundi, com.leadflow.qualification.IntentSource.GEMINI);
        lead(boutique, lundi, com.leadflow.qualification.IntentSource.GEMINI);
        lead(boutique, lundi, com.leadflow.qualification.IntentSource.RULES);
        // Sans source : ni Gemini ni lexique. La figure dit qui a analyse, pas combien de
        // leads sont arrives.
        lead(boutique, lundi, null);

        List<PointIntentionBrut> points = series.intentionsParJour(
                boutique.getId().toString(), lundi.minusSeconds(3600), PARIS);

        assertThat(points).singleElement().satisfies(p -> {
            assertThat(p.getJour()).isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(p.getGemini()).isEqualTo(2L);
            assertThat(p.getLexique()).isEqualTo(1L);
        });
    }
```

et la fabrique, en bas de classe :

```java
    private com.leadflow.qualification.Lead lead(
            Client boutique, Instant quand, com.leadflow.qualification.IntentSource source) {
        com.leadflow.capture.RawLeadEvent e = new com.leadflow.capture.RawLeadEvent();
        e.setClientId(boutique.getId());
        e.setPayload("{}");
        e.setSignature("sig-" + UUID.randomUUID());
        e.setReceivedAt(quand);
        e.setStatus(com.leadflow.capture.RawLeadEventStatus.PUBLISHED);
        e = evenements.save(e);

        com.leadflow.qualification.Lead l = new com.leadflow.qualification.Lead();
        l.setClientId(boutique.getId());
        l.setRawEventId(e.getId());
        l.setEmail("prospect-" + UUID.randomUUID() + "@test.local");
        l.setScore(50);
        l.setStatus(com.leadflow.qualification.LeadStatus.QUALIFIED);
        l.setIntentSource(source);
        l.setCreatedAt(quand);
        return leads.save(l);
    }
```

**Avertissement :** `createdAt` vient probablement de `BaseEntity` avec `@CreationTimestamp`, ce
qui **ecraserait** la date posee. Ouvrir `backend/src/main/java/com/leadflow/common/BaseEntity.java`
avant d'ecrire cette fabrique. Si la date est generee, la corriger apres insertion par un
`UPDATE` natif dans le test — c'est le seul moyen de tester un regroupement par jour sans
attendre plusieurs jours :

```java
        em.createNativeQuery("update lead set created_at = :quand where id = :id")
                .setParameter("quand", quand)
                .setParameter("id", l.getId())
                .executeUpdate();
```

(avec `@Autowired EntityManager em;` et la methode annotee `@Transactional` si necessaire).
La meme remarque vaut pour `received_at` de la tache 3.

- [ ] **Step 2: Lancer le test pour verifier qu'il echoue**

Run: `cd backend && ./mvnw test -Dtest=SeriesRepositoryTest`
Expected: FAIL a la compilation — `intentionsParJour` et `PointIntentionBrut` n'existent pas.

- [ ] **Step 3: Ecrire la projection et la requete**

```java
package com.leadflow.monitoring;

import java.time.LocalDate;

/**
 * Une journee d'analyse d'intention, repartie entre le modele et le lexique.
 *
 * <p>Les leads sans source ne comptent nulle part : la figure repond « quelle part de
 * l'analyse a ete faite par le modele », pas « combien de leads sont arrives ». Le total des
 * deux colonnes n'est donc pas le nombre de leads du jour.
 */
public interface PointIntentionBrut {

    LocalDate getJour();

    long getGemini();

    long getLexique();
}
```

Et, dans `SeriesRepository` :

```java
    @Query(
            value =
                    """
                    select (l.created_at at time zone :fuseau)::date        as jour,
                           count(*) filter (where l.intent_source = 'GEMINI') as gemini,
                           count(*) filter (where l.intent_source = 'RULES')  as lexique
                    from lead l
                    where l.created_at >= :depuis
                      and (cast(:clientId as uuid) is null
                           or l.client_id = cast(:clientId as uuid))
                    group by jour
                    order by jour
                    """,
            nativeQuery = true)
    List<PointIntentionBrut> intentionsParJour(
            @Param("clientId") String clientId,
            @Param("depuis") Instant depuis,
            @Param("fuseau") String fuseau);
```

- [ ] **Step 4: Lancer le test pour verifier qu'il passe**

Run: `cd backend && ./mvnw test -Dtest=SeriesRepositoryTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/SeriesRepository.java \
        backend/src/main/java/com/leadflow/monitoring/PointIntentionBrut.java \
        backend/src/test/java/com/leadflow/monitoring/SeriesRepositoryTest.java
git commit -m "feat: la part Gemini et lexique jour par jour

Le bandeau de F7.2 donne ce rapport en cumul ; en serie, une cle expiree
ou un quota atteint se lit comme une bascule et non comme un pourcentage
qui glisse. Les leads sans source ne comptent nulle part : la figure dit
qui a analyse, pas combien de leads sont arrives."
```

---

### Task 5 : la serie des delais capture -> ERP

**Files:**
- Modify: `backend/src/main/java/com/leadflow/monitoring/SeriesRepository.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/PointDelaiBrut.java`
- Modify: `backend/src/test/java/com/leadflow/monitoring/SeriesRepositoryTest.java`

**Interfaces:**
- Consumes: `SeriesRepository` (taches 3 et 4).
- Produces: `SeriesRepository.delaisParJour(String clientId, Instant depuis, String fuseau)`
  rendant `List<PointDelaiBrut>`, ou `PointDelaiBrut` expose `LocalDate getJour()`,
  `Double getMedianeSecondes()`, `Double getP95Secondes()`.

**Contexte que l'implementeur n'a pas :**

- `CrmSyncAttempt` **ne porte aucune association** vers `Lead` — choix de F5 qui garde
  l'adaptateur ignorant du pipeline. La jointure se fait par identifiant, exactement comme
  `SyncActivityRepository` le fait deja.
- `CrmSyncAttemptStatus` a deux valeurs, `SUCCESS` et `FAILED`, et la colonne est
  `crm_sync_attempt.status`, un `VARCHAR(32)` sous contrainte `CHECK`.
- Le chemin est `raw_lead_event.received_at` -> `lead.raw_event_id` -> `crm_sync_attempt.lead_id`.
- **Le point du jour J agrege les leads synchronises ce jour-la**, pas ceux captures.

- [ ] **Step 1: Ecrire le test qui echoue**

```java
    @Test
    void mesureLeDelaiDuPremierSuccesEtNonDuDernier() {
        Client boutique = boutique("delais");
        Instant capture = instantParisien(2026, 3, 2, 10);
        Instant premierSucces = capture.plusSeconds(120);
        Instant rejeuTardif = capture.plusSeconds(3 * 86_400);

        Lead l = lead(boutique, capture, IntentSource.GEMINI);
        tentative(l, premierSucces, CrmSyncAttemptStatus.SUCCESS);
        // Un rejeu ajoute un succes tardif. Le lire ferait afficher trois jours de delai
        // pour un lead synchronise en deux minutes.
        tentative(l, rejeuTardif, CrmSyncAttemptStatus.SUCCESS);

        List<PointDelaiBrut> points = series.delaisParJour(
                boutique.getId().toString(), capture.minusSeconds(3600), PARIS);

        assertThat(points).singleElement().satisfies(p -> {
            assertThat(p.getJour()).isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(p.getMedianeSecondes()).isEqualTo(120.0d);
        });
    }

    @Test
    void ignoreLesTentativesEnEchec() {
        Client boutique = boutique("echecs");
        Instant capture = instantParisien(2026, 3, 2, 10);

        Lead l = lead(boutique, capture, IntentSource.GEMINI);
        tentative(l, capture.plusSeconds(10), CrmSyncAttemptStatus.FAILED);
        tentative(l, capture.plusSeconds(300), CrmSyncAttemptStatus.SUCCESS);

        // Le delai se compte jusqu'au succes, pas jusqu'a la premiere tentative : un lead
        // que l'ERP a refuse deux fois a bel et bien mis cinq minutes a arriver.
        assertThat(series.delaisParJour(
                        boutique.getId().toString(), capture.minusSeconds(3600), PARIS))
                .singleElement()
                .satisfies(p -> assertThat(p.getMedianeSecondes()).isEqualTo(300.0d));
    }

    @Test
    void separeLaMedianeDuP95() {
        Client boutique = boutique("percentiles");
        Instant jour = instantParisien(2026, 3, 2, 10);

        // Nnneuf leads a 10 s et un a 1000 s. La moyenne dirait 109 s, ce qui ne decrit
        // aucun lead reel ; la mediane dit 10 s et le p95 revele la queue.
        for (int i = 0; i < 9; i++) {
            Lead rapide = lead(boutique, jour, IntentSource.RULES);
            tentative(rapide, jour.plusSeconds(10), CrmSyncAttemptStatus.SUCCESS);
        }
        Lead lent = lead(boutique, jour, IntentSource.RULES);
        tentative(lent, jour.plusSeconds(1000), CrmSyncAttemptStatus.SUCCESS);

        PointDelaiBrut point = series.delaisParJour(
                        boutique.getId().toString(), jour.minusSeconds(3600), PARIS)
                .get(0);

        assertThat(point.getMedianeSecondes()).isEqualTo(10.0d);
        assertThat(point.getP95Secondes()).isGreaterThan(400.0d);
    }

    @Test
    void ancreLePointSurLeJourDeLaSynchronisationEtNonDeLaCapture() {
        Client boutique = boutique("ancrage");
        Instant captureLundi = instantParisien(2026, 3, 2, 23);
        Instant syncMardi = instantParisien(2026, 3, 3, 2);

        Lead l = lead(boutique, captureLundi, IntentSource.RULES);
        tentative(l, syncMardi, CrmSyncAttemptStatus.SUCCESS);

        // Ancre sur la synchronisation, le point est definitif des que le jour est passe.
        // Ancre sur la capture, la courbe s'ameliorerait quand ca va mal : un lead jamais
        // synchronise n'y apparaitrait jamais.
        assertThat(series.delaisParJour(
                        boutique.getId().toString(), captureLundi.minusSeconds(3600), PARIS))
                .singleElement()
                .satisfies(p -> assertThat(p.getJour()).isEqualTo(LocalDate.of(2026, 3, 3)));
    }
```

Avec la fabrique et le nettoyage associes — `tentatives.deleteAll()` en premier dans
`nettoie()`, `crm_sync_attempt` referencant `lead` :

```java
    @Autowired private com.leadflow.crm.CrmSyncAttemptRepository tentatives;

    private void tentative(Lead lead, Instant quand, CrmSyncAttemptStatus statut) {
        CrmSyncAttempt a = new CrmSyncAttempt();
        a.setLeadId(lead.getId());
        a.setProviderId("dolibarr");
        a.setStatus(statut);
        a.setAttemptedAt(quand);
        tentatives.save(a);
    }
```

**Avertissement :** verifier le nom reel du repository de `CrmSyncAttempt` avant d'ecrire
l'injection — `grep -rn "interface Crm.*Repository" backend/src/main/java/com/leadflow/crm/`.
Meme precaution sur `attempted_at`, qui a un `DEFAULT now()` en base et peut etre pose par
l'entite : si la date est ecrasee, la corriger par `UPDATE` natif comme en tache 4.

- [ ] **Step 2: Lancer le test pour verifier qu'il echoue**

Run: `cd backend && ./mvnw test -Dtest=SeriesRepositoryTest`
Expected: FAIL a la compilation — `delaisParJour` et `PointDelaiBrut` n'existent pas.

- [ ] **Step 3: Ecrire la projection et la requete**

```java
package com.leadflow.monitoring;

import java.time.LocalDate;

/**
 * Le delai capture -> synchronisation ERP d'une journee, en secondes.
 *
 * <p>Deux mesures et pas une moyenne : un seul ERP en timeout a 30 s ecraserait la lecture
 * d'une journee normale a 2 s. La mediane decrit le lead courant, le p95 revele la queue.
 *
 * <p>Les deux sont des {@code Double} et non des {@code double} : une journee sans aucune
 * synchronisation n'a pas de ligne ici, et le service qui comble ce trou doit pouvoir y
 * mettre {@code null}. Zero voudrait dire « delai nul », soit l'inverse du sens.
 */
public interface PointDelaiBrut {

    LocalDate getJour();

    Double getMedianeSecondes();

    Double getP95Secondes();
}
```

Et, dans `SeriesRepository` :

```java
    @Query(
            value =
                    """
                    with premier_succes as (
                        select a.lead_id, min(a.attempted_at) as sync_at
                        from crm_sync_attempt a
                        where a.status = 'SUCCESS'
                        group by a.lead_id
                    )
                    select (p.sync_at at time zone :fuseau)::date as jour,
                           percentile_cont(0.5) within group (
                               order by extract(epoch from (p.sync_at - e.received_at))
                           ) as medianeSecondes,
                           percentile_cont(0.95) within group (
                               order by extract(epoch from (p.sync_at - e.received_at))
                           ) as p95Secondes
                    from premier_succes p
                    join lead l           on l.id = p.lead_id
                    join raw_lead_event e on e.id = l.raw_event_id
                    where p.sync_at >= :depuis
                      and (cast(:clientId as uuid) is null
                           or l.client_id = cast(:clientId as uuid))
                    group by jour
                    order by jour
                    """,
            nativeQuery = true)
    List<PointDelaiBrut> delaisParJour(
            @Param("clientId") String clientId,
            @Param("depuis") Instant depuis,
            @Param("fuseau") String fuseau);
```

Le `min(attempted_at)` de la CTE est le coeur de la requete : un rejeu ajoute un succes tardif,
et prendre le dernier ferait afficher trois jours de delai pour un lead synchronise en deux
minutes.

- [ ] **Step 4: Lancer le test pour verifier qu'il passe**

Run: `cd backend && ./mvnw test -Dtest=SeriesRepositoryTest`
Expected: PASS, 8 tests.

Si l'alias `medianeSecondes` n'est pas reconnu par la projection, c'est que Postgres a replie
l'alias en minuscules (`medianesecondes`). Deux sorties : mettre l'alias entre guillemets
(`as "medianeSecondes"`), ou renommer la methode de projection en `getMedianesecondes()`.
**Preferer les guillemets** — la projection reste lisible.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/SeriesRepository.java \
        backend/src/main/java/com/leadflow/monitoring/PointDelaiBrut.java \
        backend/src/test/java/com/leadflow/monitoring/SeriesRepositoryTest.java
git commit -m "feat: le delai capture vers ERP, mediane et p95 par jour de synchronisation

La metrique d'un middleware, et personne ne la voyait. Le premier succes
par lead et non le dernier : un rejeu ajoute un succes tardif qui ferait
afficher trois jours pour un lead synchronise en deux minutes. Ancre sur
le jour de la synchronisation, sans quoi la courbe s'ameliorerait quand
ca va mal."
```

---

### Task 6 : le service, les trous combles et la fenetre bornee

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/SeriesService.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/FenetreInvalideException.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/SeriesView.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/PointVolume.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/PointDelai.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/PointIntention.java`
- Modify: `backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/SeriesServiceTest.java`

**Interfaces:**
- Consumes: `SeriesRepository.volumeParJour / intentionsParJour / delaisParJour` (taches 3-5),
  `AnalyticsProperties.zone()` et `.fuseau()` (tache 2).
- Produces: `SeriesService.calcule(UUID clientId, int jours)` rendant `SeriesView`, et
  `FenetreInvalideException`. La tache 7 les appelle.

**Contexte que l'implementeur n'a pas :** `ApiExceptionHandler` est le `@RestControllerAdvice`
du projet ; il rend des `ProblemDetail`, et une exception non declaree y donnerait un `500`.
`ReglageManquantException` y est deja traduite en `400` — copier ce motif.

- [ ] **Step 1: Ecrire le test qui echoue**

```java
package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.monitoring.dto.SeriesView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeriesServiceTest {

    @Autowired private SeriesService service;

    @Test
    void rendExactementUnPointParJourDeLaFenetre() {
        // Une journee sans donnee n'a pas de ligne en base, et un graphique tracerait une
        // droite par-dessus. La serie sort comblee du service.
        SeriesView vue = service.calcule(null, 7);

        assertThat(vue.volume()).hasSize(7);
        assertThat(vue.delais()).hasSize(7);
        assertThat(vue.intentions()).hasSize(7);
    }

    @Test
    void combleAZeroLeVolumeMaisANullLeDelai() {
        // La distinction est le coeur de la feature. « Aucun lead capture ce jour-la » est
        // un fait, donc zero. « Aucun lead synchronise ce jour-la » ne veut pas dire « delai
        // de zero seconde » : mis a zero, la courbe dessinerait une chute vers le bas, soit
        // l'inverse du sens.
        SeriesView vue = service.calcule(null, 7);

        assertThat(vue.volume()).allSatisfy(p -> {
            assertThat(p.captures()).isZero();
            assertThat(p.ecartes()).isZero();
        });
        assertThat(vue.intentions()).allSatisfy(p -> {
            assertThat(p.gemini()).isZero();
            assertThat(p.lexique()).isZero();
        });
        assertThat(vue.delais()).allSatisfy(p -> {
            assertThat(p.medianeSecondes()).isNull();
            assertThat(p.p95Secondes()).isNull();
        });
    }

    @Test
    void lesJoursSontContigusEtOrdonnesDuPlusAncienAuPlusRecent() {
        SeriesView vue = service.calcule(null, 30);

        for (int i = 1; i < vue.volume().size(); i++) {
            assertThat(vue.volume().get(i).jour())
                    .isEqualTo(vue.volume().get(i - 1).jour().plusDays(1));
        }
        // Les trois series partagent exactement les memes jours : le frontend les dessine
        // sur un axe commun.
        for (int i = 0; i < vue.volume().size(); i++) {
            assertThat(vue.delais().get(i).jour()).isEqualTo(vue.volume().get(i).jour());
            assertThat(vue.intentions().get(i).jour()).isEqualTo(vue.volume().get(i).jour());
        }
    }

    @Test
    void refuseUneFenetreHorsDesTroisValeursAdmises() {
        // Sans borne, un appel a 100 000 jours ferait balayer la table entiere.
        for (int jours : new int[] {0, 1, 31, 100_000, -7}) {
            assertThatThrownBy(() -> service.calcule(null, jours))
                    .isInstanceOf(FenetreInvalideException.class);
        }
    }

    @Test
    void accepteLesTroisFenetres() {
        assertThat(service.calcule(null, 7).volume()).hasSize(7);
        assertThat(service.calcule(null, 30).volume()).hasSize(30);
        assertThat(service.calcule(null, 90).volume()).hasSize(90);
    }
}
```

- [ ] **Step 2: Lancer le test pour verifier qu'il echoue**

Run: `cd backend && ./mvnw test -Dtest=SeriesServiceTest`
Expected: FAIL a la compilation — `SeriesService`, `SeriesView` et `FenetreInvalideException`
n'existent pas.

- [ ] **Step 3: Ecrire les DTO, l'exception et le service**

```java
package com.leadflow.monitoring.dto;

import java.time.LocalDate;

/**
 * Une journee de capture.
 *
 * <p>{@code jour} est une {@link LocalDate} et non un {@code Instant} : le point represente
 * une journee entiere du fuseau de regroupement, et rendre un instant laisserait croire a une
 * precision qui n'existe pas.
 */
public record PointVolume(LocalDate jour, long captures, long ecartes) {
}
```

```java
package com.leadflow.monitoring.dto;

import java.time.LocalDate;

/**
 * Le delai capture -> ERP d'une journee, en secondes.
 *
 * <p>Les deux mesures sont des {@link Double} et non des {@code double}, seuls champs des
 * trois records de series dans ce cas : {@code null} y veut dire « aucun lead synchronise ce
 * jour-la », et le primitif le lierait a zero — soit « synchronise instantanement », l'inverse
 * du sens. Meme raison que {@code ScoringForm.seuilNotification} en F12.
 */
public record PointDelai(LocalDate jour, Double medianeSecondes, Double p95Secondes) {
}
```

```java
package com.leadflow.monitoring.dto;

import java.time.LocalDate;

/**
 * Une journee d'analyse d'intention, repartie entre le modele et le lexique.
 *
 * <p>Le total des deux n'est pas le nombre de leads du jour : un lead sans source d'intention
 * ne compte nulle part.
 */
public record PointIntention(LocalDate jour, long gemini, long lexique) {
}
```

```java
package com.leadflow.monitoring.dto;

import java.util.List;

/**
 * Les trois series de l'ecran d'analyse en un appel.
 *
 * <p>Meme parti que {@link StatsView} : l'ecran se lit d'un bloc, et les trois figures
 * partagent la meme periode et la meme boutique. Trois endpoints auraient fait trois
 * allers-retours a chaque changement de periode.
 *
 * <p>Les trois listes portent exactement les memes jours, dans le meme ordre : le frontend
 * les dessine sur un axe commun.
 */
public record SeriesView(
        List<PointVolume> volume,
        List<PointDelai> delais,
        List<PointIntention> intentions) {
}
```

```java
package com.leadflow.monitoring;

/**
 * Fenetre d'analyse hors des trois valeurs admises.
 *
 * <p>La borne n'est pas cosmetique : sans elle, un appel a 100 000 jours ferait balayer les
 * tables entieres. C'est le meme reflexe qui a donne sa pagination a la liste des leads.
 */
public class FenetreInvalideException extends RuntimeException {

    public FenetreInvalideException(int jours) {
        super("Fenetre d'analyse invalide : " + jours + " jours. Valeurs admises : 7, 30, 90.");
    }
}
```

```java
package com.leadflow.monitoring;

import com.leadflow.config.AnalyticsProperties;
import com.leadflow.monitoring.dto.PointDelai;
import com.leadflow.monitoring.dto.PointIntention;
import com.leadflow.monitoring.dto.PointVolume;
import com.leadflow.monitoring.dto.SeriesView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Les series quotidiennes de l'ecran d'analyse.
 *
 * <p>Deux responsabilites, et elles sont ici plutot qu'en SQL a dessein. <strong>La borne de
 * fenetre</strong> d'abord : sans elle, un appel a 100 000 jours balaierait les tables
 * entieres. <strong>Le comblement des trous</strong> ensuite : une journee sans donnee n'a pas
 * de ligne en base, et un graphique tracerait une droite par-dessus, ce qui est un mensonge.
 *
 * <p>La valeur de comblement differe selon la figure, et c'est le point le plus facile a
 * « simplifier » plus tard en mettant zero partout. Volume et intentions : <strong>zero</strong>,
 * « aucun lead capture ce jour-la » etant un fait. Delais : <strong>{@code null}</strong>,
 * « aucun lead synchronise » ne voulant pas dire « delai de zero seconde » — Chart.js
 * interrompt la ligne sur un {@code null}, la mettre a zero dessinerait une chute vers le bas.
 */
@Service
public class SeriesService {

    /** Les trois seules fenetres admises. Voir {@link FenetreInvalideException}. */
    private static final Set<Integer> FENETRES = Set.of(7, 30, 90);

    private final SeriesRepository series;
    private final AnalyticsProperties reglages;

    public SeriesService(SeriesRepository series, AnalyticsProperties reglages) {
        this.series = series;
        this.reglages = reglages;
    }

    @Transactional(readOnly = true)
    public SeriesView calcule(UUID clientId, int jours) {
        if (!FENETRES.contains(jours)) {
            throw new FenetreInvalideException(jours);
        }

        ZoneId zone = reglages.zone();
        String fuseau = reglages.fuseau();
        LocalDate fin = LocalDate.now(zone);
        LocalDate debut = fin.minusDays(jours - 1L);
        Instant depuis = debut.atStartOfDay(zone).toInstant();
        String cle = clientId == null ? null : clientId.toString();

        List<LocalDate> calendrier = calendrier(debut, jours);

        Map<LocalDate, PointVolumeBrut> volumes =
                indexe(series.volumeParJour(cle, depuis, fuseau), PointVolumeBrut::getJour);
        Map<LocalDate, PointDelaiBrut> delais =
                indexe(series.delaisParJour(cle, depuis, fuseau), PointDelaiBrut::getJour);
        Map<LocalDate, PointIntentionBrut> intentions =
                indexe(series.intentionsParJour(cle, depuis, fuseau),
                        PointIntentionBrut::getJour);

        return new SeriesView(
                calendrier.stream()
                        .map(j -> {
                            PointVolumeBrut brut = volumes.get(j);
                            return brut == null
                                    ? new PointVolume(j, 0L, 0L)
                                    : new PointVolume(j, brut.getCaptures(), brut.getEcartes());
                        })
                        .toList(),
                calendrier.stream()
                        .map(j -> {
                            PointDelaiBrut brut = delais.get(j);
                            // null et non zero : voir le Javadoc de la classe.
                            return brut == null
                                    ? new PointDelai(j, null, null)
                                    : new PointDelai(
                                            j,
                                            brut.getMedianeSecondes(),
                                            brut.getP95Secondes());
                        })
                        .toList(),
                calendrier.stream()
                        .map(j -> {
                            PointIntentionBrut brut = intentions.get(j);
                            return brut == null
                                    ? new PointIntention(j, 0L, 0L)
                                    : new PointIntention(
                                            j, brut.getGemini(), brut.getLexique());
                        })
                        .toList());
    }

    private static List<LocalDate> calendrier(LocalDate debut, int jours) {
        List<LocalDate> dates = new ArrayList<>(jours);
        for (int i = 0; i < jours; i++) {
            dates.add(debut.plusDays(i));
        }
        return dates;
    }

    private static <T> Map<LocalDate, T> indexe(List<T> points, Function<T, LocalDate> jour) {
        return points.stream().collect(Collectors.toMap(jour, Function.identity()));
    }
}
```

Enfin, dans `ApiExceptionHandler`, a cote de `reglageManquant` :

```java
    @ExceptionHandler(FenetreInvalideException.class)
    ProblemDetail fenetreInvalide(FenetreInvalideException echec) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, echec.getMessage());
    }
```

avec l'import `com.leadflow.monitoring.FenetreInvalideException`.

- [ ] **Step 4: Lancer le test pour verifier qu'il passe**

Run: `cd backend && ./mvnw test -Dtest=SeriesServiceTest`
Expected: PASS, 5 tests.

Ces tests s'appuient sur une base vide — c'est ce qui rend le comblement observable. Si un test
precedent a laisse des lignes, `combleAZeroLeVolumeMaisANullLeDelai` echouera ; verifier que
les `@AfterEach` des autres classes nettoient bien.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/SeriesService.java \
        backend/src/main/java/com/leadflow/monitoring/FenetreInvalideException.java \
        backend/src/main/java/com/leadflow/monitoring/dto/ \
        backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java \
        backend/src/test/java/com/leadflow/monitoring/SeriesServiceTest.java
git commit -m "feat: les trous combles, et la valeur de comblement qui depend de la figure

Volume et intentions a zero, delais a null. Aucun lead capture ce jour-la
est un fait ; aucun lead synchronise ne veut pas dire delai nul, et mis a
zero la courbe dessinerait une chute vers le bas, soit l'inverse du sens.

La fenetre est bornee a 7, 30 ou 90 jours : sans borne, un appel a 100 000
jours balaierait les tables entieres."
```

---

### Task 7 : l'endpoint, et son contrat documente

**Files:**
- Modify: `backend/src/main/java/com/leadflow/monitoring/StatsController.java`
- Modify: `docs/monitoring-api.md`
- Test: `backend/src/test/java/com/leadflow/monitoring/SeriesControllerTest.java`

**Interfaces:**
- Consumes: `SeriesService.calcule(UUID, int)` (tache 6).
- Produces: `GET /api/stats/series?clientId=<uuid>&jours=<7|30|90>` rendant le JSON de
  `SeriesView`. La tache 9 l'appelle.

**Contexte que l'implementeur n'a pas :** `StatsController` porte deja
`@RequestMapping("/api/stats")` et une methode `stats(...)` sur `@GetMapping` nu. La nouvelle
route s'ajoute dans cette meme classe — un controleur de plus pour une route soeur eparpillerait
le contrat. Les tests de controleur du projet assertent **sur le corps JSON**, pas sur le DTO :
un test sur le record ne prouverait pas qu'aucune entite ne franchit la frontiere HTTP.
Regarder `LeadTimelineControllerTest` pour le montage exact (`MockMvc`, jeton, `@WithMockUser`
ou equivalent).

- [ ] **Step 1: Ecrire le test qui echoue**

```java
package com.leadflow.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SeriesControllerTest {

    @Autowired private MockMvc mvc;

    @Test
    void rendLesTroisSeriesAuGrainJour() throws Exception {
        mvc.perform(get("/api/stats/series").param("jours", "7").with(jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volume.length()").value(7))
                .andExpect(jsonPath("$.delais.length()").value(7))
                .andExpect(jsonPath("$.intentions.length()").value(7))
                // Une date, pas un instant : le point represente une journee entiere.
                .andExpect(jsonPath("$.volume[0].jour").value(
                        org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}")));
    }

    @Test
    void laFenetreParDefautEstDeTrenteJours() throws Exception {
        mvc.perform(get("/api/stats/series").with(jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volume.length()").value(30));
    }

    @Test
    void refuseUneFenetreHorsDesTroisValeurs() throws Exception {
        mvc.perform(get("/api/stats/series").param("jours", "365").with(jeton()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exigeUneAuthentification() throws Exception {
        mvc.perform(get("/api/stats/series")).andExpect(status().isUnauthorized());
    }
}
```

**Avertissement :** `jeton()` est un marque-place. Ouvrir
`backend/src/test/java/com/leadflow/monitoring/LeadTimelineControllerTest.java` et **recopier
son mecanisme d'authentification exact** — le projet est stateless avec un JWT HS256, et la
facon de le poser dans `MockMvc` y est deja resolue.

- [ ] **Step 2: Lancer le test pour verifier qu'il echoue**

Run: `cd backend && ./mvnw test -Dtest=SeriesControllerTest`
Expected: FAIL — `404` sur `/api/stats/series`, la route n'existant pas.

- [ ] **Step 3: Ajouter la route**

Dans `StatsController` :

```java
    /**
     * Les trois series quotidiennes de l'ecran d'analyse.
     *
     * <p>{@code jours} est borne cote serveur a 7, 30 ou 90 par {@code SeriesService} : sans
     * borne, un appel a 100 000 jours balaierait les tables entieres. Le defaut est 30 —
     * assez pour voir une tendance, assez court pour qu'une instance de demonstration ait des
     * donnees.
     */
    @GetMapping("/series")
    public SeriesView series(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(defaultValue = "30") int jours) {
        return seriesService.calcule(clientId, jours);
    }
```

avec le champ `private final SeriesService seriesService;` ajoute au constructeur existant, et
les imports `com.leadflow.monitoring.dto.SeriesView`.

- [ ] **Step 4: Lancer le test pour verifier qu'il passe**

Run: `cd backend && ./mvnw test -Dtest=SeriesControllerTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Documenter le contrat**

Dans `docs/monitoring-api.md`, a la suite de la section de `GET /api/stats`, en suivant le
format exact des autres entrees (verifier comment elles presentent le `curl` et la reponse) :

```markdown
### `GET /api/stats/series`

Les trois series quotidiennes de l'ecran d'analyse, en un appel.

| Parametre | Type | Defaut | Role |
| --- | --- | --- | --- |
| `clientId` | UUID | absent | Restreint a une boutique. Absent : toutes. |
| `jours` | entier | `30` | Fenetre. **7, 30 ou 90 uniquement** — `400` sinon. |

```bash
curl -s -H "Authorization: Bearer $JETON" \
  'http://localhost:8090/api/stats/series?jours=7'
```

```json
{
  "volume": [{ "jour": "2026-02-26", "captures": 12, "ecartes": 1 }],
  "delais": [{ "jour": "2026-02-26", "medianeSecondes": 4.2, "p95Secondes": 31.7 }],
  "intentions": [{ "jour": "2026-02-26", "gemini": 9, "lexique": 3 }]
}
```

Trois choses a savoir en lisant cette reponse.

**Les trois listes portent exactement les memes jours, dans le meme ordre**, et il y en a
toujours `jours` — les journees sans donnee sont comblees par le serveur. Une journee absente
laisserait le client tracer une droite par-dessus.

**`medianeSecondes` et `p95Secondes` peuvent etre `null`**, et cela veut dire « aucun lead
synchronise ce jour-la ». Ce n'est pas un delai de zero seconde : afficher zero dessinerait une
chute vers le bas, soit l'inverse du sens.

**Le decoupage en journees se fait dans le fuseau de l'instance** (`leadflow.analytics.fuseau`,
`Europe/Paris` par defaut), et non en UTC. Le point du jour J de `delais` agrege les leads
**synchronises** ce jour-la, pas ceux captures.
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/leadflow/monitoring/StatsController.java \
        backend/src/test/java/com/leadflow/monitoring/SeriesControllerTest.java \
        docs/monitoring-api.md
git commit -m "feat: GET /api/stats/series, les trois series en un appel

Meme parti que StatsView : l'ecran se lit d'un bloc et les trois figures
partagent periode et boutique. Le test asserte sur le corps JSON et non
sur le DTO, qui ne prouverait pas qu'aucune entite ne franchit la
frontiere HTTP."
```

---

### Task 8 : le modele et le service d'appel cote frontend

**Files:**
- Modify: `frontend/src/app/core/models/monitoring.ts`
- Modify: `frontend/src/app/core/api/stats-api.ts`
- Test: `frontend/src/app/core/api/stats-api.spec.ts` (creer)

**Interfaces:**
- Consumes: `GET /api/stats/series` (tache 7).
- Produces: les interfaces `SeriesView`, `PointVolume`, `PointDelai`, `PointIntention`, et
  `StatsApi.series(clientId: string | undefined, jours: number)` rendant
  `Observable<SeriesView>`. La tache 10 les consomme.

**Contexte que l'implementeur n'a pas :** `environment.apiBaseUrl` est volontairement vide dans
les deux environnements — les services appellent des **chemins relatifs** (`/api/stats/series`),
jamais une URL absolue. `StatsApi` suit deja la regle « un parametre absent ne part pas » ;
la reprendre telle quelle.

- [ ] **Step 1: Ecrire le test qui echoue**

```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { StatsApi } from './stats-api';
import { SeriesView } from '../models/monitoring';

describe('StatsApi', () => {
  let api: StatsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(StatsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('envoie la fenetre et la boutique en parametres', () => {
    api.series('11111111-1111-1111-1111-111111111111', 7).subscribe();

    const requete = http.expectOne(
      (r) =>
        r.url === '/api/stats/series' &&
        r.params.get('jours') === '7' &&
        r.params.get('clientId') === '11111111-1111-1111-1111-111111111111',
    );
    expect(requete.request.method).toBe('GET');
    requete.flush({ volume: [], delais: [], intentions: [] } as SeriesView);
  });

  it("n'envoie pas de boutique quand aucune n'est choisie", () => {
    // Un parametre absent ne part pas, sans quoi le serveur ajouterait un predicat pour un
    // filtre que personne n'a demande.
    api.series(undefined, 30).subscribe();

    const requete = http.expectOne((r) => r.url === '/api/stats/series');
    expect(requete.request.params.has('clientId')).toBeFalse();
    expect(requete.request.params.get('jours')).toBe('30');
    requete.flush({ volume: [], delais: [], intentions: [] } as SeriesView);
  });
});
```

- [ ] **Step 2: Lancer le test pour verifier qu'il echoue**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL a la compilation TypeScript — `series` et `SeriesView` n'existent pas.

- [ ] **Step 3: Ecrire le modele et la methode**

Dans `frontend/src/app/core/models/monitoring.ts`, a la suite de `StatsView` :

```typescript
/**
 * Une journee de capture.
 *
 * `jour` est une date ISO `YYYY-MM-DD`, pas un instant : le point represente une journee
 * entiere du fuseau de l'instance.
 */
export interface PointVolume {
  jour: string;
  captures: number;
  ecartes: number;
}

/**
 * Le delai capture -> ERP d'une journee, en secondes.
 *
 * Les deux mesures sont nullables, et `null` veut dire « aucun lead synchronise ce jour-la ».
 * Ne jamais le remplacer par zero a l'affichage : la courbe dessinerait une chute vers le bas,
 * soit l'inverse du sens. Chart.js interrompt la ligne sur un `null`.
 */
export interface PointDelai {
  jour: string;
  medianeSecondes: number | null;
  p95Secondes: number | null;
}

/** Une journee d'analyse, repartie entre le modele et le lexique. */
export interface PointIntention {
  jour: string;
  gemini: number;
  lexique: number;
}

/** Les trois series de l'ecran d'analyse. Memes jours, meme ordre, dans les trois listes. */
export interface SeriesView {
  volume: PointVolume[];
  delais: PointDelai[];
  intentions: PointIntention[];
}
```

Dans `stats-api.ts`, dans la classe `StatsApi` :

```typescript
  /** Les trois series quotidiennes. `jours` vaut 7, 30 ou 90 — le serveur refuse le reste. */
  series(clientId: string | undefined, jours: number) {
    let params = new HttpParams().set('jours', String(jours));
    if (clientId !== undefined && clientId !== null && clientId !== '') {
      params = params.set('clientId', clientId);
    }
    return this.http.get<SeriesView>('/api/stats/series', { params });
  }
```

avec `SeriesView` ajoute a l'import depuis `../models/monitoring`.

- [ ] **Step 4: Lancer le test pour verifier qu'il passe**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS — 64 tests (62 existants + 2).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/core/models/monitoring.ts \
        frontend/src/app/core/api/stats-api.ts \
        frontend/src/app/core/api/stats-api.spec.ts
git commit -m "feat: le modele et l appel des series cote frontend

Les mesures de delai sont nullables jusque dans le modele TypeScript :
null veut dire aucun lead synchronise ce jour-la, et le remplacer par zero
a l affichage inverserait le sens de la courbe."
```

---

### Task 9 : le composant qui encapsule Chart.js

**Files:**
- Modify: `frontend/package.json` (dependance `chart.js`)
- Create: `frontend/src/app/features/analyse/graphique-ligne/graphique-ligne.ts`
- Test: `frontend/src/app/features/analyse/graphique-ligne/graphique-ligne.spec.ts`

**Interfaces:**
- Consumes: rien du backend.
- Produces: le composant `GraphiqueLigne`, selecteur `app-graphique-ligne`, avec les entrees
  `libelles: string[]`, `series: SerieGraphique[]`, `uniteY: string`, et le type exporte
  `SerieGraphique { nom: string; valeurs: (number | null)[]; couleur: string; remplie: boolean }`.
  La tache 10 le consomme.

**Contexte que l'implementeur n'a pas :**

- **Pas de `ng2-charts`.** Un wrapper ajouterait une peerDependency a faire correspondre a
  chaque montee d'Angular — precisement la crainte inscrite dans le Javadoc du dashboard, qui
  justifiait l'absence de bibliotheque. On importe `chart.js` directement.
- Chart.js 4 est modulaire : sans `Chart.register(...)`, rien ne se dessine et **aucune erreur
  n'est levee**. Il faut enregistrer explicitement les controleurs et echelles utilises.
- Angular 20 standalone, signals, et la convention de nommage sans suffixe de type :
  `graphique-ligne.ts` exporte `GraphiqueLigne`.

- [ ] **Step 1: Installer la dependance**

```bash
cd frontend && npm install chart.js@^4
```

Verifier que la version installee est bien une 4.x et que `package-lock.json` est modifie.
**Ne pas installer `ng2-charts`.**

- [ ] **Step 2: Ecrire le test qui echoue**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GraphiqueLigne, SerieGraphique } from './graphique-ligne';

describe('GraphiqueLigne', () => {
  let fixture: ComponentFixture<GraphiqueLigne>;

  const serie: SerieGraphique[] = [
    { nom: 'Mediane', valeurs: [1, null, 3], couleur: '#334155', remplie: false },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [GraphiqueLigne] }).compileComponents();
    fixture = TestBed.createComponent(GraphiqueLigne);
  });

  it('rend un canvas', () => {
    fixture.componentRef.setInput('libelles', ['lun', 'mar', 'mer']);
    fixture.componentRef.setInput('series', serie);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('canvas')).toBeTruthy();
  });

  it('detruit son instance Chart a la destruction du composant', () => {
    fixture.componentRef.setInput('libelles', ['lun', 'mar', 'mer']);
    fixture.componentRef.setInput('series', serie);
    fixture.detectChanges();

    // Sans cela, chaque navigation vers l'ecran laisse une instance vivante, avec son
    // ecouteur de redimensionnement.
    const instance = fixture.componentInstance;
    const graphique = (instance as unknown as { graphique?: { destroy: () => void } })
      .graphique;
    expect(graphique).toBeTruthy();
    const espion = spyOn(graphique!, 'destroy').and.callThrough();

    fixture.destroy();

    expect(espion).toHaveBeenCalled();
  });
});
```

- [ ] **Step 3: Lancer le test pour verifier qu'il echoue**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL a la compilation — `GraphiqueLigne` n'existe pas.

- [ ] **Step 4: Ecrire le composant**

```typescript
import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  effect,
  input,
  viewChild,
} from '@angular/core';
import {
  CategoryScale,
  Chart,
  Filler,
  Legend,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  Tooltip,
} from 'chart.js';

/** Une serie a dessiner. `null` interrompt la ligne, il ne vaut jamais zero. */
export interface SerieGraphique {
  nom: string;
  valeurs: (number | null)[];
  couleur: string;
  remplie: boolean;
}

// Chart.js 4 est modulaire : sans cet enregistrement, rien ne se dessine et aucune erreur
// n'est levee. Il est fait une fois pour le module, pas a chaque instance.
Chart.register(
  LineController,
  LineElement,
  PointElement,
  LinearScale,
  CategoryScale,
  Filler,
  Legend,
  Tooltip,
);

/**
 * Le seul composant du projet qui connaisse Chart.js.
 *
 * L'ecran d'analyse ne l'importe jamais : il passe des libelles, des series et des couleurs.
 * C'est ce qui rend la bibliotheque remplacable et le reste de l'ecran testable sans elle —
 * meme geste que `CrmConnector` derriere son port, a une autre echelle.
 *
 * L'instance est detruite au retrait du composant : laissee vivante, elle garde un ecouteur
 * de redimensionnement a chaque navigation vers l'ecran.
 */
@Component({
  selector: 'app-graphique-ligne',
  template: '<canvas #toile></canvas>',
  styles: ':host { display: block; position: relative; height: 260px; }',
})
export class GraphiqueLigne implements AfterViewInit, OnDestroy {
  readonly libelles = input.required<string[]>();
  readonly series = input.required<SerieGraphique[]>();
  readonly uniteY = input<string>('');

  private readonly toile = viewChild.required<ElementRef<HTMLCanvasElement>>('toile');
  private graphique?: Chart;

  constructor() {
    effect(() => {
      const libelles = this.libelles();
      const series = this.series();
      if (this.graphique) {
        this.graphique.data.labels = libelles;
        this.graphique.data.datasets = this.jeux(series);
        this.graphique.update();
      }
    });
  }

  ngAfterViewInit(): void {
    this.graphique = new Chart(this.toile().nativeElement, {
      type: 'line',
      data: { labels: this.libelles(), datasets: this.jeux(this.series()) },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        // Une valeur nulle interrompt la ligne au lieu d'etre franchie d'un trait : c'est
        // ce qui distingue « aucune donnee » de « valeur nulle ».
        spanGaps: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { position: 'bottom' },
          tooltip: {
            callbacks: {
              label: (ctx) =>
                `${ctx.dataset.label} : ${ctx.formattedValue}${
                  this.uniteY() ? ' ' + this.uniteY() : ''
                }`,
            },
          },
        },
        scales: { y: { beginAtZero: true } },
      },
    });
  }

  ngOnDestroy(): void {
    this.graphique?.destroy();
  }

  private jeux(series: SerieGraphique[]) {
    return series.map((s) => ({
      label: s.nom,
      data: s.valeurs,
      borderColor: s.couleur,
      backgroundColor: s.remplie ? s.couleur + '33' : s.couleur,
      fill: s.remplie,
      tension: 0.25,
      pointRadius: 2,
    }));
  }
}
```

- [ ] **Step 5: Lancer le test pour verifier qu'il passe**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS — 66 tests.

Si le second test echoue parce que `graphique` est prive et inaccessible, garder le cast
`as unknown as { graphique?: ... }` du test — le champ reste prive dans le composant, et le
test n'a pas a le rendre public.

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/package-lock.json \
        frontend/src/app/features/analyse/graphique-ligne/
git commit -m "feat: le seul composant qui connaisse Chart.js

Sans ng2-charts : un wrapper ajouterait une peerDependency a faire
correspondre a chaque montee d'Angular, la crainte meme qui justifiait
l'absence de bibliotheque. spanGaps est a false pour qu'une valeur nulle
interrompe la ligne au lieu d'etre franchie d'un trait."
```

---

### Task 10 : l'ecran « Analyse », sa route et sa place dans la navigation

**Files:**
- Create: `frontend/src/app/features/analyse/analyse.ts`
- Create: `frontend/src/app/features/analyse/analyse.html`
- Create: `frontend/src/app/features/analyse/analyse.scss`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/app.ts:39-44` (les entrees de navigation)
- Test: `frontend/src/app/features/analyse/analyse.spec.ts`

**Interfaces:**
- Consumes: `StatsApi.series(...)` et les modeles (tache 8), `GraphiqueLigne` et
  `SerieGraphique` (tache 9), `ClientApi` (existant).
- Produces: le composant `Analyse` sur la route `/analyse`.

**Contexte que l'implementeur n'a pas :**

- **Le visuel passe par le plugin.** Avant d'ecrire `analyse.html` et `analyse.scss`, invoquer
  `ui-ux-pro-max:ui-styling` pour la mise en page des trois cartes et `dataviz` pour les
  couleurs des series. Les couleurs se **derivent des jetons `--lf-*`** de
  `frontend/src/styles.scss` (`--lf-succes`, `--lf-attente`, `--lf-echec`, `--lf-neutre`) —
  choisies a part, l'ecran ne ressemblerait pas au reste de la console. Il n'y a pas de theme
  sombre : une seule palette a tenir.
- Chaque route utilise `loadComponent`, ce qui met la feature dans un chunk separe. C'est ce qui
  garde Chart.js hors du chunk d'accueil.
- Le selecteur de boutique du dashboard (`ClientApi`, signal `clientId`) se reprend tel quel —
  ouvrir `frontend/src/app/features/dashboard/dashboard.ts` et le recopier plutot que
  l'inventer.
- Les entrees de navigation vivent dans `app.ts`, sous la forme
  `{ chemin: '/dashboard', libelle: 'Dashboard', icone: 'monitoring' }`. L'icone est un nom de
  ligature Material Symbols — `insights` convient. **Ne pas inventer un nom d'icone** : verifier
  qu'il existe dans la police servie depuis `frontend/src/fonts/`, sinon rien ne s'affiche (la
  police est a ligatures, un nom inconnu ne rend aucun glyphe).

- [ ] **Step 1: Invoquer les skills visuels**

Avant toute ligne de template ou de SCSS : `ui-ux-pro-max:ui-styling` pour la structure des
trois cartes et le groupe de boutons de periode, puis `dataviz` pour la palette des series
(contraste, distinction sans dependre de la teinte seule). Noter les decisions retenues dans le
commit final.

- [ ] **Step 2: Ecrire le test qui echoue**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Analyse } from './analyse';
import { SeriesView } from '../../core/models/monitoring';

describe('Analyse', () => {
  let fixture: ComponentFixture<Analyse>;
  let http: HttpTestingController;

  const vide: SeriesView = { volume: [], delais: [], intentions: [] };

  const peuplee: SeriesView = {
    volume: [{ jour: '2026-03-02', captures: 5, ecartes: 1 }],
    delais: [{ jour: '2026-03-02', medianeSecondes: 4.2, p95Secondes: 30 }],
    intentions: [{ jour: '2026-03-02', gemini: 4, lexique: 1 }],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Analyse],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(Analyse);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function repondAuxAppels(vue: SeriesView) {
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/admin/clients' || r.url === '/api/clients').flush([]);
    http.expectOne((r) => r.url === '/api/stats/series').flush(vue);
    fixture.detectChanges();
  }

  it('demande trente jours par defaut', () => {
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/admin/clients' || r.url === '/api/clients').flush([]);
    const requete = http.expectOne((r) => r.url === '/api/stats/series');
    expect(requete.request.params.get('jours')).toBe('30');
    requete.flush(vide);
  });

  it("affiche un etat vide plutot qu'un graphique plat", () => {
    // Un canvas plat se lit comme une panne. L'absence de donnee doit se dire.
    repondAuxAppels(vide);

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('Aucune donnee');
    expect(fixture.nativeElement.querySelector('canvas')).toBeNull();
  });

  it('dessine les trois figures quand il y a des donnees', () => {
    repondAuxAppels(peuplee);

    expect(fixture.nativeElement.querySelectorAll('canvas').length).toBe(3);
  });
});
```

**Avertissement :** l'URL du referentiel des boutiques est un marque-place
(`/api/admin/clients` ou `/api/clients`). Ouvrir `frontend/src/app/core/api/client-api.ts` et
**utiliser l'URL reelle**, sans quoi `http.verify()` echouera sur une requete non satisfaite.

- [ ] **Step 3: Lancer le test pour verifier qu'il echoue**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL a la compilation — `Analyse` n'existe pas.

- [ ] **Step 4: Ecrire le composant**

```typescript
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ClientApi } from '../../core/api/client-api';
import { StatsApi } from '../../core/api/stats-api';
import { ClientSummary, SeriesView } from '../../core/models/monitoring';
import { GraphiqueLigne, SerieGraphique } from './graphique-ligne/graphique-ligne';

/** Les trois fenetres admises par le serveur. Toute autre valeur rend `400`. */
const FENETRES = [7, 30, 90] as const;

/**
 * Ecran d'analyse : ce que les compteurs du dashboard ne savent pas dire, faute de temps.
 *
 * Trois figures, chacune repondant a une question qu'aucun chiffre de la console ne repond.
 * Le taux d'echec ERP n'y est pas : l'ecran des connecteurs le sert deja, et un graphique qui
 * redit un compteur affiche ailleurs n'apporte rien.
 *
 * Cet ecran n'importe jamais Chart.js — seul `GraphiqueLigne` le connait.
 */
@Component({
  selector: 'app-analyse',
  imports: [GraphiqueLigne],
  templateUrl: './analyse.html',
  styleUrl: './analyse.scss',
})
export class Analyse implements OnInit {
  private readonly api = inject(StatsApi);
  private readonly clientApi = inject(ClientApi);

  readonly fenetres = FENETRES;
  readonly jours = signal<number>(30);
  readonly clientId = signal<string | undefined>(undefined);
  readonly boutiques = signal<ClientSummary[]>([]);
  readonly donnees = signal<SeriesView | null>(null);
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);

  /** Vide au sens de l'ecran : aucune journee de la fenetre ne porte quoi que ce soit. */
  readonly vide = computed(() => {
    const d = this.donnees();
    if (!d) {
      return false;
    }
    return d.volume.every((p) => p.captures === 0);
  });

  readonly libelles = computed(() => (this.donnees()?.volume ?? []).map((p) => p.jour));

  readonly serieVolume = computed<SerieGraphique[]>(() => {
    const d = this.donnees();
    if (!d) {
      return [];
    }
    return [
      {
        nom: 'Leads produits',
        valeurs: d.volume.map((p) => p.captures - p.ecartes),
        couleur: 'var(--lf-succes)',
        remplie: true,
      },
      {
        nom: 'Ecartes',
        valeurs: d.volume.map((p) => p.ecartes),
        couleur: 'var(--lf-echec)',
        remplie: true,
      },
    ];
  });

  readonly serieDelais = computed<SerieGraphique[]>(() => {
    const d = this.donnees();
    if (!d) {
      return [];
    }
    // Les nulls sont transmis tels quels : ils interrompent la ligne. Les remplacer par
    // zero dessinerait une chute vers le bas, soit l'inverse du sens.
    return [
      {
        nom: 'Mediane',
        valeurs: d.delais.map((p) => p.medianeSecondes),
        couleur: 'var(--lf-neutre)',
        remplie: false,
      },
      {
        nom: '95e centile',
        valeurs: d.delais.map((p) => p.p95Secondes),
        couleur: 'var(--lf-attente)',
        remplie: false,
      },
    ];
  });

  readonly serieIntentions = computed<SerieGraphique[]>(() => {
    const d = this.donnees();
    if (!d) {
      return [];
    }
    return [
      {
        nom: 'Gemini',
        valeurs: d.intentions.map((p) => p.gemini),
        couleur: 'var(--lf-succes)',
        remplie: true,
      },
      {
        nom: 'Lexique',
        valeurs: d.intentions.map((p) => p.lexique),
        couleur: 'var(--lf-neutre)',
        remplie: true,
      },
    ];
  });

  ngOnInit(): void {
    this.clientApi.clients().subscribe({
      next: (liste) => this.boutiques.set(liste),
      error: () => this.boutiques.set([]),
    });
    this.charge();
  }

  choisitFenetre(jours: number): void {
    this.jours.set(jours);
    this.charge();
  }

  choisitBoutique(clientId: string | undefined): void {
    this.clientId.set(clientId);
    this.charge();
  }

  private charge(): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.api.series(this.clientId(), this.jours()).subscribe({
      next: (vue) => {
        this.donnees.set(vue);
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set("L'analyse n'a pas pu etre chargee.");
        this.enCours.set(false);
      },
    });
  }
}
```

**Avertissement :** `ClientApi.clients()` est un marque-place — ouvrir `client-api.ts` et
utiliser la methode reelle, celle que `dashboard.ts` appelle deja.

Le template `analyse.html` porte trois cartes, chacune avec son titre, sa phrase d'explication
et son `<app-graphique-ligne>`, plus le groupe de boutons de periode et le selecteur de
boutique. Sa structure et son SCSS viennent des skills de l'etape 1. **La condition de l'etat
vide doit entourer les trois graphiques** : le test exige qu'aucun `<canvas>` n'existe quand la
serie est vide.

Puis, dans `app.routes.ts`, avant la route `**` :

```typescript
  {
    path: 'analyse',
    title: 'Analyse',
    canActivate: [authGuard],
    loadComponent: () => import('./features/analyse/analyse').then((m) => m.Analyse),
  },
```

et dans `app.ts`, apres l'entree du dashboard :

```typescript
    { chemin: '/analyse', libelle: 'Analyse', icone: 'insights' },
```

- [ ] **Step 5: Lancer le test pour verifier qu'il passe**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS — 69 tests.

- [ ] **Step 6: Verifier que Chart.js reste hors du chunk d'accueil**

```bash
cd frontend && npm run build
```

Chercher dans la sortie le chunk de la feature `analyse` et verifier qu'il est **separe** du
chunk principal, et que sa taille reflete l'ajout de Chart.js (de l'ordre de 60 a 80 ko
compresses). Si Chart.js apparait dans le bundle initial, c'est qu'un import a fui hors du
composant `GraphiqueLigne` — le chercher avec
`grep -rn "from 'chart.js'" src/` : il ne doit y avoir **qu'une seule** occurrence.

- [ ] **Step 7: Verifier le lint et le format**

```bash
cd frontend && npm run lint && npm run format:check
```

Le job `qualite` de la CI **bloque** : un constat ESLint ou un fichier mal formate fait echouer
l'execution. `npm run format` corrige le format.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/features/analyse/ frontend/src/app/app.routes.ts \
        frontend/src/app/app.ts
git commit -m "feat: l ecran Analyse, ses trois figures et son chunk separe

En loadComponent, donc Chart.js n entre jamais dans le chunk d accueil.
L etat vide est un vrai etat : un canvas plat se lit comme une panne.
Les valeurs nulles de la courbe des delais sont transmises telles quelles
pour que la ligne s interrompe."
```

---

### Task 11 : la recette a l'ecran

**Files:** aucun — c'est une verification manuelle.

**Interfaces:**
- Consumes: tout ce qui precede.
- Produces: la confirmation que l'ecran fonctionne sur de vraies donnees, ou une liste de
  defauts a corriger avant la tache 12.

**Contexte que l'implementeur n'a pas :** la recette manuelle a l'ecran **revient a
l'utilisateur**, sauf s'il demande explicitement qu'elle soit faite au navigateur. Cette tache
consiste donc a **preparer la recette et a la lui remettre**, pas a la faire.

- [ ] **Step 1: Monter la pile de developpement**

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

**Pas `spring-boot:test-run`** : il demarre en profil `default`, sans compte operateur, et
`src/test/resources/application.properties` y eteint les cinq consommateurs — le pipeline
resterait inerte.

Controle qui tranche en une commande :

```bash
docker exec leadflow-rabbitmq rabbitmqctl list_queues name messages consumers
```

Attendu : **cinq files avec un consommateur chacune**.

Puis, dans un second terminal :

```bash
cd frontend && npm start
```

- [ ] **Step 2: Verifier l'endpoint seul, avant l'ecran**

```bash
curl -s -H "Authorization: Bearer $JETON" \
  'http://localhost:8090/api/stats/series?jours=7' | head -40
```

Attendu : trois listes de sept points, les memes dates dans les trois, et des `null` dans
`delais` pour les journees sans synchronisation.

- [ ] **Step 3: Remettre la recette a l'utilisateur**

Lui donner la liste de ce qu'il faut regarder :

1. `/analyse` s'ouvre depuis la navigation, l'icone est visible (une icone manquante veut dire
   un nom de ligature inconnu de la police).
2. Les trois boutons de periode changent bien les figures, et l'axe passe de 7 a 30 a 90 points.
3. Le selecteur de boutique filtre.
4. Sur une instance ou peu de leads ont ete synchronises, la courbe des delais **s'interrompt**
   au lieu de tomber a zero.
5. Une boutique sans aucun lead affiche l'etat vide, pas trois canvas plats.
6. Les couleurs sont lisibles et coherentes avec le reste de la console.

- [ ] **Step 4: Corriger ce que la recette remonte**

Un commit par defaut corrige, avec un message qui dit ce que la recette a revele — c'est ce que
les sessions precedentes ont fait, et c'est ce qui rend l'historique lisible.

---

### Task 12 : les trois documents, et la verification finale

**Files:**
- Modify: `CLAUDE.md`
- Modify: `backend/src/main/java/com/leadflow/monitoring/dto/StatsView.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/package-info.java`

**Interfaces:**
- Consumes: tout ce qui precede.
- Produces: une branche prete a fusionner.

**Contexte que l'implementeur n'a pas :** le Javadoc de `StatsView` affirme aujourd'hui « Aucune
serie temporelle : sans bibliotheque de graphiques, elle n'aurait aucun consommateur. La requete
par jour s'ajoutera le jour ou un graphique existera. » Cette phrase devient fausse. De meme, le
Javadoc de `Dashboard` (frontend) dit « Pas de bibliotheque de graphiques » — le corriger aussi.

- [ ] **Step 1: Corriger les Javadoc devenus faux**

Dans `StatsView`, remplacer le second paragraphe par :

```java
 * <p>Aucune serie temporelle ici : elles vivent dans {@code SeriesView}, servies par
 * {@code GET /api/stats/series}. La separation n'est pas cosmetique — le dashboard d'accueil
 * paierait sinon le cout du {@code percentile_cont} des delais a chaque chargement, pour des
 * figures qu'il n'affiche pas.
```

Dans `frontend/src/app/features/dashboard/dashboard.ts`, corriger le paragraphe « Pas de
bibliotheque de graphiques » : la bibliotheque existe desormais, mais **cet ecran-ci** n'en
charge aucune, et c'est ce qui garde le chunk d'accueil leger.

Chercher toute autre affirmation devenue fausse :

```bash
grep -rn "graphique" --include=*.java --include=*.ts --include=*.md . | grep -vi "graphique-ligne"
```

- [ ] **Step 2: Mettre a jour `CLAUDE.md`**

Trois endroits :

1. Dans « Ce qui n'existe pas », **supprimer** la puce « **Aucun graphique** : les repartitions
   sont des compteurs et des barres de progression. Aucune bibliotheque de graphiques n'est
   installee, et c'est un choix. »
2. Dans la section « Base de donnees », passer « Neuf migrations existent » a **dix** et decrire
   `V10__analytics_index.sql` — deux index, aucune colonne, l'un des trois supposes existant
   deja depuis `V2`.
3. Dans la section « Monitoring », ajouter un paragraphe sur les series : premier repository
   natif du projet et les deux raisons qui l'imposent, le fuseau de regroupement, l'ancrage des
   delais sur le jour de synchronisation, et la regle de comblement des trous. Mentionner que le
   dashboard compte desormais **onze ecrans**.

Ajouter aussi `LEADFLOW_ANALYTICS_FUSEAU` au tableau des variables d'environnement, en precisant
qu'elle est facultative et vaut `Europe/Paris` par defaut.

- [ ] **Step 3: Lancer la suite backend par lots**

Un run complet n'a jamais survecu sur cette machine. Par lots :

```bash
cd backend
./mvnw test -Dtest='Series*,Analytics*'
./mvnw test -Dtest='com.leadflow.monitoring.*Test'
./mvnw test -Dtest='com.leadflow.capture.*Test'
./mvnw test -Dtest='com.leadflow.qualification.*Test'
./mvnw test -Dtest='com.leadflow.routing.*Test,com.leadflow.crm.*Test'
./mvnw test -Dtest='com.leadflow.tenant.*Test,com.leadflow.notification.*Test,com.leadflow.config.*Test,com.leadflow.common.*Test'
```

Expected: `BUILD SUCCESS` a chaque lot. Le total attendu est de l'ordre de **520 tests**
(503 avant F13, plus une vingtaine).

- [ ] **Step 4: Lancer la suite frontend et les controles de qualite**

```bash
cd frontend
npm test -- --watch=false --browsers=ChromeHeadless
npm run lint
npm run format:check
npm run build
```

Expected: 69 tests verts, aucun constat ESLint, aucun fichier mal formate, build reussi.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md backend/src/main/java/com/leadflow/monitoring/ \
        frontend/src/app/features/dashboard/dashboard.ts
git commit -m "docs: F13 — les series existent, et trois affirmations cessent d etre vraies

Le Javadoc de StatsView annoncait qu'aucune serie temporelle n'aurait de
consommateur sans bibliotheque de graphiques. Elle existe desormais, et
les series vivent a part pour que le dashboard d accueil ne paie pas le
percentile_cont des delais a chaque chargement."
```

- [ ] **Step 6: Consigner l'etat de fin de feature**

Ecrire `docs/superpowers/plans/2026-XX-XX-f13-fin-de-feature.md` sur le modele de
`2026-09-04-f12-fin-de-feature.md` : l'etat du depot, ce que la session a produit, ce qu'il faut
savoir avant d'y toucher, les defauts trouves et par quoi, l'etat de la machine, et la suite.
Le consigner dans un document, jamais seulement dans la conversation.

- [ ] **Step 7: Fusionner**

```bash
git checkout main
git merge --no-ff feature/f13-analytics-graphiques
git push origin main
git push origin feature/f13-analytics-graphiques
```

**Ne pas supprimer la branche** apres la fusion : elle sert d'historique de la feature.

---

## Auto-revue du plan

**Couverture de la spec** — chaque exigence a sa tache :

| Exigence de la spec | Tache |
| --- | --- |
| Trois figures, taux d'echec ERP ecarte | 3, 4, 5, 10 |
| `GET /api/stats/series`, `jours` borne, `clientId` filtre | 6, 7 |
| Records dans `monitoring/dto/`, aucune entite en sortie | 6, 7 |
| `Repository` nu, monitoring observateur | 3 |
| Trois requetes natives, et les deux raisons | 3, 4, 5 |
| Premier succes par lead | 5 |
| Ancrage sur le jour de synchronisation | 5 |
| Fuseau `Europe/Paris`, en constante | 2 |
| Trous combles, zero vs `null` | 6 |
| Migration `V10`, deux index dont un partiel | 1 |
| Route `/analyse` en `loadComponent`, chunk separe | 10 |
| Un seul composant connait Chart.js, detruit son instance | 9 |
| Chart.js empaquete, pas de `ng2-charts` | 9 |
| Etat vide, ligne interrompue | 9, 10 |
| Visuel par `ui-ux-pro-max` + `dataviz`, jetons `--lf-*` | 10 |
| Selecteur de boutique, 30 jours par defaut | 10 |
| Tests contre Postgres, assertion sur le corps JSON | 1, 3-7 |
| `docs/monitoring-api.md`, `CLAUDE.md`, Javadoc de `StatsView` | 7, 12 |

**Coherence des types** — verifiee : les projections rendent `LocalDate getJour()`,
`long getCaptures()/getEcartes()/getGemini()/getLexique()` et
`Double getMedianeSecondes()/getP95Secondes()` ; les records `PointVolume(LocalDate, long, long)`,
`PointDelai(LocalDate, Double, Double)`, `PointIntention(LocalDate, long, long)` ; cote
TypeScript, `jour: string`, `medianeSecondes: number | null`. `SeriesRepository.volumeParJour`,
`intentionsParJour` et `delaisParJour` prennent partout `(String clientId, Instant depuis,
String fuseau)`.

**Marques-places assumes, et signales comme tels dans les taches :** quatre endroits ou le plan
demande d'ouvrir un fichier existant plutot que d'inventer un nom — les fabriques d'entites de
`StatsServiceTest` (taches 3-5), le mecanisme d'authentification de `LeadTimelineControllerTest`
(tache 7), l'URL et la methode de `ClientApi` (taches 8, 10), et le nom du repository de
`CrmSyncAttempt` (tache 5). Ce sont des noms verifiables en une commande, pas des decisions
laissees ouvertes.
