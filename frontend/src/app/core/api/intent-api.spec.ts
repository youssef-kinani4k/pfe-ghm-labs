import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { IntentApi } from './intent-api';

describe('IntentApi', () => {
  let api: IntentApi;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(IntentApi);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lit l etat sur un chemin relatif', () => {
    api.etat().subscribe();

    const requete = httpMock.expectOne('/api/admin/intent');
    expect(requete.request.method).toBe('GET');
    requete.flush({
      actif: true,
      cleDefinie: false,
      apercu: null,
      source: 'AUCUNE',
      modele: 'gemini-2.5-flash',
    });
  });

  it("n'envoie pas de cle vide, pour ne pas effacer celle en place", () => {
    api.enregistre({ apiKey: '', actif: false }).subscribe();

    const requete = httpMock.expectOne('/api/admin/intent');
    expect(requete.request.method).toBe('PUT');
    // Le backend conserve la cle quand le champ est absent : l'envoyer vide serait le seul
    // moyen de la perdre par inadvertance en actionnant l'interrupteur.
    expect(requete.request.body.apiKey).toBeUndefined();
    expect(requete.request.body.actif).toBeFalse();
    requete.flush({
      actif: false,
      cleDefinie: true,
      apercu: 'rete',
      source: 'BASE',
      modele: 'gemini-2.5-flash',
    });
  });

  it('eprouve une cle sans l enregistrer', () => {
    api.teste('cle-a-eprouver').subscribe();

    const requete = httpMock.expectOne('/api/admin/intent/test');
    expect(requete.request.method).toBe('POST');
    expect(requete.request.body.apiKey).toBe('cle-a-eprouver');
    requete.flush({ ok: true, cause: 'OK', detail: 'Cle valide', intention: 'DEVIS' });
  });
});
