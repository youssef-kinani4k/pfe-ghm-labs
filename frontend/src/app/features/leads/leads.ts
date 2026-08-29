import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { LeadApi, LeadQuery } from '../../core/api/lead-api';
import { ClientApi } from '../../core/api/client-api';
import { LeadSummary } from '../../core/models/lead';
import { ClientSummary, LeadStatus } from '../../core/models/monitoring';
import { StatusBadge } from '../../shared/status-badge/status-badge';

/**
 * Table en <b>mode serveur</b> : la page affichee est celle que l'API a rendue.
 *
 * Ne pas brancher un MatTableDataSource sur `content` : applique a une page deja paginee
 * par le serveur, il paginerait en memoire une page de vingt-cinq lignes et afficherait un
 * total faux. `[length]` vient de totalElements, et chaque (page) ou (sortChange) declenche
 * une requete.
 */
@Component({
  selector: 'app-leads',
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    MatTableModule,
    MatPaginatorModule,
    MatSortModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressBarModule,
    StatusBadge,
  ],
  templateUrl: './leads.html',
  styleUrl: './leads.scss',
})
export class Leads implements OnInit {
  private readonly api = inject(LeadApi);
  private readonly clientApi = inject(ClientApi);

  /**
   * Colonnes affichees.
   *
   * `clientName` et `salesRepName` ne portent pas d'en-tete de tri dans le gabarit : ces
   * champs n'existent pas sur l'entite Lead, le service les resout apres coup par une
   * seconde lecture, et les proposer au tri ferait rendre 500 par Spring Data, incapable
   * de resoudre la propriete.
   */
  readonly colonnes = [
    'createdAt',
    'clientName',
    'companyName',
    'email',
    'detectedIntent',
    'score',
    'status',
    'salesRepName',
  ];

  readonly statuts: LeadStatus[] = ['QUALIFIED', 'ROUTED', 'SYNCED', 'REJECTED', 'FAILED'];

  readonly lignes = signal<LeadSummary[]>([]);
  readonly total = signal(0);
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly clients = signal<ClientSummary[]>([]);

  // Etat des controles de filtre, lie par ngModel.
  clientId?: string;
  statutsChoisis: LeadStatus[] = [];
  minScore?: number;
  recherche = '';
  chaudsSeulement = false;

  requete: LeadQuery = { page: 0, size: 25, sort: 'createdAt,desc' };

  ngOnInit(): void {
    this.clientApi.clients().subscribe({ next: (liste) => this.clients.set(liste) });
    this.charge();
  }

  changePage(evenement: PageEvent): void {
    this.requete = { ...this.requete, page: evenement.pageIndex, size: evenement.pageSize };
    this.charge();
  }

  changeTri(tri: Sort): void {
    this.requete = { ...this.requete, page: 0, sort: `${tri.active},${tri.direction || 'desc'}` };
    this.charge();
  }

  appliqueFiltres(): void {
    // Retour a la premiere page : rester en page 4 apres un filtre affiche souvent du vide.
    this.requete = {
      ...this.requete,
      page: 0,
      clientId: this.clientId,
      status: this.statutsChoisis,
      minScore: this.minScore,
      q: this.recherche.trim(),
      // `undefined` et non `false` quand la case est decochee : voir `LeadQuery.chaud`.
      chaud: this.chaudsSeulement ? true : undefined,
    };
    this.charge();
  }

  reinitialise(): void {
    this.clientId = undefined;
    this.statutsChoisis = [];
    this.minScore = undefined;
    this.recherche = '';
    this.chaudsSeulement = false;
    this.appliqueFiltres();
  }

  private charge(): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.api.liste(this.requete).subscribe({
      next: (page) => {
        this.lignes.set(page.content);
        this.total.set(page.totalElements);
        this.enCours.set(false);
      },
      // Le 401 est deja traite par l'intercepteur : ce qui reste ici est une panne, et
      // l'ecran doit le dire plutot que de laisser une table vide qui ressemble a « aucun
      // lead ».
      error: () => {
        this.erreur.set('La liste des leads n a pas pu etre chargee.');
        this.enCours.set(false);
      },
    });
  }
}
