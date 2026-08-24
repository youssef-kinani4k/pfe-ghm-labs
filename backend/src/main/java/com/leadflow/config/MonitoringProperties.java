package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reglages de l'observateur. Un seul record pour les trois mecanismes de F6 : ils n'ont de
 * sens qu'ensemble, et les separer multiplierait les prefixes sans rien clarifier.
 *
 * @param deadletter consommateur de la DLQ
 * @param stream     flux SSE et son consommateur
 * @param relay      filets de republication du pipeline (T12)
 */
@ConfigurationProperties(prefix = "leadflow.monitoring")
public record MonitoringProperties(
        Deadletter deadletter, Stream stream, Relay relay) {

    public record Deadletter(Listener listener) {
    }

    public record Stream(
            Listener listener, Duration emitterTimeout, Duration heartbeatInterval) {
    }

    /**
     * @param qualifiedAfter age minimal d'un lead QUALIFIED avant republication
     * @param routedAfter    age minimal d'un lead ROUTED avant republication
     * @param interval       periode de balayage
     */
    public record Relay(
            Duration qualifiedAfter, Duration routedAfter, Duration interval) {
    }

    public record Listener(boolean enabled) {
    }
}
