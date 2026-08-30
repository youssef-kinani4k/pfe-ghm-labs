package com.leadflow.capture;

import com.leadflow.config.RateLimitProperties;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * La carte des seaux, une entree par cle publique vue.
 *
 * <p><b>Elle est bornee, et ce n'est pas un detail.</b> Le filtre ne consulte jamais la base
 * pour savoir si une cle existe — c'est ce qui empeche le {@code 429} de renseigner sur les
 * cles valides. En consequence, une cle inventee cree une entree comme une vraie. Une carte
 * non bornee ferait donc du limiteur l'amplificateur de l'attaque qu'il doit arreter :
 * quelques millions de cles aleatoires, et le tas est plein.
 *
 * <p>Deux garde-fous : un plafond d'entrees, avec eviction de la plus anciennement vue, et un
 * balayage periodique des seaux inactifs. L'eviction ne punit personne — une cle evincee
 * repart avec un seau plein.
 *
 * <p>Un {@code LinkedHashMap} en ordre d'acces plutot qu'une {@code ConcurrentHashMap} :
 * l'eviction du moins recemment vu demande un ordre, que la seconde n'offre pas. Le prix est
 * une synchronisation globale, acceptable ici — le travail sous le verrou est une
 * soustraction, sans entree-sortie ni acces base.
 */
public class RegistreDeSeaux {

    private final RateLimitProperties reglages;
    private final LongSupplier horloge;
    private final double jetonsParSeconde;
    private final Map<String, SeauDeJetons> seaux;

    public RegistreDeSeaux(RateLimitProperties reglages, LongSupplier horloge) {
        this.reglages = reglages;
        this.horloge = horloge;
        this.jetonsParSeconde = reglages.requetesParMinute() / 60.0d;
        this.seaux = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SeauDeJetons> plusAncienne) {
                return size() > reglages.clesSuiviesMax();
            }
        };
    }

    /** Verdict rendu au filtre : accepte, ou refuse avec l'attente a annoncer. */
    public record Verdict(boolean accepte, long attenteSecondes) {}

    public synchronized Verdict verdict(String cle) {
        long nanos = horloge.getAsLong();
        SeauDeJetons seau = seaux.computeIfAbsent(
                cle, ignoree -> new SeauDeJetons(reglages.rafale(), jetonsParSeconde, nanos));
        if (seau.consomme(nanos)) {
            return new Verdict(true, 0L);
        }
        return new Verdict(false, seau.attenteSecondes(nanos));
    }

    /** Evacue les seaux qu'on n'a pas vus depuis la fenetre d'inactivite. */
    public synchronized void balaie() {
        long limite = horloge.getAsLong() - reglages.fenetreInactivite().toNanos();
        Iterator<Map.Entry<String, SeauDeJetons>> parcours = seaux.entrySet().iterator();
        while (parcours.hasNext()) {
            if (parcours.next().getValue().dernierAcces() < limite) {
                parcours.remove();
            }
        }
    }

    synchronized int tailleSuivie() {
        return seaux.size();
    }
}
