package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.LeadDetail;
import com.leadflow.monitoring.dto.LeadSummary;
import com.leadflow.monitoring.dto.PageResponse;
import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lecture seule. Le tenant est un filtre de requete, jamais une donnee portee par le jeton :
 * le dashboard est une console d'agence et voit tous les clients.
 */
@RestController
@RequestMapping("/api/leads")
public class LeadQueryController {

    /** Plafond de page : une liste d'ecran ne demande jamais mille lignes d'un coup. */
    private static final int TAILLE_MAXIMALE = 100;

    private final LeadQueryService service;
    private final LeadDetailService detailService;

    public LeadQueryController(LeadQueryService service, LeadDetailService detailService) {
        this.service = service;
        this.detailService = detailService;
    }

    @GetMapping
    public PageResponse<LeadSummary> liste(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) List<LeadStatus> status,
            @RequestParam(required = false) String intent,
            @RequestParam(required = false) IntentSource intentSource,
            @RequestParam(required = false) UUID salesRepId,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean chaud,
            Pageable pagination) {

        LeadFilter filtre = new LeadFilter(
                clientId, status, intent, intentSource, salesRepId, minScore, from, to, q,
                chaud);
        return service.cherche(filtre, plafonne(pagination));
    }

    /**
     * Une route et non une boite modale cote frontend : une URL partageable vaut mieux
     * qu'une modale, en exploitation comme en demonstration.
     */
    @GetMapping("/{id}")
    public LeadDetail detail(@PathVariable UUID id) {
        return detailService.detail(id);
    }

    private Pageable plafonne(Pageable demande) {
        if (demande.getPageSize() <= TAILLE_MAXIMALE) {
            return demande;
        }
        // Plafonne en silence plutot qu'en erreur : une taille excessive est une maladresse
        // d'appelant, pas une faute qui merite de faire echouer l'ecran.
        return PageRequest.of(demande.getPageNumber(), TAILLE_MAXIMALE, demande.getSort());
    }
}
