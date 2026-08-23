package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.QueuesView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queues")
public class QueueController {

    private final QueueService service;

    public QueueController(QueueService service) {
        this.service = service;
    }

    @GetMapping
    public QueuesView files() {
        return service.etatDesFiles();
    }
}
