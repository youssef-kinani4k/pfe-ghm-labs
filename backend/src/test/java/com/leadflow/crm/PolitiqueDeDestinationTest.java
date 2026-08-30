package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.config.SsrfProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolitiqueDeDestinationTest {

    /** Resolveur de test : aucune requete DNS reelle, donc aucun test dependant du reseau. */
    private PolitiqueDeDestination politique(List<String> autorises, Map<String, String> dns) {
        return new PolitiqueDeDestination(new SsrfProperties(true, autorises)) {
            @Override
            protected InetAddress[] resout(String hote) throws UnknownHostException {
                String adresse = dns.get(hote);
                if (adresse == null) {
                    throw new UnknownHostException(hote);
                }
                return new InetAddress[] {InetAddress.getByName(adresse)};
            }
        };
    }

    @Test
    void accepteUneAdressePublique() {
        PolitiqueDeDestination politique =
                politique(List.of(), Map.of("erp.client.test", "203.0.113.10"));

        assertThatCode(() -> politique.verifie(URI.create("https://erp.client.test/api")))
                .doesNotThrowAnyException();
    }

    @Test
    void refuseLeBouclage() {
        PolitiqueDeDestination politique = politique(List.of(), Map.of("interne", "127.0.0.1"));

        assertThatThrownBy(() -> politique.verifie(URI.create("http://interne/api")))
                .isInstanceOf(DestinationRefuseeException.class);
    }

    /** Le cas qui motive tout le garde : les metadonnees d'instance cloud. */
    @Test
    void refuseLesMetadonneesCloud() {
        PolitiqueDeDestination politique =
                politique(List.of(), Map.of("metadata", "169.254.169.254"));

        assertThatThrownBy(() -> politique.verifie(URI.create("http://metadata/latest/meta-data/")))
                .isInstanceOf(DestinationRefuseeException.class);
    }

    @Test
    void refuseLesPlagesPrivees() {
        PolitiqueDeDestination politique = politique(
                List.of(), Map.of("a", "10.0.0.5", "b", "192.168.1.20", "c", "172.16.4.9"));

        assertThatThrownBy(() -> politique.verifie(URI.create("http://a/api")))
                .isInstanceOf(DestinationRefuseeException.class);
        assertThatThrownBy(() -> politique.verifie(URI.create("http://b/api")))
                .isInstanceOf(DestinationRefuseeException.class);
        assertThatThrownBy(() -> politique.verifie(URI.create("http://c/api")))
                .isInstanceOf(DestinationRefuseeException.class);
    }

    @Test
    void refuseUnSchemaQuiNEstPasHttp() {
        PolitiqueDeDestination politique = politique(List.of(), Map.of());

        assertThatThrownBy(() -> politique.verifie(URI.create("file:///etc/passwd")))
                .isInstanceOf(DestinationRefuseeException.class);
    }

    /**
     * L'exception qui fait vivre la recette : le Dolibarr de la pile resout en adresse privee
     * et doit passer, sans quoi l'ecran de test ne pourrait plus joindre l'ERP du projet.
     */
    @Test
    void unHoteAutoriseTraverseMalgreUneAdressePrivee() {
        PolitiqueDeDestination politique =
                politique(List.of("dolibarr"), Map.of("dolibarr", "172.20.0.4"));

        assertThatCode(() -> politique.verifie(URI.create("http://dolibarr/api/index.php")))
                .doesNotThrowAnyException();
    }

    /** L'autorisation porte sur l'hote exact, jamais sur un suffixe. */
    @Test
    void lAutorisationNEstPasUnSuffixe() {
        PolitiqueDeDestination politique = politique(
                List.of("dolibarr"), Map.of("dolibarr.attaquant.test", "10.0.0.5"));

        assertThatThrownBy(() -> politique.verifie(URI.create("http://dolibarr.attaquant.test/")))
                .isInstanceOf(DestinationRefuseeException.class);
    }

    /** Un nom qui ne resout pas est refuse : on ne laisse pas passer ce qu'on ne sait pas juger. */
    @Test
    void refuseUnHoteQuiNeResoutPas() {
        PolitiqueDeDestination politique = politique(List.of(), Map.of());

        assertThatThrownBy(() -> politique.verifie(URI.create("http://inconnu.invalid/api")))
                .isInstanceOf(DestinationRefuseeException.class);
    }

    /** Desactivee, la politique ne refuse rien — le repli d'une instance qui l'assume. */
    @Test
    void desactiveeElleLaissePasser() {
        PolitiqueDeDestination politique = new PolitiqueDeDestination(
                new SsrfProperties(false, List.of())) {
            @Override
            protected InetAddress[] resout(String hote) throws UnknownHostException {
                return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            }
        };

        assertThatCode(() -> politique.verifie(URI.create("http://interne/api")))
                .doesNotThrowAnyException();
    }

    /** IPv6 unique-local fc00::/7 est refuse comme son equivalent IPv4. */
    @Test
    void refuseLesUniqueLocaleIpv6() {
        PolitiqueDeDestination politique =
                politique(List.of(), Map.of("ipv6local", "fc00::1"));

        assertThatThrownBy(() -> politique.verifie(URI.create("http://ipv6local/api")))
                .isInstanceOf(DestinationRefuseeException.class);
    }

    /** Le NAT d operateur RFC 6598 (100.64.0.0/10) est refuse. */
    @Test
    void refuseLeNatDOperateur() {
        PolitiqueDeDestination politique =
                politique(List.of(), Map.of("carrier", "100.64.0.1"));

        assertThatThrownBy(() -> politique.verifie(URI.create("http://carrier/api")))
                .isInstanceOf(DestinationRefuseeException.class);
    }

    /** IPv4-mapped IPv6 169.254.169.254 est refuse — le JDK l unwrappe en Inet4Address. */
    @Test
    void refuseAdresseIpv4MappeeEnIpv6() {
        PolitiqueDeDestination politique =
                politique(List.of(), Map.of("ipv4mapped", "::ffff:169.254.169.254"));

        assertThatThrownBy(() -> politique.verifie(URI.create("http://ipv4mapped/api")))
                .isInstanceOf(DestinationRefuseeException.class);
    }

    /** L autorisation est case-insensitive pour les noms DNS, qui ne le sont pas. */
    @Test
    void lAutorisationEstCaseInsensitive() {
        PolitiqueDeDestination politique =
                politique(List.of("DoLiBaRr"), Map.of("dolibarr", "172.20.0.4"));

        assertThatCode(() -> politique.verifie(URI.create("http://DOLIBARR/api/index.php")))
                .doesNotThrowAnyException();
    }
}
