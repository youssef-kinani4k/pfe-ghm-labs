package com.leadflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.common.SecretCipher;
import com.leadflow.config.SecurityProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Garde-fou sur le jeu de demonstration : les valeurs en clair documentees en commentaire de
 * {@code R__demo_data.sql} doivent etre exactement celles que ses colonnes chiffrees
 * contiennent.
 *
 * <p>Ce test existe parce que l'ecart a dure deux semaines sans que rien ne le signale : le
 * secret HMAC documente ne correspondait plus au chiffre pose en base, et la seule
 * manifestation etait un {@code 401} sur le webhook — indiscernable des quatre autres causes
 * de refus, qui rendent volontairement le meme code. Corriger la valeur sans poser ce
 * controle reprogrammerait le meme incident.
 *
 * <p>Il est volontairement unitaire : il lit les deux fichiers depuis le classpath et n'a
 * besoin ni de Spring, ni de Docker, ni de base de donnees. Il tourne donc a chaque
 * {@code ./mvnw test}, y compris quand le demon Docker est absent.
 */
class DonneesDeDemoTest {

    /** La cle maitre de repli du profil dev, celle qui a produit les valeurs chiffrees. */
    private static final Pattern CLE_MAITRE =
            Pattern.compile("master-key:\\s*\\$\\{LEADFLOW_MASTER_KEY:([^}]+)}");

    /** Le secret HMAC en clair, ecrit dans le bloc de commentaires de tete. */
    private static final Pattern HMAC_EN_CLAIR =
            Pattern.compile("Secret HMAC en clair[^\\n]*\\n--\\s+([0-9a-fA-F]{64})");

    /** Le contenu en clair de crm_config, ecrit dans le meme bloc. */
    private static final Pattern CRM_CONFIG_EN_CLAIR =
            Pattern.compile("Contenu en clair de crm_config[^\\n]*\\n--\\s+(\\{[^\\n]*})");

    /**
     * Les deux valeurs chiffrees du tuple INSERT, ancrees sur l'identifiant du connecteur qui
     * les separe. Une reecriture de la migration qui deplacerait ces colonnes fait echouer
     * l'extraction plutot que de laisser passer le controle en silence.
     */
    private static final Pattern VALEURS_CHIFFREES = Pattern.compile(
            "'([A-Za-z0-9+/=]{40,})',\\s*'dolibarr',\\s*'([A-Za-z0-9+/=]{40,})',");

    private final String migration = litDuClasspath("db/dev/R__demo_data.sql");

    private final SecretCipher cipher = new SecretCipher(
            new SecurityProperties(extrait(CLE_MAITRE, litDuClasspath("application-dev.yml"), 1)));

    @Test
    void leSecretHmacDocumenteEstCeluiQuiEstChiffreEnBase() {
        String documente = extrait(HMAC_EN_CLAIR, migration, 1);
        String chiffre = extrait(VALEURS_CHIFFREES, migration, 1);

        assertThat(cipher.decrypt(chiffre)).isEqualTo(documente);
    }

    @Test
    void laConfigurationCrmDocumenteeEstCelleQuiEstChiffreeEnBase() {
        String documente = extrait(CRM_CONFIG_EN_CLAIR, migration, 1);
        String chiffre = extrait(VALEURS_CHIFFREES, migration, 2);

        assertThat(cipher.decrypt(chiffre)).isEqualTo(documente);
    }

    private static String extrait(Pattern motif, String texte, int groupe) {
        Matcher matcher = motif.matcher(texte);
        if (!matcher.find()) {
            throw new AssertionError("Motif introuvable, le fichier a change de forme : " + motif);
        }
        return matcher.group(groupe);
    }

    private static String litDuClasspath(String chemin) {
        try (InputStream flux =
                DonneesDeDemoTest.class.getClassLoader().getResourceAsStream(chemin)) {
            if (flux == null) {
                throw new AssertionError("Ressource absente du classpath : " + chemin);
            }
            return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
