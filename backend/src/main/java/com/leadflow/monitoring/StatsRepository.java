package com.leadflow.monitoring;

import com.leadflow.qualification.Lead;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Agregats du dashboard. {@code Repository} nu, comme {@link LeadQueryRepository} : le
 * monitoring n'ecrit pas, et l'interface le montre.
 *
 * <p>Tout est compte en base. Charger les lignes pour les compter en memoire ferait payer a
 * l'ecran le volume de la table, et les index {@code idx_lead_client_status} et
 * {@code idx_raw_lead_event_client_received} poses en F1 servent exactement ici.
 *
 * <p>Les filtres facultatifs passent par le motif {@code :param is null or ...} plutot que
 * par des Specifications : une agregation n'a pas de forme dynamique, et cinq requetes
 * nommees se relisent mieux qu'un constructeur de criteres.
 *
 * <p>Le {@code cast} autour de chaque parametre facultatif n'est pas decoratif : un
 * parametre qui n'apparait que dans {@code ? is null} n'a aucun type deductible pour
 * Postgres, qui refuse alors la requete entiere avec « could not determine data type of
 * parameter ». Le cast lui donne ce type.
 */
public interface StatsRepository extends Repository<Lead, UUID> {

    @Query("""
            select cast(l.status as string) as cle, count(l) as total from Lead l
            where (cast(:clientId as java.util.UUID) is null or l.clientId = :clientId)
              and (cast(:from as java.time.Instant) is null or l.createdAt >= :from)
              and (cast(:to as java.time.Instant) is null or l.createdAt < :to)
            group by l.status
            """)
    List<Comptage> leadsParStatut(
            @Param("clientId") UUID clientId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select coalesce(l.detectedIntent, 'inconnue') as cle, count(l) as total from Lead l
            where (cast(:clientId as java.util.UUID) is null or l.clientId = :clientId)
              and (cast(:from as java.time.Instant) is null or l.createdAt >= :from)
              and (cast(:to as java.time.Instant) is null or l.createdAt < :to)
            group by l.detectedIntent
            """)
    List<Comptage> leadsParIntention(
            @Param("clientId") UUID clientId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select cast(l.intentSource as string) as cle, count(l) as total from Lead l
            where l.intentSource is not null
              and (cast(:clientId as java.util.UUID) is null or l.clientId = :clientId)
              and (cast(:from as java.time.Instant) is null or l.createdAt >= :from)
              and (cast(:to as java.time.Instant) is null or l.createdAt < :to)
            group by l.intentSource
            """)
    List<Comptage> leadsParSourceDIntention(
            @Param("clientId") UUID clientId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select cast(l.assignedSalesRepId as string) as cle, count(l) as total from Lead l
            where l.assignedSalesRepId is not null
              and (cast(:clientId as java.util.UUID) is null or l.clientId = :clientId)
              and (cast(:from as java.time.Instant) is null or l.createdAt >= :from)
              and (cast(:to as java.time.Instant) is null or l.createdAt < :to)
            group by l.assignedSalesRepId
            """)
    List<Comptage> leadsParCommercial(
            @Param("clientId") UUID clientId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select cast(e.status as string) as cle, count(e) as total
            from com.leadflow.capture.RawLeadEvent e
            where (cast(:clientId as java.util.UUID) is null or e.clientId = :clientId)
              and (cast(:from as java.time.Instant) is null or e.receivedAt >= :from)
              and (cast(:to as java.time.Instant) is null or e.receivedAt < :to)
            group by e.status
            """)
    List<Comptage> evenementsParStatut(
            @Param("clientId") UUID clientId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
