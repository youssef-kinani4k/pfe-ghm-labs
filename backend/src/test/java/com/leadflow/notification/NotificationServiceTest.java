package com.leadflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.leadflow.config.NotificationProperties;
import com.leadflow.notification.model.NotificationLead;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.ScoringConfig;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import com.leadflow.tenant.dto.ScoringForm;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Le coeur metier de la notification, eprouve <b>sans broker, sans serveur et sans base</b>.
 *
 * <p>C'est ce que le port rend possible : le service ne connait que
 * {@link CanalDeNotification}, donc le seuil, les trois refus deterministes et l'ordre des
 * ecritures se verifient en quelques millisecondes.
 */
class NotificationServiceTest {

    /** Un canal qui memorise ce qu'on lui donne, et peut echouer sur commande. */
    private static final class CanalDouble implements CanalDeNotification {
        private final List<NotificationLead> envois = new ArrayList<>();
        private RuntimeException echec;

        @Override
        public String identifiant() {
            return "smtp";
        }

        @Override
        public void envoie(NotificationLead lead) {
            if (echec != null) {
                throw echec;
            }
            envois.add(lead);
        }
    }

    /** Une trace en memoire : le repository n'a rien a faire dans un test de decision. */
    private static final class TraceDouble extends NotificationTraceWriter {
        private final List<NotificationAttempt> lignes = new ArrayList<>();

        private TraceDouble() {
            super(null);
        }

        @Override
        public void ecrit(
                UUID leadId, UUID salesRepId, String canal, String destinataire,
                NotificationStatus statut, int score, int seuil, String erreur) {
            NotificationAttempt ligne = new NotificationAttempt();
            ligne.setLeadId(leadId);
            ligne.setSalesRepId(salesRepId);
            ligne.setChannel(canal);
            ligne.setRecipient(destinataire == null ? "" : destinataire);
            ligne.setStatus(statut);
            ligne.setScore(score);
            ligne.setSeuil(seuil);
            ligne.setErrorMessage(erreur);
            lignes.add(ligne);
        }

        NotificationAttempt dernier() {
            assertThat(lignes).as("aucune trace ecrite").isNotEmpty();
            return lignes.get(lignes.size() - 1);
        }
    }

    private final UUID leadId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final UUID commercialId = UUID.randomUUID();

    private LeadRepository leads;
    private ClientRepository clients;
    private SalesRepRepository commerciaux;
    private CanalDouble canal;
    private TraceDouble traces;
    private NotificationService service;

    private Lead lead;
    private Client boutique;
    private SalesRep commercial;

    @BeforeEach
    void montage() {
        lead = new Lead();
        // BaseEntity n'expose pas de setter d'identifiant : il est genere a la persistance.
        // On le pose par reflexion plutot que d'ouvrir un setter en production pour un test.
        ReflectionTestUtils.setField(lead, "id", leadId);
        lead.setClientId(clientId);
        lead.setEmail("sara@rif.test");
        lead.setCompanyName("Rif Logistics");
        lead.setFirstName("Sara");
        lead.setLastName("Benali");
        lead.setDetectedIntent("DEVIS");
        lead.setAssignedSalesRepId(commercialId);

        boutique = new Client();
        ReflectionTestUtils.setField(boutique, "id", clientId);

        commercial = new SalesRep();
        ReflectionTestUtils.setField(commercial, "id", commercialId);
        commercial.setFullName("Karim Idrissi");
        commercial.setEmail("karim@demo.test");
        commercial.setActive(true);

        leads = mock(LeadRepository.class);
        clients = mock(ClientRepository.class);
        commerciaux = mock(SalesRepRepository.class);
        when(leads.findById(any())).thenReturn(Optional.of(lead));
        when(clients.findById(any())).thenReturn(Optional.of(boutique));
        when(commerciaux.findById(any())).thenReturn(Optional.of(commercial));

        canal = new CanalDouble();
        traces = new TraceDouble();
        service = new NotificationService(
                leads,
                clients,
                commerciaux,
                new CanalDeNotificationRegistry(List.of(canal)),
                traces,
                new NotificationProperties(
                        new NotificationProperties.Smtp(
                                true, "smtp.test", 587, "u", "p", "leadflow@agence.test",
                                Duration.ofSeconds(10)),
                        "http://localhost:4200/leads/{id}"));
    }

    private void boutiqueAvecSeuil(int seuil) {
        boutique.setScoringConfig(
                new ScoringForm(15, 10, 5, 10, Map.of(), Set.of(), Set.of(), 10, 70, seuil)
                        .versDocument());
    }

