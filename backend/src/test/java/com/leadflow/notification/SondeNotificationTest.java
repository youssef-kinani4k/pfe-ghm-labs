package com.leadflow.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.leadflow.config.NotificationProperties;
import com.leadflow.notification.dto.CauseNotification;
import com.leadflow.notification.dto.DiagnosticNotification;
import com.leadflow.notification.smtp.CanalSmtp;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * La sonde du canal, eprouvee contre un vrai serveur en memoire.
 *
 * <p>Elle existe pour la meme raison que {@code SondeIntent} : le consommateur avale les
 * defaillances pour ne perdre aucun lead, ce qui est exactement le contraire de ce qu'un
 * diagnostic doit faire. Sans elle, un relais mal configure ne se decouvre qu'au premier
 * lead chaud perdu.
 */
class SondeNotificationTest {

    @RegisterExtension
    static final GreenMailExtension SERVEUR =
            new GreenMailExtension(ServerSetupTest.SMTP).withPerMethodLifecycle(true);

    private static SondeNotification sondeVers(String hote, int port) {
        NotificationProperties proprietes = new NotificationProperties(
                new NotificationProperties.Smtp(
                        true, hote, port, null, null, "leadflow@agence.test",
                        Duration.ofSeconds(3)));
        return new SondeNotification(new CanalSmtp(proprietes), proprietes);
    }

    private static SondeNotification sonde() {
        return sondeVers("127.0.0.1", SERVEUR.getSmtp().getPort());
    }

    @Test
    void unRelaisJoignableRendUnDiagnosticFavorableEtEnvoieVraiment() throws Exception {
        DiagnosticNotification diagnostic = sonde().eprouve("operateur@agence.test");

        assertThat(diagnostic.ok()).isTrue();
        assertThat(diagnostic.cause()).isEqualTo(CauseNotification.OK);
        // Un diagnostic qui ne prouve que l'ouverture du port ne prouve rien : l'operateur
        // doit recevoir le message pour savoir que la chaine entiere fonctionne.
        assertThat(SERVEUR.getReceivedMessages()).hasSize(1);
        assertThat(SERVEUR.getReceivedMessages()[0].getAllRecipients()[0].toString())
                .isEqualTo("operateur@agence.test");
    }

    @Test
    void unRelaisInjoignableNommeLaCauseSansRecopierLaReponseBrute() {
        DiagnosticNotification diagnostic = sondeVers("127.0.0.1", 1).eprouve("x@y.test");

        assertThat(diagnostic.ok()).isFalse();
        assertThat(diagnostic.cause()).isEqualTo(CauseNotification.RELAIS_INJOIGNABLE);
        // La phrase utile, pas le roman du serveur : le corps entier va dans les journaux.
        assertThat(diagnostic.detail()).isNotBlank().hasSizeLessThan(200);
    }

    @Test
    void unCanalNonConfigureLeDitPlutotQueDEssayer() {
        DiagnosticNotification diagnostic = sondeVers("", 587).eprouve("x@y.test");

        assertThat(diagnostic.ok()).isFalse();
        assertThat(diagnostic.cause()).isEqualTo(CauseNotification.NON_CONFIGURE);
    }

    @Test
    void unDestinataireAbsentEstRefuseAvantToutEnvoi() {
        DiagnosticNotification diagnostic = sonde().eprouve("   ");

        assertThat(diagnostic.ok()).isFalse();
        assertThat(diagnostic.cause()).isEqualTo(CauseNotification.DESTINATAIRE_ABSENT);
        assertThat(SERVEUR.getReceivedMessages()).isEmpty();
    }

    @Test
    void laSondeNeRendJamaisDExceptionAAppelant() {
        // Meme parti que SondeIntent : un diagnostic rate est une reponse, pas un incident.
        // Le controleur rend 200 avec la cause, sans quoi l'intercepteur du dashboard
        // presenterait une panne la ou il n'y a qu'un reglage a corriger.
        assertThat(sondeVers("127.0.0.1", 1).eprouve("x@y.test").ok()).isFalse();
    }
}
