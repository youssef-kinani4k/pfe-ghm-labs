import { AssignmentStrategy } from './monitoring';

/**
 * Les records d'administration du backend, recopies champ pour champ.
 *
 * Meme parti que `monitoring.ts` : des types litteraux plutot que des `enum`, le JSON
 * transportant deja la chaine. `AssignmentStrategy` n'est pas redeclaree ici — c'est la
 * meme enumeration cote serveur, et en avoir deux versions les ferait diverger.
 */

/** Les causes que la sonde peut rendre, telles que l'enumeration du backend les nomme. */
export type CrmCheckCause =
  'JOIGNABLE' | 'INJOIGNABLE' | 'IDENTIFIANTS_REFUSES' | 'CIBLE_INCONNUE' | 'REPONSE_INATTENDUE';

/** Ligne de la liste des boutiques. Aucun reglage ERP : la fiche s'en charge. */
export interface ClientSummaryAdmin {
  id: string;
  name: string;
  crmProviderId: string;
  assignmentStrategy: AssignmentStrategy;
  active: boolean;
  activeSalesReps: number;
}

/**
 * Fiche d'une boutique.
 *
 * `crmSettings` ne porte que les reglages non secrets : le formulaire reaffiche l'adresse du
 * serveur, jamais la cle d'API. Le secret HMAC n'y figure a aucun titre — il n'est rendu qu'a
 * la creation et a la rotation.
 */
export interface ClientDetailAdmin {
  id: string;
  name: string;
  publicKey: string;
  webhookPath: string;
  crmProviderId: string;
  crmSettings: Record<string, string>;
  assignmentStrategy: AssignmentStrategy;
  active: boolean;
  salesReps: SalesRepAdminView[];
}

export interface SalesRepAdminView {
  id: string;
  fullName: string;
  email: string;
  sector: string | null;
  zone: string | null;
  crmRef: string | null;
  active: boolean;
}

export interface SalesRepForm {
  fullName: string;
  email: string;
  sector?: string | null;
  zone?: string | null;
  crmRef?: string | null;
}

/**
 * Corps de creation et de mise a jour.
 *
 * `firstSalesRep` n'est exige qu'a la creation — la mise a jour reutilise ce type en
 * l'ignorant, comme le record du backend.
 */
export interface ClientForm {
  name: string;
  crmProviderId: string;
  assignmentStrategy: AssignmentStrategy;
  crmSettings: Record<string, string>;
  firstSalesRep?: SalesRepForm | null;
}

/**
 * Reponse de creation — la seule, avec celle de rotation, a porter le secret en clair.
 * L'ecran l'affiche une fois puis l'oublie.
 */
export interface ClientCreated {
  id: string;
  publicKey: string;
  hmacSecret: string;
  webhookPath: string;
}

/** Reponse de rotation : le secret en clair, une derniere fois. */
export interface SecretRotated {
  hmacSecret: string;
}

/**
 * Un reglage attendu par un fournisseur. `secret` vaut vrai pour ce qui ne doit jamais etre
 * reaffiche apres saisie : le formulaire masque le champ.
 */
export interface CrmSettingSpec {
  cle: string;
  libelle: string;
  secret: boolean;
}

/** Un fournisseur disponible et les champs que son formulaire doit proposer. */
export interface CrmProviderView {
  providerId: string;
  settings: CrmSettingSpec[];
}

/** Resultat de la sonde. `cause` est le nom de l'enumeration : l'ecran phrase en francais. */
export interface CrmTestResult {
  ok: boolean;
  cause: CrmCheckCause;
  detail: string | null;
}
