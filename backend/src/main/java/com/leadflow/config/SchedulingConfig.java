package com.leadflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active l'ordonnanceur, dont le filet de republication de la capture a besoin. Isole dans
 * sa propre classe pour qu'un test qui voudrait s'en passer puisse l'exclure sans toucher
 * a la classe d'application.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
