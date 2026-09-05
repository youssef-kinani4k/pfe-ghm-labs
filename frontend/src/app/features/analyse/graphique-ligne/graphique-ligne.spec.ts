import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GraphiqueLigne, SerieGraphique } from './graphique-ligne';

describe('GraphiqueLigne', () => {
  let fixture: ComponentFixture<GraphiqueLigne>;

  const serie: SerieGraphique[] = [
    { nom: 'Mediane', valeurs: [1, null, 3], couleur: '#334155', remplie: false },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [GraphiqueLigne] }).compileComponents();
    fixture = TestBed.createComponent(GraphiqueLigne);
  });

  it('rend un canvas', () => {
    fixture.componentRef.setInput('libelles', ['lun', 'mar', 'mer']);
    fixture.componentRef.setInput('series', serie);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('canvas')).toBeTruthy();
  });

  it('detruit son instance Chart a la destruction du composant', () => {
    fixture.componentRef.setInput('libelles', ['lun', 'mar', 'mer']);
    fixture.componentRef.setInput('series', serie);
    fixture.detectChanges();

    // Sans cela, chaque navigation vers l'ecran laisse une instance vivante, avec son
    // ecouteur de redimensionnement.
    const instance = fixture.componentInstance;
    const graphique = (instance as unknown as { graphique?: { destroy: () => void } }).graphique;
    expect(graphique).toBeTruthy();
    const espion = spyOn(graphique!, 'destroy').and.callThrough();

    fixture.destroy();

    expect(espion).toHaveBeenCalled();
  });
});
