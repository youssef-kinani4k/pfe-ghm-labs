package com.leadflow.monitoring;

import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.monitoring.dto.StatsView;
import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agregats du dashboard, calcules en base et jamais en memoire.
 *
 * <p>Les cartes sont completees a zero pour toutes les valeurs de l'enumeration. Une valeur
 * absente du {@code group by} veut dire « aucune ligne », pas « pas de donnee » : la
 * distinction se perd cote client si le serveur ne la tranche pas.
 */
@Service
public class StatsService {

    private final StatsRepository stats;
    private final SalesRepRepository commerciaux;

    public StatsService(StatsRepository stats, SalesRepRepository commerciaux) {
        this.stats = stats;
        this.commerciaux = commerciaux;
    }

    @Transactional(readOnly = true)
    public StatsView calcule(UUID clientId, Instant from, Instant to) {
        Map<String, Long> parStatut = complete(
                carte(stats.leadsParStatut(clientId, from, to)),
                Arrays.stream(LeadStatus.values()).map(Enum::name).toList());

        Map<String, Long> parStatutDEvenement = complete(
                carte(stats.evenementsParStatut(clientId, from, to)),
                Arrays.stream(RawLeadEventStatus.values()).map(Enum::name).toList());

        Map<String, Long> parSource = complete(
                carte(stats.leadsParSourceDIntention(clientId, from, to)),
                Arrays.stream(IntentSource.values()).map(Enum::name).toList());

        Map<String, Long> parCommercial = carte(stats.leadsParCommercial(clientId, from, to));

        long total = parStatut.values().stream().mapToLong(Long::longValue).sum();
        long synchronises = parStatut.getOrDefault(LeadStatus.SYNCED.name(), 0L);

        return new StatsView(
                total,
                parStatut,
                parStatutDEvenement,
                total == 0 ? 0d : (double) synchronises / total,
                carte(stats.leadsParIntention(clientId, from, to)),
                parSource,
                parCommercial,
                nomsDeCommercial(parCommercial.keySet()));
    }

    private Map<String, Long> carte(List<Comptage> comptages) {
        Map<String, Long> resultat = new LinkedHashMap<>();
        comptages.forEach(comptage -> resultat.put(comptage.getCle(), comptage.getTotal()));
        return resultat;
    }

    private Map<String, Long> complete(Map<String, Long> carte, List<String> clesAttendues) {
        Map<String, Long> resultat = new LinkedHashMap<>();
        clesAttendues.forEach(cle -> resultat.put(cle, carte.getOrDefault(cle, 0L)));
        return resultat;
    }

    /**
     * Les identifiants seuls ne disent rien de l'equite du tour de role. Une seule requete,
     * bornee par le nombre de commerciaux ayant recu au moins un lead.
     */
    private Map<String, String> nomsDeCommercial(Set<String> identifiants) {
        if (identifiants.isEmpty()) {
            return Map.of();
        }
        List<UUID> uuids = identifiants.stream().map(UUID::fromString).toList();
        return commerciaux.findAllById(uuids).stream()
                .collect(Collectors.toMap(
                        commercial -> commercial.getId().toString(), SalesRep::getFullName));
    }
}
