package com.leadflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * La trace des notifications, eprouvee contre le schema reel.
 *
 * <p>{@code @SpringBootTest} et non {@code @DataJpaTest} : la tranche de ce dernier
 * n'inclut pas les {@code @Component}, dont les {@code AttributeConverter} de chiffrement
 * de {@code Client} ont besoin.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationAttemptPersistenceTest {

    @Autowired private ClientRepository clients;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private LeadRepository leads;
    @Autowired private SalesRepRepository commerciaux;
    @Autowired private NotificationAttemptRepository tentatives;
    @Autowired private JdbcTemplate jdbc;

    private UUID leadId;
    private UUID commercialId;

    @BeforeEach
    void jeuDeDonnees() {
        Client client = new Client();
        client.setPublicKey("cle-notif-" + UUID.randomUUID());
        client.setName("Boutique de test");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081"));
        Client boutique = clients.saveAndFlush(client);

        SalesRep commercial = new SalesRep();
        commercial.setClient(boutique);
        commercial.setFullName("Karim Idrissi");
        commercial.setEmail("karim+" + UUID.randomUUID() + "@demo.test");
        commercial.setActive(true);
        commercialId = commerciaux.saveAndFlush(commercial).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(boutique.getId());
        evenement.setSource("formulaire-devis");
        evenement.setPayload(Map.of("email", "prospect@exemple.test"));
        evenement.setSignature("sig-" + UUID.randomUUID());
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(boutique.getId());
        lead.setRawEventId(rawEventId);
        lead.setEmail("prospect@exemple.test");
        lead.setScore(90);
        lead.setAssignedSalesRepId(commercialId);
        leadId = leads.saveAndFlush(lead).getId();
    }

    private NotificationAttempt tentative(NotificationStatus statut, int score, int seuil) {
        NotificationAttempt tentative = new NotificationAttempt();
        tentative.setLeadId(leadId);
        tentative.setChannel("smtp");
        tentative.setRecipient("karim@demo.test");
        tentative.setSalesRepId(commercialId);
        tentative.setStatus(statut);
        tentative.setScore(score);
        tentative.setSeuil(seuil);
        return tentative;
    }

    @Test
    void uneTentativeIgnoreeGardeLeScoreEtLeSeuilQuiLOntTranchee() {
        NotificationAttempt enregistree =
                tentatives.saveAndFlush(tentative(NotificationStatus.IGNOREE, 55, 70));

        // Figes dans la ligne, pas relus : le seuil se regle depuis l'ecran « Bareme », et
        // le deplacer ne doit pas rendre incomprehensible une decision deja prise.
        assertThat(enregistree.getScore()).isEqualTo(55);
        assertThat(enregistree.getSeuil()).isEqualTo(70);
        assertThat(enregistree.getAttemptedAt()).isNotNull();
        assertThat(enregistree.getId()).isNotNull();
    }

    @Test
    void unStatutHorsDuVocabulaireEstRefuseParLaBase() {
        // Le CHECK de V9 est la derniere barriere : un statut invente ne doit pas pouvoir
        // s'ecrire. On l'eprouve en SQL direct, l'enumeration l'interdisant deja en Java.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO notification_attempt "
                                + "(id, lead_id, channel, recipient, status, score, seuil) "
                                + "VALUES (?, ?, 'smtp', 'x@y.test', 'PEUT_ETRE', 10, 70)",
                        UUID.randomUUID(), leadId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laSuppressionDuLeadEmporteSesTentatives() {
        tentatives.saveAndFlush(tentative(NotificationStatus.ENVOYEE, 90, 70));

        leads.deleteById(leadId);
        leads.flush();

        // ON DELETE CASCADE, comme crm_sync_attempt et contrairement a lead_action : une
        // tentative n'a pas de sens sans son lead, la ou un geste humain garde le sien.
        assertThat(tentatives.findByLeadIdOrderByAttemptedAtAsc(leadId)).isEmpty();
    }

    @Test
    void lesTentativesDUnLeadSeLisentDeLaPlusAncienneALaPlusRecente() {
        NotificationAttempt echec = tentative(NotificationStatus.ECHEC, 90, 70);
        echec.setErrorMessage("smtp injoignable");
        echec.setAttemptedAt(java.time.Instant.now().minusSeconds(600));
        tentatives.saveAndFlush(echec);
        tentatives.saveAndFlush(tentative(NotificationStatus.ENVOYEE, 90, 70));

        // La chronologie du lead lit dans cet ordre : un rejeu qui finit par reussir doit
        // se raconter dans le sens ou il s'est produit.
        assertThat(tentatives.findByLeadIdOrderByAttemptedAtAsc(leadId))
                .extracting(NotificationAttempt::getStatus)
                .containsExactly(NotificationStatus.ECHEC, NotificationStatus.ENVOYEE);
    }
}
