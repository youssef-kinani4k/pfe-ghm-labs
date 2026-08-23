package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.ConnectorView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/connectors")
public class ConnectorHealthController {

    private final ConnectorHealthService service;

    public ConnectorHealthController(ConnectorHealthService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConnectorView> connecteurs() {
        return service.etatDesConnecteurs();
    }
}
