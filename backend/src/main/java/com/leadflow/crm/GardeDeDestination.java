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
 * juste avant l'appel, sur l'URI effectivement demandee, quelle que soit la maniere dont
 * l'adaptateur l'a composee.
 *
 * <p>Il ne couvre en revanche PAS les redirections : le client HTTP en suivrait une a
 * l'interieur d'un seul send(), sans repasser par cet intercepteur. C'est pourquoi
 * {@code CrmHttpConfig} construit son {@code HttpClient} avec {@code Redirect.NEVER} — le
 * garde et ce reglage se tiennent, et l'un ne doit pas etre change sans l'autre.
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
