import { IntentSource, LeadStatus, RawEventStatus, SyncAttemptStatus } from './monitoring';

/**
 * Vue de liste d'un lead, calquee champ pour champ sur le record LeadSummary du backend.
 *
 * Les champs facultatifs portent `| null` plutot que `?` : le backend serialise la valeur
 * absente en `null` et non en cle manquante, et le type doit dire la meme chose que le JSON
 * recu, sans quoi un `undefined` attendu masquerait un `null` bien present.
 */
export interface LeadSummary {
  id: string;
  createdAt: string;
  clientId: string;
  clientName: string;
  companyName: string | null;
  email: string;
  detectedIntent: string | null;
  intentSource: IntentSource | null;
  score: number;
  status: LeadStatus;
  assignedSalesRepId: string | null;
  salesRepName: string | null;
  countryCode: string | null;
  sector: string | null;
}

/** Commercial attribue, tel que le detail d'un lead le porte. */
export interface SalesRepView {
  id: string;
  fullName: string;
  email: string;
  sector: string | null;
  zone: string | null;
  crmRef: string | null;
}

/** Une tentative de synchronisation ERP : la trace append-only de `crm_sync_attempt`. */
export interface SyncAttemptView {
  id: string;
  providerId: string;
  status: SyncAttemptStatus;
  accountRef: string | null;
  contactRef: string | null;
  opportunityRef: string | null;
  taskRef: string | null;
  errorMessage: string | null;
  attemptedAt: string;
}

/** L'evenement brut d'origine, avec son payload tel que le formulaire l'a envoye. */
export interface RawEventView {
  id: string;
  source: string | null;
  receivedAt: string;
  status: RawEventStatus;
  failureReason: string | null;
  payload: Record<string, unknown>;
}

/** Detail complet d'un lead : ses champs, son commercial, son historique et son origine. */
export interface LeadDetail {
  id: string;
  createdAt: string;
  updatedAt: string;
  clientId: string;
  clientName: string;
  companyName: string | null;
  firstName: string | null;
  lastName: string | null;
  email: string;
  phone: string | null;
  message: string | null;
  detectedIntent: string | null;
  intentSource: IntentSource | null;
  score: number;
  status: LeadStatus;
  countryCode: string | null;
  sector: string | null;
  salesRep: SalesRepView | null;
  syncAttempts: SyncAttemptView[];
  rawEvent: RawEventView | null;
}
