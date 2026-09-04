import { Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { LeadApi } from '../../../core/api/lead-api';
import { LeadDetail as LeadDetailModel } from '../../../core/models/lead';
import { StatusBadge } from '../../../shared/status-badge/status-badge';
import { LeadTimeline } from './lead-timeline/lead-timeline';
import {
  ReattributionData,
  ReattributionDialog,
} from './reattribution-dialog/reattribution-dialog';

/**
 * Detail d'un lead, sur une route et non dans une modale.
 *
 * Une URL partageable vaut mieux qu'une fenetre : en exploitation, le lien d'un lead en
 * echec se colle dans un ticket, et en demonstration il s'ouvre directement.
 *
 * Quatre blocs, dans l'ordre ou on les lit quand on diagnostique : le lead et son commercial,
 * la chronologie de ce qu'il a vecu, l'historique des synchronisations du plus recent au plus
 * ancien, puis l'evenement brut
 * avec sa charge utile. C'est ce dernier bloc qui repond a « pourquoi ce lead n'a pas de
 * telephone » sans ouvrir psql.
 */
@Component({
  selector: 'app-lead-detail',
  imports: [
    DatePipe,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatDividerModule,
    MatProgressBarModule,
    StatusBadge,
    LeadTimeline,
  ],
  templateUrl: './lead-detail.html',
  styleUrl: './lead-detail.scss',
})
export class LeadDetail implements OnInit {
  private readonly api = inject(LeadApi);
  private readonly route = inject(ActivatedRoute);
  private readonly dialogue = inject(MatDialog);

  /**
   * La chronologie sait recharger sa propre matiere : apres une reattribution, c'est elle
   * seule qu'il faut rafraichir, et c'est pour cela que son endpoint est separe du detail.
   */
  private readonly chronologie = viewChild(LeadTimeline);

  readonly lead = signal<LeadDetailModel | null>(null);
  readonly enCours = signal(true);
  readonly erreur = signal<string | null>(null);

  /** Du plus recent au plus ancien : en diagnostic, la derniere tentative est la question. */
  readonly tentatives = computed(() =>
    [...(this.lead()?.syncAttempts ?? [])].sort((a, b) =>
      b.attemptedAt.localeCompare(a.attemptedAt),
    ),
  );

  readonly payload = computed(() => {
    const brut = this.lead()?.rawEvent?.payload;
    return brut ? JSON.stringify(brut, null, 2) : null;
  });

  /**
   * Un lead sans commercial n'a rien a reattribuer : le backend refuse le geste, autant ne
   * pas offrir le bouton.
   */
  ouvreLaReattribution(): void {
    const lead = this.lead();
    if (!lead?.salesRep) {
      return;
    }
    this.dialogue
      // Les trois parametres de type rendent `afterClosed()` typé : sans eux la fiche
      // revient en `any` et une erreur de forme passerait la compilation.
      .open<ReattributionDialog, ReattributionData, LeadDetailModel>(ReattributionDialog, {
        width: '32rem',
        data: {
          leadId: lead.id,
          clientId: lead.clientId,
          salesRepId: lead.salesRep.id,
          statut: lead.status,
        },
      })
      .afterClosed()
      .subscribe((fiche) => {
        if (!fiche) {
          return;
        }
        // La fiche vient du serveur : elle montre l'etat reellement enregistre, pas celui
        // qu'on esperait.
        this.lead.set(fiche);
        this.chronologie()?.recharge();
      });
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.erreur.set('Identifiant de lead absent.');
      this.enCours.set(false);
      return;
    }
    this.api.detail(id).subscribe({
      next: (detail) => {
        this.lead.set(detail);
        this.enCours.set(false);
      },
      error: (echec) => {
        this.erreur.set(
          echec?.status === 404 ? 'Ce lead n existe pas.' : 'Le lead n a pas pu etre charge.',
        );
        this.enCours.set(false);
      },
    });
  }
}
