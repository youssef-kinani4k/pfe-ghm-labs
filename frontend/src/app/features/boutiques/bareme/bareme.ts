import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule, MatChipInputEvent } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TenantApi } from '../../../core/api/tenant-api';
import { ScoringForm, ScoringView } from '../../../core/models/tenant';

/** Le jeu d'intentions est ferme cote serveur : le libelle vit ici, la cle vient de la. */
const INTENTIONS: { cle: string; libelle: string }[] = [
  { cle: 'DEVIS', libelle: 'Demande de devis' },
  { cle: 'ACHAT', libelle: "Intention d'achat" },
  { cle: 'INFORMATION', libelle: 'Demande de renseignement' },
  { cle: 'SUPPORT', libelle: 'Support ou reclamation' },
  { cle: 'AUTRE', libelle: 'Autre' },
];

const PLANCHER = 0;
const PLAFOND = 100;

/**
 * Reglage du bareme d'une boutique.
 *
 * Les quatre blocs suivent l'ordre dans lequel le score se construit — presence, intention,
 * ciblage, seuil — et non un ordre esthetique : l'ecran se lit comme le calcul, ce qui evite
 * d'avoir a expliquer ailleurs comment le total se forme.
 *
 * Le maximum atteignable se recalcule **a la frappe**, sans aller-retour. C'est une addition
 * bornee a 100, pas une reimplementation du bareme : ni normalisation, ni logique de
 * ciblage, rien qui puisse diverger de `LeadScorer` autrement qu'en additionnant mal. La
 * valeur rendue par le serveur reste celle qui fait foi au chargement et apres
 * enregistrement.
 */
