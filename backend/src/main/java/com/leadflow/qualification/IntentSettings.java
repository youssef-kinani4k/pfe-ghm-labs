package com.leadflow.qualification;

import com.leadflow.config.IntentProperties;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Source de verite de la cle d'API et de l'interrupteur de l'analyse d'intention.
 *
 * <p><b>La base l'emporte sur l'environnement.</b> {@code GEMINI_API_KEY} reste un repli :
 * une instance deja deployee continue de fonctionner sans qu'on touche a rien, et la console
 * devient le chemin normal. L'inverse — l'environnement prioritaire — rendrait l'ecran
 * mensonger sur les postes ou la variable est posee.
 *
 * <p><b>Aucun cache.</b> La lecture est un acces par cle primaire sur une table d'une ligne,
 * et la qualification ecrit deja en base pour chaque lead : le cout est negligeable devant
 * l'appel au modele. En echange, un changement de cle prend effet au lead suivant, sans
 * redemarrage et sans invalidation a orchestrer.
 *
 * <p><b>Rien de ce qui sort d'ici ne permet de reconstituer la cle</b> sauf
 * {@link #cleEffective()}, reservee a l'appelant qui va s'en servir. {@link #etat()}, qui
 * alimente l'ecran, n'en rend que les quatre derniers caracteres.
 */
@Service
public class IntentSettings implements ReglageIntent {

    /** Longueur de l'apercu montre a l'ecran. Assez pour reconnaitre, trop peu pour servir. */
    private static final int APERCU = 4;

    private final IntentSettingRepository depot;
    private final IntentProperties.Gemini proprietes;

    public IntentSettings(IntentSettingRepository depot, IntentProperties proprietes) {
        this.depot = depot;
        this.proprietes = proprietes.gemini();
    }

    /** La cle a utiliser, ou {@code null} s'il n'y en a aucune. */
    @Override
    @Transactional(readOnly = true)
    public String cleEffective() {
        return cleDeLaBase().orElseGet(() -> renseignee(proprietes.apiKey()) ? proprietes.apiKey()
                : null);
    }

    /** L'interrupteur de l'ecran ; la propriete de demarrage sert de valeur initiale. */
    @Override
    @Transactional(readOnly = true)
    public boolean actif() {
        return depot.findById(IntentSettingRepository.LIGNE)
                .map(IntentSetting::isEnabled)
                .orElse(true);
    }

    /** Ce que l'ecran d'administration affiche. */
    @Transactional(readOnly = true)
    public EtatIntent etat() {
        String cle = cleEffective();
        SourceCle source = cleDeLaBase().isPresent() ? SourceCle.BASE
                : renseignee(cle) ? SourceCle.ENV : SourceCle.AUCUNE;
        return new EtatIntent(actif(), renseignee(cle), apercu(cle), source, proprietes.model());
    }

    /**
     * Enregistre la cle et l'interrupteur.
     *
     * <p>Une cle vide ou absente laisse en place celle deja enregistree : l'ecran doit
     * pouvoir couper l'analyse sans obliger l'operateur a ressaisir un secret qu'il ne voit
     * pas. Effacer la cle est une operation distincte, {@link #efface()}.
     */
    @Transactional
    public void enregistre(String cle, boolean actif) {
        IntentSetting ligne = depot.findById(IntentSettingRepository.LIGNE)
                .orElseGet(IntentSetting::new);
        if (renseignee(cle)) {
            ligne.setApiKey(cle.trim());
        }
        ligne.setEnabled(actif);
        depot.save(ligne);
    }

    /** Retire la cle de la base ; le repli sur l'environnement redevient actif. */
    @Transactional
    public void efface() {
        depot.findById(IntentSettingRepository.LIGNE).ifPresent(ligne -> {
            ligne.setApiKey(null);
            depot.save(ligne);
        });
    }

    private Optional<String> cleDeLaBase() {
        return depot.findById(IntentSettingRepository.LIGNE)
                .map(IntentSetting::getApiKey)
                .filter(IntentSettings::renseignee);
    }

    private static boolean renseignee(String valeur) {
        return valeur != null && !valeur.isBlank();
    }

    private static String apercu(String cle) {
        if (!renseignee(cle)) {
            return null;
        }
        return cle.length() <= APERCU ? cle : cle.substring(cle.length() - APERCU);
    }
}
