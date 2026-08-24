import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LeadApi } from './lead-api';

describe('LeadApi', () => {
  let api: LeadApi;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(LeadApi);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('appelle un chemin relatif et transmet pagination et tri', () => {
    api.liste({ page: 2, size: 25, sort: 'createdAt,desc' }).subscribe();

    const requete = httpMock.expectOne((r) => r.url === '/api/leads');
    expect(requete.request.params.get('page')).toBe('2');
    expect(requete.request.params.get('size')).toBe('25');
    expect(requete.request.params.get('sort')).toBe('createdAt,desc');
    requete.flush({ content: [], page: 2, size: 25, totalElements: 0, totalPages: 0 });
  });

  it("n'envoie pas les filtres absents", () => {
    api
      .liste({ page: 0, size: 25, sort: 'createdAt,desc', clientId: undefined, q: '' })
      .subscribe();

    const requete = httpMock.expectOne((r) => r.url === '/api/leads');
    // Un filtre vide envoye au serveur produirait un predicat inutile, et « q= » vide
    // ferait un LIKE '%%' sur toute la table.
    expect(requete.request.params.has('clientId')).toBeFalse();
    expect(requete.request.params.has('q')).toBeFalse();
    requete.flush({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
  });

  it('repete le parametre status pour un filtre multiple', () => {
    api
      .liste({ page: 0, size: 25, sort: 'createdAt,desc', status: ['QUALIFIED', 'ROUTED'] })
      .subscribe();

    const requete = httpMock.expectOne((r) => r.url === '/api/leads');
    expect(requete.request.params.getAll('status')).toEqual(['QUALIFIED', 'ROUTED']);
    requete.flush({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
  });

  it('demande le detail par son identifiant', () => {
    api.detail('11111111-1111-1111-1111-111111111111').subscribe();

    const requete = httpMock.expectOne('/api/leads/11111111-1111-1111-1111-111111111111');
    expect(requete.request.method).toBe('GET');
    requete.flush({});
  });

  it("n'envoie pas un tableau de statuts vide", () => {
    api.liste({ page: 0, size: 25, sort: 'createdAt,desc', status: [] }).subscribe();

    const requete = httpMock.expectOne((r) => r.url === '/api/leads');
    // « Aucun statut coche » veut dire « tous », pas « aucun » : envoyer un tableau vide
    // laisserait le serveur construire un predicat `status in ()` qui ne rend rien.
    expect(requete.request.params.has('status')).toBeFalse();
    requete.flush({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
  });
});
