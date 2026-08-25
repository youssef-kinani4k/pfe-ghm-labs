import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import {
  FormBuilder,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { TenantApi } from '../../../core/api/tenant-api';
import { AssignmentStrategy } from '../../../core/models/monitoring';
import {
  ClientCreated,
  CrmCheckCause,
  CrmProviderView,
  CrmTestResult,
} from '../../../core/models/tenant';
import { SecretRevele } from '../secret-revele/secret-revele';

const STRATEGIES: { valeur: AssignmentStrategy; libelle: string }[] = [
  { valeur: 'ROUND_ROBIN', libelle: 'Tour de role' },
  { valeur: 'GEOGRAPHIC', libelle: 'Geographique' },
  { valeur: 'SECTOR', libelle: 'Sectorielle' },
];

/**
 * Creation d'une boutique : identite, ERP, premier commercial.
 *
 * Les trois sections sont sur une seule page et non dans un assistant a etapes : elles
 * tiennent a l'ecran, et un assistant obligerait a revenir en arriere pour corriger une
 * adresse ERP que le test vient de refuser.
 */
@Component({
  selector: 'app-boutique-nouvelle',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatExpansionModule,
    SecretRevele,
  ],
  templateUrl: './boutique-nouvelle.html',
  styleUrl: './boutique-nouvelle.scss',
})
export class BoutiqueNouvelle implements OnInit {
  private readonly api = inject(TenantApi);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  readonly strategies = STRATEGIES;

  readonly fournisseurs = signal<CrmProviderView[]>([]);
  readonly enCours = signal(false);
  readonly message = signal<string | null>(null);
  readonly testEnCours = signal(false);
  readonly resultatDuTest = signal<CrmTestResult | null>(null);
  readonly testeAvecSucces = signal(false);
  readonly creee = signal<ClientCreated | null>(null);

  readonly formulaire = this.fb.group({
    name: ['', Validators.required],
    crmProviderId: ['', Validators.required],
    assignmentStrategy: ['ROUND_ROBIN' as AssignmentStrategy, Validators.required],
    crmSettings: this.fb.group({}),
    firstSalesRep: this.fb.group({
      fullName: ['', Validators.required],
      email: ['', [Validators.required, Validators.email]],
      sector: [''],
      zone: [''],
      crmRef: [''],
    }),
  });

  private readonly providerId = signal('');

  /**
   * La validite du formulaire, portee par un signal.
   *
   * `FormGroup.valid` n'est pas reactif : lu dans un `computed`, il n'y declencherait aucun
   * recalcul, et le bouton resterait dans l'etat de la premiere evaluation.
   */
  private readonly formulaireValide = signal(false);

  constructor() {
    this.formulaire.statusChanges.subscribe(() => this.formulaireValide.set(this.formulaire.valid));
  }

  readonly reglages = computed(
    () => this.fournisseurs().find((f) => f.providerId === this.providerId())?.settings ?? [],
  );

  /**
   * Le bouton reste inactif tant que la connexion ERP n'a pas ete testee avec succes et
   * qu'aucun commercial n'est saisi.
   *
   * Sans commercial actif, le routage leve AssignmentException et les leads de la boutique
   * partent en DLQ des le premier formulaire soumis. Un utilisateur non technique ne doit
   * pas pouvoir fabriquer cet etat.
   */
  readonly peutCreer = computed(() => {
    // Les trois signaux sont lus avant toute condition : un `&&` qui court-circuite
    // n'enregistre pas les suivants comme dependances, et le calcul reste alors fige sur
    // sa premiere valeur — le bouton ne se rallumerait jamais.
    const valide = this.formulaireValide();
    const teste = this.testeAvecSucces();
    const enCours = this.enCours();
    return valide && teste && !enCours;
  });

  get reglagesGroup(): FormGroup {
    return this.formulaire.get('crmSettings') as FormGroup;
  }

