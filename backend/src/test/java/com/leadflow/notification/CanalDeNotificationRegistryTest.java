package com.leadflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.notification.model.NotificationLead;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanalDeNotificationRegistryTest {

    private static CanalDeNotification canal(String identifiant) {
        return new CanalDeNotification() {
            @Override
            public String identifiant() {
                return identifiant;
            }

            @Override
            public void envoie(NotificationLead lead) {
                // Un double : le registre ne s'occupe que de resoudre, jamais d'envoyer.
            }
        };
    }

    @Test
    void resoutUnCanalParSonIdentifiant() {
        CanalDeNotificationRegistry registre =
                new CanalDeNotificationRegistry(List.of(canal("smtp")));

        assertThat(registre.resout("smtp").identifiant()).isEqualTo("smtp");
    }

    @Test
    void refuseDeDemarrerSiDeuxCanauxRevendiquentLeMemeIdentifiant() {
        // Meme garde-fou qu'AssignmentStrategyRegistry : un doublon silencieux ferait
        // dependre le canal choisi de l'ordre d'injection, donc du hasard. Une erreur de
        // cablage doit arreter le demarrage, pas surgir au premier lead chaud.
        assertThatThrownBy(
                        () -> new CanalDeNotificationRegistry(
                                List.of(canal("smtp"), canal("smtp"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("smtp");
    }

    @Test
    void unIdentifiantInconnuLeveEtNommeCeQuiExiste() {
        CanalDeNotificationRegistry registre =
                new CanalDeNotificationRegistry(List.of(canal("smtp")));

        assertThatThrownBy(() -> registre.resout("pigeon"))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("pigeon")
                .hasMessageContaining("smtp");
    }

    @Test
    void unRegistreVideNEmpechePasLeDemarrage() {
        // Contrairement au registre des strategies d'attribution, qui exige un titulaire
        // par valeur d'enumeration : ici aucun canal n'est obligatoire, et une instance
        // sans canal doit demarrer. L'echec, s'il doit arriver, arrive a la resolution.
        CanalDeNotificationRegistry registre = new CanalDeNotificationRegistry(List.of());

        assertThatThrownBy(() -> registre.resout("smtp"))
                .isInstanceOf(NotificationException.class);
    }
}
