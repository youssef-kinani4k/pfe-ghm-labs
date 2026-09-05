package com.leadflow.tenant;

import com.leadflow.capture.UsageAncienSecret;
import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.config.WebhookProperties;
import com.leadflow.crm.CrmConnectorRegistry;
import com.leadflow.crm.model.CrmSettingSpec;
import com.leadflow.tenant.dto.ClientCreated;
import com.leadflow.tenant.dto.ClientDetailAdmin;
import com.leadflow.tenant.dto.ClientForm;
import com.leadflow.tenant.dto.ClientSummaryAdmin;
import com.leadflow.tenant.dto.SecretRotated;
import com.leadflow.tenant.dto.SalesRepAdminView;
import com.leadflow.tenant.dto.TransitionSecret;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration des boutiques.
 *
 * <p>Ce service vit dans {@code tenant/} et non dans {@code monitoring/} : il ecrit dans
 * {@code client} et {@code sales_rep}, alors que {@code monitoring/} est un observateur qui
 * n'ecrit que sa table {@code dead_letter}.
 *
 * <p>Il ne chiffre rien lui-meme : {@code EncryptedStringConverter} et
 * {@code EncryptedJsonConverter} s'en chargent a l'ecriture. Le service manipule du clair.
 */
@Service
public class ClientAdminService {

    private final ClientRepository clients;
    private final SalesRepRepository commerciaux;
    private final CrmConnectorRegistry connecteurs;
    private final CleGenerator generateur;
    private final WebhookProperties webhook;
    private final UsageAncienSecret usageAncienSecret;

    public ClientAdminService(
            ClientRepository clients,
            SalesRepRepository commerciaux,
            CrmConnectorRegistry connecteurs,
            CleGenerator generateur,
            WebhookProperties webhook,
            UsageAncienSecret usageAncienSecret) {
        this.clients = clients;
        this.commerciaux = commerciaux;
        this.connecteurs = connecteurs;
        this.generateur = generateur;
        this.webhook = webhook;
        this.usageAncienSecret = usageAncienSecret;
    }

