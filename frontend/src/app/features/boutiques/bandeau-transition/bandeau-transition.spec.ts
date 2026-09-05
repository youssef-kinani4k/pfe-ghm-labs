import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BandeauTransition } from './bandeau-transition';

describe('BandeauTransition', () => {
  let fixture: ComponentFixture<BandeauTransition>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [BandeauTransition] }).compileComponents();
    fixture = TestBed.createComponent(BandeauTransition);
  });

  it('annonce que la boutique a fini de migrer quand aucun lead ne porte l ancien secret', () => {
    fixture.componentRef.setInput('transition', {
      expireLe: '2026-09-06T14:30:00Z',
      dernierLeadAncienSecret: null,
    });
    fixture.detectChanges();

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('Plus aucun lead');
  });

  it('signale un retardataire tant qu un lead porte encore l ancien secret', () => {
    fixture.componentRef.setInput('transition', {
      expireLe: '2026-09-06T14:30:00Z',
      dernierLeadAncienSecret: '2026-09-05T12:00:00Z',
    });
    fixture.detectChanges();

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('Dernier lead');
    expect(texte).not.toContain('Plus aucun lead');
  });

  it('emet la revocation au clic', () => {
    fixture.componentRef.setInput('transition', {
      expireLe: '2026-09-06T14:30:00Z',
      dernierLeadAncienSecret: null,
    });
    let demande = 0;
    fixture.componentInstance.revoque.subscribe(() => (demande += 1));
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-test="revoquer"]')
      ?.click();

    expect(demande).toBe(1);
  });
});
