package com.leadflow.qualification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * L'ecran « Parametres » : cle d'API de l'analyse d'intention, interrupteur, diagnostic.
 *
 * <p>Le controleur vit dans {@code qualification/} et non dans {@code monitoring/} : il
 * ecrit, et l'observateur n'ecrit que {@code dead_letter}. Pas dans {@code tenant/} non
 * plus : le reglage est global a l'instance, il ne parametre pas une boutique.
 *
 * <p>La cle ne ressort jamais d'ici. {@link EtatIntent} n'en porte que les quatre derniers
 * caracteres, assez pour reconnaitre celle qui est en place.
 */
@RestController
@RequestMapping("/api/admin/intent")
public class IntentAdminController {

    private final IntentSettings reglages;
    private final SondeIntent sonde;

    public IntentAdminController(IntentSettings reglages, SondeIntent sonde) {
        this.reglages = reglages;
        this.sonde = sonde;
    }

    @GetMapping
    public EtatIntent etat() {
        return reglages.etat();
    }

    @PutMapping
    public EtatIntent enregistre(@RequestBody IntentForm formulaire) {
        boolean actif = formulaire.actif() != null ? formulaire.actif() : reglages.actif();
        reglages.enregistre(formulaire.apiKey(), actif);
        return reglages.etat();
    }

    /**
     * Un diagnostic rate rend 200 avec sa cause, jamais 502 : l'intercepteur du dashboard
     * presenterait un incident la ou il n'y a qu'une cle a corriger.
     */
    @PostMapping("/test")
    public IntentTestResult teste(@RequestBody IntentTestRequest demande) {
        return sonde.eprouve(demande.apiKey());
    }
}
