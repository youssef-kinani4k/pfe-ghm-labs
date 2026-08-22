package com.leadflow.capture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.common.WebhookAuthenticationException;
import com.leadflow.config.WebhookProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Aucun conteneur, aucun contexte Spring : l'horloge est un parametre, donc aucun test ne
 * dort et la fenetre de tolerance se verifie a la seconde pres.
 */
class HmacSignatureVerifierTest {

    private static final String SECRET = "secret-de-signature";
    private static final String CORPS = "{\"source\":\"formulaire-devis\"}";
    private static final Instant MAINTENANT = Instant.ofEpochSecond(1755820000L);

    private final HmacSignatureVerifier verificateur =
            new HmacSignatureVerifier(new WebhookProperties(
                    "X-Leadflow-Signature", Duration.ofMinutes(5), 65536, null, null));

    private static String signe(String secret, long horodatage, String corps) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            String hex = HexFormat.of()
                    .formatHex(mac.doFinal((horodatage + "." + corps).getBytes(UTF_8)));
            return "t=" + horodatage + ",v1=" + hex;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void accepteUneSignatureValideDansLaFenetre() {
        String enTete = signe(SECRET, MAINTENANT.getEpochSecond(), CORPS);

        assertThatCode(() -> verificateur.verifie(SECRET, CORPS, enTete, MAINTENANT))
                .doesNotThrowAnyException();
    }

    @Test
    void refuseUnEnTeteAbsent() {
        assertThatThrownBy(() -> verificateur.verifie(SECRET, CORPS, null, MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void refuseUnEnTeteMalforme() {
        assertThatThrownBy(() -> verificateur.verifie(SECRET, CORPS, "pas-un-en-tete", MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void refuseUneSignatureFausse() {
        String enTete = signe("mauvais-secret", MAINTENANT.getEpochSecond(), CORPS);

        assertThatThrownBy(() -> verificateur.verifie(SECRET, CORPS, enTete, MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void refuseUnCorpsModifieApresSignature() {
        String enTete = signe(SECRET, MAINTENANT.getEpochSecond(), CORPS);

        assertThatThrownBy(() -> verificateur.verifie(
                        SECRET, "{\"source\":\"autre-chose\"}", enTete, MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void rejectsExpiredTimestamp() {
        // Nom en anglais : c'est celui que CLAUDE.md cite en exemple de commande.
        long vieux = MAINTENANT.minus(Duration.ofMinutes(6)).getEpochSecond();
        String enTete = signe(SECRET, vieux, CORPS);

        assertThatThrownBy(() -> verificateur.verifie(SECRET, CORPS, enTete, MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void refuseUnHorodatageTropLoinDansLeFutur() {
        // Une horloge client en avance ne doit pas non plus ouvrir la fenetre indefiniment.
        long futur = MAINTENANT.plus(Duration.ofMinutes(6)).getEpochSecond();
        String enTete = signe(SECRET, futur, CORPS);

        assertThatThrownBy(() -> verificateur.verifie(SECRET, CORPS, enTete, MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void accepteUnHorodatageAuBordDeLaFenetre() {
        long bord = MAINTENANT.minus(Duration.ofMinutes(5)).getEpochSecond();
        String enTete = signe(SECRET, bord, CORPS);

        assertThatCode(() -> verificateur.verifie(SECRET, CORPS, enTete, MAINTENANT))
                .doesNotThrowAnyException();
    }
}
