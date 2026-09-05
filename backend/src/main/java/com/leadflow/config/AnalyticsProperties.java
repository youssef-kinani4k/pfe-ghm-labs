package com.leadflow.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reglages des series quotidiennes du monitoring.
 *
 * <p>Le fuseau n'est pas un detail de presentation : {@code date_trunc} decoupe selon le
 * fuseau de la session — UTC dans le conteneur — si bien qu'un lead recu a 00 h 30 heure
 * locale tomberait dans la journee de la veille. Le regroupement le nomme donc
 * explicitement.
 *
 * <p>Il est <strong>global a l'instance</strong> et non porte par la boutique, comme les
 * reglages du relais SMTP de F12 : l'agence lit ses graphiques depuis un seul endroit.
 *
 * <p>La validation a lieu ici, dans le constructeur compact, et non a chaque requete : un
 * fuseau mal orthographie empeche l'application de demarrer, plutot que de faire echouer le
 * premier chargement de l'ecran d'analyse. {@link #zone()} reconstruit le {@link ZoneId} a
 * chaque appel -- sans interet a memoiser, {@code ZoneId.of} tenant deja un cache du JDK, et
 * un record ne pouvant pas porter de champ hors composant sans cesser d'en etre un.
 */
@ConfigurationProperties(prefix = "leadflow.analytics")
public record AnalyticsProperties(String fuseau) {

    private static final String DEFAUT = "Europe/Paris";

    public AnalyticsProperties {
        if (fuseau == null || fuseau.isBlank()) {
            fuseau = DEFAUT;
        }
        ZoneId.of(fuseau);
    }

    public ZoneId zone() {
        return ZoneId.of(fuseau);
    }
}
