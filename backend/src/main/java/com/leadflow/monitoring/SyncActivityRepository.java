package com.leadflow.monitoring;

import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Activite reelle des connecteurs, lue dans la trace append-only de F5.
 *
 * <p>La jointure vers {@code Lead} se fait sur l'identifiant et non sur une association :
 * {@code CrmSyncAttempt} n'en porte aucune, choix de F5 qui garde l'adaptateur ignorant du
 * pipeline. Le prix est une jointure explicite dans la requete, bornee par le nombre de
 * fournisseurs et de clients — pas par le nombre de traces.
 */
public interface SyncActivityRepository extends Repository<CrmSyncAttempt, UUID> {

    @Query("""
            select a.providerId as providerId,
                   sum(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.SUCCESS
                            then 1L else 0L end) as succes,
                   sum(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.FAILED
                            then 1L else 0L end) as echecs,
                   max(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.SUCCESS
                            then a.attemptedAt else null end) as dernierSucces,
                   max(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.FAILED
                            then a.attemptedAt else null end) as dernierEchec
            from CrmSyncAttempt a
            group by a.providerId
            """)
    List<ActiviteParFournisseur> activiteParFournisseur();

    @Query("""
            select a.providerId as providerId, l.clientId as clientId,
                   sum(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.SUCCESS
                            then 1L else 0L end) as succes,
                   sum(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.FAILED
                            then 1L else 0L end) as echecs,
                   max(a.attemptedAt) as derniereTentative
            from CrmSyncAttempt a, com.leadflow.qualification.Lead l
            where l.id = a.leadId
            group by a.providerId, l.clientId
            """)
    List<ActiviteParClient> activiteParClient();

    /** Message du dernier echec d'un fournisseur : une ligne, la plus recente. */
    List<CrmSyncAttempt> findTop1ByProviderIdAndStatusOrderByAttemptedAtDesc(
            String providerId, CrmSyncAttemptStatus status);

    interface ActiviteParFournisseur {
        String getProviderId();

        long getSucces();

        long getEchecs();

        Instant getDernierSucces();

        Instant getDernierEchec();
    }

    interface ActiviteParClient {
        String getProviderId();

        UUID getClientId();

        long getSucces();

        long getEchecs();

        Instant getDerniereTentative();
    }
}
