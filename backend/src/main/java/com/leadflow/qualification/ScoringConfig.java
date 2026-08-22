package com.leadflow.qualification;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bareme de scoring d'un client, lu depuis {@code client.scoring_config}.
 *
 * <p>Le jeu de criteres est <b>ferme</b> : seuls les poids et les listes cibles sont
 * configurables. Un moteur de regles generique aurait demande de specifier, parser, valider
 * et tester un mini-langage, pour un besoin que personne n'a exprime.
 *
 * <p>La lecture est <b>tolerante</b> : document vide, partiel, portant des cles inconnues ou
 * carrement malforme, les valeurs manquantes prennent le defaut et rien n'echoue. Un client
 * mal configure doit produire un score discutable, jamais un lead perdu.
 *
 * @param seuilChaud lu et porte des maintenant pour figer la forme du document, mais F3 ne
 *     s'en sert pas : c'est F4 qui alertera sur les leads chauds
 */
public record ScoringConfig(
        int telephonePresent,
        int societePresente,
        int nomPresent,
        int messagePresent,
        Map<LeadIntent, Integer> intention,
        Set<String> secteursCibles,
        Set<String> paysCibles,
        int bonusCible,
        int seuilChaud) {

    public static ScoringConfig defaut() {
        return new ScoringConfig(15, 10, 5, 10, intentionsParDefaut(), Set.of(), Set.of(), 10, 70);
    }

    private static Map<LeadIntent, Integer> intentionsParDefaut() {
        Map<LeadIntent, Integer> defauts = new HashMap<>();
        defauts.put(LeadIntent.DEVIS, 40);
        defauts.put(LeadIntent.ACHAT, 40);
        defauts.put(LeadIntent.INFORMATION, 15);
        defauts.put(LeadIntent.SUPPORT, 5);
        defauts.put(LeadIntent.AUTRE, 0);
        return defauts;
    }

    public static ScoringConfig depuis(Map<String, Object> document) {
        ScoringConfig defaut = defaut();
        if (document == null || document.isEmpty()) {
            return defaut;
        }
        Map<String, Object> poids = objet(document.get("poids"));
        return new ScoringConfig(
                entier(poids.get("telephonePresent"), defaut.telephonePresent()),
                entier(poids.get("societePresente"), defaut.societePresente()),
                entier(poids.get("nomPresent"), defaut.nomPresent()),
                entier(poids.get("messagePresent"), defaut.messagePresent()),
                intentions(poids.get("intention"), defaut.intention()),
                minuscules(document.get("secteursCibles")),
                majuscules(document.get("paysCibles")),
                entier(document.get("bonusCible"), defaut.bonusCible()),
                entier(document.get("seuilChaud"), defaut.seuilChaud()));
    }

    /**
     * Le poids d'une intention absente du document — ou ecrit de travers — reste celui du
     * bareme par defaut, comme partout ailleurs dans cette classe.
     */
    private static Map<LeadIntent, Integer> intentions(Object brut, Map<LeadIntent, Integer> defaut) {
        Map<LeadIntent, Integer> resultat = new HashMap<>(defaut);
        for (Map.Entry<String, Object> entree : objet(brut).entrySet()) {
            LeadIntent intention = intention(entree.getKey());
            if (intention != null) {
                resultat.put(intention, entier(entree.getValue(), defaut.getOrDefault(intention, 0)));
            }
        }
        return resultat;
    }

    private static LeadIntent intention(String nom) {
        for (LeadIntent candidat : LeadIntent.values()) {
            if (candidat.name().equalsIgnoreCase(nom)) {
                return candidat;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objet(Object brut) {
        return brut instanceof Map ? (Map<String, Object>) brut : Map.of();
    }

    private static int entier(Object brut, int defaut) {
        return brut instanceof Number nombre ? nombre.intValue() : defaut;
    }

    private static Set<String> minuscules(Object brut) {
        Set<String> valeurs = new HashSet<>();
        if (brut instanceof Iterable<?> elements) {
            for (Object element : elements) {
                if (element != null) {
                    valeurs.add(String.valueOf(element).trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return valeurs;
    }

    private static Set<String> majuscules(Object brut) {
        Set<String> valeurs = new HashSet<>();
        for (String valeur : minuscules(brut)) {
            valeurs.add(valeur.toUpperCase(Locale.ROOT));
        }
        return valeurs;
    }
}
