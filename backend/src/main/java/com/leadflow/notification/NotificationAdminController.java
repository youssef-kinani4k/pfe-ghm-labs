package com.leadflow.notification;

import com.leadflow.notification.dto.DiagnosticNotification;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * L'ecran « Parametres », cote canal de notification : un seul geste, eprouver l'envoi.
 *
 * <p>Il n'y a rien a enregistrer ici, contrairement a la cle d'analyse d'intention : les
 * reglages du relais sont globaux a l'instance et viennent de l'environnement. Ce que
 * l'operateur peut faire, c'est verifier qu'ils fonctionnent — et sans cette route, un
 * relais mal configure ne se decouvrirait qu'au premier lead chaud perdu.
 *
 * <p>Le controleur vit dans {@code notification/} et non dans {@code monitoring/} : ce
 * dernier observe et n'ecrit que {@code dead_letter}.
 */
@RestController
@RequestMapping("/api/admin/notification")
public class NotificationAdminController {

    private final SondeNotification sonde;

    public NotificationAdminController(SondeNotification sonde) {
        this.sonde = sonde;
    }

    /**
     * Un diagnostic rate rend {@code 200} avec sa cause, jamais {@code 502} : l'intercepteur
     * du dashboard presenterait un incident la ou il n'y a qu'un reglage a corriger. Meme
     * parti que la sonde d'intention.
     */
    @PostMapping("/test")
    public DiagnosticNotification teste(@RequestBody DemandeDeTest demande) {
        return sonde.eprouve(demande.destinataire());
    }

    /** L'adresse a laquelle envoyer le message d'essai. */
    public record DemandeDeTest(String destinataire) {
    }
}