    @Transactional(readOnly = true)
    public List<ClientSummaryAdmin> liste() {
        return clients.findAllByOrderByNameAsc().stream()
                .map(client -> new ClientSummaryAdmin(
                        client.getId(),
                        client.getName(),
                        client.getCrmProviderId(),
                        client.getAssignmentStrategy(),
                        client.isActive(),
                        commerciaux.countByClientIdAndActiveTrue(client.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClientDetailAdmin fiche(UUID id) {
        Client client = clients.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException(
                        "Aucune boutique avec cet identifiant"));
        TransitionSecret transition = null;
        Instant expiration = client.getPreviousSecretExpiresAt();
        // Une fenetre close ne s'affiche pas : le secret precedent survit en base jusqu'a la
        // rotation suivante, mais il n'est plus accepte, donc il n'y a plus rien a dire.
        if (client.getPreviousHmacSecret() != null
                && expiration != null
                && expiration.isAfter(Instant.now())) {
            transition = new TransitionSecret(
                    expiration,
                    usageAncienSecret
                            .dernierUsage(client.getId(), client.getPreviousSecretSince())
                            .orElse(null));
        }
        return new ClientDetailAdmin(
                client.getId(),
                client.getName(),
                client.getPublicKey(),
                "/api/webhooks/leads/" + client.getPublicKey(),
                client.getCrmProviderId(),
                reglagesNonSecrets(client),
                client.getAssignmentStrategy(),
                client.isActive(),
                commerciaux.findByClientIdOrderByFullName(client.getId()).stream()
                        .map(this::vue)
                        .toList(),
                transition);
    }

    /**
     * Cree la boutique et son premier commercial <b>dans une seule transaction</b>.
     *
     * <p>Deux appels separes laisseraient, si le second echoue, une boutique active sans
     * commercial : le routage leverait AssignmentException et ses leads partiraient en DLQ
     * des le premier formulaire soumis. C'est exactement l'etat que cet ecran doit rendre
     * impossible a fabriquer.
     */
    @Transactional
    public ClientCreated cree(ClientForm formulaire) {
        if (formulaire.firstSalesRep() == null) {
            throw new ReglageManquantException(
                    "Une boutique doit etre creee avec au moins un commercial");
        }
        valideLesReglages(formulaire.crmProviderId(), formulaire.crmSettings());

        Client client = new Client();
        client.setName(formulaire.name());
        client.setPublicKey(generateur.clePublique());
        String secret = generateur.secretHmac();
        client.setHmacSecret(secret);
        client.setCrmProviderId(formulaire.crmProviderId());
        client.setCrmConfig(new LinkedHashMap<>(formulaire.crmSettings()));
        client.setAssignmentStrategy(formulaire.assignmentStrategy());
        client.setActive(true);
        Client enregistre = clients.save(client);

        SalesRep rep = new SalesRep();
        rep.setClient(enregistre);
        rep.setFullName(formulaire.firstSalesRep().fullName());
        rep.setEmail(formulaire.firstSalesRep().email());
        rep.setSector(formulaire.firstSalesRep().sector());
        rep.setZone(formulaire.firstSalesRep().zone());
        rep.setCrmRef(formulaire.firstSalesRep().crmRef());
        rep.setActive(true);
        commerciaux.save(rep);

        return new ClientCreated(
                enregistre.getId(),
                enregistre.getPublicKey(),
                secret,
                "/api/webhooks/leads/" + enregistre.getPublicKey());
    }

    /**
     * Met a jour l'identite, le fournisseur et les reglages ERP.
     *
     * <p>Ni les cles ni l'etat actif ne passent par la : ce sont des actions aux consequences
     * distinctes, exposees en sous-ressources pour qu'une simple correction de nom ne puisse
     * pas casser la signature d'une boutique par inadvertance.
     */
    @Transactional
    public ClientDetailAdmin metAJour(UUID id, ClientForm formulaire) {
        Client client = trouve(id);
        // Fusionner d'abord, valider ensuite : un champ secret laisse vide n'est pas un
        // reglage manquant, c'est un reglage inchange.
        Map<String, String> reglages = fusionne(client, formulaire);
        valideLesReglages(formulaire.crmProviderId(), reglages);
        client.setName(formulaire.name());
        client.setCrmProviderId(formulaire.crmProviderId());
        client.setCrmConfig(new LinkedHashMap<>(reglages));
        client.setAssignmentStrategy(formulaire.assignmentStrategy());
        return fiche(client.getId());
    }

    /**
     * Active ou desactive une boutique.
     *
     * <p>La desactivation remplace la suppression, que le schema interdit : {@code lead} et
     * {@code raw_lead_event} referencent {@code client} sans cascade, donc Postgres refuserait
     * d'effacer la premiere boutique ayant recu un lead.
     */
    @Transactional
    public ClientDetailAdmin change(UUID id, boolean actif) {
        Client client = trouve(id);
        // Les commerciaux ne sont pas touches : la reactivation doit restituer la
        // configuration telle quelle, et coupler les deux ferait perdre qui etait actif.
        client.setActive(actif);
        return fiche(client.getId());
    }

    /**
     * Regenere le secret HMAC, en laissant l'ancien vivre le temps d'une fenetre.
     *
     * <p>Sans cette fenetre, chaque lead de la boutique etait refuse en 401 entre la
     * rotation et le redeploiement de son site. L'ancien secret <b>doit</b> finir : une
     * fenetre sans fin, ce sont deux secrets permanents pour la meme porte.
     *
     * <p>Une seconde rotation pendant la fenetre est autorisee, et l'ancien devient celui
     * qu'on vient de retirer : il n'y a jamais plus de deux secrets vivants. Le secret
     * d'origine cesse alors immediatement de valoir, et l'ecran l'annonce avant de
     * confirmer. {@code previousSecretSince} est repose a l'instant de <b>cette</b> rotation,
     * pour que la fenetre precedente ne fasse pas remonter un vieux retardataire.
     */
    @Transactional
    public SecretRotated tourneLeSecret(UUID id) {
        Client client = trouve(id);
        Instant maintenant = Instant.now();
        Instant expiration = maintenant.plus(webhook.transitionSecret());
        client.setPreviousHmacSecret(client.getHmacSecret());
        client.setPreviousSecretExpiresAt(expiration);
        client.setPreviousSecretSince(maintenant);
        client.setHmacSecret(generateur.secretHmac());
        return new SecretRotated(client.getHmacSecret(), expiration);
    }

    /**
     * Ferme la fenetre de transition sans attendre son terme.
     *
     * <p>Le geste d'une fuite averee. Les trois colonnes tombent ensemble : un secret
     * precedent sans expiration serait un second secret permanent.
     *
     * <p>Revoquer une transition inexistante est un succes sans effet — l'etat vise est
     * atteint, et un 404 obligerait l'ecran a distinguer deux cas identiques pour
     * l'operateur.
     */
    @Transactional
    public ClientDetailAdmin revoqueLeSecretPrecedent(UUID id) {
        Client client = trouve(id);
        client.setPreviousHmacSecret(null);
        client.setPreviousSecretExpiresAt(null);
        client.setPreviousSecretSince(null);
        return fiche(client.getId());
    }

    /**
     * Regenere la cle publique, donc l'URL du webhook.
     *
     * <p>C'est precisement pour cela que la cle publique est distincte de la cle primaire :
     * elle est revocable sans recreer la ligne, et sans perdre les leads deja captures.
     */
    @Transactional
    public ClientDetailAdmin tourneLaClePublique(UUID id) {
        Client client = trouve(id);
        client.setPublicKey(generateur.clePublique());
        return fiche(client.getId());
    }

    private Client trouve(UUID id) {
        return clients.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException(
                        "Aucune boutique avec cet identifiant"));
    }

    /**
     * Un champ secret laisse vide conserve la valeur enregistree : la fiche ne le rend pas.
     *
     * <p>Un fournisseur inconnu ressort tel quel plutot que de lever ici : la fusion n'a rien
     * a dire de ce cas, et {@code valideLesReglages}, appelee juste apres, en fait le meme
     * ReglageManquantException que la creation — donc un 400, pas un 500.
     */
    private Map<String, String> fusionne(Client client, ClientForm formulaire) {
        Map<String, String> fusion = new LinkedHashMap<>(formulaire.crmSettings());
        List<CrmSettingSpec> attendus;
        try {
            attendus = connecteurs.forProvider(formulaire.crmProviderId()).reglagesAttendus();
        } catch (IllegalArgumentException fournisseurInconnu) {
            return fusion;
        }
        attendus.stream()
                .filter(CrmSettingSpec::secret)
                .forEach(spec -> {
                    String fourni = fusion.get(spec.cle());
                    if ((fourni == null || fourni.isBlank())
                            && client.getCrmConfig().containsKey(spec.cle())) {
                        fusion.put(spec.cle(), client.getCrmConfig().get(spec.cle()));
                    }
                });
        return fusion;
    }

    /**
     * Valide contre ce que le connecteur declare, jamais contre une liste ecrite ici :
     * ajouter un ERP ne doit rien demander a ce package.
     */
    private void valideLesReglages(String providerId, Map<String, String> reglages) {
        List<CrmSettingSpec> attendus;
        try {
            attendus = connecteurs.forProvider(providerId).reglagesAttendus();
        } catch (IllegalArgumentException inconnu) {
            throw new ReglageManquantException(inconnu.getMessage());
        }
        for (CrmSettingSpec attendu : attendus) {
            String valeur = reglages == null ? null : reglages.get(attendu.cle());
            if (valeur == null || valeur.isBlank()) {
                throw new ReglageManquantException(
                        "Le reglage '" + attendu.cle() + "' est requis pour " + providerId);
            }
        }
    }

    /**
     * Ne rend que ce qui n'est pas secret.
     *
     * <p>La liste des cles secretes vient du connecteur, jamais d'un {@code switch} ecrit
     * ici : {@code tenant/} ne doit rien savoir de Dolibarr ni d'Odoo. Un fournisseur dont
     * le connecteur a disparu de la configuration rend une carte vide plutot que d'echouer —
     * la fiche doit rester consultable pour qu'on puisse corriger le fournisseur.
     */
    private Map<String, String> reglagesNonSecrets(Client client) {
        Set<String> secretes;
        try {
            secretes = connecteurs.forProvider(client.getCrmProviderId()).reglagesAttendus()
                    .stream()
                    .filter(CrmSettingSpec::secret)
                    .map(CrmSettingSpec::cle)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException fournisseurInconnu) {
            return Map.of();
        }
        Map<String, String> visibles = new LinkedHashMap<>();
        client.getCrmConfig().forEach((cle, valeur) -> {
            if (!secretes.contains(cle)) {
                visibles.put(cle, valeur);
            }
        });
        return visibles;
    }

    private SalesRepAdminView vue(SalesRep rep) {
        return new SalesRepAdminView(
                rep.getId(), rep.getFullName(), rep.getEmail(),
                rep.getSector(), rep.getZone(), rep.getCrmRef(), rep.isActive());
    }
}
