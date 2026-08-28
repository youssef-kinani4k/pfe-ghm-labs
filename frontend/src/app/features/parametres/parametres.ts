import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { IntentApi } from '../../core/api/intent-api';
import { StatsApi } from '../../core/api/stats-api';
import { CauseIntent, EtatIntent, IntentTestResult } from '../../core/models/intent';

/**
 * Reglage de l'analyse d'intention par IA.
 *
 * L'ecran repose sur une distinction que la console doit rendre evidente : la cle
 * enregistree, et ce que la qualification en fait reellement. Une cle valide dont
 * l'interrupteur est ferme, ou une cle expiree qui laisse le pipeline en mode lexical,
 * doivent se voir immediatement — d'ou le bandeau d'etat en tete, appuye sur les compteurs
 * de `intent_source` et non sur ce que le formulaire croit.
 *
 * La cle saisie n'est jamais reaffichee : le serveur ne la rend pas, et le champ se vide
 * apres l'enregistrement plutot que de garder un secret a l'ecran.
 */
@Component({
  selector: 'app-parametres',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
  ],
  templateUrl: './parametres.html',
  styleUrl: './parametres.scss',
})
export class Parametres implements OnInit {
  private readonly api = inject(IntentApi);
  private readonly statsApi = inject(StatsApi);

  readonly etat = signal<EtatIntent | null>(null);
  readonly diagnostic = signal<IntentTestResult | null>(null);
  readonly cleSaisie = signal('');
  readonly cleVisible = signal(false);
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly message = signal<string | null>(null);

  private readonly parSource = signal<Record<string, number>>({});

  readonly parGemini = computed(() => this.parSource()['GEMINI'] ?? 0);
  readonly parRegles = computed(() => this.parSource()['RULES'] ?? 0);

  /**
   * L'analyse ne tourne que si l'interrupteur est ouvert ET qu'une cle existe. Les deux
   * conditions echouent differemment et se reparent differemment : le bandeau les nomme.
   */
  readonly analyseActive = computed(() => {
    const etat = this.etat();
    return etat !== null && etat.actif && etat.cleDefinie;
  });

  ngOnInit(): void {
    this.charge();
  }

  charge(): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.api.etat().subscribe({
      next: (etat) => {
        this.etat.set(etat);
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set('Le reglage n a pas pu etre charge.');
        this.enCours.set(false);
      },
    });
    // Les compteurs disent ce que le pipeline a reellement fait ; ils sont deja calcules
    // par /api/stats, aucun agregat nouveau n'a ete ajoute pour cet ecran.
    this.statsApi.stats().subscribe({
      next: (stats) => this.parSource.set(stats.leadsParSourceDIntention ?? {}),
      error: () => this.parSource.set({}),
    });
  }

  teste(): void {
    this.diagnostic.set(null);
    this.message.set(null);
    this.enCours.set(true);
    this.api.teste(this.cleSaisie() || undefined).subscribe({
      next: (resultat) => {
        this.diagnostic.set(resultat);
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set('Le diagnostic n a pas pu etre lance.');
        this.enCours.set(false);
      },
    });
  }

  enregistre(): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.api.enregistre({ apiKey: this.cleSaisie(), actif: this.etat()?.actif ?? true }).subscribe({
      next: (etat) => {
        this.etat.set(etat);
        // Le champ se vide : garder un secret affiche apres coup n'apporte rien et
        // l'expose a toute personne qui passe derriere l'operateur.
        this.cleSaisie.set('');
        this.cleVisible.set(false);
        this.message.set('Reglage enregistre. Il s applique au prochain lead.');
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set('Le reglage n a pas pu etre enregistre.');
        this.enCours.set(false);
      },
    });
  }

  basculeAnalyse(actif: boolean): void {
    this.enCours.set(true);
    this.erreur.set(null);
    // Sans cle dans le corps : le backend conserve celle en place, que l'operateur ne peut
    // de toute facon pas ressaisir puisqu'il ne la voit pas.
    this.api.enregistre({ actif }).subscribe({
      next: (etat) => {
        this.etat.set(etat);
        this.message.set(
          actif ? 'Analyse par IA activee.' : 'Analyse par IA coupee : mode lexical.',
        );
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set('L interrupteur n a pas pu etre change.');
        this.enCours.set(false);
      },
    });
  }

  basculeVisibilite(): void {
    this.cleVisible.update((visible) => !visible);
  }

  /** Ce que l'operateur doit faire, cause par cause. Un code HTTP ne le lui dirait pas. */
  libelleCause(cause: CauseIntent): string {
    return {
      OK: 'La cle fonctionne',
      CLE_ABSENTE: 'Aucune cle a eprouver : en coller une ci-dessus',
      CLE_REFUSEE: 'Cle refusee : verifier la cle et que l API Gemini est activee sur le projet',
      QUOTA_DEPASSE: 'Quota epuise : la cle est bonne, le service repartira plus tard',
      INJOIGNABLE: 'Serveur injoignable : verifier le reseau ou le proxy sortant',
      ERREUR_SERVEUR: 'Panne chez le fournisseur : reessayer plus tard',
      REPONSE_INATTENDUE: 'Reponse inattendue du modele',
    }[cause];
  }

  libelleSource(): string {
    const etat = this.etat();
    if (!etat) {
      return '';
    }
    return {
      BASE: 'Cle enregistree depuis cet ecran',
      ENV: 'Cle heritee de la variable GEMINI_API_KEY',
      AUCUNE: 'Aucune cle',
    }[etat.source];
  }
}
