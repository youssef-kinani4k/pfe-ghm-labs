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
import java.util.List;
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
     * @param secrets les secrets acceptables, <b>le courant en premier</b>. L'ordre est
     *     porteur de sens : le cas normal ne calcule qu'un seul HMAC, et le second n'est
     *     essaye que si le premier ne correspond pas — c'est-a-dire pendant une fenetre de
     *     transition, et sur les tentatives reellement fausses.
     * @return la forme <b>canonique</b> de la signature et le rang du secret qui a repondu.
     *     La forme canonique est reconstruite a partir des valeurs validees et jamais
     *     reprise du texte recu : l'analyse tolere les espaces et les parametres inconnus,
     *     si bien que {@code t=1,v1=ab}, {@code t=1, v1=ab} et {@code t=1,v1=ab,x=9} sont
     *     trois textes valides pour une meme soumission. Les stocker tels quels laisserait
     *     injecter des doublons en repaddant l'en-tete.
     * @throws WebhookAuthenticationException si l'en-tete est absent, malforme, hors
     *     fenetre, ou si <b>aucun</b> des secrets ne correspond. Le message est destine aux
     *     logs : l'appelant rend le meme 401 pour les cinq causes de refus.
     */
    public SignatureVerifiee verifie(
            List<String> secrets, String corpsBrut, String enTeteSignature, Instant maintenant) {
        if (enTeteSignature == null || enTeteSignature.isBlank()) {
            throw new WebhookAuthenticationException("En-tete de signature absent");
        }

        // L'horodatage et la forme de l'en-tete ne dependent d'aucun secret : les controler
        // ici, une seule fois, evite d'essayer deux secrets contre un texte qui n'en
        // contient pas.
        long horodatage = horodatage(enTeteSignature);
        String signatureFournie = valeur(enTeteSignature, "v1");

        Duration ecart = Duration.between(instant(horodatage), maintenant).abs();
        if (ecart.compareTo(properties.tolerance()) > 0) {
            throw new WebhookAuthenticationException(
                    "Horodatage hors fenetre : ecart de " + ecart.toSeconds() + "s");
        }

        String charge = horodatage + "." + corpsBrut;
        for (int rang = 0; rang < secrets.size(); rang++) {
            String attendue = calcule(secrets.get(rang), charge);
            // Comparaison en temps constant : une comparaison de chaines ordinaire s'arrete
            // au premier octet different et laisse deduire la signature attendue octet par
            // octet.
            if (MessageDigest.isEqual(
                    attendue.getBytes(UTF_8), signatureFournie.getBytes(UTF_8))) {
                return new SignatureVerifiee("t=" + horodatage + ",v1=" + attendue, rang > 0);
            }
        }
        throw new WebhookAuthenticationException("Signature invalide");
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
