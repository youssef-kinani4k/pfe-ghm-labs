package com.leadflow.capture;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawLeadEventRepository extends JpaRepository<RawLeadEvent, UUID> {

    /** Evenements en echec d'un client, pour l'ecran de rejeu du dashboard en F6. */
    List<RawLeadEvent> findByClientIdAndStatusOrderByReceivedAtDesc(
            UUID clientId, RawLeadEventStatus status);

    /**
     * Rejeu exact : meme client, meme en-tete de signature. Adosse a l'index unique
     * {@code uk_raw_lead_event_client_signature}.
     */
    Optional<RawLeadEvent> findByClientIdAndSignature(UUID clientId, String signature);

    /** Ce que le filet de republication doit reprendre : non publie et assez vieux. */
    List<RawLeadEvent> findByStatusInAndReceivedAtBefore(
            Collection<RawLeadEventStatus> statuts, Instant limite);

    /**
     * Dernier lead d'un client encore signe avec l'ancien secret, pour repondre a « puis-je
     * revoquer maintenant ? » depuis la fiche de la boutique.
     */
    Optional<RawLeadEvent> findFirstByClientIdAndSignedWithPreviousSecretTrueOrderByReceivedAtDesc(
            UUID clientId);
}
