import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
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
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TenantApi } from '../../../core/api/tenant-api';
import { AssignmentStrategy } from '../../../core/models/monitoring';
import {
  ClientDetailAdmin,
  CrmCheckCause,
  CrmProviderView,
  CrmTestResult,
  SalesRepAdminView,
} from '../../../core/models/tenant';
import { SecretRevele } from '../secret-revele/secret-revele';

const STRATEGIES: { valeur: AssignmentStrategy; libelle: string }[] = [
  { valeur: 'ROUND_ROBIN', libelle: 'Tour de role' },
  { valeur: 'GEOGRAPHIC', libelle: 'Geographique' },
  { valeur: 'SECTOR', libelle: 'Sectorielle' },
];

/**
 * Fiche d'une boutique, sur une route et non dans une modale — meme raison que le detail
 * d'un lead : l'URL se colle dans un ticket.
 *
 * Quatre blocs dans l'ordre d'usage : Identite, ERP, Commerciaux, Integration.
 *
 * Le bloc ERP se genere a partir de ce que le backend declare dans
 * {@code GET /api/admin/crm/providers} : un champ par reglage attendu, masque quand le
 * reglage est secret. Aucune liste de champs ecrite ici — c'est ce qui fait qu'ajouter un
 * ERP reste trois gestes cote backend et ne touche pas cet ecran.
 */
