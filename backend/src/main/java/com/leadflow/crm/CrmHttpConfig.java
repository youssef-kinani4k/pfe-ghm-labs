package com.leadflow.crm;

import com.leadflow.config.CrmProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Un {@code RestClient.Builder} par fournisseur, portant ses propres delais.
 *
 * <p>Les timeouts sont poses ici et non dans l'adaptateur : un test peut ainsi injecter un
 * builder nu branche sur {@code MockRestServiceServer} sans que la fabrique de requetes de
 * production interfere. L'URL de l'instance n'est pas connue a ce stade — elle vient de la
 * ligne client — donc aucun {@code baseUrl} n'est fixe ici.
 */
@Configuration
public class CrmHttpConfig {

    @Bean
    RestClient.Builder dolibarrRestClientBuilder(CrmProperties properties) {
        return RestClient.builder().requestFactory(requestFactory(properties, "dolibarr"));
    }

    @Bean
    RestClient.Builder odooRestClientBuilder(CrmProperties properties) {
        return RestClient.builder().requestFactory(requestFactory(properties, "odoo"));
    }

    static ClientHttpRequestFactory requestFactory(CrmProperties properties, String providerId) {
        CrmProperties.Provider provider =
                properties.providers() == null ? null : properties.providers().get(providerId);
        if (provider == null) {
            throw new IllegalStateException(
                    "leadflow.crm.providers." + providerId + " est absente de la configuration");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(provider.connectTimeout());
        factory.setReadTimeout(provider.readTimeout());
        return factory;
    }
}
