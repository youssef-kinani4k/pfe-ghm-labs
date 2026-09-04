package com.leadflow.notification.smtp;

import com.leadflow.config.NotificationProperties;
import com.leadflow.notification.CanalDeNotification;
import com.leadflow.notification.NotificationException;
import com.leadflow.notification.model.NotificationLead;
import java.util.Properties;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Envoie l'alerte par e-mail.
 *
 * <p>Seul endroit de la feature qui connaisse SMTP. Il traduit le pivot en message et rien
 * d'autre : la decision d'envoyer appartient au service, la composition des phrases au
 * {@link GabaritMessage}.
 *
 * <p>Il construit son propre {@code JavaMailSenderImpl} plutot que de dependre du bean
 * autoconfigure par {@code spring.mail.*}. Deux raisons : les reglages du projet vivent sous
 * {@code leadflow.notification.smtp.*}, avec les quatre autres variables de l'instance ; et
 * la sonde a besoin d'eprouver une configuration <b>sans</b> passer par celle en service.
 */
@Component
public class CanalSmtp implements CanalDeNotification {

    /** Ecrit tel quel dans {@code notification_attempt.channel}. */
    public static final String IDENTIFIANT = "smtp";

    private final NotificationProperties proprietes;

    public CanalSmtp(NotificationProperties proprietes) {
        this.proprietes = proprietes;
    }

    @Override
    public String identifiant() {
        return IDENTIFIANT;
    }

    @Override
    public void envoie(NotificationLead lead) {
        envoie(proprietes.smtp(), lead.adresseCommercial(),
                GabaritMessage.objet(lead), GabaritMessage.corps(lead));
    }

    /**
     * L'envoi nu, partage avec la sonde : celle-ci eprouve une configuration donnee, la ou
     * cette classe utilise celle en service. Les deux passent donc par le meme code, seul a
     * savoir parler le protocole.
     */
    public void envoie(
            NotificationProperties.Smtp config, String destinataire, String objet, String corps) {
        if (!config.configure()) {
            // On leve sans ouvrir de connexion : une instance sans relais doit dire pourquoi
            // l'alerte n'a pas eu lieu, pas attendre un delai vers nulle part.
            throw new NotificationException(
                    "Le canal SMTP n'est pas configure : aucun hote de relais n'est defini");
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(config.from());
            message.setTo(destinataire);
            message.setSubject(objet);
            message.setText(corps);
            expediteur(config).send(message);
        } catch (MailException echec) {
            // Le message court remonte jusqu'a la trace et jusqu'a l'ecran ; la cause
            // complete reste dans les journaux, ou elle ne trompe personne.
            throw new NotificationException(causeCourte(echec), echec);
        }
    }

    private JavaMailSenderImpl expediteur(NotificationProperties.Smtp config) {
        JavaMailSenderImpl expediteur = new JavaMailSenderImpl();
        expediteur.setHost(config.host());
        expediteur.setPort(config.port());
        if (config.username() != null && !config.username().isBlank()) {
            expediteur.setUsername(config.username());
            expediteur.setPassword(config.password());
        }

        Properties options = expediteur.getJavaMailProperties();
        long millisecondes = config.timeout().toMillis();
        options.put("mail.smtp.connectiontimeout", String.valueOf(millisecondes));
        options.put("mail.smtp.timeout", String.valueOf(millisecondes));
        options.put("mail.smtp.writetimeout", String.valueOf(millisecondes));
        // L'authentification n'a lieu que si un identifiant est fourni : un relais de test
        // ou un relais interne ouvert n'en demande pas.
        options.put("mail.smtp.auth",
                String.valueOf(config.username() != null && !config.username().isBlank()));
        return expediteur;
    }

    /**
     * La phrase utile, pas le roman du serveur. Un relais peut repondre plusieurs lignes de
     * diagnostic ; les recopier a l'ecran n'aiderait personne et exposerait sa configuration.
     */
    private String causeCourte(MailException echec) {
        Throwable racine = echec;
        while (racine.getCause() != null) {
            racine = racine.getCause();
        }
        String message = racine.getMessage();
        if (message == null || message.isBlank()) {
            return "Envoi refuse par le relais SMTP";
        }
        String premiereLigne = message.lines().findFirst().orElse(message).trim();
        return premiereLigne.length() > 140 ? premiereLigne.substring(0, 140) : premiereLigne;
    }
}
