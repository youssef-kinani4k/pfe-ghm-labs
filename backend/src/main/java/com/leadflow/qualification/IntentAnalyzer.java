package com.leadflow.qualification;

/**
 * Port d'analyse d'intention du message libre.
 *
 * <p><b>Une implementation ne leve jamais d'exception.</b> Un analyseur indisponible doit
 * produire une analyse degradee, pas faire echouer la qualification : le lead a ete
 * legitimement recu et doit etre traite meme sans son intention.
 */
public interface IntentAnalyzer {

    /** @param message texte libre du prospect, eventuellement {@code null} ou vide */
    IntentAnalysis analyse(String message);
}
