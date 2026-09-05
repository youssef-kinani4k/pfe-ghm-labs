package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
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

    @Autowired private SeriesRepository series;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;
    @Autowired private LeadRepository leads;
    @Autowired private EntityManager em;

    @AfterEach
    void nettoie() {
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
                boutique.getId().toString(), lundi.minusSeconds(3600), PARIS);

        assertThat(points).singleElement().satisfies(p -> {
            assertThat(p.getJour()).isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(p.getGemini()).isEqualTo(2L);
            assertThat(p.getLexique()).isEqualTo(1L);
        });
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

    private static Instant instantParisien(int annee, int mois, int jour, int heure) {
        return ZonedDateTime.of(annee, mois, jour, heure, 0, 0, 0, ZoneId.of(PARIS))
                .toInstant();
    }
}
