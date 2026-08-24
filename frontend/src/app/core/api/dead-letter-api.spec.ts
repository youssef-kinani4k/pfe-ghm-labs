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

  it('rejoue une ligne par un POST unitaire', () => {
    api.rejoue('abc').subscribe();

    // Pas d'endpoint de rejeu en masse : un rejeu de masse sur un incident non compris
    // multiplie l'incident. L'ecran boucle sur une selection explicite.
    const requete = httpMock.expectOne('/api/dead-letters/abc/replay');
    expect(requete.request.method).toBe('POST');
    requete.flush(null);
  });

  it('ecarte une ligne', () => {
    api.ecarte('abc').subscribe();

    const requete = httpMock.expectOne('/api/dead-letters/abc/discard');
    expect(requete.request.method).toBe('POST');
    requete.flush(null);
  });
});
