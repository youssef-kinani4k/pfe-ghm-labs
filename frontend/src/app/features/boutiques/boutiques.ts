import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TenantApi } from '../../core/api/tenant-api';
import { ClientSummaryAdmin } from '../../core/models/tenant';

/** Les libelles des strategies, ecrits une fois : l'enumeration du backend est en anglais. */
const STRATEGIES: Record<string, string> = {
  ROUND_ROBIN: 'Tour de role',
  GEOGRAPHIC: 'Geographique',
  SECTOR: 'Sectorielle',
};

/**
 * L'annuaire des boutiques, et le point d'entree de leur administration.
 *
 * Le compteur de commerciaux actifs se signale quand il vaut zero, comme « aucun
 * consommateur » sur l'ecran File d'attente : une boutique sans commercial capte des leads
 * que le routage ne pourra attribuer a personne, et ils finiront en DLQ. La configuration
 * qui casse silencieusement le pipeline doit se voir avant les leads morts.
 */
@Component({
  selector: 'app-boutiques',
  imports: [
    RouterLink,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './boutiques.html',
  styleUrl: './boutiques.scss',
})
export class Boutiques implements OnInit {
  private readonly api = inject(TenantApi);

  readonly colonnes = [
    'name',
    'crmProviderId',
    'assignmentStrategy',
    'activeSalesReps',
    'active',
    'actions',
  ];

  readonly boutiques = signal<ClientSummaryAdmin[]>([]);
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);

  /** Meme intention que `filesMuettes` : compter les configurations qui perdront des leads. */
  readonly sansCommercial = computed(() =>
    this.boutiques().filter((boutique) => boutique.active && boutique.activeSalesReps === 0),
  );

  ngOnInit(): void {
    this.charge();
  }

  charge(): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.api.boutiques().subscribe({
      next: (liste) => {
        this.boutiques.set(liste);
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set('La liste des boutiques n a pas pu etre chargee.');
        this.enCours.set(false);
      },
    });
  }

  strategie(valeur: string): string {
    return STRATEGIES[valeur] ?? valeur;
  }
}
