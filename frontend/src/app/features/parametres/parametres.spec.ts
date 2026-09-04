import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Parametres } from './parametres';

describe('Parametres', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideNoopAnimations(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  function ecran() {
    const fixture = TestBed.createComponent(Parametres);
    fixture.detectChanges();
    return fixture;
  }

  function repondEtat(actif = true, source = 'BASE', apercu: string | null = 'rete') {
    httpMock.expectOne('/api/admin/intent').flush({
      actif,
      cleDefinie: apercu !== null,
      apercu,
      source,
      modele: 'gemini-2.5-flash',
    });
    httpMock
      .expectOne((r) => r.url === '/api/stats')
      .flush({
        total: 0,
        leadsParStatut: {},
        evenementsParStatut: {},
        leadsParIntention: {},
        leadsParSourceDIntention: { GEMINI: 12, RULES: 3 },
        leadsParCommercial: {},
        nomsDeCommercial: {},
      });
  }

  it('affiche l etat de la cle sans jamais la montrer entiere', () => {
    const fixture = ecran();
    repondEtat();

    fixture.detectChanges();
    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(texte).toContain('rete');
    expect(fixture.componentInstance.etat()?.source).toBe('BASE');
  });

  it('eprouve la cle saisie sans l enregistrer', () => {
    const fixture = ecran();
    repondEtat();
    const composant = fixture.componentInstance;

    composant.cleSaisie.set('cle-a-eprouver');
    composant.teste();

    const requete = httpMock.expectOne('/api/admin/intent/test');
    expect(requete.request.body.apiKey).toBe('cle-a-eprouver');
    requete.flush({ ok: true, cause: 'OK', detail: 'Cle valide', intention: 'DEVIS' });
    // Aucun PUT ne doit suivre : eprouver n'est pas mettre en service.
    httpMock.verify();
    expect(composant.diagnostic()?.ok).toBeTrue();
  });

  it('vide le champ apres enregistrement, pour ne pas laisser un secret a l ecran', () => {
    const fixture = ecran();
    repondEtat();
    const composant = fixture.componentInstance;

    composant.cleSaisie.set('AIzaSyExempleDeCleSecrete');
    composant.enregistre();

    httpMock.expectOne('/api/admin/intent').flush({
      actif: true,
      cleDefinie: true,
      apercu: 'rete',
      source: 'BASE',
      modele: 'gemini-2.5-flash',
    });

    expect(composant.cleSaisie()).toBe('');
  });

  /**
   * L'ecran porte desormais deux sondes tres proches. Celle du relais doit envoyer une
   * adresse : sans destinataire, le serveur ne peut rien eprouver, et un bouton qui rendrait
   * toujours « indiquez une adresse » ferait croire a une panne du relais.
   */
  it('eprouve le relais avec l adresse saisie', () => {
    const fixture = ecran();
    repondEtat();
    fixture.detectChanges();

    fixture.componentInstance.destinataireDEssai.set('operateur@agence.test');
    fixture.componentInstance.testeLEnvoi();

    const requete = httpMock.expectOne('/api/admin/notification/test');
    expect(requete.request.method).toBe('POST');
    expect(requete.request.body.destinataire).toBe('operateur@agence.test');
    requete.flush({ ok: true, cause: 'OK', detail: 'Message d essai accepte par le relais' });
    fixture.detectChanges();

    expect(fixture.componentInstance.diagnosticEnvoi()?.ok).toBeTrue();
  });

  /**
   * La cause est une enumeration precisement pour que l'ecran dise quoi faire. L'afficher
   * brute — « RELAIS_INJOIGNABLE » — reviendrait a ne pas l'avoir traduite.
   */
  it('traduit la cause d un echec de relais au lieu de la montrer brute', () => {
    const fixture = ecran();
    repondEtat();
    fixture.detectChanges();

    fixture.componentInstance.destinataireDEssai.set('operateur@agence.test');
    fixture.componentInstance.testeLEnvoi();
    httpMock
      .expectOne('/api/admin/notification/test')
      .flush({ ok: false, cause: 'RELAIS_INJOIGNABLE', detail: 'Connection refused' });
    fixture.detectChanges();

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).not.toContain('RELAIS_INJOIGNABLE');
    expect(texte).toContain('injoignable');
  });

  it('compte les leads analyses par le modele et par le lexique', () => {
    const fixture = ecran();
    repondEtat();

    expect(fixture.componentInstance.parGemini()).toBe(12);
    expect(fixture.componentInstance.parRegles()).toBe(3);
  });

  afterEach(() => httpMock.verify());
});
