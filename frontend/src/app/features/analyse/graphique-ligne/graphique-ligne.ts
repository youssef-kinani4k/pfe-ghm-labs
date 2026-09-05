import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  computed,
  effect,
  input,
  signal,
  viewChild,
} from '@angular/core';
import {
  CategoryScale,
  Chart,
  ChartArea,
  Filler,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  ScriptableContext,
  Tooltip,
  TooltipModel,
} from 'chart.js';

/**
 * Une serie a dessiner.
 *
 * `couleur` est attendue en notation hexadecimale (ex. '#334155') : le remplissage en degrade
 * et l'etat survole calculent leur alpha en concatenant deux chiffres au bout de la chaine,
 * ce qui suppose un hexadecimal a six chiffres. Une notation `rgb(...)` ou `hsl(...)`
 * produirait une couleur invalide, ignoree en silence par le canvas.
 *
 * `null` interrompt la ligne dans `valeurs`, il ne vaut jamais zero.
 *
 * `pointille` donne a la serie une **seconde cle de lecture que la couleur ne porte pas** :
 * deux courbes qui ne different que par la teinte sont indistinguables en deuteranopie, et
 * l'exigence vaut aussi pour un jury qui regarde un videoprojecteur delave.
 */
export interface SerieGraphique {
  nom: string;
  valeurs: (number | null)[];
  couleur: string;
  remplie: boolean;
  pointille?: boolean;
}

// Chart.js 4 est modulaire : sans cet enregistrement, rien ne se dessine et aucune erreur
// n'est levee. Il est fait une fois pour le module, pas a chaque instance. `Legend` n'y est
// pas : la legende est rendue en HTML, voir le Javadoc de la classe.
Chart.register(
  LineController,
  LineElement,
  PointElement,
  LinearScale,
  CategoryScale,
  Filler,
  Tooltip,
);

/** Etat d'une entree de legende, rendue en HTML et non par Chart.js. */
interface EntreeLegende {
  nom: string;
  couleur: string;
  pointille: boolean;
  masquee: boolean;
}

/**
 * Le seul composant du projet qui connaisse Chart.js.
 *
 * L'ecran d'analyse ne l'importe jamais : il passe des libelles, des series, des couleurs et
 * des formateurs. C'est ce qui rend la bibliotheque remplacable et le reste de l'ecran
 * testable sans elle — meme geste que `CrmConnector` derriere son port, a une autre echelle.
 * Aucun terme propre a une figure particuliere n'entre ici : ni « intention », ni « delai ».
 *
 * Trois choix de rendu meritent d'etre connus avant d'y toucher.
 *
 * <strong>La legende et l'infobulle sont en HTML, pas dessinees dans le canvas.</strong> Une
 * legende dessinee n'est ni focalisable ni lisible par un lecteur d'ecran, et une infobulle
 * dessinee ne peut porter ni ombre, ni rayon, ni typographie du systeme. Les sortir du canvas
 * coute un conteneur positionne et rend les deux accessibles au clavier.
 *
 * <strong>Le tableau de donnees cache double le graphique.</strong> Un canvas est opaque pour
 * un lecteur d'ecran : sans ce tableau, la figure n'existe pas pour qui ne la voit pas.
 *
 * <strong>L'instance est detruite au retrait du composant</strong> : laissee vivante, elle
 * garde un ecouteur de redimensionnement a chaque navigation vers l'ecran.
 */
@Component({
  selector: 'app-graphique-ligne',
  templateUrl: './graphique-ligne.html',
  styleUrl: './graphique-ligne.scss',
})
export class GraphiqueLigne implements AfterViewInit, OnDestroy {
  /** Libelles courts de l'axe des abscisses. */
  readonly libelles = input.required<string[]>();
  readonly series = input.required<SerieGraphique[]>();

  /**
   * Libelles longs, employes en titre d'infobulle. Vide : les libelles courts servent aux
   * deux usages.
   */
  readonly libellesLongs = input<string[]>([]);

  /**
   * Met en forme une valeur pour l'axe et l'infobulle. Par defaut, le nombre brut.
   *
   * C'est ce qui evite « 4.155809 » sur un axe de secondes sans que le composant ait a savoir
   * qu'il s'agit de secondes.
   */
  readonly formateur = input<(valeur: number) => string>((v) => String(v));

  /**
   * Ligne supplementaire affichee en pied d'infobulle pour l'index survole, ou `null`.
   *
   * Generique a dessein : le composant ignore ce qu'elle dit. L'ecran s'en sert pour donner
   * l'effectif d'une journee sous une part en pourcentage, sans quoi « 100 % » resterait
   * indiscernable selon qu'il repose sur un point ou sur trois cents.
   */
  readonly noteInfobulle = input<(index: number) => string | null>(() => null);

  /** Resume lu par un lecteur d'ecran a la place du canvas. */
  readonly descriptionAccessible = input<string>('Graphique');

