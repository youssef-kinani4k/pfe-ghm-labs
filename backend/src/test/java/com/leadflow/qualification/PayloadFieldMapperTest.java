package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Le payload est un JSON libre : capture ne le regarde jamais. Ces tests fixent jusqu'ou va
 * la tolerance du mapping, et ou elle s'arrete.
 */
class PayloadFieldMapperTest {

    private final PayloadFieldMapper mapper = new PayloadFieldMapper();

    @Test
    void litLesNomsCanoniques() {
        ChampsBruts champs = mapper.extrait(Map.of(
                "email", "karim@acme.test",
                "telephone", "+212600000000",
                "message", "Je veux un devis"));

        assertThat(champs.email()).isEqualTo("karim@acme.test");
        assertThat(champs.phone()).isEqualTo("+212600000000");
        assertThat(champs.message()).isEqualTo("Je veux un devis");
    }

    @Test
    void accepteLesAliasAnglais() {
        ChampsBruts champs = mapper.extrait(Map.of(
                "mail", "karim@acme.test",
                "phone", "0600000000",
                "company", "ACME",
                "firstname", "Karim",
                "lastname", "Bennani"));

        assertThat(champs.email()).isEqualTo("karim@acme.test");
        assertThat(champs.phone()).isEqualTo("0600000000");
        assertThat(champs.companyName()).isEqualTo("ACME");
        assertThat(champs.firstName()).isEqualTo("Karim");
        assertThat(champs.lastName()).isEqualTo("Bennani");
    }

    @Test
    void ignoreLaCasseLesAccentsEtLesSeparateurs() {
        assertThat(mapper.extrait(Map.of("Adresse-Email", "a@b.test")).email())
                .isEqualTo("a@b.test");
        assertThat(mapper.extrait(Map.of("adresse_email", "a@b.test")).email())
                .isEqualTo("a@b.test");
        assertThat(mapper.extrait(Map.of("ADRESSE EMAIL", "a@b.test")).email())
                .isEqualTo("a@b.test");
        assertThat(mapper.extrait(Map.of("Société", "ACME")).companyName())
                .isEqualTo("ACME");
    }

    @Test
    void retientLePremierAliasDeclareQuandPlusieursSontPresents() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mail", "second@acme.test");
        payload.put("email", "premier@acme.test");

        assertThat(mapper.extrait(payload).email()).isEqualTo("premier@acme.test");
    }

    @Test
    void convertitLesScalairesNonTextuels() {
        assertThat(mapper.extrait(Map.of("telephone", 212600000000L)).phone())
                .isEqualTo("212600000000");
    }

    @Test
    void ignoreLesValeursImbriquees() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", Map.of("valeur", "karim@acme.test"));
        payload.put("message", List.of("un", "deux"));

        ChampsBruts champs = mapper.extrait(payload);

        assertThat(champs.email()).isNull();
        assertThat(champs.message()).isNull();
    }

    @Test
    void rendDesChampsNulsSurUnPayloadVideOuNul() {
        assertThat(mapper.extrait(Map.of()).email()).isNull();
        assertThat(mapper.extrait(null).email()).isNull();
    }

    @Test
    void neMappePasSourceQuiEstDejaPorteeParLEvenementBrut() {
        ChampsBruts champs = mapper.extrait(Map.of("source", "formulaire-devis"));

        assertThat(champs.email()).isNull();
        assertThat(champs.message()).isNull();
        assertThat(champs.sector()).isNull();
    }
}
