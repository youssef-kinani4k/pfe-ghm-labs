package com.leadflow.capture;

import java.util.UUID;

/**
 * Corps du {@code 202}. L'identifiant est rendu pour qu'un integrateur puisse correler sa
 * soumission avec ce qu'il verra plus tard dans le dashboard.
 */
public record CaptureAccepted(UUID eventId) {
}
