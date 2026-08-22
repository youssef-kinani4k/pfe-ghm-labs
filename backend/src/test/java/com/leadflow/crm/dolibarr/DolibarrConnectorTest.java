package com.leadflow.crm.dolibarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DolibarrConnectorTest {

    private static final CrmTarget CIBLE = new CrmTarget(
            "dolibarr", Map.of("baseUrl", "http://erp.test", "apiKey", "cle"));

    /** Faux transport : ce test porte sur la traduction, pas sur HTTP. */
    private static final class TransportFactice extends DolibarrClient {

        private final List<String> appels = new ArrayList<>();
        private Map<String, Object> corpsTiers;
        private Map<String, Object> corpsContact;
        private Map<String, Object> corpsOpportunite;
        private String responsableLie;
        private String refCherchee;
        private String opportuniteExistante;
        private boolean echoueSurOpportunite;

        private TransportFactice() {
            super(RestClient.builder());
        }

        @Override
        public String creeTiers(CrmTarget target, Map<String, Object> corps) {
            appels.add("tiers");
            corpsTiers = corps;
            return "42";
        }

        @Override
        public String creeContact(CrmTarget target, Map<String, Object> corps) {
            appels.add("contact");
            corpsContact = corps;
            return "77";
        }

        @Override
        public String creeOpportunite(CrmTarget target, Map<String, Object> corps) {
            appels.add("opportunite");
            corpsOpportunite = corps;
            if (echoueSurOpportunite) {
                throw new CrmSyncException("dolibarr", "projet refuse", null);
            }
            return "99";
        }

        @Override
        public String chercheOpportuniteParRef(CrmTarget target, String ref) {
            appels.add("recherche");
            refCherchee = ref;
            return opportuniteExistante;
        }

        @Override
        public void lieResponsable(CrmTarget target, String opportuniteRef, String utilisateurRef) {
            appels.add("responsable");
            responsableLie = utilisateurRef;
        }

        @Override
        public String chercheUtilisateurParEmail(CrmTarget target, String email) {
            appels.add("utilisateur");
            return "9";
        }
    }

    private static CrmLead lead(String companyName) {
        return leadAvecReference("LF-3F2A9C1B7D4E", companyName);
    }

    private static CrmLead leadAvecReference(String reference, String companyName) {
        return new CrmLead(reference, companyName, "Amina", "Bensalem", "amina@acme.test",
                "+212600000000", "Je veux un devis", "DEMANDE_DEVIS", 72, "MA", "industrie", "9");
    }

    private final TransportFactice transport = new TransportFactice();

    private final DolibarrConnector connecteur = new DolibarrConnector(transport);

    @Test
    void seDeclareSousLIdentifiantDolibarr() {
        assertThat(connecteur.providerId()).isEqualTo("dolibarr");
    }

    @Test
    void creeLeTiersLeContactPuisLOpportunite() {
        CrmSyncResult resultat = connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.appels)
                .containsExactly("tiers", "contact", "recherche", "opportunite", "responsable");
        assertThat(resultat.accountRef()).isEqualTo("42");
        assertThat(resultat.contactRef()).isEqualTo("77");
        assertThat(resultat.opportunityRef()).isEqualTo("99");
        assertThat(resultat.taskRef()).isNull();
        assertThat(resultat.providerId()).isEqualTo("dolibarr");
    }

    @Test
    void rattacheLeContactAuTiersEtMarqueLeTiersProspect() {
        connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.corpsTiers).containsEntry("name", "Acme");
        assertThat(transport.corpsTiers).containsEntry("client", "2");
        assertThat(transport.corpsContact).containsEntry("socid", "42");
        assertThat(transport.corpsContact).containsEntry("lastname", "Bensalem");
        assertThat(transport.corpsOpportunite).containsEntry("socid", "42");
    }

    /**
     * Releve de la sonde : sans {@code ref}, Dolibarr repond 400 ; en doublon, 500. Elle est
     * desormais celle du lead, donc identique d'une tentative a l'autre.
     */
    @Test
    void envoieLaReferenceDuLeadCommeRefDOpportunite() {
        connecteur.sync(leadAvecReference("LF-3F2A9C1B7D4E", "Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.corpsOpportunite).containsEntry("ref", "LF-3F2A9C1B7D4E");
        assertThat(transport.refCherchee).isEqualTo("LF-3F2A9C1B7D4E");
    }

    /**
     * Le cas que la reference stable rend traitable : la reponse de Dolibarr s'est perdue
     * apres la creation, l'etat anterieur ignore donc l'opportunite, et le rejeu la
     * retrouve au lieu de se heurter a {@code uk_projet_ref}.
     */
    @Test
    void adopteLOpportuniteExistanteAuLieuDenCreerUneSeconde() {
        transport.opportuniteExistante = "42";

        CrmSyncResult resultat = connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.appels).containsExactly("tiers", "contact", "recherche");
        assertThat(resultat.opportunityRef()).isEqualTo("42");
    }

    @Test
    void assigneLeCommercialALOpportuniteParUnAppelDedie() {
        connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.responsableLie).isEqualTo("9");
    }

    @Test
    void nommeLeTiersDApresLaPersonneQuandAucuneSocieteNEstFournie() {
        connecteur.sync(lead(null), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.corpsTiers).containsEntry("name", "Amina Bensalem");
    }

    @Test
    void neRecreeRienDeCeQuiExisteDeja() {
        CrmSyncResult resultat =
                connecteur.sync(lead("Acme"), CIBLE, new CrmSyncState("42", "77", null));

        assertThat(transport.appels).containsExactly("recherche", "opportunite", "responsable");
        assertThat(resultat.accountRef()).isEqualTo("42");
        assertThat(resultat.contactRef()).isEqualTo("77");
    }

    @Test
    void neFaitAucunAppelQuandToutExisteDeja() {
        CrmSyncResult resultat =
                connecteur.sync(lead("Acme"), CIBLE, new CrmSyncState("42", "77", "99"));

        assertThat(transport.appels).isEmpty();
        assertThat(resultat.opportunityRef()).isEqualTo("99");
    }

    @Test
    void enrichitLExceptionDeCeQuiAvaitDejaEteCree() {
        transport.echoueSurOpportunite = true;

        assertThatThrownBy(() -> connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE))
                .isInstanceOf(CrmSyncException.class)
                .satisfies(echec -> {
                    CrmSyncState partiel = ((CrmSyncException) echec).partialState();
                    assertThat(partiel.accountRef()).isEqualTo("42");
                    assertThat(partiel.contactRef()).isEqualTo("77");
                    assertThat(partiel.opportunityRef()).isNull();
                });
    }

    @Test
    void resoutLeCommercialParSonEmail() {
        String reference = connecteur.resolveAssignee(
                new CrmAssignee("Amina Bensalem", "amina@demo.test"), CIBLE);

        assertThat(reference).isEqualTo("9");
        assertThat(transport.appels).containsExactly("utilisateur");
    }
}
