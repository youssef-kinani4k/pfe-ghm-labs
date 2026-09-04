package com.leadflow.notification;

import com.leadflow.config.NotificationProperties;
import com.leadflow.notification.dto.CauseNotification;
import com.leadflow.notification.dto.DiagnosticNotification;
import com.leadflow.notification.smtp.CanalSmtp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Eprouve le canal de notification sans le mettre en service.
 *
 * <p>Meme role que {@code SondeIntent} pour la cle d'analyse, et meme raison d'exister : le
 * consommateur avale les defaillances pour ne perdre aucun lead, ce qui est exactement le
 * contraire de ce qu'un diagnostic doit faire. Sans elle, un relais mal configure ne se
 * decouvre qu'au premier lead chaud perdu — c'est-a-dire trop tard, et sans que rien ne le
 * signale.
 *
 * <p>Elle <b>n'ecrit aucune ligne</b> de {@code notification_attempt} : cette table trace ce
 * que des leads reels ont declenche, et y meler des essais d'operateur rendrait le journal
 * inutilisable pour repondre a « ce lead a-t-il ete notifie ? ».
 *
 * <p>Elle partage {@link CanalSmtp} avec le consommateur : un seul endroit sait parler le
 * protocole, exactement comme {@code SondeIntent} et {@code GeminiIntentAnalyzer} partagent
 * {@code GeminiClient}.
 */
@Service
public class SondeNotification {

    private static final Logger log = LoggerFactory.getLogger(SondeNotification.class);

    /** Ce qu'on garde du motif rendu par le relais. Assez pour agir, pas un roman. */
    private static final int DETAIL_MAX = 140;

    private static final String OBJET = "LeadFlow — test d'envoi";
    private static final String CORPS = """
            Ceci est un message d'essai envoye depuis l'ecran « Parametres » de LeadFlow.

            Si vous le lisez, le relais est correctement configure et les commerciaux
            recevront leurs alertes de leads chauds.
            """;

    private final CanalSmtp canal;
    private final NotificationProperties proprietes;

    public SondeNotification(CanalSmtp canal, NotificationProperties proprietes) {
        this.canal = canal;
        this.proprietes = proprietes;
    }

    /**
     * Envoie un message d'essai a l'adresse donnee et dit ce qui s'est passe.
     *
     * <p>Elle envoie reellement, et ne se contente pas d'ouvrir le port : un diagnostic qui
     * ne prouve que la connexion ne prouve rien d'utile — l'authentification, le domaine
     * d'expedition et les filtres du relais se decouvriraient au premier vrai lead.
     *
     * <p>Elle ne leve jamais : un diagnostic rate est une reponse, pas un incident.
     */
    public DiagnosticNotification eprouve(String destinataire) {
        if (destinataire == null || destinataire.isBlank()) {
            return new DiagnosticNotification(
                    false, CauseNotification.DESTINATAIRE_ABSENT,
                    "Indiquez une adresse a laquelle envoyer le message d'essai");
        }
        if (!proprietes.smtp().configure()) {
            return new DiagnosticNotification(
                    false, CauseNotification.NON_CONFIGURE,
                    "Aucun hote de relais n'est defini pour cette instance");
        }
        try {
            canal.envoie(proprietes.smtp(), destinataire.trim(), OBJET, CORPS);
            return new DiagnosticNotification(
                    true, CauseNotification.OK, "Message d'essai accepte par le relais");
        } catch (NotificationException echec) {
            // La cause complete part dans les journaux, ou un operateur technique peut aller
            // la chercher ; l'ecran ne recoit que la phrase qui dit quoi reparer.
            log.warn("Diagnostic du canal de notification en echec", echec);
            return new DiagnosticNotification(false, cause(echec), tronque(echec.getMessage()));
        }
    }

    /**
     * Le vocabulaire d'erreur des relais SMTP n'est pas normalise : on classe sur ce qui est
     * stable — la nature de l'exception JavaMail sous-jacente — plutot que sur le texte.
     */
    private CauseNotification cause(NotificationException echec) {
        Throwable racine = echec;
        while (racine.getCause() != null) {
            racine = racine.getCause();
        }
        String nom = racine.getClass().getSimpleName();
        if (nom.contains("Authentication")) {
            return CauseNotification.AUTHENTIFICATION_REFUSEE;
        }
        if (racine instanceof java.net.ConnectException
                || racine instanceof java.net.UnknownHostException
                || racine instanceof java.net.SocketTimeoutException) {
            return CauseNotification.RELAIS_INJOIGNABLE;
        }
        return CauseNotification.ENVOI_REFUSE;
    }

    private String tronque(String message) {
        if (message == null || message.isBlank()) {
            return "Le relais a refuse le message d'essai";
        }
        String premiereLigne = message.lines().findFirst().orElse(message).trim();
        return premiereLigne.length() > DETAIL_MAX
                ? premiereLigne.substring(0, DETAIL_MAX)
                : premiereLigne;
    }
}
