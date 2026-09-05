package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.monitoring.dto.SeriesView;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeriesServiceTest {

    @Autowired private SeriesService service;
    @Autowired private ClientRepository clients;
    @Autowired private RawLeadEventRepository evenements;

    private Client boutiqueVide;
    private Client boutiqueDemain;
    private UUID evenementDemainId;

    @AfterEach
    void nettoie() {
        if (boutiqueVide != null) {
            clients.deleteById(boutiqueVide.getId());
            boutiqueVide = null;
        }
        if (boutiqueDemain != null) {
            // L'evenement d'abord : raw_lead_event reference client sans cascade, Postgres
            // refuserait la suppression de la boutique tant qu'il existe.
            if (evenementDemainId != null) {
                evenements.deleteById(evenementDemainId);
                evenementDemainId = null;
            }
            clients.deleteById(boutiqueDemain.getId());
            boutiqueDemain = null;
        }
    }

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
        //
        // Filtree sur une boutique sans aucun lead, plutot que sur clientId = null : les
        // classes de test partagent le meme conteneur Postgres, et une assertion "tout est
        // vide" sur l'ensemble des boutiques dependrait de l'ordre d'execution des autres
        // classes plutot que d'un fait de la fixture.
        boutiqueVide = boutique("vide");

        SeriesView vue = service.calcule(boutiqueVide.getId(), 7);

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
    void uneLigneDateeDeDemainNApparaitPasDansLaSerie() {
        // Sans borne haute, une ligne posterieure au dernier jour du calendrier serait
        // ramenee par SQL, rangee sous une date absente du calendrier, puis jamais relue --
        // silencieusement perdue. Le seul fait observable depuis le service est que cette
        // ligne ne doit gonfler aucun jour de la fenetre.
        boutiqueDemain = boutique("demain");
        RawLeadEvent e = new RawLeadEvent();
        e.setClientId(boutiqueDemain.getId());
        e.setSource("formulaire");
        e.setPayload(new HashMap<>(Map.of("email", "a@b.fr")));
        e.setSignature("sig-" + UUID.randomUUID());
        e.setReceivedAt(Instant.now().plus(Duration.ofDays(2)));
        e.setStatus(RawLeadEventStatus.PUBLISHED);
        evenementDemainId = evenements.save(e).getId();

        SeriesView vue = service.calcule(boutiqueDemain.getId(), 7);

        assertThat(vue.volume()).allSatisfy(p -> {
            assertThat(p.captures()).isZero();
            assertThat(p.ecartes()).isZero();
        });
    }

    @Test
    void accepteLesTroisFenetres() {
        assertThat(service.calcule(null, 7).volume()).hasSize(7);
        assertThat(service.calcule(null, 30).volume()).hasSize(30);
        assertThat(service.calcule(null, 90).volume()).hasSize(90);
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
}
