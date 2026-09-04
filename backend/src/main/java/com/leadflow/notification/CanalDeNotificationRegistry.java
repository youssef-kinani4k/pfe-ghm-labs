package com.leadflow.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resout un canal de notification par son identifiant.
 *
 * <p>Meme motif que {@code CrmConnectorRegistry} et {@code AssignmentStrategyRegistry} : les
 * implementations sont collectees par injection de {@code List<CanalDeNotification>}, donc
 * il n'y a aucune liste a maintenir a la main et aucun {@code switch} sur le canal.
 *
 * <p>Le constructeur echoue si deux canaux revendiquent le meme identifiant : un doublon
 * silencieux ferait dependre le canal choisi de l'ordre d'injection, donc du hasard.
 *
 * <p>Il n'exige en revanche <b>aucun</b> canal, contrairement au registre des strategies
 * d'attribution qui reclame un titulaire par valeur d'enumeration. Une instance sans canal
 * configure doit demarrer et continuer a traiter des leads : l'alerte est un confort, le
 * pipeline ne l'est pas.
 */
@Component
public class CanalDeNotificationRegistry {

    private final Map<String, CanalDeNotification> parIdentifiant = new LinkedHashMap<>();

    public CanalDeNotificationRegistry(List<CanalDeNotification> canaux) {
        for (CanalDeNotification canal : canaux) {
            CanalDeNotification precedent = parIdentifiant.put(canal.identifiant(), canal);
            if (precedent != null) {
                throw new IllegalStateException(
                        "Deux canaux revendiquent l'identifiant " + canal.identifiant());
            }
        }
    }

    public CanalDeNotification resout(String identifiant) {
        CanalDeNotification canal = parIdentifiant.get(identifiant);
        if (canal == null) {
            throw new NotificationException(
                    "Canal de notification inconnu : " + identifiant
                            + ". Canaux disponibles : " + parIdentifiant.keySet());
        }
        return canal;
    }
}
