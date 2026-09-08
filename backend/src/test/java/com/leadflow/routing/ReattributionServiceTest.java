package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.config.RabbitMQConfig;
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
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

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
