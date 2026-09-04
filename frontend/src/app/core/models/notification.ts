/**
 * Diagnostic du canal de notification, rendu par `POST /api/admin/notification/test`.
 *
 * La cause est une enumeration et non une phrase : le serveur rend des faits, l'ecran les
 * met en francais. Le detail, lui, vient du relais et n'est jamais traduit — il est plafonne
 * cote serveur, la reponse brute restant dans les journaux.
 */
export type CauseNotification =
  | 'OK'
  | 'NON_CONFIGURE'
  | 'DESTINATAIRE_ABSENT'
  | 'RELAIS_INJOIGNABLE'
  | 'AUTHENTIFICATION_REFUSEE'
  | 'ENVOI_REFUSE';

export interface DiagnosticNotification {
  ok: boolean;
  cause: CauseNotification;
  detail: string;
}
