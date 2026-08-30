package com.leadflow.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.leadflow.TestcontainersConfiguration;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * La rafale est ramenee a 3 pour que le test soit court. Aucun client n'est cree en base :
 * c'est delibere — le filtre doit refuser AVANT toute consultation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
    "leadflow.webhook.rate-limit.actif=true",
    "leadflow.webhook.rate-limit.rafale=3",
    "leadflow.webhook.rate-limit.requetes-par-minute=1"
})
class LimiteurDeDebitTest {

    private static final String CORPS = "{\"source\":\"formulaire\",\"email\":\"a@b.test\"}";

    @Autowired private MockMvc mockMvc;

    private int soumet(String cle) throws Exception {
        return mockMvc.perform(post("/api/webhooks/leads/" + cle)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /**
     * Comme {@link #soumet}, mais a partir d'un chemin deja forme via {@link URI#create}, qui
     * ne re-encode pas ce qu'on lui donne — contrairement a {@code post(String, Object...)},
     * qui traite l'argument comme un gabarit et encoderait un {@code %63} deja present en
     * {@code %2563}, faussant le test d'une cle volontairement percent-encodee.
     */
    private int soumetChemin(String chemin) throws Exception {
        return mockMvc.perform(post(URI.create(chemin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /** Au-dela de la rafale, le webhook rend 429. */
    @Test
    void refuseAuDelaDeLaRafale() throws Exception {
        String cle = "cle-" + UUID.randomUUID();

        assertThat(soumet(cle)).isEqualTo(401);
        assertThat(soumet(cle)).isEqualTo(401);
        assertThat(soumet(cle)).isEqualTo(401);
        assertThat(soumet(cle)).isEqualTo(429);
    }

    /**
     * <b>Le test qui compte.</b> Une cle publique inventee est limitee exactement comme une
     * cle valide, parce que le filtre ne consulte pas la base. Si un jour quelqu'un
     * « optimisait » le filtre en ne comptant que les clients connus, le 429 deviendrait
     * l'oracle que les cinq 401 uniformes existent pour fermer, et ce test tomberait.
     */
    @Test
    void uneCleInconnueEstLimiteeCommeUneConnue() throws Exception {
        String inventee = "totalement-inventee-" + UUID.randomUUID();

        soumet(inventee);
        soumet(inventee);
        soumet(inventee);

        assertThat(soumet(inventee)).isEqualTo(429);
    }

    /** Le refus annonce une attente, sans quoi l'appelant ne peut que marteler. */
    @Test
    void leRefusPorteRetryAfter() throws Exception {
        String cle = "cle-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            soumet(cle);
        }

        MvcResult refus = mockMvc.perform(post("/api/webhooks/leads/" + cle)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andReturn();

        assertThat(refus.getResponse().getStatus()).isEqualTo(429);
        assertThat(refus.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(refus.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    /** Deux boutiques ne partagent pas de plafond. */
    @Test
    void deuxClesNePartagentPasLeurPlafond() throws Exception {
        String premiere = "cle-" + UUID.randomUUID();
        String seconde = "cle-" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            soumet(premiere);
        }

        assertThat(soumet(seconde)).isEqualTo(401);
    }

    /**
     * Deux ecritures de la meme cle partagent un seul seau : {@code %63} decode en {@code c}.
     * Sans decodage, chaque orthographe ouvrirait son propre seau et la boutique echapperait
     * a son plafond en variant l'encodage de l'URL.
     */
    @Test
    void deuxEncodagesDeLaMemeCleNePartagentQuUnSeau() throws Exception {
        String suffixe = UUID.randomUUID().toString();
        String cle = "cle-" + suffixe;
        String cleEncodee = "%63le-" + suffixe;

        soumet(cle);
        soumet(cle);
        soumet(cle);

        assertThat(soumetChemin("/api/webhooks/leads/" + cleEncodee)).isEqualTo(429);
    }

    /**
     * <b>Verification d'une hypothese de contournement, pas d'un comportement voulu du
     * limiteur.</b> Un parametre de matrice ({@code ;n=1}) colle au dernier segment de
     * {@link jakarta.servlet.http.HttpServletRequest#getRequestURI()}, et {@code
     * derniereSection} ne le retire pas : verifie en debogage, la requete decoree obtient bien
     * un seau neuf (le seau de la cle nue est epuise, celui-ci ne l'est pas), donc {@code
     * verdict.accepte()} vaut {@code true} et le filtre laisse passer.
     *
     * <p>Mais le filtre n'est pas le dernier mot : {@code LimiteurDeDebit} s'enregistre a
     * {@code Ordered.HIGHEST_PRECEDENCE}, avant la chaine Spring Security, et le {@code
     * StrictHttpFirewall} par defaut de celle-ci rejette toute URI contenant un point-virgule
     * avant meme le routage — {@code Handler} reste nul, aucune exception applicative n'est
     * resolue, seulement 400. La cle mal derivee n'atteint donc jamais le controleur : il n'y
     * a pas de fuite de seau exploitable sur cette pile, meme si {@code derniereSection} reste
     * naive face aux parametres de matrice. Ce test verrouille cette conclusion — si le
     * pare-feu changeait de politique ({@code setAllowSemicolon(true)}), il faudrait revisiter
     * {@code derniereSection}.
     */
    @Test
    void unParametreDeMatriceNAtteintJamaisLeControleur() throws Exception {
        String cle = "cle-" + UUID.randomUUID();

        soumet(cle);
        soumet(cle);
        soumet(cle);

        assertThat(soumetChemin("/api/webhooks/leads/" + cle + ";n=1")).isEqualTo(400);
    }
}