  /**
   * Demande un axe empile a 100 %, plutot que des valeurs absolues superposees.
   *
   * Pose `scales.y.stacked` et fixe l'axe a `[0, 100]` : c'est la seule forme empilee que
   * l'ecran d'analyse utilise. Le composant reste generique — il ne sait toujours pas ce que
   * les series representent — mais cette entree encode que « empile » veut ici dire
   * « empile a 100 % », pas un empilement en valeurs absolues sans borne connue.
   */
  readonly empile = input<boolean>(false);

  private readonly toile = viewChild.required<ElementRef<HTMLCanvasElement>>('toile');
  private graphique?: Chart;

  /** Series masquees par un clic sur la legende, par indice. */
  private readonly masquees = signal<ReadonlySet<number>>(new Set());

  readonly legende = computed<EntreeLegende[]>(() => {
    const cachees = this.masquees();
    return this.series().map((s, i) => ({
      nom: s.nom,
      couleur: s.couleur,
      pointille: s.pointille === true,
      masquee: cachees.has(i),
    }));
  });

  /** Lignes du tableau cache : un libelle long, puis une valeur formatee par serie. */
  readonly lignesAccessibles = computed(() => {
    const longs = this.libellesLongs();
    const courts = this.libelles();
    const series = this.series();
    return courts.map((court, i) => ({
      jour: longs[i] ?? court,
      valeurs: series.map((s) => {
        const v = s.valeurs[i];
        return v === null || v === undefined ? 'aucune donnee' : this.formateur()(v);
      }),
    }));
  });

  constructor() {
    effect(() => {
      const libelles = this.libelles();
      const series = this.series();
      const cachees = this.masquees();
      if (this.graphique) {
        this.graphique.data.labels = libelles;
        this.graphique.data.datasets = this.jeux(series, cachees);
        this.graphique.update();
      }
    });
  }

