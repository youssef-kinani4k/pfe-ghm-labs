package com.leadflow.monitoring;

import com.leadflow.config.AnalyticsProperties;
import com.leadflow.monitoring.dto.PointDelai;
import com.leadflow.monitoring.dto.PointIntention;
import com.leadflow.monitoring.dto.PointVolume;
import com.leadflow.monitoring.dto.SeriesView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Les series quotidiennes de l'ecran d'analyse.
 *
 * <p>Deux responsabilites, et elles sont ici plutot qu'en SQL a dessein. <strong>La borne de
 * fenetre</strong> d'abord : sans elle, un appel a 100 000 jours balaierait les tables
 * entieres. <strong>Le comblement des trous</strong> ensuite : une journee sans donnee n'a pas
 * de ligne en base, et un graphique tracerait une droite par-dessus, ce qui est un mensonge.
 *
 * <p>La valeur de comblement differe selon la figure, et c'est le point le plus facile a
 * « simplifier » plus tard en mettant zero partout. Volume et intentions : <strong>zero</strong>,
 * « aucun lead capture ce jour-la » etant un fait. Delais : <strong>{@code null}</strong>,
 * « aucun lead synchronise » ne voulant pas dire « delai de zero seconde » — Chart.js
 * interrompt la ligne sur un {@code null}, la mettre a zero dessinerait une chute vers le bas.
 */
@Service
public class SeriesService {

    /** Les trois seules fenetres admises. Voir {@link FenetreInvalideException}. */
    private static final Set<Integer> FENETRES = Set.of(7, 30, 90);

    private final SeriesRepository series;
    private final AnalyticsProperties reglages;

    public SeriesService(SeriesRepository series, AnalyticsProperties reglages) {
        this.series = series;
        this.reglages = reglages;
    }

    @Transactional(readOnly = true)
    public SeriesView calcule(UUID clientId, int jours) {
        if (!FENETRES.contains(jours)) {
            throw new FenetreInvalideException(jours);
        }

        ZoneId zone = reglages.zone();
        String fuseau = reglages.fuseau();
        LocalDate fin = LocalDate.now(zone);
        LocalDate debut = fin.minusDays(jours - 1L);
        Instant depuis = debut.atStartOfDay(zone).toInstant();
        String cle = clientId == null ? null : clientId.toString();

        List<LocalDate> calendrier = calendrier(debut, jours);

        Map<LocalDate, PointVolumeBrut> volumes =
                indexe(series.volumeParJour(cle, depuis, fuseau), PointVolumeBrut::getJour);
        Map<LocalDate, PointDelaiBrut> delais =
                indexe(series.delaisParJour(cle, depuis, fuseau), PointDelaiBrut::getJour);
        Map<LocalDate, PointIntentionBrut> intentions =
                indexe(series.intentionsParJour(cle, depuis, fuseau),
                        PointIntentionBrut::getJour);

        return new SeriesView(
                calendrier.stream()
                        .map(j -> {
                            PointVolumeBrut brut = volumes.get(j);
                            return brut == null
                                    ? new PointVolume(j, 0L, 0L)
                                    : new PointVolume(j, brut.getCaptures(), brut.getEcartes());
                        })
                        .toList(),
                calendrier.stream()
                        .map(j -> {
                            PointDelaiBrut brut = delais.get(j);
                            // null et non zero : voir le Javadoc de la classe.
                            return brut == null
                                    ? new PointDelai(j, null, null)
                                    : new PointDelai(
                                            j,
                                            brut.getMedianeSecondes(),
                                            brut.getP95Secondes());
                        })
                        .toList(),
                calendrier.stream()
                        .map(j -> {
                            PointIntentionBrut brut = intentions.get(j);
                            return brut == null
                                    ? new PointIntention(j, 0L, 0L)
                                    : new PointIntention(
                                            j, brut.getGemini(), brut.getLexique());
                        })
                        .toList());
    }

    private static List<LocalDate> calendrier(LocalDate debut, int jours) {
        List<LocalDate> dates = new ArrayList<>(jours);
        for (int i = 0; i < jours; i++) {
            dates.add(debut.plusDays(i));
        }
        return dates;
    }

    private static <T> Map<LocalDate, T> indexe(List<T> points, Function<T, LocalDate> jour) {
        return points.stream().collect(Collectors.toMap(jour, Function.identity()));
    }
}
