import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Observable } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DeadLetterApi, DeadLetterQuery } from '../../core/api/dead-letter-api';
import { QueueApi } from '../../core/api/queue-api';
import { DeadLetterStatus, DeadLetterView, QueuesView } from '../../core/models/monitoring';
import { MotifData, MotifDialog } from '../../shared/motif-dialog/motif-dialog';
import { StatusBadge } from '../../shared/status-badge/status-badge';

/**
 * Les deux moities de la meme histoire.
 *
 * En haut la profondeur des files, en bas le journal des morts avec le motif de chaque
 * echec et le bouton de rejeu. Le journal est la source de verite des leads en echec : la
 * DLQ elle-meme devrait rester vide, puisque le consommateur de F6 la vide en journalisant.
 */
@Component({
  selector: 'app-queue',
  imports: [
    DatePipe,
    FormsModule,
    MatCardModule,
    MatTableModule,
    MatPaginatorModule,
    MatButtonModule,
    MatIconModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatSelectModule,
    MatProgressBarModule,
    MatTooltipModule,
    StatusBadge,
  ],
  templateUrl: './queue.html',
  styleUrl: './queue.scss',
})
export class Queue implements OnInit {
  private readonly queueApi = inject(QueueApi);
  private readonly api = inject(DeadLetterApi);
  private readonly dialogue = inject(MatDialog);

  readonly colonnes = [
    'selection',
    'deadAt',
    'originQueue',
    'clientName',
    'failureReason',
    'status',
    'actions',
  ];

  readonly files = signal<QueuesView | null>(null);
  readonly morts = signal<DeadLetterView[]>([]);
  readonly total = signal(0);
  readonly enCours = signal(false);
  readonly message = signal<string | null>(null);
  readonly erreur = signal<string | null>(null);
  readonly selection = signal<Set<string>>(new Set());

  readonly statuts: DeadLetterStatus[] = ['PENDING', 'REPLAYED', 'DISCARDED'];
  statutChoisi?: DeadLetterStatus = 'PENDING';

  requete: DeadLetterQuery = { page: 0, size: 25, status: 'PENDING' };

  /** Une file sans consommateur est la mesure la plus lisible d'un listener tombe. */
  readonly filesMuettes = computed(
    () => this.files()?.queues.filter((file) => file.reachable && file.consumerCount === 0) ?? [],
  );

  ngOnInit(): void {
    this.chargeLesFiles();
    this.charge();
  }

  chargeLesFiles(): void {
    this.queueApi.files().subscribe({
      next: (vue) => this.files.set(vue),
      // L'etat des files est lu en AMQP : le broker peut etre injoignable sans que le reste
      // de l'ecran soit inutilisable, donc l'echec ne vide pas le journal.
      error: () => this.files.set(null),
    });
  }

  changeStatut(): void {
    this.requete = { ...this.requete, page: 0, status: this.statutChoisi };
    this.charge();
  }

  changePage(evenement: PageEvent): void {
    this.requete = { ...this.requete, page: evenement.pageIndex, size: evenement.pageSize };
    this.charge();
  }

