package com.leadflow.routing;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection Spring Data : un commercial et la date de sa derniere attribution.
 *
 * <p>Interface et non {@code record} : Spring Data sait construire une projection fermee
 * a partir des alias de la requete, sans constructeur a faire correspondre.
 */
public interface AttributionRecente {

    UUID getSalesRepId();

    Instant getDerniereAttribution();
}
