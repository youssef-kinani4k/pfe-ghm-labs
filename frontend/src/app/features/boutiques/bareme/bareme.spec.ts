import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { Bareme } from './bareme';
import { ScoringView } from '../../../core/models/tenant';

/**
 * Ce que ces tests protegent n'est pas la mise en page mais deux promesses faites a
 * l'operateur : le maximum affiche est celui que le scoreur atteindra vraiment, et un seuil
 * hors de portee se voit avant d'etre enregistre. Un barème regle a l'aveugle produit des
 * leads qui ne sont jamais chauds, et personne ne s'en apercoit avant des semaines.
 */
describe('Bareme', () => {
  let httpMock: HttpTestingController;

  const VALEURS = {
    telephonePresent: 15,
    societePresente: 10,
    nomPresent: 5,
    messagePresent: 10,
    intention: { DEVIS: 40, ACHAT: 40, INFORMATION: 15, SUPPORT: 5, AUTRE: 0 },
    secteursCibles: [],
    paysCibles: [],
    bonusCible: 10,
    seuilChaud: 70,
  };

  const VUE: ScoringView = {
    valeurs: VALEURS,
    scoreMaximum: 90,
    seuilInatteignable: false,
    defauts: VALEURS,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideNoopAnimations(),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'boutique-1' }) } },
        },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function ecran(vue: ScoringView = VUE) {
    const fixture = TestBed.createComponent(Bareme);
    fixture.detectChanges();
    httpMock.expectOne('/api/admin/clients/boutique-1/scoring').flush(vue);
    fixture.detectChanges();
    return fixture;
  }

  it('affiche les valeurs effectives rendues par le serveur', () => {
    const fixture = ecran();

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('90');
    expect(fixture.componentInstance.formulaire.controls.telephonePresent.value).toBe(15);
  });

  it('avertit quand le seuil depasse le maximum atteignable', () => {
    const fixture = ecran();

    fixture.componentInstance.formulaire.controls.seuilChaud.setValue(95);
    fixture.detectChanges();

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('Aucun lead ne pourra etre chaud');
    expect(fixture.componentInstance.seuilInatteignable()).toBeTrue();
  });

  /**
   * Le maximum bouge a la frappe, sans aller-retour : un apercu qui demanderait le serveur a
   * chaque touche serait inutilisable, et le calcul est une addition, pas le barème.
   */
  it('recalcule le maximum a la frappe, sans appel reseau', () => {
    const fixture = ecran();
    const composant = fixture.componentInstance;

    expect(composant.scoreMaximum()).toBe(90);

    composant.formulaire.controls.bonusCible.setValue(0);
    fixture.detectChanges();

    expect(composant.scoreMaximum()).toBe(80);
  });

  /** Le maximum est borne a 100 comme le score lui-meme, sinon l'apercu mentirait. */
  it('borne le maximum a cent', () => {
    const fixture = ecran();
    const composant = fixture.componentInstance;

    composant.formulaire.controls.telephonePresent.setValue(100);
    fixture.detectChanges();

    expect(composant.scoreMaximum()).toBe(100);
  });

  it('enregistre le barème et reprend les valeurs normalisees du serveur', () => {
    const fixture = ecran();
    const composant = fixture.componentInstance;

    composant.secteurs.set(['Industrie']);
    composant.pays.set(['fr']);
    composant.enregistre();

    const requete = httpMock.expectOne('/api/admin/clients/boutique-1/scoring');
    expect(requete.request.method).toBe('PUT');
    expect(requete.request.body.secteursCibles).toEqual(['Industrie']);
    expect(requete.request.body.intention.DEVIS).toBe(40);

    // Le serveur normalise : c'est sa reponse qui fait foi, pas ce qui a ete tape.
    requete.flush({
      ...VUE,
      valeurs: { ...VALEURS, secteursCibles: ['industrie'], paysCibles: ['FR'] },
    });
    fixture.detectChanges();

    expect(composant.secteurs()).toEqual(['industrie']);
    expect(composant.pays()).toEqual(['FR']);
  });

  it('revient aux defauts sans rien demander au serveur', () => {
    const fixture = ecran({
      ...VUE,
      valeurs: { ...VALEURS, telephonePresent: 3, seuilChaud: 20 },
    });
    const composant = fixture.componentInstance;

    expect(composant.formulaire.controls.telephonePresent.value).toBe(3);

    composant.revientAuxDefauts();
    fixture.detectChanges();

    expect(composant.formulaire.controls.telephonePresent.value).toBe(15);
    expect(composant.formulaire.controls.seuilChaud.value).toBe(70);
    // Rien n'est enregistre tant que l'operateur n'a pas valide : afterEach le verifie.
  });
});
