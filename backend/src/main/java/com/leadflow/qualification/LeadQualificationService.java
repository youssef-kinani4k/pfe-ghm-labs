package com.leadflow.qualification;

import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Transforme un evenement brut en lead qualifie.
 *
 * <p><b>Volontairement non transactionnel.</b> L'appel a l'analyseur d'intention peut durer
 * plusieurs secondes : a l'interieur d'une transaction JPA, il tiendrait une connexion
 * Postgres ouverte pendant tout ce temps et le pool s'epuiserait avant le broker. L'ecriture
 * a sa propre transaction, portee par {@link LeadWriter}.
 *
 * <p><b>L'ordre des etapes n'est pas arbitraire.</b> La deduplication vient avant l'analyse
 * d'intention pour qu'un doublon ne coute pas un appel au modele. La normalisation vient
 * avant la deduplication parce que celle-ci compare des emails normalises. Et l'idempotence
 * est verifiee deux fois : une lecture optimiste, puis la contrainte unique
 * {@code raw_event_id}, seule a faire foi quand deux livraisons arrivent en parallele.
 *
 * <p>Ne choisit aucun commercial (F4) et n'appelle aucun ERP (F5) :
 * {@code assigned_sales_rep_id} reste nul.
 */
@Service
public class LeadQualificationService {

    private static final Logger log = LoggerFactory.getLogger(LeadQualificationService.class);

    private static final String SANS_EMAIL = "qualification : aucun email exploitable";

    private final RawLeadEventRepository rawLeadEventRepository;
    private final ClientRepository clientRepository;
    private final LeadRepository leadRepository;
    private final PayloadFieldMapper mapper;
    private final ContactNormalizer normalizer;
    private final DuplicateGuard garde;
    private final IntentAnalyzer analyzer;
    private final LeadScorer scorer;
    private final LeadWriter writer;

    public LeadQualificationService(
            RawLeadEventRepository rawLeadEventRepository,
            ClientRepository clientRepository,
            LeadRepository leadRepository,
            PayloadFieldMapper mapper,
            ContactNormalizer normalizer,
            DuplicateGuard garde,
            IntentAnalyzer analyzer,
            LeadScorer scorer,
            LeadWriter writer) {
        this.rawLeadEventRepository = rawLeadEventRepository;
        this.clientRepository = clientRepository;
        this.leadRepository = leadRepository;
        this.mapper = mapper;
        this.normalizer = normalizer;
        this.garde = garde;
        this.analyzer = analyzer;
        this.scorer = scorer;
        this.writer = writer;
    }

    /**
     * @return le lead, qualifie ou rejete ; vide quand il ne faut rien ecrire — evenement
     *     introuvable ou sans email exploitable. Dans les deux cas le message est acquitte :
     *     ces echecs sont deterministes et n'ont rien a faire en DLQ.
     */
    public Optional<Lead> qualifie(UUID eventId) {
        RawLeadEvent brut = rawLeadEventRepository.findById(eventId).orElse(null);
        if (brut == null) {
            log.warn("Evenement {} introuvable : rien a qualifier", eventId);
            return Optional.empty();
        }

        Optional<Lead> deja = leadRepository.findByRawEventId(eventId);
        if (deja.isPresent()) {
            return deja;
        }

        ContactNormalise contact = normalizer.normalise(mapper.extrait(brut.getPayload()))
                .orElse(null);
        if (contact == null) {
            marqueEnEchec(brut);
            return Optional.empty();
        }

        if (garde.estDoublon(brut.getClientId(), contact.email())) {
            return Optional.of(ecrit(brut, contact, null, 0, LeadStatus.REJECTED));
        }

        IntentAnalysis analyse = analyzer.analyse(contact.message());
        int score = scorer.score(contact, analyse.intent(), scoringConfig(brut.getClientId()));
        return Optional.of(ecrit(brut, contact, analyse, score, LeadStatus.QUALIFIED));
    }

    /**
     * Un client desactive entre la capture et la qualification voit quand meme son lead
     * qualifie : il a ete legitimement recu. La desactivation ferme l'entree, elle ne vide
     * pas la file.
     */
    private Map<String, Object> scoringConfig(UUID clientId) {
        return clientRepository.findById(clientId)
                .map(Client::getScoringConfig)
                .orElse(Map.of());
    }

    private void marqueEnEchec(RawLeadEvent brut) {
        log.info("Evenement {} sans email exploitable : aucun lead ecrit", brut.getId());
        brut.setStatus(RawLeadEventStatus.FAILED);
        brut.setFailureReason(SANS_EMAIL);
        rawLeadEventRepository.save(brut);
    }

    /**
     * L'insertion peut echouer sur la contrainte unique {@code raw_event_id} si une
     * livraison concurrente a gagne la course. Ce n'est pas une erreur : on relit la ligne
     * gagnante, exactement comme {@code LeadCaptureService} le fait en F2.
     */
    private Lead ecrit(
            RawLeadEvent brut,
            ContactNormalise contact,
            IntentAnalysis analyse,
            int score,
            LeadStatus statut) {
        Lead lead = new Lead();
        lead.setClientId(brut.getClientId());
        lead.setRawEventId(brut.getId());
        lead.setEmail(contact.email());
        lead.setPhone(contact.phone());
        lead.setMessage(contact.message());
        lead.setCompanyName(contact.companyName());
        lead.setFirstName(contact.firstName());
        lead.setLastName(contact.lastName());
        lead.setCountryCode(contact.countryCode());
        lead.setSector(contact.sector());
        lead.setScore(score);
        lead.setStatus(statut);
        if (analyse != null) {
            lead.setDetectedIntent(analyse.intent().name());
            lead.setIntentSource(analyse.source());
        }

        try {
            return writer.insere(lead);
        } catch (DataIntegrityViolationException course) {
            return leadRepository.findByRawEventId(brut.getId()).orElseThrow(() -> course);
        }
    }
}