  ngOnInit(): void {
    this.api.fournisseurs().subscribe({
      next: (liste) => {
        this.fournisseurs.set(liste);
        // Un seul ERP declare : le choisir d'office evite une selection sans alternative.
        if (liste.length === 1) {
          this.formulaire.patchValue({ crmProviderId: liste[0].providerId });
          this.changeDeFournisseur(liste[0].providerId);
        }
      },
      error: () =>
        this.message.set(
          'La liste des ERP disponibles n a pas pu etre lue : la creation est impossible.',
        ),
    });
  }

  /**
   * Reconstruit les champs ERP. A la creation, tous sont obligatoires — y compris les
   * secrets : il n'y a aucune valeur enregistree dont « vide » pourrait vouloir dire
   * « inchangee ».
   */
  changeDeFournisseur(providerId: string): void {
    this.providerId.set(providerId);
    this.invalideLeTest();
    const groupe = this.reglagesGroup;
    for (const cle of Object.keys(groupe.controls)) {
      groupe.removeControl(cle);
    }
    for (const reglage of this.reglages()) {
      const controle = new FormControl('', Validators.required);
      // Un test reussi ne prouve rien sur des valeurs modifiees depuis.
      controle.valueChanges.subscribe(() => this.invalideLeTest());
      groupe.addControl(reglage.cle, controle);
    }
  }

  private invalideLeTest(): void {
    this.testeAvecSucces.set(false);
    this.resultatDuTest.set(null);
  }

  teste(): void {
    this.testEnCours.set(true);
    this.resultatDuTest.set(null);
    const reglages = this.reglagesGroup.getRawValue() as Record<string, string>;
    this.api.teste(this.providerId(), reglages).subscribe({
      next: (resultat) => {
        this.resultatDuTest.set(resultat);
        this.testeAvecSucces.set(resultat.ok);
        this.testEnCours.set(false);
      },
      error: () => {
        this.resultatDuTest.set({
          ok: false,
          cause: 'REPONSE_INATTENDUE',
          detail: 'Le dashboard n a pas pu joindre son propre serveur.',
        });
        this.testeAvecSucces.set(false);
        this.testEnCours.set(false);
      },
    });
  }

  private readonly phrases: Record<CrmCheckCause, string> = {
    JOIGNABLE: 'Connexion etablie.',
    INJOIGNABLE: 'Aucun serveur ne repond a cette adresse.',
    IDENTIFIANTS_REFUSES: 'Le serveur repond mais refuse la cle ou le compte.',
    CIBLE_INCONNUE: 'Le serveur repond mais ne connait pas cette base ou cette adresse.',
    REPONSE_INATTENDUE: 'Reponse illisible : cette adresse pointe probablement ailleurs.',
  };

  phrase(cause: CrmCheckCause): string {
    return this.phrases[cause] ?? cause;
  }

  cree(): void {
    if (!this.peutCreer()) {
      this.formulaire.markAllAsTouched();
      return;
    }
    this.enCours.set(true);
    this.message.set(null);
    const valeurs = this.formulaire.getRawValue();
    const commercial = valeurs.firstSalesRep;
    this.api
      .cree({
        name: valeurs.name!,
        crmProviderId: valeurs.crmProviderId!,
        assignmentStrategy: valeurs.assignmentStrategy!,
        crmSettings: this.reglagesGroup.getRawValue() as Record<string, string>,
        firstSalesRep: {
          fullName: commercial.fullName!,
          email: commercial.email!,
          sector: commercial.sector || null,
          zone: commercial.zone || null,
          crmRef: commercial.crmRef || null,
        },
      })
      .subscribe({
        next: (rendu) => {
          // La boutique existe : l'ecran ne montre plus que le secret, qu'on ne reverra pas.
          this.creee.set(rendu);
          this.enCours.set(false);
        },
        error: (echec: { status?: number }) => {
          this.message.set(
            echec?.status === 400
              ? 'La creation a ete refusee : un reglage est absent ou mal forme.'
              : 'La creation n a pas abouti.',
          );
          this.enCours.set(false);
        },
      });
  }

  /** L'accuse de reception mene a la fiche : le secret n'est plus nulle part. */
  termine(): void {
    const rendu = this.creee();
    this.router.navigate(rendu ? ['/boutiques', rendu.id] : ['/boutiques']);
  }
}
