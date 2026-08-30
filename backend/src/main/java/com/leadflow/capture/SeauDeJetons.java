package com.leadflow.capture;

/**
 * Seau a jetons, sans horloge propre.
 *
 * <p>L'instant est passe en parametre a chaque appel plutot que lu ici. Un seau qui
 * appellerait {@code System.nanoTime()} lui-meme ne s'eprouverait qu'a coups de
 * {@code Thread.sleep} : lentement, et avec des tests qui echouent par intermittence sur
 * une machine chargee.
 *
 * <p>La classe n'est pas sure vis-a-vis des threads a elle seule : c'est
 * {@link RegistreDeSeaux} qui serialise les acces, un seau par cle.
 */
class SeauDeJetons {

    private final int rafale;
    private final double jetonsParSeconde;

    private double jetons;
    private long dernierAcces;

    SeauDeJetons(int rafale, double jetonsParSeconde, long nanosInitiaux) {
        this.rafale = rafale;
        this.jetonsParSeconde = jetonsParSeconde;
        this.jetons = rafale;
        this.dernierAcces = nanosInitiaux;
    }

    /** @return vrai si un jeton etait disponible, et il est alors consomme. */
    boolean consomme(long nanos) {
        recharge(nanos);
        if (jetons < 1.0d) {
            return false;
        }
        jetons -= 1.0d;
        return true;
    }

    /** Secondes a attendre avant le prochain jeton. Jamais zero : un Retry-After nul invite au marteau. */
    long attenteSecondes(long nanos) {
        recharge(nanos);
        double manque = Math.max(0.0d, 1.0d - jetons);
        return Math.max(1L, (long) Math.ceil(manque / jetonsParSeconde));
    }

    long dernierAcces() {
        return dernierAcces;
    }

    /**
     * Le plafonnement a {@code rafale} est ce qui empeche une longue inactivite de se
     * transformer en credit : sans lui, un client silencieux une heure pourrait ensuite
     * emettre une heure de trafic d'un coup, et le plafond ne voudrait plus rien dire.
     */
    private void recharge(long nanos) {
        long ecoule = Math.max(0L, nanos - dernierAcces);
        jetons = Math.min(rafale, jetons + (ecoule / 1_000_000_000.0d) * jetonsParSeconde);
        dernierAcces = nanos;
    }
}
