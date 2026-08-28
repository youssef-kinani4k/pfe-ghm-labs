package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * L'ecran « Parametres » se sert de ces trois routes. Ce qui est verrouille ici : la cle ne
 * ressort jamais entiere, le diagnostic n'est pas presente comme une panne du serveur, et
 * l'ensemble reste derriere le jeton du dashboard.
 *
 * <p>La racine de l'API est detournee vers un port mort : aucun appel ne part vers Google
 * pendant la suite de tests, et le diagnostic doit quand meme rendre une cause exploitable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.intent.gemini.api-key=",
        "leadflow.intent.gemini.base-url=http://127.0.0.1:9/",
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class IntentAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void videLaTable() {
        jdbcTemplate.update("DELETE FROM intent_setting");
    }

    @Test
    void sansCleLEtatDitQuIlNyEnAAucune() throws Exception {
        String corps = mockMvc.perform(get("/api/admin/intent")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(corps).get("source").asText()).isEqualTo("AUCUNE");
        assertThat(mapper.readTree(corps).get("cleDefinie").asBoolean()).isFalse();
    }

    @Test
    void enregistreLaCleEtNeLaRendJamaisEntiere() throws Exception {
        mockMvc.perform(put("/api/admin/intent")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"AIzaSyExempleDeCleSecrete\",\"actif\":true}"))
                .andExpect(status().isOk());

        String corps = mockMvc.perform(get("/api/admin/intent")
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(corps).get("source").asText()).isEqualTo("BASE");
        assertThat(mapper.readTree(corps).get("apercu").asText()).isEqualTo("rete");
        // La reponse est assertee sur le JSON et non sur le DTO : c'est le corps qui part
        // sur le reseau, et lui seul prouve que le secret ne fuit pas.
        assertThat(corps).doesNotContain("AIzaSyExempleDeCleSecrete");
    }

    @Test
    void lInterrupteurSeCoupeSansRessaisirLaCle() throws Exception {
        mockMvc.perform(put("/api/admin/intent")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"AIzaSyExempleDeCleSecrete\",\"actif\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/intent")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actif\":false}"))
                .andExpect(status().isOk());

        String corps = mockMvc.perform(get("/api/admin/intent")
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(corps).get("actif").asBoolean()).isFalse();
        assertThat(mapper.readTree(corps).get("cleDefinie").asBoolean()).isTrue();
    }

    @Test
    void unDiagnosticQuiEchoueRend200AvecSaCause() throws Exception {
        String corps = mockMvc.perform(post("/api/admin/intent/test")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"cle-qui-ne-marche-pas\"}"))
                // Meme regle que le test de connexion ERP : un diagnostic rate n'est pas
                // une panne du serveur, sans quoi l'intercepteur du dashboard le presenterait
                // comme un incident.
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(corps).get("ok").asBoolean()).isFalse();
        assertThat(mapper.readTree(corps).get("cause").asText()).isEqualTo("INJOIGNABLE");
    }

    @Test
    void leDiagnosticNEnregistreRien() throws Exception {
        mockMvc.perform(post("/api/admin/intent/test")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"cle-a-eprouver\"}"))
                .andExpect(status().isOk());

        Integer lignes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM intent_setting", Integer.class);

        // Eprouver une cle ne doit pas la mettre en service : c'est l'enregistrement qui le
        // fait, et lui seul.
        assertThat(lignes).isZero();
    }

    @Test
    void toutLEcranEstDerriereLeJeton() throws Exception {
        mockMvc.perform(get("/api/admin/intent")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/admin/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actif\":false}"))
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
