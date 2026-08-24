import { Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ClientApi } from '../../core/api/client-api';
import { StatsApi } from '../../core/api/stats-api';
import { LeadStream } from '../../core/stream/lead-stream';
import { ClientSummary, LeadStatus, StatsView } from '../../core/models/monitoring';
import { StatusBadge } from '../../shared/status-badge/status-badge';

/** Une part de repartition, prete a etre dessinee en barre. */
export interface Part {
  cle: string;
  libelle: string;
  valeur: number;
  pourcentage: number;
}

/**
 * Ecran d'accueil : l'etat du pipeline en un coup d'oeil, plus le flux de ce qui bouge.
 *
 * Pas de bibliotheque de graphiques : des compteurs et des `mat-progress-bar` suffisent aux
 * repartitions de cet ecran, et une dependance de plus aurait demande de faire correspondre
 * ses peerDependencies a chaque montee d'Angular.
 *
 * Le flux s'ouvre a l'arrivee et se ferme au depart : laisse ouvert apres la navigation, il
 * garderait une connexion et un emetteur serveur pour un ecran que personne ne regarde.
 */
@Component({
  selector: 'app-dashboard',
  imports: [
    DatePipe,
    DecimalPipe,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatProgressBarModule,
    MatFormFieldModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    StatusBadge,
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard implements OnInit, OnDestroy {
  private readonly api = inject(StatsApi);
  private readonly clientApi = inject(ClientApi);
  readonly flux = inject(LeadStream);

  readonly stats = signal<StatsView | null>(null);
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly clients = signal<ClientSummary[]>([]);
  readonly calculeesA = signal<Date | null>(null);
  readonly enPause = signal(false);

  clientId?: string;

  /** Ordre de lecture du pipeline, et non ordre alphabetique : un lead les traverse ainsi. */
  readonly statuts: LeadStatus[] = ['QUALIFIED', 'ROUTED', 'SYNCED', 'REJECTED', 'FAILED'];

  private dernierRafraichissement = 0;

  constructor() {
    // Un evenement du flux veut dire que les agregats ont vieilli. Les relire a chaque
    // evenement noierait le serveur sous les requetes lors d'une rafale : au plus une fois
    // toutes les dix secondes.
    effect(() => {
      this.flux.derniersLeads();
      const maintenant = Date.now();
      if (!this.enPause() && maintenant - this.dernierRafraichissement > 10_000) {
        this.dernierRafraichissement = maintenant;
        this.charge();
      }
    });
  }

  ngOnInit(): void {
    this.clientApi.clients().subscribe({ next: (liste) => this.clients.set(liste) });
    this.charge();
    this.flux.ouvre();
  }

  ngOnDestroy(): void {
    this.flux.ferme();
  }

  basculeLaPause(): void {
    this.enPause.update((pause) => !pause);
  }

  changeClient(): void {
    this.charge();
  }

  charge(): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.api.stats(this.clientId).subscribe({
      next: (vue) => {
        this.stats.set(vue);
        this.calculeesA.set(new Date());
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set('Les statistiques n ont pas pu etre chargees.');
        this.enCours.set(false);
      },
    });
  }

  /** Un statut sans lead vaut zero et s'affiche : l'absence est justement l'information. */
  compte(statut: string): number {
    return this.stats()?.leadsParStatut[statut] ?? 0;
  }

  readonly evenements = computed(() => this.parts(this.stats()?.evenementsParStatut));
  readonly intentions = computed(() => this.parts(this.stats()?.leadsParIntention));
  readonly sources = computed(() => this.parts(this.stats()?.leadsParSourceDIntention));

  readonly commerciaux = computed(() => {
    const vue = this.stats();
    return this.parts(vue?.leadsParCommercial, (cle) => vue?.nomsDeCommercial[cle] ?? cle);
  });

  private parts(
    repartition: Record<string, number> | undefined,
    nomme: (cle: string) => string = (cle) => cle,
  ): Part[] {
    if (!repartition) {
      return [];
    }
    const total = Object.values(repartition).reduce((somme, valeur) => somme + valeur, 0);
    return Object.entries(repartition)
      .map(([cle, valeur]) => ({
        cle,
        libelle: nomme(cle),
        valeur,
        // Une barre se lit par rapport a la plus grande part, pas dans l'absolu : sans
        // total, la division rendrait NaN et la barre disparaitrait.
        pourcentage: total > 0 ? (valeur / total) * 100 : 0,
      }))
      .sort((a, b) => b.valeur - a.valeur);
  }
}
