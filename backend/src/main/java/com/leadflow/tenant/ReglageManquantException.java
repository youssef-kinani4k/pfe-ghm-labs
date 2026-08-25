package com.leadflow.tenant;

/** Traduite en 400 : fournisseur inconnu, reglage ERP absent, ou commercial manquant. */
public class ReglageManquantException extends RuntimeException {
    public ReglageManquantException(String message) {
        super(message);
    }
}
