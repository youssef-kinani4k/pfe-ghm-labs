package com.leadflow.monitoring.deadletter;

import com.leadflow.monitoring.dto.DeadLetterView;
import com.leadflow.monitoring.dto.PageResponse;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le nom de l'operateur vient du {@link Principal}, donc du jeton, et jamais d'un parametre
 * de requete : une trace de responsabilite que l'appelant pourrait choisir ne tracerait
 * rien.
 */
@RestController
@RequestMapping("/api/dead-letters")
public class DeadLetterController {

    private final DeadLetterQueryService lecture;
    private final DeadLetterReplayService rejeu;

    public DeadLetterController(DeadLetterQueryService lecture, DeadLetterReplayService rejeu) {
        this.lecture = lecture;
        this.rejeu = rejeu;
    }

    @GetMapping
    public PageResponse<DeadLetterView> liste(
            @RequestParam(required = false) DeadLetterStatus status,
            @RequestParam(required = false) String originQueue,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Pageable pagination) {
        return lecture.cherche(status, originQueue, clientId, from, to, pagination);
    }

    @PostMapping("/{id}/replay")
    public void rejoue(@PathVariable UUID id, Principal operateur) {
        rejeu.rejoue(id, operateur.getName());
    }

    @PostMapping("/{id}/discard")
    public void ecarte(@PathVariable UUID id, Principal operateur) {
        rejeu.ecarte(id, operateur.getName());
    }
}
