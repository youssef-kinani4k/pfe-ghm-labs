import { Component, computed, input } from '@angular/core';

type Ton = 'succes' | 'attente' | 'echec' | 'neutre';

/**
 * Ton et libelle de chaque statut, en un seul endroit.
 *
 * Les cinq statuts de lead, les trois du journal des morts et les quatre de l'evenement brut
 * passent par cette table : la couleur d'un statut est une decision d'interface, et la
 * repeter dans quatre ecrans la ferait diverger au premier ajout.
 */
const TONS: Record<string, { ton: Ton; libelle: string }> = {
  // Leads
  QUALIFIED: { ton: 'neutre', libelle: 'Qualifie' },
  ROUTED: { ton: 'attente', libelle: 'Attribue' },
  SYNCED: { ton: 'succes', libelle: 'Synchronise' },
  REJECTED: { ton: 'neutre', libelle: 'Rejete' },
  FAILED: { ton: 'echec', libelle: 'En echec' },
  // Journal des messages morts
  PENDING: { ton: 'attente', libelle: 'A traiter' },
  REPLAYED: { ton: 'succes', libelle: 'Rejoue' },
  DISCARDED: { ton: 'neutre', libelle: 'Ecarte' },
  // Evenements bruts et tentatives de synchronisation
  RECEIVED: { ton: 'neutre', libelle: 'Recu' },
  PUBLISHED: { ton: 'succes', libelle: 'Publie' },
  SUCCESS: { ton: 'succes', libelle: 'Succes' },
};

/**
 * Pastille de statut.
 *
 * Le libelle est toujours ecrit a cote de la couleur : rouge et vert seuls ne se
 * distinguent pas pour une partie des lecteurs, et l'ecran s'imprime aussi en noir et blanc
 * dans les rapports d'exploitation.
 */
@Component({
  selector: 'app-status-badge',
  templateUrl: './status-badge.html',
  styleUrl: './status-badge.scss',
})
export class StatusBadge {
  readonly status = input.required<string>();

  // Un statut inconnu s'affiche tel quel en ton neutre plutot que de disparaitre : une
  // valeur ajoutee cote backend doit se voir a l'ecran, pas se taire.
  private readonly entree = computed(
    () => TONS[this.status()] ?? { ton: 'neutre' as Ton, libelle: this.status() },
  );

  readonly ton = computed(() => this.entree().ton);
  readonly libelle = computed(() => this.entree().libelle);
}
