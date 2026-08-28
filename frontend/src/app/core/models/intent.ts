/** D'ou vient la cle d'API effectivement utilisee. */
export type SourceCle = 'BASE' | 'ENV' | 'AUCUNE';

/** Pourquoi un diagnostic de cle a echoue. */
export type CauseIntent =
  | 'OK'
  | 'CLE_ABSENTE'
  | 'CLE_REFUSEE'
  | 'QUOTA_DEPASSE'
  | 'INJOIGNABLE'
  | 'ERREUR_SERVEUR'
  | 'REPONSE_INATTENDUE';

/**
 * Etat du reglage de l'analyse d'intention.
 *
 * `apercu` ne porte que les quatre derniers caracteres de la cle : le backend ne la rend
 * jamais entiere, et l'ecran n'a pas a la connaitre pour faire son travail.
 */
export interface EtatIntent {
  actif: boolean;
  cleDefinie: boolean;
  apercu: string | null;
  source: SourceCle;
  modele: string;
}

/** Ce que l'ecran envoie pour modifier le reglage. */
export interface IntentForm {
  apiKey?: string;
  actif?: boolean;
}

/** Resultat d'un diagnostic de cle. */
export interface IntentTestResult {
  ok: boolean;
  cause: CauseIntent;
  detail: string | null;
  intention: string | null;
}
