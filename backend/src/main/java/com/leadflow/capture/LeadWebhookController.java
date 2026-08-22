package com.leadflow.capture;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entree du pipeline. Ne decide rien : lit le corps BRUT, l'en-tete et la cle publique,
 * puis delegue.
 *
 * <p>Le corps est recu en {@code String} et non en objet : il faut les octets exacts pour
 * recalculer le HMAC, et un aller-retour Jackson ne garantirait plus l'egalite. C'est aussi
 * ce qui permet de n'analyser le contenu qu'apres authentification.
 *
 * <p>La route est en {@code permitAll} dans {@code SecurityConfig} : ces requetes viennent
 * de serveurs tiers qui ne peuvent pas s'authentifier autrement. Leur authentification,
 * c'est la signature — ne pas y superposer un mecanisme Spring Security.
 */
@RestController
@RequestMapping("/api/webhooks/leads")
public class LeadWebhookController {

    private final LeadCaptureService service;

    public LeadWebhookController(LeadCaptureService service) {
        this.service = service;
    }

    @PostMapping(path = "/{clientKey}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CaptureAccepted capture(
            @PathVariable String clientKey,
            @RequestBody String corpsBrut,
            @RequestHeader(value = "${leadflow.webhook.signature-header}", required = false)
                    String signature) {
        return service.capture(clientKey, corpsBrut, signature);
    }
}
