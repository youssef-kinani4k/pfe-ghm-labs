package com.leadflow.notification.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.leadflow.config.NotificationProperties;
import com.leadflow.notification.NotificationException;
import com.leadflow.notification.model.NotificationLead;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * L'adaptateur SMTP, eprouve contre un vrai serveur en memoire.
 *
 * <p>Le test <b>asserte le contenu du message</b>, et pas seulement l'absence d'exception :
 * c'est le parti des adaptateurs ERP, qui assertent les corps envoyes et non les codes
 * retour. Un gabarit qui oublierait le score ou le telephone passerait sans cela.
 */
class CanalSmtpTest {

    @RegisterExtension
    static final GreenMailExtension SERVEUR =
            new GreenMailExtension(ServerSetupTest.SMTP).withPerMethodLifecycle(true);

    private static CanalSmtp canalVers(String hote, int port) {
        return new CanalSmtp(new NotificationProperties(
                new NotificationProperties.Smtp(
                        true, hote, port, null, null, "leadflow@agence.test",
                        Duration.ofSeconds(3))));
    }

    private static CanalSmtp canal() {
        return canalVers("127.0.0.1", SERVEUR.getSmtp().getPort());
    }

    private static NotificationLead unLead() {
        return new NotificationLead(
                "Karim Idrissi", "karim@demo.test", "Sara Benali", "sara@rif.test",
                "+212600000000", "Rif Logistics", 92, "DEVIS",
                "Je souhaite un devis urgent pour 80 postes");
    }

    @Test
    void envoieUnMessageAuCommercialAvecLesFaitsDuLead() throws Exception {
        canal().envoie(unLead());

        MimeMessage[] recus = SERVEUR.getReceivedMessages();
        assertThat(recus).hasSize(1);
        assertThat(recus[0].getAllRecipients()[0].toString()).isEqualTo("karim@demo.test");
        assertThat(recus[0].getFrom()[0].toString()).contains("leadflow@agence.test");
        // L'objet doit nommer le prospect : c'est ce que le commercial voit sans ouvrir.
        assertThat(recus[0].getSubject()).contains("Sara Benali");

        String corps = GreenMailUtil.getBody(recus[0]);
        assertThat(corps)
                .contains("92")
                .contains("Rif Logistics")
                .contains("DEVIS")
                // Ce dont un commercial a besoin pour rappeler, et qui manquait : le
                // telephone du prospect et ce qu'il a ecrit.
                .contains("+212600000000")
                .contains("Je souhaite un devis urgent pour 80 postes");
    }

    @Test
    void neCiteAucunLienVersLeDashboard() {
        canal().envoie(unLead());

        // Le commercial n'a aucun compte sur le dashboard : c'est une console d'agence, un
        // seul modele d'utilisateur et aucun role. Un lien l'enverrait sur un ecran de
        // connexion qu'il ne peut pas franchir, et un lien mort dans une alerte apprend
        // surtout a ignorer les suivantes.
        assertThat(GreenMailUtil.getBody(SERVEUR.getReceivedMessages()[0]))
                .doesNotContain("localhost:4200")
                .doesNotContain("/leads/");
    }

    @Test
    void composeUnMessageLisibleQuandLeProspectNAQueSonEmail() throws Exception {
        // La qualification ne fait echouer un lead que sur son email : nom, societe et
        // intention peuvent etre nuls. Un gabarit qui les concatenerait sans precaution
        // enverrait « null » a un commercial.
        canal().envoie(new NotificationLead(
                "Karim Idrissi", "karim@demo.test", null, "sara@rif.test", null, null, 80,
                null, null));

        MimeMessage recu = SERVEUR.getReceivedMessages()[0];
        assertThat(recu.getSubject()).doesNotContain("null");
        assertThat(GreenMailUtil.getBody(recu)).doesNotContain("null");
    }

    @Test
    void unRelaisInjoignableLeveUneNotificationException() {
        // Port ferme : l'echec est technique, donc rejouable, donc il doit lever pour que le
        // message parte en DLQ apres trois tentatives.
        CanalSmtp casse = canalVers("127.0.0.1", 1);

        assertThatThrownBy(() -> casse.envoie(unLead()))
                .isInstanceOf(NotificationException.class);
    }

    @Test
    void unCanalNonConfigureLeveSansTenterDeConnexion() {
        // Hote vide : l'instance demarre quand meme, mais chaque envoi doit dire pourquoi il
        // n'a pas eu lieu plutot que de tenter une connexion vers nulle part.
        CanalSmtp inerte = canalVers("", 587);

        assertThatThrownBy(() -> inerte.envoie(unLead()))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("configur");
    }

    @Test
    void unIdentifiantNePartJamaisSurUneConnexionEnClair() {
        // GreenMail ne propose pas STARTTLS : c'est le relais qui laisserait passer un mot
        // de passe en clair. Un relais authentifie (Gmail, un fournisseur transactionnel)
        // exige le chiffrement ; le canal doit donc refuser d'envoyer l'identifiant plutot
        // que de le livrer lisible a quiconque ecoute le reseau.
        CanalSmtp authentifie = new CanalSmtp(new NotificationProperties(
                new NotificationProperties.Smtp(
                        true, "127.0.0.1", SERVEUR.getSmtp().getPort(), "agence@exemple.test",
                        "mot-de-passe", "agence@exemple.test", Duration.ofSeconds(3))));

        assertThatThrownBy(() -> authentifie.envoie(unLead()))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("STARTTLS");
        assertThat(SERVEUR.getReceivedMessages()).isEmpty();
    }

    @Test
    void sonIdentifiantEstCeluiQuiSecritDansLaTrace() {
        assertThat(canal().identifiant()).isEqualTo("smtp");
    }
}
