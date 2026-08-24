package com.leadflow.monitoring.dto;

import java.util.List;

/**
 * {@code pendingDeadLetters} vient de la base et non du broker : depuis F6, la table est la
 * source de verite des leads en echec, et la profondeur de la DLQ doit rester nulle. Voir
 * les deux cote a cote est exactement ce qui permet de detecter que le journal ne suit pas.
 */
public record QueuesView(List<QueueView> queues, long pendingDeadLetters) {
}
