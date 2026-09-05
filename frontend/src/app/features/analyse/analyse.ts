import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
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
 * Le chiffre qui resume une carte, lu avant le graphique.
 *
 * `variation` compare la seconde moitie de la fenetre a la premiere, et non la periode
 * precedente : l'endpoint ne sert qu'une fenetre a la fois, et un second appel pour une
 * comparaison indicative couterait le double de requetes. Le libelle affiche le dit — « vs
 * debut de periode » — plutot que de laisser croire a une comparaison qui n'a pas lieu.
 *
 * `baisseEstBonne` porte le sens metier de la couleur : un delai qui descend est une bonne
 * nouvelle, un volume qui descend n'en est pas une.
 */
export interface Kpi {
  valeur: string;
  variation: number | null;
  unite: 'pourcent' | 'points';
  baisseEstBonne: boolean;
}

/** Met en forme une duree en secondes de facon lisible a l'oeil, pas a la microseconde. */
export function formateDuree(secondes: number): string {
  if (secondes < 10) {
    return `${secondes.toFixed(1).replace('.', ',')} s`;
  }
  if (secondes < 60) {
    return `${Math.round(secondes)} s`;
  }
  if (secondes < 3600) {
    const min = Math.floor(secondes / 60);
    const reste = Math.round(secondes % 60);
    return reste === 0 ? `${min} min` : `${min} min ${String(reste).padStart(2, '0')}`;
  }
  const heures = Math.floor(secondes / 3600);
  const min = Math.round((secondes % 3600) / 60);
  return min === 0 ? `${heures} h` : `${heures} h ${String(min).padStart(2, '0')}`;
}

/** Entiers separes par groupes de milliers, a la francaise. */
export function formateEntier(valeur: number): string {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(valeur);
}

/** Pourcentage sans decimale superflue : « 62 % », « 62,5 % ». */
export function formatePourcentage(valeur: number): string {
  const arrondi = Math.round(valeur * 10) / 10;
  return `${String(arrondi).replace('.', ',')} %`;
}

/**
 * Construit une date locale a partir d'un `YYYY-MM-DD`.
 *
 * `new Date('2026-03-02')` est interprete en UTC : sous un fuseau negatif, la date affichee
 * reculerait d'un jour. Les composants sont donc poses explicitement.
 */
function dateDe(iso: string): Date {
  const [a, m, j] = iso.split('-').map(Number);
  return new Date(a, m - 1, j);
}

/** « 22 août » — pour un axe, ou la place manque. */
export function dateCourte(iso: string): string {
  return new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short' }).format(dateDe(iso));
}

