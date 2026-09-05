package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.SeriesView;
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
    private final SeriesService seriesService;

    public StatsController(StatsService service, SeriesService seriesService) {
        this.service = service;
        this.seriesService = seriesService;
    }

    @GetMapping
    public StatsView stats(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return service.calcule(clientId, from, to);
    }

    /**
     * Les trois series quotidiennes de l'ecran d'analyse.
     *
     * <p>{@code jours} est borne cote serveur a 7, 30 ou 90 par {@code SeriesService} : sans
     * borne, un appel a 100 000 jours balaierait les tables entieres. Le defaut est 30 —
     * assez pour voir une tendance, assez court pour qu'une instance de demonstration ait des
     * donnees.
     */
    @GetMapping("/series")
    public SeriesView series(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(defaultValue = "30") int jours) {
        return seriesService.calcule(clientId, jours);
    }
}
