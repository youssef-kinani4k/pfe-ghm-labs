package com.leadflow.tenant;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesRepRepository extends JpaRepository<SalesRep, UUID> {

    /** Commerciaux eligibles a une attribution, consomme par la couche routing en F4. */
    List<SalesRep> findByClientIdAndActiveTrue(UUID clientId);

    /**
     * Tous les commerciaux d'un client, actifs ou non. L'annuaire du dashboard doit montrer
     * les deux : un commercial desactive est souvent l'explication d'un tour de role
     * desequilibre.
     */
    List<SalesRep> findByClientIdOrderByFullName(UUID clientId);

    /** Nombre de commerciaux actifs, pour la colonne de la liste des boutiques. */
    long countByClientIdAndActiveTrue(UUID clientId);
}
