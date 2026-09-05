import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TenantApi } from './tenant-api';

describe('TenantApi', () => {
  let api: TenantApi;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(TenantApi);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('liste les boutiques sur un chemin relatif', () => {
    api.boutiques().subscribe();

    const requete = httpMock.expectOne('/api/admin/clients');
    expect(requete.request.method).toBe('GET');
    requete.flush([]);
  });

  it('teste une connexion sans enregistrer', () => {
    api.teste('dolibarr', { baseUrl: 'http://erp.test', apiKey: 'cle' }).subscribe();

    const requete = httpMock.expectOne('/api/admin/crm/test');
    expect(requete.request.method).toBe('POST');
    expect(requete.request.body.crmProviderId).toBe('dolibarr');
    requete.flush({ ok: true, cause: 'JOIGNABLE', detail: null });
  });

  it('desactive par un POST sur la sous-ressource', () => {
    api.desactive('abc').subscribe();

    // Une action aux consequences propres, pas un champ du PUT : la capture s'arrete.
    const requete = httpMock.expectOne('/api/admin/clients/abc/deactivate');
    expect(requete.request.method).toBe('POST');
    requete.flush({});
  });

  it('regenere le secret par une route dediee', () => {
    api.tourneLeSecret('abc').subscribe();

    const requete = httpMock.expectOne('/api/admin/clients/abc/rotate-secret');
    expect(requete.request.method).toBe('POST');
    requete.flush({ hmacSecret: 'nouveau' });
  });

  it('revoque le secret precedent par une route dediee', () => {
    api.revoqueLeSecretPrecedent('abc').subscribe();

    const requete = httpMock.expectOne('/api/admin/clients/abc/revoke-previous-secret');
    expect(requete.request.method).toBe('POST');
    requete.flush({});
  });
});
