package com.leadflow.routing;

import com.leadflow.monitoring.LeadDetailService;
import com.leadflow.monitoring.dto.LeadDetail;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reattribution manuelle d'un lead.
 *
 * <p>Le chemin suit la ressource, le package suit la responsabilite : l'URL commence par
 * {@code /api/leads}, mais le choix du commercial appartient a {@code routing/} depuis F4.
 *
 * <p><b>L'import de {@code monitoring/} est assume.</b> Rendre la fiche rechargee evite un
 * aller-retour et garantit que l'ecran affiche l'etat reellement enregistre. C'est le meme
 * parti qu'en F8, ou {@code tenant/} importe {@code qualification/} : le cycle de packages
 * est un fait, et ce qui compte est le test qui verrouille le contrat.
 */
@RestController
@RequestMapping("/api/leads")
public class LeadReassignmentController {

    private final ReattributionService service;
    private final LeadDetailService detail;

    public LeadReassignmentController(ReattributionService service, LeadDetailService detail) {
        this.service = service;
        this.detail = detail;
    }

    @PostMapping("/{id}/reassign")
    public LeadDetail reattribue(
            @PathVariable UUID id,
            @Valid @RequestBody ReassignmentForm formulaire,
            Principal operateur) {
        service.reattribue(id, formulaire.salesRepId(), formulaire.reason(),
                operateur.getName());
        return detail.detail(id);
    }
}
