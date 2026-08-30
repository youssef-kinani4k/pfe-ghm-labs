package com.leadflow.capture;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Plafond de volume sur le webhook, par cle publique.
 *
 * <p><b>Hors de la chaine Spring Security, deliberement.</b> L'authentification de
 * {@code /api/webhooks/**} est la signature HMAC, verifiee dans la couche capture ; la
 * consigne du projet est de ne pas superposer un mecanisme Spring a cette route. Un limiteur
 * n'authentifie pas — il compte. Le tenir devant la chaine garde la frontiere lisible.
 *
 * <p><b>Il ne consulte jamais la base.</b> La cle est le segment brut de l'URL, sans savoir
 * si une boutique lui correspond. C'est ce qui permet d'ajouter une sixieme reponse possible
 * sans rouvrir l'oracle que les cinq {@code 401} uniformes ferment : un {@code 429} dit
 * « trop d'appels », jamais « cette cle existe ».
 *
 * <p>La reponse est ecrite ici plutot que levee en exception : un
 * {@code @RestControllerAdvice} ne voit que ce qui traverse le {@code DispatcherServlet}, et
 * un filtre s'execute avant lui.
 *
 * <p><b>Mono-instance</b>, comme {@code PendingEventRelay} : deux exemplaires de
 * l'application offriraient deux fois le plafond. La levee demanderait un compteur partage.
 */
public class LimiteurDeDebit extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LimiteurDeDebit.class);

    private final RegistreDeSeaux registre;
    private final ObjectMapper json;

    public LimiteurDeDebit(RegistreDeSeaux registre, ObjectMapper json) {
        this.registre = registre;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest requete, HttpServletResponse reponse, FilterChain suite)
            throws ServletException, IOException {

        String cle = derniereSection(requete.getRequestURI());
        RegistreDeSeaux.Verdict verdict = registre.verdict(cle);
        if (verdict.accepte()) {
            suite.doFilter(requete, reponse);
            return;
        }

        // La cle n'est pas journalisee : elle n'est pas secrete, mais un journal d'attaque
        // rempli de segments arbitraires n'aide personne.
        log.warn("Webhook refuse : plafond de debit atteint");
        reponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        reponse.setHeader("Retry-After", String.valueOf(verdict.attenteSecondes()));
        reponse.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail corps = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Trop de soumissions, reessayez plus tard");
        json.writeValue(reponse.getOutputStream(), corps);
    }

    private String derniereSection(String uri) {
        int barre = uri.lastIndexOf('/');
        return barre < 0 ? uri : uri.substring(barre + 1);
    }
}
