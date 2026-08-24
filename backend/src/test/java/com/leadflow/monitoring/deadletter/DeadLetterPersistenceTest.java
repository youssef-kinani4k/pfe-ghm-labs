package com.leadflow.monitoring.deadletter;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@code @SpringBootTest} et non {@code @DataJpaTest} : la tranche de ce dernier n'inclut
 * pas les {@code @Component} que sont les converters chiffres, dont Hibernate a besoin des
 * qu'une entite du projet est chargee.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeadLetterPersistenceTest {

    @Autowired private DeadLetterRepository repository;

    @AfterEach
    void nettoie() {
        repository.deleteAll();
    }

    @Test
    void ecritEtRelitUneMortSansLeadNiClient() {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.qualified");
        mort.setRoutingKey("lead.qualified");
        mort.setPayload("{ceci n'est pas du json");
        mort.setFailureReason("Charge utile illisible");
        mort.setStatus(DeadLetterStatus.PENDING);

        UUID id = repository.saveAndFlush(mort).getId();

        assertThat(repository.findById(id)).hasValueSatisfying(relue -> {
            assertThat(relue.getPayload()).isEqualTo("{ceci n'est pas du json");
            assertThat(relue.getDeadAt()).isNotNull();
            // Ni client_id ni lead_id : une mort corrompue doit s'ecrire quand meme.
            assertThat(relue.getLeadId()).isNull();
        });
    }

    @Test
    void filtreLesMortsEnAttenteDUnLead() {
        UUID leadId = UUID.randomUUID();
        repository.saveAndFlush(mort(leadId, DeadLetterStatus.PENDING));
        repository.saveAndFlush(mort(UUID.randomUUID(), DeadLetterStatus.REPLAYED));

        assertThat(repository.existsByLeadIdAndStatus(leadId, DeadLetterStatus.PENDING)).isTrue();
        assertThat(repository.existsByLeadIdAndStatus(UUID.randomUUID(), DeadLetterStatus.PENDING))
                .isFalse();
    }

    @Test
    void compteLesMortsEnAttente() {
        repository.saveAndFlush(mort(UUID.randomUUID(), DeadLetterStatus.PENDING));
        repository.saveAndFlush(mort(UUID.randomUUID(), DeadLetterStatus.PENDING));
        repository.saveAndFlush(mort(UUID.randomUUID(), DeadLetterStatus.DISCARDED));

        assertThat(repository.countByStatus(DeadLetterStatus.PENDING)).isEqualTo(2L);
    }

    private DeadLetter mort(UUID leadId, DeadLetterStatus statut) {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.qualified");
        mort.setRoutingKey("lead.qualified");
        mort.setPayload("{}");
        mort.setLeadId(leadId);
        mort.setStatus(statut);
        mort.setDeadAt(Instant.now());
        return mort;
    }
}
