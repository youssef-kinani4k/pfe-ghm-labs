package com.leadflow.crm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrmSyncAttemptRepository extends JpaRepository<CrmSyncAttempt, UUID> {

    /**
     * Derniere tentative reussie d'un lead. C'est la source des references deja obtenues
     * dans l'ERP, que l'adaptateur consulte avant toute creation pour ne pas produire de
     * doublon lors d'un rejeu.
     */
    Optional<CrmSyncAttempt> findFirstByLeadIdAndStatusOrderByAttemptedAtDesc(
            UUID leadId, CrmSyncAttemptStatus status);

    /** Historique complet d'un lead, pour l'ecran de diagnostic du dashboard en F6. */
    List<CrmSyncAttempt> findByLeadIdOrderByAttemptedAtDesc(UUID leadId);
}
