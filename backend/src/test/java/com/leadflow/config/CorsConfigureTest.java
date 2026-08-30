package com.leadflow.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.leadflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Le developpement sert le dashboard depuis :4200 et l'API depuis :8090 — deux origines,
 * donc CORS a un objet. C'est le seul environnement ou il en a un.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties =
        "leadflow.security.cors.allowed-origins=http://localhost:4200")
class CorsConfigureTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void lePreflightRendLOrigineAutorisee() throws Exception {
        mockMvc.perform(options("/api/leads")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().string(
                        "Access-Control-Allow-Origin", "http://localhost:4200"));
    }

    @Test
    void uneOrigineNonListeeNEstPasAutorisee() throws Exception {
        mockMvc.perform(options("/api/leads")
                        .header("Origin", "http://ailleurs.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
