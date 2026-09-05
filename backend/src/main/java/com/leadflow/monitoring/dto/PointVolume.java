package com.leadflow.monitoring.dto;

import java.time.LocalDate;

/**
 * Une journee de capture.
 *
 * <p>{@code jour} est une {@link LocalDate} et non un {@code Instant} : le point represente
 * une journee entiere du fuseau de regroupement, et rendre un instant laisserait croire a une
 * precision qui n'existe pas.
 */
public record PointVolume(LocalDate jour, long captures, long ecartes) {
}
