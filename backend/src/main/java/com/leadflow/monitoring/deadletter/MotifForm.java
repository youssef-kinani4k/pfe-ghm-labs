package com.leadflow.monitoring.deadletter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Le motif d'un rejeu ou d'un ecart. Obligatoire pour la meme raison que celui de la
 * reattribution : {@code replayed_by} seul ne dit pas pourquoi.
 */
public record MotifForm(@NotBlank @Size(max = 500) String reason) {
}
