package com.leadflow.qualification;

import com.leadflow.routing.AttributionRecente;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    /** Verifie qu'un evenement brut rejoue n'a pas deja produit son lead. */
    Optional<Lead> findByRawEventId(UUID rawEventId);

    /** Deduplication de F3 : meme client, meme email, dans une fenetre temporelle. */
    boolean existsByClientIdAndEmailAndCreatedAtAfter(UUID clientId, String email, Instant depuis);

    /**
     * Etat du tour de rotation pour F4, deduit des attributions passees. Un commercial
     * absent du resultat n'a jamais rien recu : c'est a lui que revient le prochain lead.
     */
    @Query("""
            select l.assignedSalesRepId as salesRepId, max(l.createdAt) as derniereAttribution
            from Lead l
            where l.clientId = :clientId and l.assignedSalesRepId is not null
            group by l.assignedSalesRepId
            """)
    List<AttributionRecente> derniereAttributionParCommercial(@Param("clientId") UUID clientId);
}
