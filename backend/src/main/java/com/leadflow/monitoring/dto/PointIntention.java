package com.leadflow.monitoring.dto;

import java.time.LocalDate;

/**
 * Une journee d'analyse d'intention, repartie entre le modele et le lexique.
 *
 * <p>Le total des deux n'est pas le nombre de leads du jour : un lead sans source d'intention
 * ne compte nulle part.
 */
public record PointIntention(LocalDate jour, long gemini, long lexique) {
}
