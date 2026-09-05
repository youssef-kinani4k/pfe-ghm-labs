import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ClientApi } from '../../core/api/client-api';
import { StatsApi } from '../../core/api/stats-api';
import { ClientSummary, SeriesView } from '../../core/models/monitoring';
import { GraphiqueLigne, SerieGraphique } from './graphique-ligne/graphique-ligne';

/** Les trois fenetres admises par le serveur. Toute autre valeur rend `400`. */
const FENETRES = [7, 30, 90] as const;

/**
 * Ecran d'analyse : ce que les compteurs du dashboard ne savent pas dire, faute de temps.
 *
 * Trois figures, chacune repondant a une question qu'aucun chiffre de la console ne repond.
 * Le taux d'echec ERP n'y est pas : l'ecran des connecteurs le sert deja, et un graphique qui
 * redit un compteur affiche ailleurs n'apporte rien.
 *
 * Cet ecran n'importe jamais Chart.js — seul `GraphiqueLigne` le connait. Les couleurs des
 * jetons `--lf-*` sont resolues ici en valeurs concretes avant d'etre transmises : un canvas
 * ne resout pas les variables CSS, une couleur passee telle quelle serait ignoree et la
 * courbe sortirait invisible, en silence.
 */
@Component({
  selector: 'app-analyse',
  imports: [
    FormsModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    GraphiqueLigne,
  ],
  templateUrl: './analyse.html',
  styleUrl: './analyse.scss',
})
export class Analyse implements OnInit {
  private readonly api = inject(StatsApi);
  private readonly clientApi = inject(ClientApi);

  readonly fenetres = FENETRES;
  readonly jours = signal<number>(30);
  readonly clientId = signal<string | undefined>(undefined);
  readonly boutiques = signal<ClientSummary[]>([]);
  readonly donnees = signal<SeriesView | null>(null);
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);

  /** Vide au sens de l'ecran : aucune journee de la fenetre ne porte quoi que ce soit. */
  readonly vide = computed(() => {
    const d = this.donnees();
    if (!d) {
      return false;
    }
    return d.volume.every((p) => p.captures === 0);
  });

  readonly libelles = computed(() => (this.donnees()?.volume ?? []).map((p) => p.jour));

  readonly serieVolume = computed<SerieGraphique[]>(() => {
    const d = this.donnees();
    if (!d) {
      return [];
    }
    return [
      {
        nom: 'Leads produits',
        valeurs: d.volume.map((p) => p.captures - p.ecartes),
        couleur: this.jeton('--lf-succes'),
        remplie: true,
      },
      {
        // Meme paire rouge/vert que le reste de la console (badges de statut), mais une
        // teinte rouge-vert se distingue mal en deuteranopie. Le contour seul, sans
        // remplissage, donne une seconde cle de lecture que la couleur seule ne porte pas —
        // en plus de la legende et de l'infobulle, deja textuelles.
        nom: 'Ecartes',
        valeurs: d.volume.map((p) => p.ecartes),
        couleur: this.jeton('--lf-echec'),
        remplie: false,
      },
    ];
  });

  readonly serieDelais = computed<SerieGraphique[]>(() => {
    const d = this.donnees();
    if (!d) {
      return [];
    }
    // Les nulls sont transmis tels quels : ils interrompent la ligne. Les remplacer par
    // zero dessinerait une chute vers le bas, soit l'inverse du sens.
    return [
      {
        nom: 'Mediane',
        valeurs: d.delais.map((p) => p.medianeSecondes),
        couleur: this.jeton('--lf-neutre'),
        remplie: false,
      },
      {
        nom: '95e centile',
        valeurs: d.delais.map((p) => p.p95Secondes),
        couleur: this.jeton('--lf-attente'),
        remplie: false,
      },
    ];
  });

  /**
   * Aire empilee a 100 % : chaque journee devient une part de Gemini et une part de lexique,
   * pas un compteur absolu — deux boutiques a 10 et 1000 leads par jour ne sont pas
   * comparables en valeurs brutes.
   *
   * Une journee sans aucune analyse (`gemini` et `lexique` tous deux a zero) rend `null` des
   * deux cotes, jamais zero : zero pour cent de Gemini serait une affirmation fausse la ou
   * il n'y a simplement rien a mesurer ce jour-la. Meme regle que celle qui gouverne deja les
   * delais dans cette feature.
   */
  readonly serieIntentions = computed<SerieGraphique[]>(() => {
    const d = this.donnees();
    if (!d) {
      return [];
    }
    const parts = d.intentions.map((p) => {
      const total = p.gemini + p.lexique;
      if (total === 0) {
        return { gemini: null, lexique: null };
      }
      return {
        gemini: Math.round((p.gemini / total) * 1000) / 10,
        lexique: Math.round((p.lexique / total) * 1000) / 10,
      };
    });
    return [
      {
        nom: 'Gemini',
        valeurs: parts.map((p) => p.gemini),
        couleur: this.jeton('--lf-succes'),
        remplie: true,
      },
      {
        nom: 'Lexique',
        valeurs: parts.map((p) => p.lexique),
        couleur: this.jeton('--lf-neutre'),
        remplie: true,
      },
    ];
  });

  ngOnInit(): void {
    this.clientApi.clients().subscribe({
      next: (liste) => this.boutiques.set(liste),
      error: () => this.boutiques.set([]),
    });
    this.charge();
  }

  choisitFenetre(jours: number): void {
    this.jours.set(jours);
    this.charge();
  }

  choisitBoutique(clientId: string | undefined): void {
    this.clientId.set(clientId);
    this.charge();
  }

  private charge(): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.api.series(this.clientId(), this.jours()).subscribe({
      next: (vue) => {
        this.donnees.set(vue);
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set("L'analyse n'a pas pu etre chargee.");
        this.enCours.set(false);
      },
    });
  }

  /**
   * Resout un jeton `--lf-*` en sa valeur hexadecimale concrete.
   *
   * Un canvas ne resout pas les variables CSS : passer `var(--lf-succes)` telle quelle a
   * Chart.js produirait une couleur invalide, ignoree en silence — meme genre d'echec muet
   * que l'inlining du CSS critique du dashboard. `getComputedStyle` donne la valeur reellement
   * appliquee, celle que `styles.scss` pose sur `:root`.
   */
  private jeton(nom: string): string {
    return getComputedStyle(document.documentElement).getPropertyValue(nom).trim();
  }
}
