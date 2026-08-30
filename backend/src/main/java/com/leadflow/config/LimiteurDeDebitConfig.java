package com.leadflow.config;

import com.leadflow.capture.LimiteurDeDebit;
import com.leadflow.capture.RegistreDeSeaux;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

/**
 * Enregistrement du limiteur sur {@code /api/webhooks/*} et rien d'autre.
 *
 * <p>Le filtre n'est volontairement pas un {@code @Component} : Spring Boot l'enregistrerait
 * alors sur toutes les requetes, dashboard compris, ou il n'a rien a faire — ces routes sont
 * derriere un jeton et le compte operateur est unique.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "leadflow.webhook.rate-limit",
        name = "actif",
        havingValue = "true",
        matchIfMissing = true)
public class LimiteurDeDebitConfig {

    private final RegistreDeSeaux registre;

    public LimiteurDeDebitConfig(RateLimitProperties reglages) {
        this.registre = new RegistreDeSeaux(reglages, System::nanoTime);
    }

    @Bean
    FilterRegistrationBean<LimiteurDeDebit> limiteurDeDebit(ObjectMapper json) {
        FilterRegistrationBean<LimiteurDeDebit> enregistrement = new FilterRegistrationBean<>();
        enregistrement.setFilter(new LimiteurDeDebit(registre, json));
        enregistrement.addUrlPatterns("/api/webhooks/*");
        // Devant la chaine Spring Security, dont l'ordre par defaut est 0 : un refus de
        // volume ne doit pas couter le travail des filtres d'authentification.
        enregistrement.setOrder(-100);
        return enregistrement;
    }

    /** Evacue les seaux inactifs. La periode n'a pas besoin d'etre reglable : elle ne
     * gouverne rien d'observable, seulement la vitesse a laquelle la memoire se rend. */
    @Scheduled(fixedDelay = 60_000L)
    void balaie() {
        registre.balaie();
    }
}
