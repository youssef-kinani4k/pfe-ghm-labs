package com.leadflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * La route de diagnostic du canal, telle que l'ecran « Parametres » s'en sert.
 *
 * <p>Le relais est pointe vers un port mort : aucun message ne part pendant la suite de
 * tests, et le diagnostic doit quand meme rendre une cause exploitable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.notification.smtp.host=127.0.0.1",
        "leadflow.notification.smtp.port=1",
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class NotificationAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private NotificationAttemptRepository tentatives;

    @Test
    void unDiagnosticRateRend200AvecSaCauseEtNonUneErreurServeur() throws Exception {
        String corps = mockMvc.perform(post("/api/admin/notification/test")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinataire\":\"operateur@agence.test\"}"))
                // 200 et non 502 : l'intercepteur du dashboard presenterait un incident la
                // ou il n'y a qu'un reglage a corriger. Meme parti que la sonde d'intention.
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Assertion sur le corps JSON et non sur le DTO : c'est lui qui part sur le reseau.
        assertThat(mapper.readTree(corps).get("ok").asBoolean()).isFalse();
        assertThat(mapper.readTree(corps).get("cause").asText())
                .isEqualTo("RELAIS_INJOIGNABLE");
        assertThat(mapper.readTree(corps).get("detail").asText()).isNotBlank();
    }

    @Test
    void leDiagnosticNecritAucuneTrace() throws Exception {
        long avant = tentatives.count();

        mockMvc.perform(post("/api/admin/notification/test")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinataire\":\"operateur@agence.test\"}"))
                .andExpect(status().isOk());

        // notification_attempt trace ce que des leads reels ont declenche. Y meler des
        // essais d'operateur rendrait le journal inutilisable pour repondre a « ce lead
        // a-t-il ete notifie ? ».
        assertThat(tentatives.count()).isEqualTo(avant);
    }

    @Test
    void laRouteResteDerriereLeJetonDuDashboard() throws Exception {
        mockMvc.perform(post("/api/admin/notification/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinataire\":\"operateur@agence.test\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String jeton() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"secret-de-test\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(corps).get("token").asText();
    }
}
