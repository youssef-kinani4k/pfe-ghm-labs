import { Injectable, NgZone, inject, signal } from '@angular/core';
import { Auth } from '../auth/auth';
import { DeadLetterView, StreamEvent } from '../models/monitoring';

export interface TrameSse {
  nom: string;
  donnees: string;
}

/**
 * Decoupe un tampon SSE en trames completes et rend ce qui reste.
 *
 * Fonction pure et exportee : un ReadableStream livre des morceaux arbitraires, une trame
 * peut arriver coupee en deux, et c'est la seule logique du flux qui merite un test isole.
 */
export function decoupeTrames(tampon: string): { evenements: TrameSse[]; reste: string } {
  const evenements: TrameSse[] = [];
  const blocs = tampon.split('\n\n');
  // Le dernier bloc est incomplet tant qu'il n'est pas suivi d'une ligne vide.
  const reste = blocs.pop() ?? '';

  for (const bloc of blocs) {
    let nom = 'message';
    const lignesDeDonnees: string[] = [];
    for (const ligne of bloc.split('\n')) {
      if (ligne.startsWith(':')) {
        continue; // commentaire de maintien
      }
      if (ligne.startsWith('event:')) {
        nom = ligne.slice(6).trim();
      } else if (ligne.startsWith('data:')) {
        lignesDeDonnees.push(ligne.slice(5).trim());
      }
    }
    if (lignesDeDonnees.length > 0) {
      evenements.push({ nom, donnees: lignesDeDonnees.join('\n') });
    }
  }
  return { evenements, reste };
}

/** Delai avant nouvelle tentative apres une coupure, en millisecondes. */
const DELAI_DE_RECONNEXION = 5000;

/** Profondeur des fenetres glissantes : le flux anime l'ecran, il ne le remplace pas. */
const FENETRE = 50;

/**
 * Lecture du flux par fetch et non par EventSource : celui-ci ne sait pas poser d'en-tete
 * Authorization, et passer le jeton en parametre d'URL le ferait apparaitre dans tous les
 * journaux d'acces.
 *
 * La reconnexion est volontairement simple — un delai fixe puis nouvelle tentative : le
 * serveur expire ses emetteurs au bout d'un temps fini, donc une reconnexion est attendue,
 * pas exceptionnelle.
 */
@Injectable({ providedIn: 'root' })
export class LeadStream {
  private readonly auth = inject(Auth);
  private readonly zone = inject(NgZone);

  readonly derniersLeads = signal<StreamEvent[]>([]);
  readonly dernieresMorts = signal<DeadLetterView[]>([]);
  readonly connecte = signal(false);

  private controleur: AbortController | null = null;
  private reconnexion: ReturnType<typeof setTimeout> | null = null;

  ouvre(): void {
    this.annuleLaReconnexion();
    this.controleur?.abort();
    this.controleur = new AbortController();
    void this.lit(this.controleur.signal);
  }

  ferme(): void {
    this.annuleLaReconnexion();
    this.controleur?.abort();
    this.controleur = null;
    this.connecte.set(false);
  }

  private annuleLaReconnexion(): void {
    if (this.reconnexion !== null) {
      clearTimeout(this.reconnexion);
      this.reconnexion = null;
    }
  }

  private async lit(signalDArret: AbortSignal): Promise<void> {
    try {
      const reponse = await fetch('/api/stream/leads', {
        headers: { Authorization: `Bearer ${this.auth.token()}` },
        signal: signalDArret,
      });
      if (!reponse.ok || !reponse.body) {
        throw new Error(`Flux refuse : ${reponse.status}`);
      }
      this.zone.run(() => this.connecte.set(true));

      const lecteur = reponse.body.getReader();
      const decodeur = new TextDecoder();
      let tampon = '';

      while (!signalDArret.aborted) {
        const { value, done } = await lecteur.read();
        if (done) {
          break;
        }
        tampon += decodeur.decode(value, { stream: true });
        const { evenements, reste } = decoupeTrames(tampon);
        tampon = reste;
        // fetch vit hors de la zone Angular : sans run(), les signaux changeraient sans
        // que la vue s'en apercoive.
        this.zone.run(() => evenements.forEach((trame) => this.applique(trame)));
      }
      // Fin de flux nette (expiration de l'emetteur cote serveur) : on rouvre, sinon
      // l'ecran resterait muet jusqu'au prochain rechargement.
      if (!signalDArret.aborted) {
        this.zone.run(() => this.connecte.set(false));
        this.programmeUneReconnexion();
      }
    } catch {
      if (!signalDArret.aborted) {
        this.zone.run(() => this.connecte.set(false));
        this.programmeUneReconnexion();
      }
    }
  }

  private programmeUneReconnexion(): void {
    this.annuleLaReconnexion();
    // Hors zone : une attente de cinq secondes reprogrammee sans fin empecherait la
    // stabilisation de l'application, ce dont les tests dependent.
    this.zone.runOutsideAngular(() => {
      this.reconnexion = setTimeout(() => this.ouvre(), DELAI_DE_RECONNEXION);
    });
  }

  private applique(trame: TrameSse): void {
    let charge: unknown;
    try {
      charge = JSON.parse(trame.donnees);
    } catch {
      // Une trame illisible ne doit pas rompre la lecture du flux : le morceau suivant
      // peut etre parfaitement valide.
      return;
    }
    if (trame.nom === 'lead') {
      this.derniersLeads.update((liste) => [charge as StreamEvent, ...liste].slice(0, FENETRE));
    } else if (trame.nom === 'dead-letter') {
      this.dernieresMorts.update((liste) => [charge as DeadLetterView, ...liste].slice(0, FENETRE));
    }
  }
}
