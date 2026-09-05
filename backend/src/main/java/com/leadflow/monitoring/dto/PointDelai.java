package com.leadflow.monitoring.dto;

import java.time.LocalDate;

/**
 * Le delai capture -> ERP d'une journee, en secondes.
 *
 * <p>Les deux mesures sont des {@link Double} et non des {@code double}, seuls champs des
 * trois records de series dans ce cas : {@code null} y veut dire « aucun lead synchronise ce
 * jour-la », et le primitif le lierait a zero — soit « synchronise instantanement », l'inverse
 * du sens. Meme raison que {@code ScoringForm.seuilNotification} en F12.
 */
public record PointDelai(LocalDate jour, Double medianeSecondes, Double p95Secondes) {
}