    private void leadAvecScore(int score) {
        lead.setScore(score);
    }

    @Test
    void unLeadSousLeSeuilNestPasEnvoyeMaisLaisseUneTrace() {
        boutiqueAvecSeuil(70);
        leadAvecScore(55);

        service.notifie(leadId);

        assertThat(canal.envois).isEmpty();
        // Le silence doit etre explicable : sans cette ligne, « pourquoi n'ai-je pas ete
        // prevenu ? » n'a pas de reponse, et une panne ressemble a une decision.
        assertThat(traces.dernier().getStatus()).isEqualTo(NotificationStatus.IGNOREE);
        assertThat(traces.dernier().getScore()).isEqualTo(55);
        assertThat(traces.dernier().getSeuil()).isEqualTo(70);
    }

    @Test
    void unLeadAuSeuilExactEstEnvoye() {
        // Le seuil est inclusif, comme le badge « chaud » du monitoring : score >= seuil.
        boutiqueAvecSeuil(70);
        leadAvecScore(70);

        service.notifie(leadId);

        assertThat(canal.envois).hasSize(1);
        assertThat(traces.dernier().getStatus()).isEqualTo(NotificationStatus.ENVOYEE);
        assertThat(traces.dernier().getRecipient()).isEqualTo("karim@demo.test");
    }

    @Test
    void leCommercialSansAdresseNePartJamaisEnDlq() {
        boutiqueAvecSeuil(70);
        leadAvecScore(90);
        commercial.setEmail(null);

        // Aucune repetition ne reparera une adresse absente : lever ferait mourir le message
        // trois fois pour rien. C'est la distinction que la qualification fait avec DISCARDED.
        assertThatCode(() -> service.notifie(leadId)).doesNotThrowAnyException();

        assertThat(canal.envois).isEmpty();
        assertThat(traces.dernier().getStatus()).isEqualTo(NotificationStatus.ECHEC);
        assertThat(traces.dernier().getErrorMessage()).contains("adresse");
    }

    @Test
    void unLeadSansCommercialNePartJamaisEnDlq() {
        boutiqueAvecSeuil(70);
        leadAvecScore(90);
        lead.setAssignedSalesRepId(null);

        assertThatCode(() -> service.notifie(leadId)).doesNotThrowAnyException();

        assertThat(canal.envois).isEmpty();
        assertThat(traces.dernier().getStatus()).isEqualTo(NotificationStatus.ECHEC);
        assertThat(traces.dernier().getErrorMessage()).contains("commercial");
    }

    @Test
    void unEchecTechniqueEcritSaTraceAvantDeLeverPourLaDlq() {
        boutiqueAvecSeuil(70);
        leadAvecScore(90);
        canal.echec = new NotificationException("smtp injoignable");

        assertThatThrownBy(() -> service.notifie(leadId))
                .isInstanceOf(NotificationException.class);

        // L'ordre EST l'invariant : la trace s'ecrit avant que l'exception ne parte, dans sa
        // propre transaction. Sans cela, la seule trace de l'echec disparaitrait avec lui —
        // c'est ce que fait LeadActionJournal pour un rejeu rate.
        assertThat(traces.dernier().getStatus()).isEqualTo(NotificationStatus.ECHEC);
        assertThat(traces.dernier().getErrorMessage()).contains("smtp injoignable");
    }

    @Test
    void lePivotPorteLesFaitsDuLeadEtDuCommercial() {
        boutiqueAvecSeuil(70);
        leadAvecScore(90);

        service.notifie(leadId);

        NotificationLead envoye = canal.envois.get(0);
        assertThat(envoye.adresseCommercial()).isEqualTo("karim@demo.test");
        assertThat(envoye.nomCommercial()).isEqualTo("Karim Idrissi");
        assertThat(envoye.nomProspect()).isEqualTo("Sara Benali");
        assertThat(envoye.societeProspect()).isEqualTo("Rif Logistics");
        assertThat(envoye.score()).isEqualTo(90);
        assertThat(envoye.intention()).isEqualTo("DEVIS");
        assertThat(envoye.urlFiche()).isEqualTo("http://localhost:4200/leads/" + leadId);
    }

    @Test
    void uneBoutiqueSansBaremeUtiliseLeSeuilParDefaut() {
        // scoring_config vide : ScoringConfig.depuis est tolerante et rend les defauts. Une
        // boutique jamais configuree doit se comporter comme les autres, pas se taire.
        leadAvecScore(ScoringConfig.defaut().seuilNotification());

        service.notifie(leadId);

        assertThat(canal.envois).hasSize(1);
    }
}
