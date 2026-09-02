package com.leadflow.monitoring.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * L'index unique partiel de V8 : un lead n'a qu'une mort en attente d'action humaine.
 *
 * <p>Le test asserte le comportement de la <b>base</b>, pas celui du listener : c'est la
 * contrainte qui tranche, et le rattrapage applicatif n'a de sens que si elle leve.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeadLetterJournalTest {

    @Autowired private DeadLetterJournal journal;
    @Autowired private DeadLetterRepository morts;

    @AfterEach
    void nettoyage() {
        morts.deleteAll();
    }

    @Test
    void deuxMortsEnAttentePourUnMemeLeadSontRefusees() {
        UUID leadId = UUID.randomUUID();
        journal.enregistre(mort(leadId, DeadLetterStatus.PENDING));

        assertThatThrownBy(() -> journal.enregistre(mort(leadId, DeadLetterStatus.PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(morts.findFirstByLeadIdAndStatus(leadId, DeadLetterStatus.PENDING))
                .isPresent();
    }

    @Test
    void uneNouvelleMortEstAccepteeQuandLaPrecedenteEstRejouee() {
        UUID leadId = UUID.randomUUID();
        DeadLetter premiere = journal.enregistre(mort(leadId, DeadLetterStatus.PENDING));
        premiere.setStatus(DeadLetterStatus.REPLAYED);
        premiere.setReplayedAt(Instant.now());
        morts.saveAndFlush(premiere);

        // Une seconde mort apres un rejeu qui a de nouveau echoue est un fait reel : l'index
        // partiel doit la laisser passer.
        journal.enregistre(mort(leadId, DeadLetterStatus.PENDING));

        assertThat(morts.findAll()).hasSize(2);
    }

    @Test
    void deuxMortsSansLeadIdNeSeGenentPas() {
        // L'index est partiel sur lead_id IS NOT NULL : un message illisible, dont on n'a
        // pas su tirer d'identifiant, doit toujours pouvoir etre journalise.
        journal.enregistre(mort(null, DeadLetterStatus.PENDING));
        journal.enregistre(mort(null, DeadLetterStatus.PENDING));

        assertThat(morts.findAll()).hasSize(2);
    }

    private DeadLetter mort(UUID leadId, DeadLetterStatus statut) {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.routed");
        mort.setRoutingKey("lead.routed");
        mort.setPayload("{}");
        mort.setLeadId(leadId);
        mort.setStatus(statut);
        mort.setDeadAt(Instant.now());
        return mort;
    }
}
