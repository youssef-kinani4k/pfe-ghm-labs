package com.leadflow.crm;

import com.leadflow.config.CrmProperties;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fabrique de {@code RestClient.Builder}, un par fournisseur, portant ses propres delais.
 *
 * <p>Volontairement <b>sans bean nomme par fournisseur</b> : le builder se demande par cle,
 * si bien qu'ajouter un ERP ne demande rien ici. C'est ce qui maintient l'invariant des
 * trois gestes — un sous-package, un {@code @Component} implementant {@code CrmConnector},
 * une entree {@code leadflow.crm.providers.<provider>}.
 *
 * <p>Les timeouts sont poses ici et non dans l'adaptateur : un test peut ainsi injecter un
 * builder nu branche sur {@code MockRestServiceServer} sans que la fabrique de requetes de
 * production interfere. L'URL de l'instance n'est pas connue a ce stade — elle vient de la
 * ligne client — donc aucun {@code baseUrl} n'est fixe ici.
 */
@Component
public class CrmHttpConfig {

    private final CrmProperties properties;

    public CrmHttpConfig(CrmProperties properties) {
        this.properties = properties;
    }

    /** @throws IllegalStateException si le fournisseur n'a aucune entree de configuration. */
    public RestClient.Builder builderPour(String providerId) {
        return RestClient.builder().requestFactory(requestFactory(providerId));
    }

    private ClientHttpRequestFactory requestFactory(String providerId) {
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
