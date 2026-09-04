package com.leadflow.notification.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.leadflow.config.NotificationProperties;
import com.leadflow.notification.model.NotificationLead;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Etage 2 de la strategie de test : contre un vrai relais SMTP.
 *
 * <p>L'etage contractuel de {@code CanalSmtpTest} tourne a chaque {@code ./mvnw test} contre
 * GreenMail, et prouve que le message est bien forme. Il ne prouve <b>pas</b> ce qu'un vrai
 * relais ajoute : authentification, TLS, domaine d'expedition accepte, filtres anti-pourriel.
 * Ces quatre-la ne se decouvrent que contre un serveur reel — sinon au premier lead chaud.
 *
 * <p>Exclu de la suite par defaut ({@code @Tag("notification")}), et exclu aussi sous
 * {@code -Perp-it} : chaque profil n'ouvre que son etage, sans quoi une execution ERP
 * enverrait de vrais e-mails. Pour le lancer :
 *
 * <pre>
 * export LEADFLOW_SMTP_HOST=smtp.exemple.test
 * export LEADFLOW_SMTP_PORT=587
 * export LEADFLOW_SMTP_USERNAME=...
 * export LEADFLOW_SMTP_PASSWORD=...
 * export LEADFLOW_SMTP_FROM=leadflow@exemple.test
 * export LEADFLOW_SMTP_DESTINATAIRE_DE_TEST=vous@exemple.test
 * ./mvnw verify -Pnotification-it
 * </pre>
 *
 * <p>Sans ces variables, le test est <b>ignore</b> et non en echec : lancer le profil sur un
 * poste qui n'a pas de relais ne doit pas ressembler a une regression. Meme parti que
 * {@code ErpIntegrationTest} face a une cle d'API absente.
 */
@Tag("notification")
class CanalSmtpIntegrationTest {

    private static String variable(String nom) {
        return System.getenv().getOrDefault(nom, "");
    }

    private static NotificationProperties.Smtp configurationReelle() {
        return new NotificationProperties.Smtp(
                true,
                variable("LEADFLOW_SMTP_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("LEADFLOW_SMTP_PORT", "587")),
                variable("LEADFLOW_SMTP_USERNAME"),
                variable("LEADFLOW_SMTP_PASSWORD"),
                System.getenv().getOrDefault("LEADFLOW_SMTP_FROM", "leadflow@exemple.test"),
                Duration.ofSeconds(15));
    }

    @Test
    void unVraiRelaisAccepteLeMessageDAlerte() {
        NotificationProperties.Smtp config = configurationReelle();
        String destinataire = variable("LEADFLOW_SMTP_DESTINATAIRE_DE_TEST");
        assumeTrue(
                config.configure() && !destinataire.isBlank(),
                "Relais SMTP non configure : definir LEADFLOW_SMTP_HOST et "
                        + "LEADFLOW_SMTP_DESTINATAIRE_DE_TEST");

        CanalSmtp canal = new CanalSmtp(new NotificationProperties(config));

        // Aucune assertion sur le contenu : elle est deja faite contre GreenMail. Ce qui
        // s'eprouve ici, c'est que le relais ACCEPTE — donc l'authentification, le domaine
        // d'expedition et le reseau sortant.
        canal.envoie(new NotificationLead(
                "Karim Idrissi", destinataire, "Sara Benali", "sara@rif.test",
                "+212600000000", "Rif Logistics", 92, "DEVIS",
                "Je souhaite un devis urgent pour 80 postes"));

        assertThat(canal.identifiant()).isEqualTo("smtp");
    }
}
