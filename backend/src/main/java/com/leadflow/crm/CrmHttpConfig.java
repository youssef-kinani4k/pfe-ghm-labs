package com.leadflow.crm;

import com.leadflow.config.CrmProperties;
import java.net.http.HttpClient;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
 * <p>La fabrique repose sur {@code java.net.http.HttpClient} et non sur
 * {@code HttpURLConnection} : ce dernier analyse l'en-tete {@code WWW-Authenticate} des
 * reponses 401 et leve {@code IllegalArgumentException: invalid start or end} quand elle est
 * vide — ce que Dolibarr renvoie precisement lorsqu'une cle d'API est fausse. L'exception
 * n'etant pas une {@code RestClientException}, elle traversait les adaptateurs et rendait un
 * 500 opaque la ou la sonde devait dire « identifiants refuses ».
 *
 * <p>Les timeouts sont poses ici et non dans l'adaptateur : un test peut ainsi injecter un
 * builder nu branche sur {@code MockRestServiceServer} sans que la fabrique de requetes de
 * production interfere. L'URL de l'instance n'est pas connue a ce stade — elle vient de la
 * ligne client — donc aucun {@code baseUrl} n'est fixe ici.
 *
 * <p>La fabrique pose aussi {@link GardeDeDestination}. C'est le seul endroit ou le poser :
 * tous les adaptateurs passent par elle, donc celui qui ajoutera le prochain ERP est protege
 * sans rien avoir a savoir du garde. Une verification recopiee dans chaque adaptateur aurait
 * ajoute un quatrieme geste a l'invariant des trois, et aurait fini par etre oubliee.
 *
 * <p>Les constructeurs de test des adaptateurs recoivent un builder nu branche sur
 * MockRestServiceServer : ils eprouvent des corps de requete, pas une politique de
 * destination, qui a ses propres tests.
 */
@Component
public class CrmHttpConfig {

    private final CrmProperties properties;
    private final GardeDeDestination garde;

    public CrmHttpConfig(CrmProperties properties, GardeDeDestination garde) {
        this.properties = properties;
        this.garde = garde;
    }

    /** @throws IllegalStateException si le fournisseur n'a aucune entree de configuration. */
    public RestClient.Builder builderPour(String providerId) {
        return RestClient.builder()
                .requestFactory(requestFactory(providerId))
                .requestInterceptor(garde);
    }

    private ClientHttpRequestFactory requestFactory(String providerId) {
        CrmProperties.Provider provider =
                properties.providers() == null ? null : properties.providers().get(providerId);
        if (provider == null) {
            throw new IllegalStateException(
                    "leadflow.crm.providers." + providerId + " est absente de la configuration");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(provider.connectTimeout())
                // Explicite, et non laisse au defaut : une redirection suivie a l'interieur
                // d'un send() echapperait a GardeDeDestination, qui ne voit la requete qu'une
                // fois, a la frontiere de la fabrique. Un ERP qui repond 302 vers
                // 169.254.169.254 contournerait donc le garde en silence. On refuse de suivre
                // plutot que de faire confiance a un defaut que personne ne relit.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(provider.readTimeout());
        return factory;
    }
}
