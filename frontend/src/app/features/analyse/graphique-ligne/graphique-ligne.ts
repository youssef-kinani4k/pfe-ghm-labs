import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  effect,
  input,
  viewChild,
} from '@angular/core';
import {
  CategoryScale,
  Chart,
  Filler,
  Legend,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  Tooltip,
} from 'chart.js';

/**
 * Une serie a dessiner.
 *
 * `couleur` est attendue en notation hexadecimale (ex. '#334155') : le remplissage calcule
 * son alpha en concatenant '33' au bout de la chaine, ce qui suppose un hexadecimal a six
 * chiffres. Une notation `rgb(...)` ou `hsl(...)` produirait une couleur invalide, ignoree
 * en silence par le canvas. `null` interrompt la ligne dans `valeurs`, il ne vaut jamais
 * zero.
 */
export interface SerieGraphique {
  nom: string;
  valeurs: (number | null)[];
  couleur: string;
  remplie: boolean;
}

// Chart.js 4 est modulaire : sans cet enregistrement, rien ne se dessine et aucune erreur
// n'est levee. Il est fait une fois pour le module, pas a chaque instance.
Chart.register(
  LineController,
  LineElement,
  PointElement,
  LinearScale,
  CategoryScale,
  Filler,
  Legend,
  Tooltip,
);

/**
 * Le seul composant du projet qui connaisse Chart.js.
 *
 * L'ecran d'analyse ne l'importe jamais : il passe des libelles, des series et des couleurs.
 * C'est ce qui rend la bibliotheque remplacable et le reste de l'ecran testable sans elle —
 * meme geste que `CrmConnector` derriere son port, a une autre echelle.
 *
 * L'instance est detruite au retrait du composant : laissee vivante, elle garde un ecouteur
 * de redimensionnement a chaque navigation vers l'ecran.
 */
@Component({
  selector: 'app-graphique-ligne',
  template: '<canvas #toile></canvas>',
  styles: ':host { display: block; position: relative; height: 260px; }',
})
export class GraphiqueLigne implements AfterViewInit, OnDestroy {
  readonly libelles = input.required<string[]>();
  readonly series = input.required<SerieGraphique[]>();
  readonly uniteY = input<string>('');
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

  constructor() {
    effect(() => {
      const libelles = this.libelles();
      const series = this.series();
      if (this.graphique) {
        this.graphique.data.labels = libelles;
        this.graphique.data.datasets = this.jeux(series);
        this.graphique.update();
      }
    });
  }

  ngAfterViewInit(): void {
    this.graphique = new Chart(this.toile().nativeElement, {
      type: 'line',
      data: { labels: this.libelles(), datasets: this.jeux(this.series()) },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        // Une valeur nulle interrompt la ligne au lieu d'etre franchie d'un trait : c'est
        // ce qui distingue « aucune donnee » de « valeur nulle ».
        spanGaps: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { position: 'bottom' },
          tooltip: {
            callbacks: {
              label: (ctx) =>
                `${ctx.dataset.label} : ${ctx.formattedValue}${
                  this.uniteY() ? ' ' + this.uniteY() : ''
                }`,
            },
          },
        },
        scales: {
          y: this.empile() ? { stacked: true, min: 0, max: 100 } : { beginAtZero: true },
        },
      },
    });
  }

  ngOnDestroy(): void {
    this.graphique?.destroy();
  }

  private jeux(series: SerieGraphique[]) {
    return series.map((s) => ({
      label: s.nom,
      data: s.valeurs,
      borderColor: s.couleur,
      backgroundColor: s.remplie ? s.couleur + '33' : s.couleur,
      fill: s.remplie,
      tension: 0.25,
      pointRadius: 2,
    }));
  }
}
