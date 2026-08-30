package com.leadflow.crm;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

/**
 * Applique {@link PolitiqueDeDestination} a chaque appel sortant vers un ERP.
 *
 * <p>Un intercepteur plutot qu'une verification a l'entree de l'adaptateur : il s'execute
 * juste avant l'appel, donc aussi sur les requetes issues d'une redirection, la ou une
 * verification faite une fois sur l'URL de base ne verrait rien.
 */
@Component
public class GardeDeDestination implements ClientHttpRequestInterceptor {

    private final PolitiqueDeDestination politique;

    public GardeDeDestination(PolitiqueDeDestination politique) {
        this.politique = politique;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest requete, byte[] corps, ClientHttpRequestExecution suite)
            throws IOException {
        politique.verifie(requete.getURI());
        return suite.execute(requete, corps);
    }
}
