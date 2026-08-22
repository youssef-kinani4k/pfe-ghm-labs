package com.leadflow.capture;

import com.leadflow.common.PayloadRejectedException;
import com.leadflow.common.WebhookAuthenticationException;
import com.leadflow.config.WebhookProperties;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Le travail synchrone du webhook, et rien de plus : authentifier, ecrire, rendre la main.
 * Aucune validation metier du contenu — ni email, ni telephone, ni doublon fonctionnel :
 * tout cela appartient a la qualification (F3), derriere la file.
 *
 * <p>L'ordre des operations n'est pas negociable : le corps n'est deserialise qu'une fois
 * l'appelant authentifie. Faire tourner Jackson sur une entree non authentifiee reviendrait
 * a traiter une donnee dont on n'a pas encore verifie l'origine.
 */
@Service
public class LeadCaptureService {

    private final ClientRepository clientRepository;
    private final RawLeadEventRepository rawLeadEventRepository;
    private final HmacSignatureVerifier verificateur;
    private final ObjectMapper objectMapper;
    private final WebhookProperties properties;

    public LeadCaptureService(
            ClientRepository clientRepository,
            RawLeadEventRepository rawLeadEventRepository,
            HmacSignatureVerifier verificateur,
            ObjectMapper objectMapper,
            WebhookProperties properties) {
        this.clientRepository = clientRepository;
        this.rawLeadEventRepository = rawLeadEventRepository;
        this.verificateur = verificateur;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public CaptureAccepted capture(String clientKey, String corpsBrut, String enTeteSignature) {
        // Un client desactive est simplement introuvable : le repository filtre sur
        // active=true, ce qui rend les deux cas indistinguables sans effort particulier.
        Client client = clientRepository.findByPublicKeyAndActiveTrue(clientKey)
                .orElseThrow(() -> new WebhookAuthenticationException(
                        "Cle publique inconnue ou client desactive : " + clientKey));

        verificateur.verifie(client.getHmacSecret(), corpsBrut, enTeteSignature, Instant.now());

        int taille = corpsBrut.getBytes(StandardCharsets.UTF_8).length;
        if (taille > properties.maxPayloadBytes()) {
            throw new PayloadRejectedException(
                    "Corps de " + taille + " octets, maximum " + properties.maxPayloadBytes(),
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }

        Map<String, Object> payload = deserialise(corpsBrut);
        Object source = payload.get("source");
        if (!(source instanceof String canal) || canal.isBlank()) {
            throw new PayloadRejectedException(
                    "Le champ 'source' est obligatoire", HttpStatus.BAD_REQUEST);
        }

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(client.getId());
        evenement.setSource(canal);
        evenement.setPayload(payload);
        evenement.setSignature(enTeteSignature);
        evenement.setReceivedAt(Instant.now());
        evenement.setStatus(RawLeadEventStatus.RECEIVED);

        RawLeadEvent enregistre = rawLeadEventRepository.saveAndFlush(evenement);
        return new CaptureAccepted(enregistre.getId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserialise(String corpsBrut) {
        try {
            return objectMapper.readValue(corpsBrut, Map.class);
        } catch (JacksonException e) {
            throw new PayloadRejectedException("Corps JSON illisible", HttpStatus.BAD_REQUEST);
        }
    }
}