  charge(): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.selection.set(new Set());
    this.api.liste(this.requete).subscribe({
      next: (page) => {
        this.morts.set(page.content);
        this.total.set(page.totalElements);
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set('Le journal des messages morts n a pas pu etre charge.');
        this.enCours.set(false);
      },
    });
  }

  bascule(id: string): void {
    this.selection.update((courante) => {
      const suivante = new Set(courante);
      if (!suivante.delete(id)) {
        suivante.add(id);
      }
      return suivante;
    });
  }

  estSelectionne(id: string): boolean {
    return this.selection().has(id);
  }

  rejoue(mort: DeadLetterView): void {
    // L'avertissement vient du serveur : la reattribution est une consequence d'une
    // decision de F4 — le tour de role n'est pas idempotent — pas une regle d'ecran.
    this.demandeUnMotif(
      {
        titre: 'Rejouer ce message',
        avertissement: mort.replayWarning ?? undefined,
        action: 'Rejouer',
      },
      (motif) => this.appelle(this.api.rejoue(mort.id, motif), 'Message rejoue.'),
    );
  }

  ecarte(mort: DeadLetterView): void {
    this.demandeUnMotif(
      {
        titre: 'Ecarter ce message',
        avertissement: 'Il ne sera plus rejouable.',
        action: 'Ecarter',
      },
      (motif) => this.appelle(this.api.ecarte(mort.id, motif), 'Message ecarte.'),
    );
  }

  /**
   * Rejeu de la selection.
   *
   * Il n'y a pas d'endpoint de masse, et ce n'est pas un oubli : un rejeu en bloc sur un
   * incident non compris multiplie l'incident. La boucle sur l'appel unitaire garde la
   * meme garantie ligne a ligne, et la confirmation annonce le nombre.
   */
  rejoueLaSelection(): void {
    const identifiants = [...this.selection()];
    if (identifiants.length === 0) {
      return;
    }
    // Un seul motif pour tout le lot, et non un par ligne : on rejoue une selection parce
    // qu'on a compris une cause commune, et redemander la meme phrase a chaque ligne la
    // ferait ecrire au hasard des la troisieme.
    this.demandeUnMotif(
      {
        titre: `Rejouer ${identifiants.length} message(s)`,
        avertissement: 'Le meme motif sera consigne pour toute la selection.',
        action: 'Rejouer',
      },
      (motif) => {
        let restants = identifiants.length;
        let echecs = 0;
        for (const id of identifiants) {
          this.api.rejoue(id, motif).subscribe({
            next: () => this.termine(--restants, echecs),
            error: () => {
              echecs += 1;
              this.termine(--restants, echecs);
            },
          });
        }
      },
    );
  }

  /**
   * Le motif remplace le `confirm()` du navigateur : il confirme le geste et le justifie
   * d'un seul mouvement, la ou la boite native ne rendait qu'un oui ou un non. Une fermeture
   * sans motif — annulation, echappement, clic hors du cadre — n'appelle rien.
   */
  private demandeUnMotif(donnees: MotifData, suite: (motif: string) => void): void {
    this.dialogue
      .open<MotifDialog, MotifData, string>(MotifDialog, { width: '30rem', data: donnees })
      .afterClosed()
      .subscribe((motif) => {
        if (motif) {
          suite(motif);
        }
      });
  }

  private termine(restants: number, echecs: number): void {
    if (restants > 0) {
      return;
    }
    this.message.set(
      echecs === 0 ? 'Selection rejouee.' : `${echecs} message(s) n ont pas pu etre rejoues.`,
    );
    this.chargeLesFiles();
    this.charge();
  }

  private appelle(appel: Observable<void>, succes: string): void {
    appel.subscribe({
      next: () => {
        this.message.set(succes);
        this.chargeLesFiles();
        this.charge();
      },
      error: (echec: { status?: number }) => {
        this.message.set(this.explique(echec?.status));
        // Un 409 veut dire que la ligne a bouge ailleurs : la liste affichee est perimee,
        // et la recharger vaut mieux que laisser l'operateur cliquer sur un etat mort.
        if (echec?.status === 409) {
          this.charge();
        }
      },
    });
  }

  private explique(code?: number): string {
    if (code === 409) {
      return 'Cette ligne a deja ete traitee, sans doute depuis un autre onglet.';
    }
    if (code === 503) {
      // Ce n'est pas un echec de l'operateur : la ligne reste en attente et le geste
      // reussira une fois le broker revenu.
      return 'Le broker est injoignable. Le message reste en attente, a rejouer plus tard.';
    }
    if (code === 404) {
      return 'Cette ligne n existe plus.';
    }
    return 'Le geste n a pas abouti.';
  }
}
