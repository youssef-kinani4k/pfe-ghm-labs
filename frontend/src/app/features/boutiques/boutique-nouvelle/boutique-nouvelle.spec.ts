import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { BoutiqueNouvelle } from './boutique-nouvelle';

/**
 * Ce que ces tests protegent n'est pas le formulaire mais une garantie du pipeline : une
 * boutique creee sans commercial actif, ou avec des reglages ERP faux, envoie ses premiers
 * leads en file des messages morts. Le bouton est le dernier endroit ou cela se rattrape.
 */
describe('BoutiqueNouvelle', () => {
  let httpMock: HttpTestingController;

  const FOURNISSEURS = [
    {
      providerId: 'dolibarr',
      settings: [
        { cle: 'baseUrl', libelle: 'Adresse', secret: false },
        { cle: 'apiKey', libelle: 'Cle', secret: true },
      ],
    },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideNoopAnimations(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function composantPret(): BoutiqueNouvelle {
    const fixture = TestBed.createComponent(BoutiqueNouvelle);
    fixture.detectChanges();
    httpMock.expectOne('/api/admin/crm/providers').flush(FOURNISSEURS);
    return fixture.componentInstance;
  }

  function remplit(composant: BoutiqueNouvelle): void {
    composant.formulaire.patchValue({
      name: 'Boutique du Nord',
      crmProviderId: 'dolibarr',
      assignmentStrategy: 'ROUND_ROBIN',
      firstSalesRep: { fullName: 'Amine Idrissi', email: 'amine@test.fr' },
    });
    composant.reglagesGroup.patchValue({ baseUrl: 'http://erp.test', apiKey: 'cle' });
  }

  function testeAvecSucces(composant: BoutiqueNouvelle): void {
    composant.teste();
    httpMock.expectOne('/api/admin/crm/test').flush({ ok: true, cause: 'JOIGNABLE', detail: null });
  }

  it('garde le bouton inactif tant que la connexion ERP n a pas ete testee', () => {
    const composant = composantPret();
    remplit(composant);

    expect(composant.formulaire.valid).toBeTrue();
    expect(composant.peutCreer()).toBeFalse();
  });

  it('active le bouton une fois la connexion testee avec succes', () => {
    const composant = composantPret();
    remplit(composant);
    testeAvecSucces(composant);

    expect(composant.peutCreer()).toBeTrue();
  });

  it('reactive le test quand un champ ERP change', () => {
    const composant = composantPret();
    remplit(composant);
    testeAvecSucces(composant);

    // Un test reussi sur d'anciennes valeurs ne prouve rien sur les nouvelles.
    composant.reglagesGroup.get('baseUrl')!.setValue('http://autre.test');

    expect(composant.peutCreer()).toBeFalse();
  });

  it('refuse de creer sans premier commercial', () => {
    const composant = composantPret();
    remplit(composant);
    testeAvecSucces(composant);

    // Sans commercial actif, le routage leve AssignmentException des le premier lead.
    composant.formulaire.controls.firstSalesRep.controls.email.setValue('');

    expect(composant.peutCreer()).toBeFalse();
  });

  it('garde le resultat du test en echec sans activer la creation', () => {
    const composant = composantPret();
    remplit(composant);
    composant.teste();
    httpMock
      .expectOne('/api/admin/crm/test')
      .flush({ ok: false, cause: 'INJOIGNABLE', detail: 'Connection refused' });

    expect(composant.resultatDuTest()?.cause).toBe('INJOIGNABLE');
    expect(composant.peutCreer()).toBeFalse();
  });
});
