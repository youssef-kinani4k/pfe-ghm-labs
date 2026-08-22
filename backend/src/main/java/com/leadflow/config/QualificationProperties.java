package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reglages de l'etape de qualification.
 *
 * @param dedupWindow duree pendant laquelle un second lead du meme client portant le meme
 *     email normalise est considere comme un doublon. Propriete d'instance et non de
 *     client : un cycle de vente long et un e-commerce n'ont pas la meme notion de doublon,
 *     et le jour ou cela se manifestera, le reglage rejoindra {@code scoring_config}.
 */
@ConfigurationProperties(prefix = "leadflow.qualification")
public record QualificationProperties(Duration dedupWindow) {
}
