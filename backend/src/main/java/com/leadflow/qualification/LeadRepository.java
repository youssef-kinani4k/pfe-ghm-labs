package com.leadflow.qualification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    /** Verifie qu'un evenement brut rejoue n'a pas deja produit son lead. */
    Optional<Lead> findByRawEventId(UUID rawEventId);

    /** Deduplication de F3 : meme client, meme email, dans une fenetre temporelle. */
    boolean existsByClientIdAndEmailAndCreatedAtAfter(UUID clientId, String email, Instant depuis);
}
