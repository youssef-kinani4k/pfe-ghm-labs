package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.ClientSummary;
import com.leadflow.monitoring.dto.SalesRepSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Annuaire de reference, en lecture seule comme tout {@code monitoring/}. */
@RestController
@RequestMapping("/api/clients")
public class ClientDirectoryController {

    private final ClientDirectoryService service;

    public ClientDirectoryController(ClientDirectoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClientSummary> clients() {
        return service.tousLesClients();
    }

    @GetMapping("/{id}/sales-reps")
    public List<SalesRepSummary> commerciaux(@PathVariable UUID id) {
        return service.commerciauxDe(id);
    }
}
