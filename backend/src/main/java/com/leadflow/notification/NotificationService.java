package com.leadflow.notification;

import com.leadflow.config.NotificationProperties;
import com.leadflow.notification.model.NotificationLead;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.ScoringConfig;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import com.leadflow.tenant.ClientRepository;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Decide si un lead merite qu'on derange son commercial, et le fait dire par un canal.
 *
 * <p><b>Ce service n'est pas transactionnel, et c'est delibere.</b> Un envoi peut durer
 * plusieurs secondes ; a l'interieur d'une transaction JPA il tiendrait une connexion
 * Postgres pendant tout ce temps, et le pool s'epuiserait avant le relais. C'est
 * exactement le parti de {@code LeadQualificationService} face a l'appel au modele. Les
 * ecritures portent leur propre transaction, dans {@link NotificationTraceWriter}.
 *
 * <p><b>Trois causes ne partent jamais en DLQ</b> — un score sous le seuil, un lead sans
 * commercial, un commercial sans adresse. Aucune repetition ne les reparera : les lever
 * ferait mourir le message trois fois pour rien, puis encombrerait le journal des morts
 * d'incidents qu'aucun rejeu ne peut resoudre. Elles ecrivent une trace explicite et
 * acquittent. C'est la distinction que la qualification fait deja avec {@code DISCARDED}.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /**
     * Une constante et non un reglage : tant qu'il n'existe qu'un canal, un reglage
     * offrirait le choix entre une seule valeur et une faute de frappe. Le jour ou un
     * second canal arrive, c'est ici qu'on decide comment choisir.
     */
    private static final String CANAL_PAR_DEFAUT = "smtp";

    private final LeadRepository leads;
    private final ClientRepository clients;
    private final SalesRepRepository commerciaux;
    private final CanalDeNotificationRegistry canaux;
    private final NotificationTraceWriter traces;
    private final NotificationProperties proprietes;

    public NotificationService(
            LeadRepository leads,
            ClientRepository clients,
            SalesRepRepository commerciaux,
            CanalDeNotificationRegistry canaux,
            NotificationTraceWriter traces,
            NotificationProperties proprietes) {
        this.leads = leads;
        this.clients = clients;
        this.commerciaux = commerciaux;
        this.canaux = canaux;
        this.traces = traces;
        this.proprietes = proprietes;
    }

    public void notifie(UUID leadId) {
        Lead lead = leads.findById(leadId)
                .orElseThrow(() -> new NotificationException("Lead inconnu : " + leadId));
        Client boutique = clients.findById(lead.getClientId())
                .orElseThrow(() -> new NotificationException(
                        "Boutique inconnue : " + lead.getClientId()));

        int seuil = ScoringConfig.depuis(boutique.getScoringConfig()).seuilNotification();
        CanalDeNotification canal = canaux.resout(CANAL_PAR_DEFAUT);

        if (lead.getScore() < seuil) {
            // Une trace, pas un silence : c'est elle qui repond « score 55, seuil 70 » a la
            // question « pourquoi n'ai-je pas ete prevenu ? ».
            traces.ecrit(leadId, lead.getAssignedSalesRepId(), canal.identifiant(), null,
                    NotificationStatus.IGNOREE, lead.getScore(), seuil, null);
            return;
        }

        if (lead.getAssignedSalesRepId() == null) {
            refuseSansRejeu(lead, canal, seuil, null, "Le lead n'a aucun commercial attribue");
            return;
        }
        SalesRep commercial = commerciaux.findById(lead.getAssignedSalesRepId()).orElse(null);
        if (commercial == null) {
            refuseSansRejeu(lead, canal, seuil, lead.getAssignedSalesRepId(),
                    "Le commercial attribue est introuvable");
            return;
        }
        if (commercial.getEmail() == null || commercial.getEmail().isBlank()) {
            refuseSansRejeu(lead, canal, seuil, commercial.getId(),
                    "Le commercial n'a pas d'adresse e-mail");
            return;
        }

        try {
            canal.envoie(pivot(lead, commercial));
        } catch (NotificationException echec) {
            // La trace AVANT l'exception : REQUIRES_NEW la fait survivre au depart en DLQ.
            traces.ecrit(leadId, commercial.getId(), canal.identifiant(), commercial.getEmail(),
                    NotificationStatus.ECHEC, lead.getScore(), seuil, echec.getMessage());
            throw echec;
        }
        traces.ecrit(leadId, commercial.getId(), canal.identifiant(), commercial.getEmail(),
                NotificationStatus.ENVOYEE, lead.getScore(), seuil, null);
    }

    /**
     * Un refus qu'aucune repetition ne reparera : il trace et rend la main, sans lever.
     * Le log existe pour que la configuration incomplete d'une boutique se voie, comme le
     * repli logue des strategies d'attribution geographique et sectorielle.
     */
    private void refuseSansRejeu(
            Lead lead, CanalDeNotification canal, int seuil, UUID salesRepId, String raison) {
        log.warn("Notification impossible pour le lead {} : {}", lead.getId(), raison);
        traces.ecrit(lead.getId(), salesRepId, canal.identifiant(), null,
                NotificationStatus.ECHEC, lead.getScore(), seuil, raison);
    }

    private NotificationLead pivot(Lead lead, SalesRep commercial) {
        return new NotificationLead(
                commercial.getFullName(),
                commercial.getEmail(),
                nomComplet(lead),
                lead.getEmail(),
                lead.getCompanyName(),
                lead.getScore(),
                lead.getDetectedIntent(),
                proprietes.urlDe(lead.getId()));
    }

    /**
     * Le nom du prospect, ou {@code null} s'il n'en a aucun : la qualification ne fait
     * echouer un lead que sur son email, tout le reste pouvant rester illisible. Un canal
     * doit donc pouvoir composer son message sans nom.
     */
    private String nomComplet(Lead lead) {
        String nom = Stream.of(lead.getFirstName(), lead.getLastName())
                .filter(partie -> partie != null && !partie.isBlank())
                .reduce((prenom, patronyme) -> prenom + " " + patronyme)
                .orElse(null);
        return nom;
    }
}