  ngAfterViewInit(): void {
    this.graphique = new Chart(this.toile().nativeElement, {
      type: 'line',
      data: { labels: this.libelles(), datasets: this.jeux(this.series(), this.masquees()) },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        // Une valeur nulle interrompt la ligne au lieu d'etre franchie d'un trait : c'est
        // ce qui distingue « aucune donnee » de « valeur nulle ».
        spanGaps: false,
        // Une seule duree, courte, et rien du tout si le systeme demande moins de mouvement.
        animation: this.mouvementReduit() ? false : { duration: 420, easing: 'easeOutQuart' },
        interaction: { mode: 'index', intersect: false },
        layout: { padding: { top: 8, right: 4, bottom: 0, left: 0 } },
        plugins: {
          legend: { display: false },
          tooltip: {
            enabled: false,
            external: (ctx) => this.dessineInfobulle(ctx.tooltip),
          },
        },
        scales: {
          x: {
            grid: { display: false },
            border: { display: false },
            ticks: {
              color: '#64748b',
              font: { family: "'Fira Sans', Roboto, sans-serif", size: 11 },
              maxRotation: 0,
              autoSkipPadding: 24,
            },
          },
          y: {
            stacked: this.empile(),
            beginAtZero: true,
            ...(this.empile() ? { min: 0, max: 100 } : {}),
            // Grille horizontale seule, tres pale : elle guide l'oeil sans concurrencer la
            // courbe. La bordure d'axe est retiree, le graphique n'est pas un tableau.
            grid: { color: 'rgba(100, 116, 139, 0.14)' },
            border: { display: false, dash: [] },
            ticks: {
              color: '#64748b',
              font: { family: "'Fira Sans', Roboto, sans-serif", size: 11 },
              maxTicksLimit: 5,
              padding: 8,
              callback: (valeur) =>
                typeof valeur === 'number' ? this.formateur()(valeur) : String(valeur),
            },
          },
        },
      },
      plugins: [
        {
          id: 'viseur',
          // Une ligne verticale sous le point survole : elle rattache la valeur a sa date
          // sans que l'oeil ait a redescendre jusqu'a l'axe.
          afterDatasetsDraw: (chart) => {
            const actifs = chart.tooltip?.getActiveElements();
            if (!actifs?.length) {
              return;
            }
            const x = actifs[0].element.x;
            const { top, bottom } = chart.chartArea;
            const ctx = chart.ctx;
            ctx.save();
            ctx.beginPath();
            ctx.moveTo(x, top);
            ctx.lineTo(x, bottom);
            ctx.lineWidth = 1;
            ctx.strokeStyle = 'rgba(100, 116, 139, 0.45)';
            ctx.setLineDash([4, 4]);
            ctx.stroke();
            ctx.restore();
          },
        },
      ],
    });
  }

  ngOnDestroy(): void {
    this.graphique?.destroy();
    this.infobulle?.remove();
  }

  /** Masque ou revele une serie. La legende est un bouton, donc utilisable au clavier. */
  basculeSerie(indice: number): void {
    const suivant = new Set(this.masquees());
    if (suivant.has(indice)) {
      suivant.delete(indice);
    } else {
      suivant.add(indice);
    }
    this.masquees.set(suivant);
  }

  private mouvementReduit(): boolean {
    return (
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    );
  }

  private jeux(series: SerieGraphique[], masquees: ReadonlySet<number>) {
    return series.map((s, i) => ({
      label: s.nom,
      data: s.valeurs,
      hidden: masquees.has(i),
      borderColor: s.couleur,
      borderWidth: 2.25,
      borderDash: s.pointille ? [5, 4] : [],
      borderCapStyle: 'round' as const,
      borderJoinStyle: 'round' as const,
      // Le degrade s'evanouit vers le bas : il donne du corps a la courbe sans peser sur la
      // grille, la ou un aplat uniforme salit le fond de la carte.
      backgroundColor: s.remplie
        ? (ctx: ScriptableContext<'line'>) => this.degrade(ctx, s.couleur)
        : 'transparent',
      fill: s.remplie ? 'origin' : false,
      tension: 0.35,
      // Points invisibles au repos, reveles au survol : une courbe piquee de pastilles est
      // bruyante, et le point sert au moment ou on le vise, pas avant.
      pointRadius: 0,
      pointHoverRadius: 5,
      pointBackgroundColor: '#ffffff',
      pointHoverBackgroundColor: '#ffffff',
      pointBorderColor: s.couleur,
      pointHoverBorderColor: s.couleur,
      pointBorderWidth: 2,
      pointHoverBorderWidth: 2.5,
      pointHitRadius: 12,
    }));
  }

  private degrade(ctx: ScriptableContext<'line'>, couleur: string): CanvasGradient | string {
    const aire: ChartArea | undefined = ctx.chart.chartArea;
    // A la toute premiere passe, l'aire n'est pas encore mesuree. Rendre une couleur plate
    // evite l'exception ; la passe suivante pose le degrade.
    if (!aire) {
      return couleur + '22';
    }
    const g = ctx.chart.ctx.createLinearGradient(0, aire.top, 0, aire.bottom);
    g.addColorStop(0, couleur + '40');
    g.addColorStop(1, couleur + '05');
    return g;
  }

  private infobulle?: HTMLDivElement;

  /**
   * Rend l'infobulle en HTML, hors du canvas.
   *
   * Chart.js dessine son infobulle native dans le canvas : elle ne peut porter ni ombre, ni
   * rayon, ni la typographie du systeme, et elle est invisible pour un lecteur d'ecran. La
   * sortir coute un conteneur positionne et rend la carte coherente avec le reste de la
   * console.
   */
  private dessineInfobulle(modele: TooltipModel<'line'>): void {
    const hote = this.toile().nativeElement.parentElement;
    if (!hote) {
      return;
    }
    if (!this.infobulle) {
      this.infobulle = document.createElement('div');
      this.infobulle.className = 'graphique__infobulle';
      hote.appendChild(this.infobulle);
    }
    const bulle = this.infobulle;

    if (modele.opacity === 0) {
      bulle.style.opacity = '0';
      return;
    }

    const index = modele.dataPoints?.[0]?.dataIndex ?? 0;
    const longs = this.libellesLongs();
    const titre = longs[index] ?? modele.title?.[0] ?? '';
    const note = this.noteInfobulle()(index);

    const lignes = modele.dataPoints
      // Un point nul n'a rien a dire : l'infobulle l'omet plutot que d'afficher une ligne
      // vide, la ou la courbe s'est deja interrompue.
      .filter((p) => p.parsed.y !== null && p.parsed.y !== undefined)
      .map((p) => {
        const serie = this.series()[p.datasetIndex];
        const valeur = this.formateur()(p.parsed.y as number);
        const trait = serie?.pointille ? 'graphique__puce--pointille' : '';
        return `<li class="graphique__ligne">
            <span class="graphique__puce ${trait}" style="--puce: ${serie?.couleur}"></span>
            <span class="graphique__nom">${serie?.nom ?? ''}</span>
            <span class="graphique__valeur">${valeur}</span>
          </li>`;
      })
      .join('');

    bulle.innerHTML = `
      <p class="graphique__titre">${titre}</p>
      <ul class="graphique__lignes">${lignes}</ul>
      ${note ? `<p class="graphique__note">${note}</p>` : ''}`;

    // Positionnement relatif au conteneur, borne a gauche et a droite pour qu'une infobulle
    // de bord ne deborde pas de la carte.
    const largeur = bulle.offsetWidth;
    const max = hote.clientWidth - largeur - 4;
    const x = Math.min(Math.max(4, modele.caretX - largeur / 2), Math.max(4, max));
    bulle.style.opacity = '1';
    bulle.style.transform = `translate(${x}px, ${Math.max(4, modele.caretY - 12)}px)`;
  }
}
