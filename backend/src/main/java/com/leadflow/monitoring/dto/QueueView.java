package com.leadflow.monitoring.dto;

/**
 * Etat d'une file, lu en AMQP.
 *
 * <p>{@code reachable} a false plutot qu'une erreur : un broker momentanement injoignable ne
 * doit pas faire echouer l'ecran qui sert justement a le constater.
 */
public record QueueView(
        String name, boolean reachable, long messageCount, long consumerCount) {
}
