package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.TimelineEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controleur a part et non methode de plus sur {@code LeadQueryController} : une classe,
 * une raison de changer.
 *
 * <p>Pas de pagination : le volume est borne par construction — une capture, une
 * qualification, une attribution, au plus trois tentatives ERP avant la DLQ, une mort et un
 * rejeu.
 */
@RestController
@RequestMapping("/api/leads")
public class LeadTimelineController {

    private final LeadTimelineService service;

    public LeadTimelineController(LeadTimelineService service) {
        this.service = service;
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineEntry> timeline(@PathVariable UUID id) {
        return service.timeline(id);
    }
}
