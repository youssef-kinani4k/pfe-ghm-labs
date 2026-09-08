package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptNature;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeriesRepositoryTest {

    private static final String PARIS = "Europe/Paris";
    // Loin dans le futur : ces tests exercent les requetes brutes, pas la borne haute
    // elle-meme (verrouillee cote service dans SeriesServiceTest), donc elle ne doit exclure
    // aucune des donnees de la fixture.
    private static final Instant JUSQU_AU_LOIN = Instant.parse("2100-01-01T00:00:00Z");

    @Autowired private SeriesRepository series;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;
    @Autowired private LeadRepository leads;
    @Autowired private CrmSyncAttemptRepository tentatives;
    @Autowired private EntityManager em;

    @AfterEach
    void nettoie() {
        tentatives.deleteAll();
        leads.deleteAll();
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
                boutique.getId().toString(), lundi.minusSeconds(3600), JUSQU_AU_LOIN, PARIS);

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
                JUSQU_AU_LOIN,
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
                        mienne.getId().toString(), quand.minusSeconds(3600), JUSQU_AU_LOIN, PARIS))
                .singleElement()
                .satisfies(p -> assertThat(p.getCaptures()).isEqualTo(1L));

        // clientId nul = toutes les boutiques. C'est la vue de l'agence.
        assertThat(series.volumeParJour(null, quand.minusSeconds(3600), JUSQU_AU_LOIN, PARIS))
                .singleElement()
                .satisfies(p -> assertThat(p.getCaptures()).isEqualTo(2L));
    }

    @Test
    @Transactional
    void comptePourChaqueJourLaPartDuModeleEtCelleDuLexique() {
        Client boutique = boutique("intentions");
        Instant lundi = instantParisien(2026, 3, 2, 10);

        lead(boutique, lundi, IntentSource.GEMINI);
        lead(boutique, lundi, IntentSource.GEMINI);
        lead(boutique, lundi, IntentSource.RULES);
        // Sans source : ni Gemini ni lexique. La figure dit qui a analyse, pas combien de
        // leads sont arrives.
        lead(boutique, lundi, null);

        List<PointIntentionBrut> points = series.intentionsParJour(
                boutique.getId().toString(), lundi.minusSeconds(3600), JUSQU_AU_LOIN, PARIS);

        assertThat(points).singleElement().satisfies(p -> {
            assertThat(p.getJour()).isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(p.getGemini()).isEqualTo(2L);
            assertThat(p.getLexique()).isEqualTo(1L);
        });
    }

    @Test
    @Transactional
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
                boutique.getId().toString(), capture.minusSeconds(3600), JUSQU_AU_LOIN, PARIS);

        assertThat(points).singleElement().satisfies(p -> {
            assertThat(p.getJour()).isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(p.getMedianeSecondes()).isEqualTo(120.0d);
        });
    }

    @Test
    @Transactional
    void ignoreLesTentativesEnEchec() {
        Client boutique = boutique("echecs");
        Instant capture = instantParisien(2026, 3, 2, 10);

        Lead l = lead(boutique, capture, IntentSource.GEMINI);
        tentative(l, capture.plusSeconds(10), CrmSyncAttemptStatus.FAILED);
        tentative(l, capture.plusSeconds(300), CrmSyncAttemptStatus.SUCCESS);

        // Le delai se compte jusqu'au succes, pas jusqu'a la premiere tentative : un lead
        // que l'ERP a refuse deux fois a bel et bien mis cinq minutes a arriver.
        assertThat(series.delaisParJour(
                        boutique.getId().toString(), capture.minusSeconds(3600), JUSQU_AU_LOIN, PARIS))
                .singleElement()
                .satisfies(p -> assertThat(p.getMedianeSecondes()).isEqualTo(300.0d));
    }

    @Test
    @Transactional
    void ignoreLesLignesDeReaffectation() {
        // F15 : status = 'SUCCESS' ne designe plus une synchronisation a lui seul. La
        // propagation d'une reattribution ecrit elle aussi des lignes SUCCESS, y compris
        // « sans effet » pour un lead que l'ERP n'a jamais recu. Sans le filtre sur la
        // nature, ce lead-la entrerait dans la mediane alors que rien n'est jamais parti.
        Client boutique = boutique("reaffectation");
        Instant capture = instantParisien(2026, 3, 2, 10);

        Lead jamaisPousse = lead(boutique, capture, IntentSource.RULES);
        tentative(
                jamaisPousse,
                capture.plusSeconds(60),
                CrmSyncAttemptStatus.SUCCESS,
                CrmSyncAttemptNature.REAFFECTATION);

        assertThat(series.delaisParJour(
                        boutique.getId().toString(), capture.minusSeconds(3600), JUSQU_AU_LOIN, PARIS))
                .isEmpty();

        // Et sur un lead reellement synchronise, une reaffectation tardive ne deplace pas
        // le delai : elle n'est pas une synchronisation plus lente, elle n'en est pas une.
        Lead pousse = lead(boutique, capture, IntentSource.RULES);
        tentative(pousse, capture.plusSeconds(120), CrmSyncAttemptStatus.SUCCESS);
        tentative(
                pousse,
                capture.plusSeconds(3 * 86_400),
                CrmSyncAttemptStatus.SUCCESS,
                CrmSyncAttemptNature.REAFFECTATION);

        assertThat(series.delaisParJour(
                        boutique.getId().toString(), capture.minusSeconds(3600), JUSQU_AU_LOIN, PARIS))
                .singleElement()
                .satisfies(p -> assertThat(p.getMedianeSecondes()).isEqualTo(120.0d));
    }

    @Test
    @Transactional
    void separeLaMedianeDuP95() {
        Client boutique = boutique("percentiles");
        Instant jour = instantParisien(2026, 3, 2, 10);

        // Neuf leads a 10 s et un a 1000 s. La moyenne dirait 109 s, ce qui ne decrit
        // aucun lead reel ; la mediane dit 10 s et le p95 revele la queue.
        for (int i = 0; i < 9; i++) {
            Lead rapide = lead(boutique, jour, IntentSource.RULES);
            tentative(rapide, jour.plusSeconds(10), CrmSyncAttemptStatus.SUCCESS);
        }
        Lead lent = lead(boutique, jour, IntentSource.RULES);
        tentative(lent, jour.plusSeconds(1000), CrmSyncAttemptStatus.SUCCESS);

        PointDelaiBrut point = series.delaisParJour(
                        boutique.getId().toString(), jour.minusSeconds(3600), JUSQU_AU_LOIN, PARIS)
                .get(0);

        assertThat(point.getMedianeSecondes()).isEqualTo(10.0d);
        assertThat(point.getP95Secondes()).isGreaterThan(400.0d);
    }

    @Test
    @Transactional
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
                        boutique.getId().toString(),
                        captureLundi.minusSeconds(3600),
                        JUSQU_AU_LOIN,
                        PARIS))
                .singleElement()
                .satisfies(p -> assertThat(p.getJour()).isEqualTo(LocalDate.of(2026, 3, 3)));
    }

    @Test
    void laBorneHauteEcarteCeQuiSuitLaFenetre() {
        // Ce test est le seul endroit ou la borne haute est reellement eprouvee. Le meme
        // controle porte depuis le service ne prouverait rien : celui-ci ne relit que les
        // jours de son calendrier, si bien qu'une ligne posterieure disparaitrait de sa
        // sortie avec ou sans clause `< :jusqu`. Ici, la clause est la seule chose qui
        // puisse ecarter la ligne — retirez-la du SQL et l'assertion tombe.
        Client boutique = boutique("borne-haute");
        Instant lundi = instantParisien(2026, 3, 2, 10);
        Instant mercredi = instantParisien(2026, 3, 4, 10);

        evenement(boutique, lundi, RawLeadEventStatus.PUBLISHED);
        evenement(boutique, mercredi, RawLeadEventStatus.PUBLISHED);

        // Fenetre fermee au mardi minuit : le mercredi tombe au-dela.
        Instant jusqu = instantParisien(2026, 3, 3, 0).plusSeconds(86_400);

        List<PointVolumeBrut> points = series.volumeParJour(
                boutique.getId().toString(), lundi.minusSeconds(3600), jusqu, PARIS);

        assertThat(points)
                .singleElement()
                .satisfies(p -> assertThat(p.getJour()).isEqualTo(LocalDate.of(2026, 3, 2)));
    }

    private Client boutique(String suffixe) {
        Client c = new Client();
        c.setName("Boutique " + suffixe);
        c.setPublicKey("pk-" + suffixe + "-" + UUID.randomUUID());
        c.setHmacSecret("secret-" + suffixe);
        c.setCrmProviderId("dolibarr");
        c.setCrmConfig(Map.of());
        c.setActive(true);
        return clients.save(c);
    }

    private void evenement(Client boutique, Instant quand, RawLeadEventStatus statut) {
        RawLeadEvent e = new RawLeadEvent();
        e.setClientId(boutique.getId());
        e.setSource("formulaire");
        e.setPayload(new HashMap<>(Map.of("email", "a@b.fr")));
        e.setSignature("sig-" + UUID.randomUUID());
        e.setReceivedAt(quand);
        e.setStatus(statut);
        evenements.save(e);
    }

    private Lead lead(Client boutique, Instant quand, IntentSource source) {
        RawLeadEvent e = new RawLeadEvent();
        e.setClientId(boutique.getId());
        e.setSource("formulaire");
        e.setPayload(new HashMap<>(Map.of("email", "a@b.fr")));
        e.setSignature("sig-" + UUID.randomUUID());
        e.setReceivedAt(quand);
        e.setStatus(RawLeadEventStatus.PUBLISHED);
        e = evenements.save(e);

        Lead l = new Lead();
        l.setClientId(boutique.getId());
        l.setRawEventId(e.getId());
        l.setEmail("prospect-" + UUID.randomUUID() + "@test.local");
        l.setScore(50);
        l.setStatus(LeadStatus.QUALIFIED);
        l.setIntentSource(source);
        l = leads.save(l);
        em.flush();

        em.createNativeQuery("update lead set created_at = :quand where id = :id")
                .setParameter("quand", quand)
                .setParameter("id", l.getId())
                .executeUpdate();
        em.clear();

        return l;
    }

    private void tentative(Lead lead, Instant quand, CrmSyncAttemptStatus statut) {
        tentative(lead, quand, statut, CrmSyncAttemptNature.SYNCHRONISATION);
    }

    private void tentative(
            Lead lead, Instant quand, CrmSyncAttemptStatus statut, CrmSyncAttemptNature nature) {
        CrmSyncAttempt a = new CrmSyncAttempt();
        a.setLeadId(lead.getId());
        a.setProviderId("dolibarr");
        a.setStatus(statut);
        a.setNature(nature);
        a.setAttemptedAt(quand);
        tentatives.save(a);
    }

    private static Instant instantParisien(int annee, int mois, int jour, int heure) {
        return ZonedDateTime.of(annee, mois, jour, heure, 0, 0, 0, ZoneId.of(PARIS))
                .toInstant();
    }
}
