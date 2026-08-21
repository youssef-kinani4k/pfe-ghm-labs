package com.leadflow.capture;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawLeadEventRepository extends JpaRepository<RawLeadEvent, UUID> {

    /** Evenements en echec d'un client, pour l'ecran de rejeu du dashboard en F6. */
    List<RawLeadEvent> findByClientIdAndStatusOrderByReceivedAtDesc(
            UUID clientId, RawLeadEventStatus status);
}
