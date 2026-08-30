package com.leadflow.crm;

import com.leadflow.config.SsrfProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * La regle : quelles destinations le serveur s'autorise a appeler.
 *
 * <p>Separee de {@link GardeDeDestination} pour s'eprouver sans reseau ni contexte Spring.
 * La resolution passe par {@link #resout(String)}, surchargee dans les tests : sans cela, la
 * suite dependrait d'un resolveur DNS et de l'acces Internet.
 *
 * <p><b>Ce que cette politique ne fait pas.</b> Elle ne ferme pas la fenetre de
 * DNS-rebinding : elle resout le nom, puis le client HTTP le resout a son tour, et un serveur
 * DNS hostile peut repondre differemment aux deux. La fermer demanderait de cabler la
 * resolution dans le client HTTP lui-meme. La limitation est ecrite ici plutot que passee
 * sous silence — un garde qui promet plus qu'il ne tient est pire qu'un garde absent, parce
 * qu'on cesse de s'en mefier.
 */
@Component
public class PolitiqueDeDestination {

    private static final Set<String> SCHEMAS = Set.of("http", "https");

    private final SsrfProperties reglages;

    public PolitiqueDeDestination(SsrfProperties reglages) {
        this.reglages = reglages;
    }

    /** @throws DestinationRefuseeException si l'appel ne doit pas partir. */
    public void verifie(URI destination) {
        if (!reglages.actif()) {
            return;
        }

        String schema = destination.getScheme();
        if (schema == null || !SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
            throw new DestinationRefuseeException(
                    "Seuls http et https sont autorises vers un ERP");
        }

        String hote = destination.getHost();
        if (hote == null || hote.isBlank()) {
            throw new DestinationRefuseeException("URL d'ERP sans hote");
        }

        List<String> autorises = reglages.hotesAutorises();
        if (autorises != null) {
            String hoteLowercase = hote.toLowerCase(Locale.ROOT);
            if (autorises.stream().anyMatch(h -> h.toLowerCase(Locale.ROOT).equals(hoteLowercase))) {
                return;
            }
        }

        InetAddress[] adresses;
        try {
            adresses = resout(hote);
        } catch (UnknownHostException introuvable) {
            // On ne laisse pas passer ce qu'on ne sait pas juger.
            throw new DestinationRefuseeException("Hote d'ERP introuvable : " + hote);
        }

        for (InetAddress adresse : adresses) {
            if (interne(adresse)) {
                throw new DestinationRefuseeException(
                        "Destination interne refusee pour l'hote " + hote);
            }
        }
    }

    /** Point de substitution des tests : aucune requete DNS ne part de la suite. */
    protected InetAddress[] resout(String hote) throws UnknownHostException {
        return InetAddress.getAllByName(hote);
    }

    /**
     * {@code isSiteLocalAddress} couvre 10/8, 172.16/12 et 192.168/16 ;
     * {@code isLinkLocalAddress} couvre 169.254/16, dont 169.254.169.254 — les metadonnees
     * d'instance des fournisseurs cloud, la cible SSRF la plus rentable qui soit.
     * Les methodes complementaires traitent les plages qu aucun predicat du JDK ne couvre :
     * fc00::/7 pour IPv6 et 100.64.0.0/10 pour les operateurs.
     */
    private boolean interne(InetAddress adresse) {
        return adresse.isLoopbackAddress()
                || adresse.isLinkLocalAddress()
                || adresse.isSiteLocalAddress()
                || adresse.isAnyLocalAddress()
                || adresse.isMulticastAddress()
                || uniqueLocaleIpv6(adresse)
                || natDOperateur(adresse);
    }

    /**
     * fc00::/7 — l equivalent IPv6 des plages privees. isSiteLocalAddress ne le couvre pas :
     * sur une Inet6Address, ce predicat ne connait que fec0::/10, deprecie depuis.
     */
    private boolean uniqueLocaleIpv6(InetAddress adresse) {
        byte[] octets = adresse.getAddress();
        return octets.length == 16 && (octets[0] & 0xFE) == 0xFC;
    }

    /** 100.64.0.0/10 — le NAT d operateur de la RFC 6598, qu aucun predicat du JDK ne couvre. */
    private boolean natDOperateur(InetAddress adresse) {
        byte[] octets = adresse.getAddress();
        return octets.length == 4
                && (octets[0] & 0xFF) == 100
                && (octets[1] & 0xFF) >= 64
                && (octets[1] & 0xFF) <= 127;
    }
}
