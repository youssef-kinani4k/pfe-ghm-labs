import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ClientApi } from '../../../../core/api/client-api';
import { LeadApi } from '../../../../core/api/lead-api';
import { LeadDetail } from '../../../../core/models/lead';
import { LeadStatus, SalesRepSummary } from '../../../../core/models/monitoring';

/** Ce que la fiche du lead passe au dialogue. */
export interface ReattributionData {
  leadId: string;
  clientId: string;
  /** Le commercial en place : il ne figure pas parmi les candidats. */
  salesRepId: string;
  statut: LeadStatus;
}

/**
 * Reattribution manuelle d'un lead.
 *
 * Le premier dialogue de l'application, et il pose la convention : le motif etant
 * obligatoire, un `confirm()` du navigateur — la solution retenue jusqu'ici par le journal
 * des morts — ne suffisait plus.
 *
 * L'avertissement sur un lead `SYNCED` n'est pas cosmetique : depuis F15, la reattribution
 * propage la correction du responsable vers l'ERP, de facon asynchrone. Le message annonce
 * cette transmission, il n'avoue pas une limitation qui n'existe plus.
 */
@Component({
  selector: 'app-reattribution-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
  ],
  templateUrl: './reattribution-dialog.html',
  styleUrl: './reattribution-dialog.scss',
})
export class ReattributionDialog {
  private readonly clients = inject(ClientApi);
  private readonly leads = inject(LeadApi);
  private readonly fermeture = inject<MatDialogRef<ReattributionDialog, LeadDetail>>(MatDialogRef);
  readonly data = inject<ReattributionData>(MAT_DIALOG_DATA);

  readonly commerciaux = signal<SalesRepSummary[]>([]);
  readonly commercialChoisi = signal<string | null>(null);
  readonly motif = signal('');
  readonly chargement = signal(true);
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);

  /**
   * Ni le commercial en place — le backend refuse le geste en 409 — ni les desactives, qu'il
   * refuse aussi. Filtrer ici evite de proposer un choix voue au refus.
   */
  readonly candidats = computed(() =>
    this.commerciaux().filter((c) => c.active && c.id !== this.data.salesRepId),
  );

  /** La liste chargee et vide n'est pas la liste en cours de chargement. */
  readonly aucunCandidat = computed(() => !this.chargement() && this.candidats().length === 0);

  readonly avertitSurERP = computed(() => this.data.statut === 'SYNCED');

  readonly valide = computed(
    () => this.commercialChoisi() !== null && this.motif().trim().length > 0,
  );

  constructor() {
    this.clients.commerciaux(this.data.clientId).subscribe({
      next: (liste) => {
        this.commerciaux.set(liste);
        this.chargement.set(false);
      },
      error: () => {
        this.chargement.set(false);
        this.erreur.set("La liste des commerciaux n'a pas pu etre chargee.");
      },
    });
  }

  confirme(): void {
    if (!this.valide() || this.enCours()) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    this.leads
      .reattribue(this.data.leadId, {
        salesRepId: this.commercialChoisi()!,
        reason: this.motif().trim(),
      })
      .subscribe({
        next: (fiche) => this.fermeture.close(fiche),
        error: (echec) => {
          this.enCours.set(false);
          // Le backend rend un ProblemDetail dont `detail` porte la phrase utile ; le corps
          // brut ne s'affiche jamais tel quel, il n'a de sens que dans les journaux.
          this.erreur.set(echec?.error?.detail ?? "La reattribution n'a pas pu etre enregistree.");
        },
      });
  }

  annule(): void {
    this.fermeture.close();
  }
}
