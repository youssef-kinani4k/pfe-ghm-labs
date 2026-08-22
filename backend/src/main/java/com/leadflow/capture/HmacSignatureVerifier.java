package com.leadflow.capture;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.leadflow.common.WebhookAuthenticationException;
import com.leadflow.config.WebhookProperties;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifie l'en-tete {@code X-Leadflow-Signature: t=<epoch>,v1=<hex>}, ou l'hexadecimal est
 * {@code HMAC-SHA256(secret du client, t + "." + corps brut)}.
 *
 * <p>L'horodatage fait partie de la charge signee : il ne peut donc pas etre bricole sans
 * invalider la signature. C'est la raison du format a un seul en-tete plutot que deux.
 *
 * <p>Ne connait ni la base, ni HTTP, ni l'horloge du systeme : tout arrive en parametre.
 * C'est ce qui rend la fenetre de tolerance testable a la seconde pres, sans conteneur et
 * sans faire dormir un test.
 */
@Component
public class HmacSignatureVerifier {

    private static final String ALGORITHME = "HmacSHA256";

    private final WebhookProperties properties;

    public HmacSignatureVerifier(WebhookProperties properties) {
        this.properties = properties;
    }

    /**
     * @return la forme <b>canonique</b> de la signature, {@code t=<epoch>,v1=<hex>}, a
     *     utiliser comme cle d'idempotence. Elle est reconstruite a partir des valeurs
     *     validees et jamais reprise du texte recu : l'analyse tolere les espaces et les
     *     parametres inconnus, si bien que {@code t=1,v1=ab}, {@code t=1, v1=ab} et
     *     {@code t=1,v1=ab,x=9} sont trois textes valides pour une meme soumission. Les
     *     stocker tels quels laisserait injecter des doublons en repaddant l'en-tete.
     * @throws WebhookAuthenticationException si l'en-tete est absent, malforme, hors
     *     fenetre, ou si la signature ne correspond pas. Le message est destine aux logs.
     */
    public String verifie(
            String secret, String corpsBrut, String enTeteSignature, Instant maintenant) {
        if (enTeteSignature == null || enTeteSignature.isBlank()) {
            throw new WebhookAuthenticationException("En-tete de signature absent");
        }

        long horodatage = horodatage(enTeteSignature);
        String signatureFournie = valeur(enTeteSignature, "v1");

        Duration ecart = Duration.between(instant(horodatage), maintenant).abs();
        if (ecart.compareTo(properties.tolerance()) > 0) {
            throw new WebhookAuthenticationException(
                    "Horodatage hors fenetre : ecart de " + ecart.toSeconds() + "s");
        }

        String attendue = calcule(secret, horodatage + "." + corpsBrut);
        // Comparaison en temps constant : une comparaison de chaines ordinaire s'arrete au
        // premier octet different et laisse deduire la signature attendue octet par octet.
        if (!MessageDigest.isEqual(attendue.getBytes(UTF_8), signatureFournie.getBytes(UTF_8))) {
            throw new WebhookAuthenticationException("Signature invalide");
        }
        return "t=" + horodatage + ",v1=" + attendue;
    }

    /**
     * {@code Long.parseLong} accepte des valeurs qu'{@code Instant} refuse : sans cette
     * conversion gardee, un horodatage absurde ferait une erreur serveur sur la seule route
     * ouverte du projet, a partir d'une entree non authentifiee.
     */
    private Instant instant(long horodatage) {
        try {
            return Instant.ofEpochSecond(horodatage);
        } catch (DateTimeException e) {
            throw new WebhookAuthenticationException("Horodatage hors des bornes representables");
        }
    }

    private long horodatage(String enTete) {
        try {
            return Long.parseLong(valeur(enTete, "t"));
        } catch (NumberFormatException e) {
            throw new WebhookAuthenticationException("Horodatage illisible dans l'en-tete");
        }
    }

    /** Extrait {@code cle=valeur} d'un en-tete de la forme {@code t=...,v1=...}. */
    private String valeur(String enTete, String cle) {
        for (String partie : enTete.split(",")) {
            String[] paire = partie.trim().split("=", 2);
            if (paire.length == 2 && paire[0].trim().equals(cle)) {
                return paire[1].trim();
            }
        }
        throw new WebhookAuthenticationException(
                "En-tete de signature malforme : '" + cle + "' absent");
    }

    private String calcule(String secret, String charge) {
        try {
            Mac mac = Mac.getInstance(ALGORITHME);
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), ALGORITHME));
            return HexFormat.of().formatHex(mac.doFinal(charge.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            // Algorithme absent de la JVM ou secret vide : ce n'est pas un refus
            // d'authentification mais une defaillance de configuration.
            throw new IllegalStateException("Calcul HMAC impossible", e);
        }
    }
}
