package com.leadflow.monitoring;

import com.leadflow.qualification.Lead;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Les series quotidiennes du monitoring. {@code Repository} nu, comme {@link StatsRepository}
 * et {@link LeadQueryRepository} : le monitoring n'ecrit pas, et l'interface le montre.
 *
 * <p><strong>Premier repository natif du projet</strong>, et deux contraintes independantes
 * l'imposent. Le regroupement par jour dans un fuseau ne s'exprime pas en JPQL — ni
 * {@code date_trunc}, ni {@code AT TIME ZONE} — et la seule alternative, poser
 * {@code hibernate.jdbc.time_zone}, changerait la lecture de tous les {@code Instant} du
 * projet pour resoudre le besoin de trois requetes. Et {@code percentile_cont} n'existe pas
 * davantage en JPQL.
 *
 * <p>Le prix du natif : <strong>Hibernate ne valide plus ces requetes au demarrage</strong>.
 * Une colonne renommee ne se verrait qu'a l'execution, et c'est pour cela que les tests de ce
 * repository sont des {@code @SpringBootTest} contre un vrai Postgres.
 *
 * <p>{@code clientId} est une {@code String} et non un {@code UUID} : un parametre {@code UUID}
 * a {@code null} n'a aucun type deductible pour Postgres, qui refuse alors la requete entiere
 * avec « could not determine data type of parameter ». Le {@code cast} le lui donne.
 */
public interface SeriesRepository extends Repository<Lead, UUID> {

    @Query(
            value =
                    """
                    select (e.received_at at time zone :fuseau)::date as jour,
                           count(*)                                     as captures,
                           count(*) filter (where e.status = 'DISCARDED') as ecartes
                    from raw_lead_event e
                    where e.received_at >= :depuis
                      and (cast(:clientId as uuid) is null
                           or e.client_id = cast(:clientId as uuid))
                    group by jour
                    order by jour
                    """,
            nativeQuery = true)
    List<PointVolumeBrut> volumeParJour(
            @Param("clientId") String clientId,
            @Param("depuis") Instant depuis,
            @Param("fuseau") String fuseau);

    @Query(
            value =
                    """
                    select (l.created_at at time zone :fuseau)::date        as jour,
                           count(*) filter (where l.intent_source = 'GEMINI') as gemini,
                           count(*) filter (where l.intent_source = 'RULES')  as lexique
                    from lead l
                    where l.created_at >= :depuis
                      and (cast(:clientId as uuid) is null
                           or l.client_id = cast(:clientId as uuid))
                    group by jour
                    order by jour
                    """,
            nativeQuery = true)
    List<PointIntentionBrut> intentionsParJour(
            @Param("clientId") String clientId,
            @Param("depuis") Instant depuis,
            @Param("fuseau") String fuseau);

    @Query(
            value =
                    """
                    with premier_succes as (
                        select a.lead_id, min(a.attempted_at) as sync_at
                        from crm_sync_attempt a
                        where a.status = 'SUCCESS'
                        group by a.lead_id
                    )
                    select (p.sync_at at time zone :fuseau)::date as jour,
                           percentile_cont(0.5) within group (
                               order by extract(epoch from (p.sync_at - e.received_at))
                           ) as "medianeSecondes",
                           percentile_cont(0.95) within group (
                               order by extract(epoch from (p.sync_at - e.received_at))
                           ) as "p95Secondes"
                    from premier_succes p
                    join lead l           on l.id = p.lead_id
                    join raw_lead_event e on e.id = l.raw_event_id
                    where p.sync_at >= :depuis
                      and (cast(:clientId as uuid) is null
                           or l.client_id = cast(:clientId as uuid))
                    group by jour
                    order by jour
                    """,
            nativeQuery = true)
    List<PointDelaiBrut> delaisParJour(
            @Param("clientId") String clientId,
            @Param("depuis") Instant depuis,
            @Param("fuseau") String fuseau);
}
