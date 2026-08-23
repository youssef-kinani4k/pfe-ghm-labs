package com.leadflow.monitoring.stream;

import com.leadflow.config.MonitoringProperties;
import com.leadflow.monitoring.dto.DeadLetterView;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registre des emetteurs SSE ouverts.
 *
 * <p><b>Mono-instance</b>, comme {@code PendingEventRelay} et le tour de role de F4 : le
 * registre est en memoire, donc a deux instances un abonne de l'une ne verrait pas ce que
 * traite l'autre. La reponse serait un exchange fanout et une file exclusive par instance —
 * une evolution, pas un correctif.
 *
 * <p>Le commentaire de maintien toutes les 20 secondes n'est pas cosmetique : sans trafic,
 * un proxy coupe une connexion inactive et l'ecran se fige <b>sans le dire</b>.
 */
@Component
public class LeadStreamBroadcaster {

    private final List<SseEmitter> emetteurs = new CopyOnWriteArrayList<>();
    private final Duration expiration;

    /**
     * Dernier evenement diffuse, expose pour les tests : un flux SSE ne s'observe pas
     * autrement sans monter un client HTTP complet.
     */
    private volatile StreamEvent dernier;

    public LeadStreamBroadcaster(MonitoringProperties properties) {
        this.expiration = properties.stream().emitterTimeout();
    }

    public SseEmitter abonne() {
        SseEmitter emetteur = new SseEmitter(expiration.toMillis());
        // Les trois retraits sont indispensables : un emetteur mort laisse dans le registre
        // fait echouer chaque diffusion suivante.
        emetteur.onCompletion(() -> emetteurs.remove(emetteur));
        emetteur.onTimeout(() -> emetteurs.remove(emetteur));
        emetteur.onError(erreur -> emetteurs.remove(emetteur));
        emetteurs.add(emetteur);
        return emetteur;
    }

    public void diffuseLead(StreamEvent evenement) {
        this.dernier = evenement;
        diffuse("lead", evenement);
    }

    /** Alimente le meme flux depuis le journal, en memoire : c'est le meme processus. */
    public void diffuseMort(DeadLetterView mort) {
        diffuse("dead-letter", mort);
    }

    @Scheduled(fixedDelayString = "${leadflow.monitoring.stream.heartbeat-interval}")
    void maintientLesConnexions() {
        emetteurs.forEach(emetteur -> {
            try {
                emetteur.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException ferme) {
                emetteurs.remove(emetteur);
            }
        });
    }

    private void diffuse(String nom, Object charge) {
        emetteurs.forEach(emetteur -> {
            try {
                emetteur.send(SseEmitter.event().name(nom).data(charge));
            } catch (IOException | IllegalStateException ferme) {
                // Un client parti n'est pas une erreur : on le retire et on continue.
                emetteurs.remove(emetteur);
            }
        });
    }

    public int nombreDAbonnes() {
        return emetteurs.size();
    }

    public StreamEvent dernierEvenementDiffuse() {
        return dernier;
    }
}
