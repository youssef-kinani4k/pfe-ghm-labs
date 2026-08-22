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
