package com.leadflow.monitoring;

import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptNature;
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
 *
 * <p><b>Les deux agregats ne comptent que les lignes de nature {@code SYNCHRONISATION}</b>,
 * et ce predicat est indispensable depuis F15 : la propagation d'une reattribution ecrit
 * elle aussi dans cette table, y compris des lignes {@code SUCCESS} « sans effet » ou rien
 * n'a ete pousse. Les compter ferait dire a l'ecran Connecteurs qu'un ERP a reussi des
 * synchronisations qu'il n'a jamais faites — corriger un responsable n'est pas synchroniser
 * un lead.
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
            where a.nature = com.leadflow.crm.CrmSyncAttemptNature.SYNCHRONISATION
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
              and a.nature = com.leadflow.crm.CrmSyncAttemptNature.SYNCHRONISATION
            group by a.providerId, l.clientId
            """)
    List<ActiviteParClient> activiteParClient();

    /**
     * Message du dernier echec d'un fournisseur : une ligne, la plus recente.
     *
     * <p>Borne a la meme nature que les deux agregats ci-dessus, et pour la meme raison
     * poussee d'un cran : sans cela un fournisseur pourrait afficher « 0 echec » et, juste a
     * cote, le message d'une reaffectation ratee que ce compteur ne compte plus.
     */
    List<CrmSyncAttempt> findTop1ByProviderIdAndStatusAndNatureOrderByAttemptedAtDesc(
            String providerId, CrmSyncAttemptStatus status, CrmSyncAttemptNature nature);

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
