package com.leadflow.monitoring.stream;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE plutot que WebSocket : le flux est purement descendant, rien ne remonte du navigateur.
 *
 * <p>Cote client, <b>pas d'{@code EventSource}</b> : il ne sait pas poser d'en-tete
 * {@code Authorization}, et passer le jeton en parametre d'URL le ferait apparaitre dans les
 * journaux d'acces. Le service Angular lit le flux par {@code fetch} et decoupe les trames
 * lui-meme.
 */
@RestController
@RequestMapping("/api/stream")
public class LeadStreamController {

    private final LeadStreamBroadcaster diffuseur;

    public LeadStreamController(LeadStreamBroadcaster diffuseur) {
        this.diffuseur = diffuseur;
    }

    @GetMapping(value = "/leads", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter flux() {
        return diffuseur.abonne();
    }
}
