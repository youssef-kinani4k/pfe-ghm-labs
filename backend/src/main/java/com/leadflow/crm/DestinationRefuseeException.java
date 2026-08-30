package com.leadflow.crm;

/**
 * La destination d'un appel ERP est refusee par la politique de sortie.
 *
 * <p>Volontairement distincte de {@code CrmSyncException} : ce n'est pas l'ERP qui a echoue,
 * c'est nous qui avons refuse de l'appeler. L'adaptateur la traduit ensuite pour que l'ecran
 * et le journal disent la meme chose que pour les autres echecs.
 */
public class DestinationRefuseeException extends RuntimeException {

    public DestinationRefuseeException(String message) {
        super(message);
    }
}