@Component({
  selector: 'app-bareme',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatChipsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
  ],
  templateUrl: './bareme.html',
  styleUrl: './bareme.scss',
})
export class Bareme implements OnInit {
  private readonly api = inject(TenantApi);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);

  readonly intentions = INTENTIONS;

  readonly vue = signal<ScoringView | null>(null);
  readonly enCours = signal(true);
  readonly erreur = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  readonly enregistrement = signal(false);

  /**
   * Les listes cibles vivent dans des signals et non dans le formulaire : ce sont des
   * ensembles, pas des champs de saisie, et un `FormArray` de chaines n'apporterait ici
   * qu'une syntaxe de plus.
   */
  readonly secteurs = signal<string[]>([]);
  readonly pays = signal<string[]>([]);

  private boutiqueId = '';

  readonly formulaire = this.fb.nonNullable.group({
    telephonePresent: [0, [Validators.required, Validators.min(0), Validators.max(100)]],
    societePresente: [0, [Validators.required, Validators.min(0), Validators.max(100)]],
    nomPresent: [0, [Validators.required, Validators.min(0), Validators.max(100)]],
    messagePresent: [0, [Validators.required, Validators.min(0), Validators.max(100)]],
    bonusCible: [0, [Validators.required, Validators.min(0), Validators.max(100)]],
    seuilChaud: [0, [Validators.required, Validators.min(0), Validators.max(100)]],
    seuilNotification: [0, [Validators.required, Validators.min(0), Validators.max(100)]],
    intention: this.fb.nonNullable.group(
      Object.fromEntries(
        INTENTIONS.map((intention) => [
          intention.cle,
          [0, [Validators.required, Validators.min(0), Validators.max(100)]],
        ]),
      ),
    ),
  });

  /**
   * Les valeurs du formulaire en signal : un `computed` ne voit pas les changements d'un
   * `FormGroup`, qui n'est pas reactif au sens des signals. `toSignal` sur `valueChanges`
   * est le pont, et l'application est zoneless — sans lui, le maximum ne bougerait pas.
   */
  private readonly valeurs = toSignal(this.formulaire.valueChanges, {
    initialValue: this.formulaire.getRawValue(),
  });

  /** Quatre poids de presence, plus le meilleur poids d'intention, plus le bonus. */
  readonly scoreMaximum = computed(() => {
    this.valeurs();
    const brut = this.formulaire.getRawValue();
    const meilleureIntention = Math.max(
      0,
      ...INTENTIONS.map((intention) => this.nombre(brut.intention[intention.cle])),
    );
    const total =
      this.nombre(brut.telephonePresent) +
      this.nombre(brut.societePresente) +
      this.nombre(brut.nomPresent) +
      this.nombre(brut.messagePresent) +
      meilleureIntention +
      this.nombre(brut.bonusCible);
    return Math.max(PLANCHER, Math.min(PLAFOND, total));
  });

  readonly seuilInatteignable = computed(() => {
    this.valeurs();
    return this.nombre(this.formulaire.getRawValue().seuilChaud) > this.scoreMaximum();
  });

  /**
   * Le pendant du precedent, et le plus important des deux : un badge qui ne s'allume jamais
   * finit par se remarquer, un commercial qui n'est jamais prevenu ne remarque rien.
   */
  readonly notificationInatteignable = computed(() => {
    this.valeurs();
    return this.nombre(this.formulaire.getRawValue().seuilNotification) > this.scoreMaximum();
  });

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.erreur.set('Identifiant de boutique absent.');
      this.enCours.set(false);
      return;
    }
    this.boutiqueId = id;
    this.charge();
  }

  private charge(): void {
    this.enCours.set(true);
    this.api.bareme(this.boutiqueId).subscribe({
      next: (vue) => {
        this.applique(vue);
        this.enCours.set(false);
      },
      error: (echec: { status?: number }) => {
        this.erreur.set(
          echec?.status === 404
            ? 'Cette boutique n existe pas.'
            : 'Le bareme n a pas pu etre charge.',
        );
        this.enCours.set(false);
      },
    });
  }

  private applique(vue: ScoringView): void {
    this.vue.set(vue);
    this.remplit(vue.valeurs);
  }

  private remplit(valeurs: ScoringForm): void {
    this.formulaire.patchValue({
      telephonePresent: valeurs.telephonePresent,
      societePresente: valeurs.societePresente,
      nomPresent: valeurs.nomPresent,
      messagePresent: valeurs.messagePresent,
      bonusCible: valeurs.bonusCible,
      seuilChaud: valeurs.seuilChaud,
      seuilNotification: valeurs.seuilNotification,
      intention: Object.fromEntries(
        INTENTIONS.map((intention) => [intention.cle, valeurs.intention[intention.cle] ?? 0]),
      ),
    });
    this.secteurs.set([...valeurs.secteursCibles]);
    this.pays.set([...valeurs.paysCibles]);
  }

  enregistre(): void {
    if (this.formulaire.invalid) {
      this.formulaire.markAllAsTouched();
      this.message.set('Un poids est hors des bornes : chaque valeur va de 0 a 100.');
      return;
    }
    this.enregistrement.set(true);
    this.message.set(null);
    const brut = this.formulaire.getRawValue();
    const formulaire: ScoringForm = {
      telephonePresent: this.nombre(brut.telephonePresent),
      societePresente: this.nombre(brut.societePresente),
      nomPresent: this.nombre(brut.nomPresent),
      messagePresent: this.nombre(brut.messagePresent),
      intention: Object.fromEntries(
        INTENTIONS.map((intention) => [intention.cle, this.nombre(brut.intention[intention.cle])]),
      ),
      secteursCibles: this.secteurs(),
      paysCibles: this.pays(),
      bonusCible: this.nombre(brut.bonusCible),
      seuilChaud: this.nombre(brut.seuilChaud),
      // Le PUT remplace le document entier : oublier ce champ ferait retomber la boutique
      // sur le defaut a chaque enregistrement du bareme.
      seuilNotification: this.nombre(brut.seuilNotification),
    };
    this.api.enregistreBareme(this.boutiqueId, formulaire).subscribe({
      next: (vue) => {
        // La reponse fait foi : le serveur normalise les listes cibles, et reafficher ce
        // qui a ete tape laisserait croire que « Industrie » a ete enregistre tel quel.
        this.applique(vue);
        this.message.set('Bareme enregistre. Il s applique au prochain lead.');
        this.enregistrement.set(false);
      },
      error: (echec: { status?: number }) => {
        this.message.set(
          echec?.status === 400
            ? 'Refuse : un poids est hors des bornes, ou un code pays n en est pas un.'
            : 'Le bareme n a pas pu etre enregistre.',
        );
        this.enregistrement.set(false);
      },
    });
  }

  /** Remplit le formulaire sans rien enregistrer : la validation reste un geste explicite. */
  revientAuxDefauts(): void {
    const vue = this.vue();
    if (!vue) {
      return;
    }
    this.remplit(vue.defauts);
    this.message.set(
      'Valeurs par defaut chargees. Rien n est enregistre tant que vous n avez pas valide.',
    );
  }

  ajouteUnSecteur(evenement: MatChipInputEvent): void {
    this.ajoute(this.secteurs, evenement, (valeur) => valeur.toLowerCase());
  }

  ajouteUnPays(evenement: MatChipInputEvent): void {
    this.ajoute(this.pays, evenement, (valeur) => valeur.toUpperCase());
  }

  retireUnSecteur(valeur: string): void {
    this.secteurs.update((liste) => liste.filter((element) => element !== valeur));
  }

  retireUnPays(valeur: string): void {
    this.pays.update((liste) => liste.filter((element) => element !== valeur));
  }

  /**
   * La normalisation est refaite ici pour que l'etiquette apparaisse deja sous la forme que
   * le serveur retiendra — sinon l'operateur voit « Industrie » se transformer en
   * « industrie » a l'enregistrement et se demande ce qui a ete pris en compte. Le serveur
   * normalise de toute facon : ceci ne fait qu'eviter la surprise.
   */
  private ajoute(
    liste: ReturnType<typeof signal<string[]>>,
    evenement: MatChipInputEvent,
    normalise: (valeur: string) => string,
  ): void {
    const valeur = normalise(evenement.value.trim());
    if (valeur && !liste().includes(valeur)) {
      liste.update((actuelle) => [...actuelle, valeur]);
    }
    evenement.chipInput?.clear();
  }

  private nombre(valeur: unknown): number {
    const converti = Number(valeur);
    return Number.isFinite(converti) ? converti : 0;
  }
}
