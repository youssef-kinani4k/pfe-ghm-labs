import { Component, OnInit, inject, input, signal } from '@angular/core';
import { DatePipe, KeyValuePipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { LeadApi } from '../../../../core/api/lead-api';
import { TimelineEntry, TimelineEventType, TimelineOutcome } from '../../../../core/models/lead';

/**
 * Libelle et icone de chacun des huit faits, en un seul endroit — meme parti que
 * `StatusBadge` : le vocabulaire d'interface ne se repete pas dans un template.
 *
 * Les icones sont des ligatures Material Symbols Outlined, police servie par l'origine :
 * la CSP de production dit `font-src 'self'`, et une icone chargee depuis Google
 * disparaitrait purement et simplement.
 */
const FAITS: Record<TimelineEventType, { libelle: string; icone: string }> = {
  CAPTURE: { libelle: 'Capture', icone: 'download' },
  QUALIFICATION: { libelle: 'Qualification', icone: 'psychology' },
  ATTRIBUTION: { libelle: 'Attribution', icone: 'person_add' },
  REATTRIBUTION: { libelle: 'Reattribution', icone: 'swap_horiz' },
  SYNC_ERP: { libelle: 'Synchronisation ERP', icone: 'sync' },
  MORT: { libelle: 'Message mort', icone: 'report' },
  REJEU: { libelle: 'Rejeu', icone: 'replay' },
  // Ecarter n'est pas rejouer : le message est abandonne, pas republie. Le libelle et
  // l'icone disent ce renoncement, sans quoi l'ecran annoncerait un traitement qui n'a
  // pas eu lieu.
  ECART: { libelle: 'Ecart', icone: 'block' },
};

/** Le mot qui double la couleur. `NEUTRE` n'en a pas : il n'y a rien a signaler. */
const ISSUES: Record<TimelineOutcome, string | null> = {
  SUCCES: 'Succes',
  ECHEC: 'Echec',
  NEUTRE: null,
};

/**
 * Les cles de `details` viennent du backend, qui rend des faits et non des phrases. Leur
 * mise en francais est une decision d'interface et vit donc ici.
 */
const DETAILS: Record<string, string> = {
  source: 'source',
  statut: 'statut',
  erreur: 'erreur',
  score: 'score',
  intention: 'intention',
  sourceIntention: 'source de l intention',
  connecteur: 'connecteur',
  commercialId: 'commercial',
  file: 'file',
  // « par » et non « rejoue par » : la meme cle porte desormais l'auteur d'un rejeu, d'un
  // ecart et d'une reattribution.
  par: 'par',
  motif: 'motif',
  ancienCommercial: 'ancien commercial',
  nouveauCommercial: 'nouveau commercial',
};

/**
 * Chronologie d'un lead, de sa capture a son eventuel rejeu.
 *
 * Local a l'ecran de detail et non dans `shared/` : rien d'autre ne le consomme. C'est ce
 * composant que F10 rouvrira pour y accrocher la reattribution, d'ou l'entree `leadId`
 * plutot qu'une liste passee toute faite — il sait recharger sa propre matiere.
 */
@Component({
  selector: 'app-lead-timeline',
  imports: [DatePipe, KeyValuePipe, MatIconModule],
  templateUrl: './lead-timeline.html',
  styleUrl: './lead-timeline.scss',
})
export class LeadTimeline implements OnInit {
  private readonly api = inject(LeadApi);

  readonly leadId = input.required<string>();

  readonly entrees = signal<TimelineEntry[]>([]);
  readonly enCours = signal(true);
  readonly erreur = signal<string | null>(null);

  ngOnInit(): void {
    this.recharge();
  }

  /** Publique : F10 l'appellera apres une reattribution. */
  recharge(): void {
    this.enCours.set(true);
    this.api.timeline(this.leadId()).subscribe({
      next: (entrees) => {
        this.entrees.set(entrees);
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set("La chronologie n'a pas pu etre chargee.");
        this.enCours.set(false);
      },
    });
  }

  // Un type inconnu s'affiche tel quel plutot que de disparaitre : un fait ajoute cote
  // backend doit se voir a l'ecran, pas se taire.
  libelle(type: TimelineEventType): string {
    return FAITS[type]?.libelle ?? type;
  }

  icone(type: TimelineEventType): string {
    return FAITS[type]?.icone ?? 'circle';
  }

  motDeLIssue(outcome: TimelineOutcome): string | null {
    return ISSUES[outcome] ?? null;
  }

  libelleDetail(cle: string): string {
    return DETAILS[cle] ?? cle;
  }
}
