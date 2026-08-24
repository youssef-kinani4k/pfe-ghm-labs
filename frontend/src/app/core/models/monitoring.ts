/**
 * Les enumerations du backend, recopiees comme types litteraux.
 *
 * Un type litteral plutot qu'un `enum` TypeScript : le JSON transporte deja la chaine, et un
 * `enum` obligerait a une conversion a chaque lecture pour ne rien apporter de plus. Le
 * compilateur refuse de la meme facon une valeur hors vocabulaire.
 */
export type LeadStatus = 'QUALIFIED' | 'ROUTED' | 'SYNCED' | 'REJECTED' | 'FAILED';
export type DeadLetterStatus = 'PENDING' | 'REPLAYED' | 'DISCARDED';
export type IntentSource = 'RULES' | 'GEMINI';
export type RawEventStatus = 'RECEIVED' | 'PUBLISHED' | 'FAILED' | 'DISCARDED';
export type SyncAttemptStatus = 'SUCCESS' | 'FAILED';
export type AssignmentStrategy = 'ROUND_ROBIN' | 'GEOGRAPHIC' | 'SECTOR';

/**
 * Agregats du dashboard.
 *
 * Les repartitions sont des dictionnaires et non des listes : le backend les produit par
 * `group by`, et l'ecran n'a besoin que de retrouver un compte par cle. `nomsDeCommercial`
 * accompagne `leadsParCommercial`, dont les cles sont des identifiants — sans quoi l'ecran
 * afficherait des UUID.
 */
export interface StatsView {
  total: number;
  leadsParStatut: Record<string, number>;
  evenementsParStatut: Record<string, number>;
  tauxDeConversion: number;
  leadsParIntention: Record<string, number>;
  leadsParSourceDIntention: Record<string, number>;
  leadsParCommercial: Record<string, number>;
  nomsDeCommercial: Record<string, string>;
}

/** Profondeur et consommateurs d'une file, lus en AMQP. */
export interface QueueView {
  name: string;
  reachable: boolean;
  messageCount: number;
  consumerCount: number;
}

/** L'etat des files, plus le nombre de morts qui attendent une decision humaine. */
export interface QueuesView {
  queues: QueueView[];
  pendingDeadLetters: number;
}

/** Une ligne du journal des messages morts, rejouable une par une. */
export interface DeadLetterView {
  id: string;
  originQueue: string;
  routingKey: string | null;
  clientId: string | null;
  clientName: string | null;
  leadId: string | null;
  failureReason: string | null;
  deadAt: string;
  status: DeadLetterStatus;
  replayedAt: string | null;
  replayedBy: string | null;
  payload: string | null;
  replayWarning: string | null;
}

/** Activite d'un connecteur pour un client donne : le detail derriere les totaux. */
export interface ConnectorClientActivity {
  clientId: string;
  clientName: string;
  successCount: number;
  failureCount: number;
  lastAttemptAt: string | null;
}

/**
 * Etat d'un connecteur ERP, derive des traces de synchronisation.
 *
 * `implemented` et `enabled` sont distincts : un fournisseur configure sans adaptateur
 * present, ou un adaptateur present mais desactive, ne se diagnostiquent pas pareil.
 */
export interface ConnectorView {
  providerId: string;
  implemented: boolean;
  enabled: boolean;
  successCount: number;
  failureCount: number;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  lastFailureMessage: string | null;
  parClient: ConnectorClientActivity[];
}

/** Un client de l'annuaire, sans aucun de ses secrets. */
export interface ClientSummary {
  id: string;
  name: string;
  active: boolean;
  crmProviderId: string | null;
  assignmentStrategy: AssignmentStrategy;
}

/** Un commercial de l'annuaire. */
export interface SalesRepSummary {
  id: string;
  fullName: string;
  email: string;
  sector: string | null;
  zone: string | null;
  active: boolean;
  crmRef: string | null;
}

/**
 * Un evenement du flux temps reel.
 *
 * Volontairement maigre : le flux dit qu'un lead a bouge et ou il en est, pas tout ce qu'il
 * contient. L'ecran de detail reste la source complete, et une charge legere permet de
 * diffuser a tous les abonnes sans relire la base.
 */
export interface StreamEvent {
  /**
   * Nul pendant la capture : le flux n'a d'identifiant de lead qu'a partir de la
   * qualification, l'evenement brut n'en ayant pas encore.
   */
  leadId: string | null;
  clientId: string;
  /** `CAPTURED` n'est pas un statut de lead : c'est une etape que seul le flux rapporte. */
  status: LeadStatus | 'CAPTURED';
  score: number | null;
  salesRepId: string | null;
  occurredAt: string;
}
