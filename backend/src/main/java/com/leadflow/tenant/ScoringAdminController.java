package com.leadflow.tenant;

import com.leadflow.tenant.dto.ScoringForm;
import com.leadflow.tenant.dto.ScoringView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le bareme a son propre controleur plutot qu'une methode de plus sur
 * {@code ClientAdminController} : c'est un sous-etat de la boutique, avec sa validation et sa
 * vue calculee, et {@code ClientForm} ne doit pas se mettre a porter dix champs de scoring.
 */
@RestController
@RequestMapping("/api/admin/clients/{id}/scoring")
public class ScoringAdminController {

    private final ScoringAdminService service;

    public ScoringAdminController(ScoringAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ScoringView lit(@PathVariable UUID id) {
        return service.lit(id);
    }

    @PutMapping
    public ScoringView remplace(@PathVariable UUID id, @Valid @RequestBody ScoringForm form) {
        return service.remplace(id, form);
    }
}