/** « vendredi 22 août 2026 » — pour une infobulle, ou la place existe. */
export function dateLongue(iso: string): string {
  return new Intl.DateTimeFormat('fr-FR', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(dateDe(iso));
}

/** Variation en pourcentage entre deux totaux, ou `null` si le point de depart est nul. */
function variation(avant: number, apres: number): number | null {
  if (avant === 0) {
    return null;
  }
  return Math.round(((apres - avant) / avant) * 1000) / 10;
}

/**
 * Ecran d'analyse : ce que les compteurs du dashboard ne savent pas dire, faute de temps.
 *
 * Trois figures, chacune repondant a une question qu'aucun chiffre de la console ne repond.
 * Le taux d'echec ERP n'y est pas : l'ecran des connecteurs le sert deja, et un graphique qui
 * redit un compteur affiche ailleurs n'apporte rien.
 *
 * La lecture est voulue de haut en bas et en trois temps : le chiffre-cle de chaque carte se
 * comprend en une seconde, la courbe donne la tendance, l'infobulle donne le detail du jour.
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

  /** Formateurs passes au composant graphique, qui ignore ce que ses valeurs representent. */
  readonly formateEntier = formateEntier;
  readonly formateDuree = formateDuree;
  readonly formatePourcentage = formatePourcentage;

  /**
   * Vide au sens de l'ecran : aucune des trois series ne porte quoi que ce soit.
   *
   * Le volume seul ne suffit pas : les delais sont ancres sur le jour de la
   * synchronisation, pas de la capture. Un lead capture il y a quarante jours et
   * synchronise aujourd'hui (un rejeu apres une panne ERP, par exemple) donne un volume a
   * zero sur toute une fenetre de sept jours et pourtant un point de delai reel. Juger
   * l'ecran vide sur le seul volume afficherait alors « Aucune donnee », ce qui est faux et
   * se lit comme la panne que cet etat vide devait justement eviter.
   */
  readonly vide = computed(() => {
    const d = this.donnees();
    if (!d) {
      return false;
    }
    const volumeVide = d.volume.every((p) => p.captures === 0);
    const delaisVide = d.delais.every((p) => p.medianeSecondes === null && p.p95Secondes === null);
    const intentionsVide = d.intentions.every((p) => p.gemini === 0 && p.lexique === 0);
    return volumeVide && delaisVide && intentionsVide;
  });

  /** Libelles d'axe : courts, sans quoi 90 dates illisibles se chevauchent. */
  readonly libelles = computed(() => (this.donnees()?.volume ?? []).map((p) => dateCourte(p.jour)));

  /** Libelles d'infobulle : la date entiere, la ou la place existe. */
  readonly libellesLongs = computed(() =>
    (this.donnees()?.volume ?? []).map((p) => dateLongue(p.jour)),
  );

  readonly kpiVolume = computed<Kpi | null>(() => {
    const d = this.donnees();
    if (!d) {
      return null;
    }
    const produits = d.volume.map((p) => p.captures - p.ecartes);
    const total = produits.reduce((a, b) => a + b, 0);
    const moitie = Math.floor(produits.length / 2);
    const debut = produits.slice(0, moitie).reduce((a, b) => a + b, 0);
    const fin = produits.slice(moitie).reduce((a, b) => a + b, 0);
    return {
      valeur: formateEntier(total),
      variation: variation(debut, fin),
      unite: 'pourcent',
      baisseEstBonne: false,
    };
  });

  readonly kpiDelai = computed<Kpi | null>(() => {
    const d = this.donnees();
    if (!d) {
      return null;
    }
    // Mediane des medianes journalieres : une journee compte pour une, quel que soit son
    // volume. Une moyenne laisserait un seul jour de panne ERP ecraser tout le mois.
    const medianes = d.delais
      .map((p) => p.medianeSecondes)
      .filter((v): v is number => v !== null)
      .sort((a, b) => a - b);
    if (medianes.length === 0) {
      return null;
    }
    const milieu = Math.floor(medianes.length / 2);
    const typique =
      medianes.length % 2 === 0 ? (medianes[milieu - 1] + medianes[milieu]) / 2 : medianes[milieu];

    const moitie = Math.floor(d.delais.length / 2);
    const moyenne = (de: number, a: number) => {
      const v = d.delais
        .slice(de, a)
        .map((p) => p.medianeSecondes)
        .filter((x): x is number => x !== null);
      return v.length ? v.reduce((s, x) => s + x, 0) / v.length : 0;
    };
    return {
      valeur: formateDuree(typique),
      variation: variation(moyenne(0, moitie), moyenne(moitie, d.delais.length)),
      unite: 'pourcent',
      // Un delai qui descend est une bonne nouvelle : la couleur doit suivre le sens metier,
      // pas le signe arithmetique.
      baisseEstBonne: true,
    };
  });

  readonly kpiIntentions = computed<Kpi | null>(() => {
    const d = this.donnees();
    if (!d) {
      return null;
    }
    const part = (de: number, a: number): number | null => {
      const tranche = d.intentions.slice(de, a);
      const g = tranche.reduce((s, p) => s + p.gemini, 0);
      const total = g + tranche.reduce((s, p) => s + p.lexique, 0);
      return total === 0 ? null : (g / total) * 100;
    };
    const globale = part(0, d.intentions.length);
    if (globale === null) {
      return null;
    }
    const moitie = Math.floor(d.intentions.length / 2);
    const avant = part(0, moitie);
    const apres = part(moitie, d.intentions.length);
    return {
      valeur: formatePourcentage(globale),
      // Une part se compare en points de pourcentage, pas en pourcentage de pourcentage.
      variation: avant === null || apres === null ? null : Math.round((apres - avant) * 10) / 10,
      unite: 'points',
      baisseEstBonne: false,
    };
  });

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
        // teinte rouge-vert se distingue mal en deuteranopie. Le trait pointille et l'absence
        // de remplissage donnent deux cles de lecture que la couleur seule ne porte pas.
        nom: 'Ecartes',
        valeurs: d.volume.map((p) => p.ecartes),
        couleur: this.jeton('--lf-echec'),
        remplie: false,
        pointille: true,
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
        remplie: true,
      },
      {
        nom: '95e centile',
        valeurs: d.delais.map((p) => p.p95Secondes),
        couleur: this.jeton('--lf-attente'),
        remplie: false,
        pointille: true,
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
        pointille: true,
      },
    ];
  });

  /**
   * Effectif du jour, affiche en pied d'infobulle de la carte des intentions.
   *
   * Sans lui, « 100 % Gemini » resterait indiscernable selon qu'il repose sur un lead ou sur
   * trois cents — la limite connue de toute aire empilee a 100 % sur de petits echantillons.
   */
  readonly effectifIntentions = (index: number): string | null => {
    const p = this.donnees()?.intentions[index];
    if (!p) {
      return null;
    }
    const total = p.gemini + p.lexique;
    if (total === 0) {
      return 'Aucun lead analyse ce jour-la';
    }
    return `Sur ${formateEntier(total)} lead${total > 1 ? 's' : ''} analyse${total > 1 ? 's' : ''}`;
  };

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