@Component({
  selector: 'app-boutique-detail',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatProgressBarModule,
    MatExpansionModule,
    MatTooltipModule,
    SecretRevele,
  ],
  templateUrl: './boutique-detail.html',
  styleUrl: './boutique-detail.scss',
})
export class BoutiqueDetail implements OnInit {
  private readonly api = inject(TenantApi);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);

  readonly strategies = STRATEGIES;
  readonly colonnes = ['fullName', 'email', 'sector', 'zone', 'crmRef', 'active', 'actions'];

  readonly boutique = signal<ClientDetailAdmin | null>(null);
  readonly fournisseurs = signal<CrmProviderView[]>([]);
  readonly enCours = signal(true);
  readonly erreur = signal<string | null>(null);
  readonly message = signal<string | null>(null);

  readonly enregistrement = signal(false);
  readonly testEnCours = signal(false);
  readonly resultatDuTest = signal<CrmTestResult | null>(null);

  /** Rendu une seule fois apres rotation : le backend ne sait plus le redonner ensuite. */
  readonly secretRevele = signal<string | null>(null);

  readonly commercialEnEdition = signal<SalesRepAdminView | null>(null);

  readonly formulaire = this.fb.group({
    name: ['', Validators.required],
    crmProviderId: ['', Validators.required],
    assignmentStrategy: ['ROUND_ROBIN' as AssignmentStrategy, Validators.required],
    crmSettings: this.fb.group({}),
  });

  readonly formulaireCommercial = this.fb.group({
    fullName: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    sector: [''],
    zone: [''],
    crmRef: [''],
  });

  private readonly providerId = signal('');

  /** Incremente a chaque frappe dans le bloc ERP : voir `testeAveugle`. */
  private readonly saisie = signal(0);

  /** Les champs du fournisseur choisi, tels que le backend les declare. */
  readonly reglages = computed(
    () => this.fournisseurs().find((f) => f.providerId === this.providerId())?.settings ?? [],
  );

  /**
   * La sonde n'a pas acces au secret enregistre : elle ne lit pas la base, par construction.
   * Un champ secret laisse vide se teste donc a vide, et le dire evite un « IDENTIFIANTS
   * REFUSES » incomprehensible.
   */
  readonly testeAveugle = computed(() => {
    // `saisie` n'est pas utilise : il rend le calcul dependant de la frappe, que les
    // controles de formulaire ne signalent pas d'eux-memes a un `computed`.
    this.saisie();
    return this.reglages().some(
      (reglage) => reglage.secret && !this.reglagesGroup.get(reglage.cle)?.value,
    );
  });

  get reglagesGroup(): FormGroup {
    return this.formulaire.get('crmSettings') as FormGroup;
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.erreur.set('Identifiant de boutique absent.');
      this.enCours.set(false);
      return;
    }
    this.api.fournisseurs().subscribe({
      next: (liste) => {
        this.fournisseurs.set(liste);
        this.charge(id);
      },
      // La fiche reste lisible sans la liste des fournisseurs : seul le bloc ERP en depend.
      error: () => this.charge(id),
    });
  }

  charge(id: string): void {
    this.enCours.set(true);
    this.api.boutique(id).subscribe({
      next: (fiche) => {
        this.applique(fiche);
        this.enCours.set(false);
      },
      error: (echec: { status?: number }) => {
        this.erreur.set(
          echec?.status === 404
            ? 'Cette boutique n existe pas.'
            : 'La fiche n a pas pu etre chargee.',
        );
        this.enCours.set(false);
      },
    });
  }

  private applique(fiche: ClientDetailAdmin): void {
    this.boutique.set(fiche);
    this.formulaire.patchValue({
      name: fiche.name,
      crmProviderId: fiche.crmProviderId,
      assignmentStrategy: fiche.assignmentStrategy,
    });
    this.changeDeFournisseur(fiche.crmProviderId, fiche.crmSettings);
  }

  /**
   * Reconstruit les controles du bloc ERP.
   *
   * Les anciens sont retires et non simplement caches : un champ reste d'un fournisseur
   * precedent partirait dans `crmSettings` et serait enregistre tel quel.
   */
  changeDeFournisseur(providerId: string, valeurs: Record<string, string> = {}): void {
    this.providerId.set(providerId);
    this.resultatDuTest.set(null);
    const groupe = this.reglagesGroup;
    for (const cle of Object.keys(groupe.controls)) {
      groupe.removeControl(cle);
    }
    for (const reglage of this.reglages()) {
      // Un reglage secret n'est jamais rendu par l'API : le champ part vide, et vide veut
      // dire « inchange » cote backend.
      const valeur = reglage.secret ? '' : (valeurs[reglage.cle] ?? '');
      const controle = new FormControl(valeur, reglage.secret ? [] : [Validators.required]);
      controle.valueChanges.subscribe(() => this.saisie.update((tour) => tour + 1));
      groupe.addControl(reglage.cle, controle);
    }
  }

  enregistre(): void {
    const fiche = this.boutique();
    if (!fiche || this.formulaire.invalid) {
      this.formulaire.markAllAsTouched();
      return;
    }
    this.enregistrement.set(true);
    this.message.set(null);
    const valeurs = this.formulaire.getRawValue();
    this.api
      .metAJour(fiche.id, {
        name: valeurs.name!,
        crmProviderId: valeurs.crmProviderId!,
        assignmentStrategy: valeurs.assignmentStrategy!,
        crmSettings: this.reglagesGroup.getRawValue() as Record<string, string>,
      })
      .subscribe({
        next: (mise) => {
          this.applique(mise);
          this.message.set('Boutique enregistree.');
          this.enregistrement.set(false);
        },
        error: (echec: { status?: number }) => {
          this.message.set(this.explique(echec?.status));
          this.enregistrement.set(false);
        },
      });
  }

  teste(): void {
    this.testEnCours.set(true);
    this.resultatDuTest.set(null);
    this.api
      .teste(this.providerId(), this.reglagesGroup.getRawValue() as Record<string, string>)
      .subscribe({
        next: (resultat) => {
          this.resultatDuTest.set(resultat);
          this.testEnCours.set(false);
        },
        error: () => {
          this.resultatDuTest.set({
            ok: false,
            cause: 'REPONSE_INATTENDUE',
            detail: 'Le dashboard n a pas pu joindre son propre serveur.',
          });
          this.testEnCours.set(false);
        },
      });
  }

  /**
   * Le backend rend un nom d'enumeration ; la phrase vit ici pour qu'un libelle se corrige
   * sans redeployer le backend.
   */
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

  // Les confirmations annoncent la consequence plutot que de demander « etes-vous sur ? » :
  // c'est l'effet, et non la solennite de la question, qui permet de decider.
  basculeLActivation(): void {
    const fiche = this.boutique();
    if (!fiche) {
      return;
    }
    if (
      fiche.active &&
      !confirm(
        'Desactiver cette boutique ?\n\n' +
          'Le formulaire de cette boutique recevra une erreur d authentification ' +
          'immediatement. Les leads deja captes ne sont pas touches.',
      )
    ) {
      return;
    }
    const appel = fiche.active ? this.api.desactive(fiche.id) : this.api.active(fiche.id);
    appel.subscribe({
      next: (mise) => {
        this.applique(mise);
        this.message.set(mise.active ? 'Boutique activee.' : 'Boutique desactivee.');
      },
      error: (echec: { status?: number }) => this.message.set(this.explique(echec?.status)),
    });
  }

  regenereLeSecret(): void {
    const fiche = this.boutique();
    if (
      !fiche ||
      !confirm(
        'Regenerer le secret HMAC ?\n\n' +
          'Son formulaire cessera de fonctionner tant qu elle n aura pas mis a jour son ' +
          'secret. Le secret actuel sera definitivement perdu.',
      )
    ) {
      return;
    }
    this.api.tourneLeSecret(fiche.id).subscribe({
      next: (rendu) => {
        this.secretRevele.set(rendu.hmacSecret);
        this.message.set('Secret regenere. Il n est affiche qu une fois.');
      },
      error: (echec: { status?: number }) => this.message.set(this.explique(echec?.status)),
    });
  }

  regenereLaClePublique(): void {
    const fiche = this.boutique();
    if (
      !fiche ||
      !confirm(
        'Regenerer la cle publique ?\n\n' +
          'L URL de son webhook change. L ancienne ne sera plus reconnue.',
      )
    ) {
      return;
    }
    this.api.tourneLaClePublique(fiche.id).subscribe({
      next: (mise) => {
        this.applique(mise);
        this.message.set('Cle publique regeneree. La nouvelle URL est ci-dessous.');
      },
      error: (echec: { status?: number }) => this.message.set(this.explique(echec?.status)),
    });
  }

  copie(texte: string): void {
    navigator.clipboard?.writeText(texte).then(
      () => this.message.set('Copie dans le presse-papier.'),
      () => this.message.set('La copie a echoue : selectionner le texte a la main.'),
    );
  }

  edite(commercial: SalesRepAdminView): void {
    this.commercialEnEdition.set(commercial);
    this.formulaireCommercial.setValue({
      fullName: commercial.fullName,
      email: commercial.email,
      sector: commercial.sector ?? '',
      zone: commercial.zone ?? '',
      crmRef: commercial.crmRef ?? '',
    });
  }

  annuleLEdition(): void {
    this.commercialEnEdition.set(null);
    this.formulaireCommercial.reset({ fullName: '', email: '', sector: '', zone: '', crmRef: '' });
  }

  soumetLeCommercial(): void {
    const fiche = this.boutique();
    if (!fiche || this.formulaireCommercial.invalid) {
      this.formulaireCommercial.markAllAsTouched();
      return;
    }
    const valeurs = this.formulaireCommercial.getRawValue();
    const formulaire = {
      fullName: valeurs.fullName!,
      email: valeurs.email!,
      sector: valeurs.sector || null,
      zone: valeurs.zone || null,
      crmRef: valeurs.crmRef || null,
    };
    const enEdition = this.commercialEnEdition();
    const appel = enEdition
      ? this.api.metAJourUnCommercial(enEdition.id, formulaire)
      : this.api.ajouteUnCommercial(fiche.id, formulaire);
    appel.subscribe({
      next: () => {
        this.message.set(enEdition ? 'Commercial modifie.' : 'Commercial ajoute.');
        this.annuleLEdition();
        this.charge(fiche.id);
      },
      error: (echec: { status?: number }) => this.message.set(this.explique(echec?.status)),
    });
  }

  basculeLeCommercial(commercial: SalesRepAdminView): void {
    const fiche = this.boutique();
    if (!fiche) {
      return;
    }
    // Le backend refuse de desactiver le dernier commercial actif : sans lui, le routage
    // n'aurait personne a qui attribuer et les leads finiraient en DLQ.
    const appel = commercial.active
      ? this.api.desactiveUnCommercial(commercial.id)
      : this.api.activeUnCommercial(commercial.id);
    appel.subscribe({
      next: () => this.charge(fiche.id),
      error: (echec: { status?: number }) => this.message.set(this.explique(echec?.status)),
    });
  }

  private explique(code?: number): string {
    if (code === 400) {
      return 'Le formulaire a ete refuse : un reglage est absent ou mal forme.';
    }
    if (code === 409) {
      return 'Refuse : cette adresse est deja prise, ou c est le dernier commercial actif.';
    }
    if (code === 404) {
      return 'Cette ligne n existe plus.';
    }
    return 'Le geste n a pas abouti.';
  }
}
