package com.leadflow.tenant;

import com.leadflow.tenant.dto.ClientDetailAdmin;
import com.leadflow.tenant.dto.ClientSummaryAdmin;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
