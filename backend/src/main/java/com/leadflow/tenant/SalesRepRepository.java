package com.leadflow.tenant;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesRepRepository extends JpaRepository<SalesRep, UUID> {

    /** Commerciaux eligibles a une attribution, consomme par la couche routing en F4. */
    List<SalesRep> findByClientIdAndActiveTrue(UUID clientId);
}
