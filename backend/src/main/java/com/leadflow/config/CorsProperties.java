package com.leadflow.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origines autorisees a appeler l'API depuis un navigateur.
 *
 * <p>La liste est <b>vide en production</b>, et c'est le cas nominal : Nginx sert le
 * dashboard et relaie l'API sous une origine unique, donc il n'y a aucune requete
 * cross-origin a autoriser. Elle n'est peuplee qu'en developpement, ou ng serve tient le
 * :4200 pendant que le backend tient le :8090.
 *
 * <p>Un record separe de {@link SecurityProperties} plutot qu'un champ de plus : la cle
 * maitre et les origines n'ont ni la meme duree de vie ni le meme lecteur, et
 * SecurityProperties est instancie a la main par trois tests de chiffrement.
 */
@ConfigurationProperties(prefix = "leadflow.security.cors")
public record CorsProperties(List<String> allowedOrigins) {

    /** Jamais {@code null} : une propriete absente vaut une liste vide, pas une erreur. */
    public List<String> origines() {
        return allowedOrigins == null ? List.of() : allowedOrigins;
    }
}
