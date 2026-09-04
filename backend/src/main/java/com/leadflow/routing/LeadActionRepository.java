package com.leadflow.routing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadActionRepository extends JpaRepository<LeadAction, UUID> {

    /** Ordre chronologique : c'est celui dans lequel la timeline les fusionne. */
    List<LeadAction> findByLeadIdOrderByCreatedAtAsc(UUID leadId);
}
