import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DeadLetterApi } from './dead-letter-api';

describe('DeadLetterApi', () => {
  let api: DeadLetterApi;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(DeadLetterApi);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('liste avec le filtre de statut', () => {
    api.liste({ page: 0, size: 25, status: 'PENDING' }).subscribe();

    const requete = httpMock.expectOne((r) => r.url === '/api/dead-letters');
    expect(requete.request.params.get('status')).toBe('PENDING');
    requete.flush({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
  });

  it("n'envoie pas les filtres absents", () => {
    api.liste({ page: 0, size: 25, status: undefined, originQueue: '' }).subscribe();

    const requete = httpMock.expectOne((r) => r.url === '/api/dead-letters');
    expect(requete.request.params.has('status')).toBeFalse();
    expect(requete.request.params.has('originQueue')).toBeFalse();
    requete.flush({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
  });

  it('rejoue une ligne par un POST unitaire, motif compris', () => {
    api.rejoue('abc', 'Broker revenu').subscribe();

    // Pas d'endpoint de rejeu en masse : un rejeu de masse sur un incident non compris
    // multiplie l'incident. L'ecran boucle sur une selection explicite.
    const requete = httpMock.expectOne('/api/dead-letters/abc/replay');
    expect(requete.request.method).toBe('POST');
    // Le corps, et pas seulement le code retour : le serveur refuse un motif absent en
    // 400 depuis F10, et un corps vide passerait ce test s'il n'assertait que la methode.
    expect(requete.request.body).toEqual({ reason: 'Broker revenu' });
    requete.flush(null);
  });

  it('ecarte une ligne, motif compris', () => {
    api.ecarte('abc', 'Doublon').subscribe();

    const requete = httpMock.expectOne('/api/dead-letters/abc/discard');
    expect(requete.request.method).toBe('POST');
    expect(requete.request.body).toEqual({ reason: 'Doublon' });
    requete.flush(null);
  });
});
