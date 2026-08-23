package com.leadflow.monitoring.deadletter;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.monitoring.dto.DeadLetterView;
import com.leadflow.monitoring.dto.PageResponse;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lecture filtree du journal, sur le meme schema que {@code LeadQueryService}. */
@Service
public class DeadLetterQueryService {

    static final String AVERTISSEMENT_REATTRIBUTION =
            "Rejouer ce message refait passer le lead par l'attribution : il peut changer "
                    + "de commercial et la rotation se decale.";

    private final DeadLetterRepository repository;
    private final ClientRepository clients;

    public DeadLetterQueryService(DeadLetterRepository repository, ClientRepository clients) {
        this.repository = repository;
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public PageResponse<DeadLetterView> cherche(
            DeadLetterStatus status,
            String originQueue,
            UUID clientId,
            Instant from,
            Instant to,
            Pageable pagination) {

        Page<DeadLetter> page = repository.findAll(
                specification(status, originQueue, clientId, from, to), pagination);

        Set<UUID> identifiants = page.getContent().stream()
                .map(DeadLetter::getClientId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> noms = identifiants.isEmpty()
                ? Map.of()
                : clients.findAllById(identifiants).stream()
                        .collect(Collectors.toMap(Client::getId, Client::getName));

        List<DeadLetterView> lignes = page.getContent().stream()
                .map(mort -> new DeadLetterView(
                        mort.getId(),
                        mort.getOriginQueue(),
                        mort.getRoutingKey(),
                        mort.getClientId(),
                        noms.get(mort.getClientId()),
                        mort.getLeadId(),
                        mort.getFailureReason(),
                        mort.getDeadAt(),
                        mort.getStatus(),
                        mort.getReplayedAt(),
                        mort.getReplayedBy(),
                        mort.getPayload(),
                        avertissement(mort)))
                .toList();

        return PageResponse.de(page, lignes);
    }

    /**
     * Les trois rejeux n'ont pas le meme risque. {@code lead.captured} est couvert par
     * l'index unique {@code lead.raw_event_id}, {@code lead.routed} par la reconstruction
     * de {@code CrmSyncState} ; {@code lead.qualified} n'a aucun garde-fou.
     */
    private String avertissement(DeadLetter mort) {
        return RabbitMQConfig.QUALIFIED_ROUTING_KEY.equals(mort.getRoutingKey())
                ? AVERTISSEMENT_REATTRIBUTION
                : null;
    }

    private Specification<DeadLetter> specification(
            DeadLetterStatus status, String originQueue, UUID clientId, Instant from, Instant to) {
        return (racine, requete, constructeur) -> {
            List<Predicate> predicats = new ArrayList<>();
            if (status != null) {
                predicats.add(constructeur.equal(racine.get("status"), status));
            }
            if (originQueue != null) {
                predicats.add(constructeur.equal(racine.get("originQueue"), originQueue));
            }
            if (clientId != null) {
                predicats.add(constructeur.equal(racine.get("clientId"), clientId));
            }
            if (from != null) {
                predicats.add(constructeur.greaterThanOrEqualTo(racine.get("deadAt"), from));
            }
            if (to != null) {
                predicats.add(constructeur.lessThan(racine.get("deadAt"), to));
            }
            return constructeur.and(predicats.toArray(Predicate[]::new));
        };
    }
}
