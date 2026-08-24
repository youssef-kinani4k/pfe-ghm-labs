package com.leadflow.tenant;

import com.leadflow.tenant.dto.ClientCreated;
import com.leadflow.tenant.dto.ClientDetailAdmin;
import com.leadflow.tenant.dto.ClientForm;
import com.leadflow.tenant.dto.ClientSummaryAdmin;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration des boutiques, sous {@code /api/admin} pour ne pas entrer en collision avec
 * {@code /api/clients}, l'annuaire en lecture seule de {@code monitoring/} qui alimente les
 * filtres du dashboard. Les deux coexistent : ils servent deux besoins distincts.
 */
@RestController
@RequestMapping("/api/admin/clients")
public class ClientAdminController {

    private final ClientAdminService service;

    public ClientAdminController(ClientAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClientSummaryAdmin> liste() {
        return service.liste();
    }

    @GetMapping("/{id}")
    public ClientDetailAdmin fiche(@PathVariable UUID id) {
        return service.fiche(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientCreated cree(@Valid @RequestBody ClientForm formulaire) {
        return service.cree(formulaire);
    }
}
