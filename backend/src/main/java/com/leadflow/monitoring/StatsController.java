package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.StatsView;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tous les compteurs du dashboard en un appel, filtrables par client et par periode. */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService service;

    public StatsController(StatsService service) {
        this.service = service;
    }

    @GetMapping
    public StatsView stats(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return service.calcule(clientId, from, to);
    }
}
