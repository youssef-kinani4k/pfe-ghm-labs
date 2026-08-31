import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { LeadTimeline } from './lead-timeline';

describe('LeadTimeline', () => {
  let fixture: ComponentFixture<LeadTimeline>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LeadTimeline],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LeadTimeline);
    httpMock = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('leadId', 'abc');
  });

  afterEach(() => httpMock.verify());

  it('rend une entree par fait', async () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/leads/abc/timeline').flush([
      { type: 'CAPTURE', at: '2026-08-31T10:00:00Z', outcome: 'NEUTRE', details: {} },
      { type: 'QUALIFICATION', at: '2026-08-31T10:00:01Z', outcome: 'NEUTRE', details: {} },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();

    const entrees = fixture.nativeElement.querySelectorAll('[data-entree]');
    expect(entrees.length).toBe(2);
  });

  it('marque une date absente comme inconnue et non comme vide', async () => {
    fixture.detectChanges();
    httpMock
      .expectOne('/api/leads/abc/timeline')
      .flush([{ type: 'ATTRIBUTION', at: null, outcome: 'NEUTRE', details: {} }]);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('date inconnue');
  });

  it('nomme un echec en toutes lettres, et pas seulement par la couleur', async () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/leads/abc/timeline').flush([
      {
        type: 'SYNC_ERP',
        at: '2026-08-31T10:00:05Z',
        outcome: 'ECHEC',
        details: { erreur: 'Connection refused' },
      },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();

    const entree = fixture.nativeElement.querySelector('[data-entree]');
    expect(entree.getAttribute('data-issue')).toBe('ECHEC');
    // La couleur ne porte jamais seule l'information : le mot est ecrit, et l'icone le
    // double. Un ecran imprime en noir et blanc doit rester lisible.
    expect(entree.textContent).toContain('Echec');
    expect(entree.textContent).toContain('Synchronisation ERP');
  });
});
