package com.leadflow.monitoring.deadletter;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Seule table que {@code monitoring/} ecrit, donc seul repository du package qui expose
 * l'ecriture. {@code JpaSpecificationExecutor} sert les filtres de l'ecran, sur le meme
 * schema que {@code LeadQueryRepository}.
 *
 * <p>{@link #existsByLeadIdAndStatus} est le garde-fou des filets de republication (T12) :
 * un echec deja constate et presente a un humain n'est plus republie automatiquement.
 */
public interface DeadLetterRepository
        extends JpaRepository<DeadLetter, UUID>, JpaSpecificationExecutor<DeadLetter> {

    boolean existsByLeadIdAndStatus(UUID leadId, DeadLetterStatus status);

    long countByStatus(DeadLetterStatus status);
}
