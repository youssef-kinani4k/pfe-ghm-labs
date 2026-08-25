package com.leadflow.common;

import com.leadflow.common.auth.DashboardAuthenticationException;
import com.leadflow.monitoring.deadletter.DejaTraiteException;
import com.leadflow.monitoring.deadletter.RejeuIndisponibleException;
import com.leadflow.tenant.DernierCommercialException;
import com.leadflow.tenant.ReglageManquantException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduction des exceptions applicatives en reponses HTTP. Premiere pierre de la gestion
 * d'erreurs REST du projet : F2 est le premier endpoint.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Reponse deliberement avare et identique pour les cinq causes de refus. La raison
     * exacte n'existe que dans ce log : la distinguer cote client donnerait un oracle sur
     * les cles publiques existantes et sur l'etat d'activation des clients.
     */
    @ExceptionHandler(WebhookAuthenticationException.class)
    ProblemDetail refusDAuthentification(WebhookAuthenticationException echec) {
        log.warn("Webhook refuse : {}", echec.getMessage());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Signature invalide ou expiree");
    }

    @ExceptionHandler(PayloadRejectedException.class)
    ProblemDetail corpsRefuse(PayloadRejectedException echec) {
        return ProblemDetail.forStatusAndDetail(echec.statut(), echec.getMessage());
    }

    @ExceptionHandler(RessourceIntrouvableException.class)
    ProblemDetail ressourceIntrouvable(RessourceIntrouvableException echec) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, echec.getMessage());
    }

    /** Meme reponse pour un identifiant inconnu et un mot de passe faux (cf. AuthController). */
    @ExceptionHandler(DashboardAuthenticationException.class)
    ProblemDetail refusDeConnexion(DashboardAuthenticationException echec) {
        log.warn("Connexion au dashboard refusee");
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Identifiants invalides");
    }

    @ExceptionHandler(DejaTraiteException.class)
    ProblemDetail dejaTraite(DejaTraiteException echec) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, echec.getMessage());
    }

    @ExceptionHandler(RejeuIndisponibleException.class)
    ProblemDetail rejeuIndisponible(RejeuIndisponibleException echec) {
        log.warn("Rejeu impossible : broker injoignable", echec);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, echec.getMessage());
    }

    @ExceptionHandler(ReglageManquantException.class)
    ProblemDetail reglageManquant(ReglageManquantException echec) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, echec.getMessage());
    }

    @ExceptionHandler(DernierCommercialException.class)
    ProblemDetail dernierCommercial(DernierCommercialException echec) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, echec.getMessage());
    }

    /**
     * L'unicite est tranchee par la base plutot que par un « existe-t-il deja ? » prealable,
     * que deux requetes concurrentes passeraient toutes les deux — meme raisonnement que
     * l'idempotence de la capture.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflitDeContrainte(DataIntegrityViolationException echec) {
        log.warn("Contrainte d'unicite violee", echec);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Cette valeur est deja utilisee pour cette boutique");
    }
}
