package com.leadflow.crm.dolibarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;

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
        private String responsableRetire;
        private String refCherchee;
        private String emailTiersCherche;
        private String tiersExistant;
        private String opportuniteExistante;
        private boolean echoueSurOpportunite;
        private boolean echoueSurResponsable;

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
        public String chercheTiersParEmail(CrmTarget target, String email) {
            appels.add("recherche-tiers");
            emailTiersCherche = email;
            return tiersExistant;
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
            if (echoueSurResponsable) {
                throw new CrmSyncException("dolibarr", "affectation refusee", null);
            }
            responsableLie = utilisateurRef;
        }

        @Override
        public void retireResponsable(CrmTarget target, String opportuniteRef, String utilisateurRef) {
            appels.add("retrait");
            responsableRetire = utilisateurRef;
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
                .containsExactly(
                        "recherche-tiers", "tiers", "contact", "recherche", "opportunite",
                        "responsable");
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
     * retrouve au lieu de se heurter a {@code uk_projet_ref}. L'attribution reste une etape
     * a part entiere : la trouver par recherche ne la dispense pas.
     */
    @Test
    void adopteLOpportuniteExistanteAuLieuDenCreerUneSeconde() {
        transport.opportuniteExistante = "42";

        CrmSyncResult resultat = connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.appels)
                .containsExactly("recherche-tiers", "tiers", "contact", "recherche", "responsable");
        assertThat(resultat.opportunityRef()).isEqualTo("42");
        assertThat(resultat.assigneeRef()).isEqualTo("9");
    }

    /**
     * Le tiers se cherche par courriel avant d'etre cree, comme l'opportunite se cherche par
     * sa {@code ref}. Sans cette recherche, un prospect qui revient par un second formulaire
     * ouvrait un doublon de tiers chez Dolibarr, et un rejeu apres une reponse perdue en
     * ouvrait un troisieme — la seule etape de {@code sync} qui n'etait protegee ni par une
     * reference stable ni par la deduplication de l'ERP.
     */
    @Test
    void chercheLeTiersParCourrielAvantDenCreerUn() {
        connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.emailTiersCherche).isEqualTo("amina@acme.test");
        assertThat(transport.appels).startsWith("recherche-tiers", "tiers");
    }

    @Test
    void adopteLeTiersExistantAuLieuDenCreerUnSecond() {
        transport.tiersExistant = "58";

        CrmSyncResult resultat = connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.appels).doesNotContain("tiers");
        assertThat(resultat.accountRef()).isEqualTo("58");
        // Le contact et l'opportunite se rattachent au tiers trouve, pas a un tiers neuf.
        assertThat(transport.corpsContact).containsEntry("socid", "58");
        assertThat(transport.corpsOpportunite).containsEntry("socid", "58");
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
                connecteur.sync(lead("Acme"), CIBLE, new CrmSyncState("42", "77", null, null));

        assertThat(transport.appels).containsExactly("recherche", "opportunite", "responsable");
        assertThat(resultat.accountRef()).isEqualTo("42");
        assertThat(resultat.contactRef()).isEqualTo("77");
    }

    @Test
    void neFaitAucunAppelQuandToutExisteDeja() {
        CrmSyncResult resultat =
                connecteur.sync(lead("Acme"), CIBLE, new CrmSyncState("42", "77", "99", "9"));

        assertThat(transport.appels).isEmpty();
        assertThat(resultat.opportunityRef()).isEqualTo("99");
        assertThat(resultat.assigneeRef()).isEqualTo("9");
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

    /**
     * Le defaut que cette tache corrige. L'attribution echoue apres une creation reussie ;
     * l'etat partiel porte alors l'opportunite mais pas le responsable, et le rejeu doit
     * attribuer SANS recreer.
     */
    @Test
    void leRejeuAttribueSansRecreerLOpportunite() {
        CrmSyncState apresEchec = new CrmSyncState("42", "77", "99", null);

        CrmSyncResult resultat = connecteur.sync(lead("Acme"), CIBLE, apresEchec);

        assertThat(transport.appels).containsExactly("responsable");
        assertThat(resultat.opportunityRef()).isEqualTo("99");
        assertThat(resultat.assigneeRef()).isEqualTo("9");
    }

    /** Une fois l'attribution faite, un rejeu ne la refait pas : aucun appel n'est attendu. */
    @Test
    void uneAttributionDejaFaiteNEstPasRefaite() {
        CrmSyncState complet = new CrmSyncState("42", "77", "99", "9");

        CrmSyncResult resultat = connecteur.sync(lead("Acme"), CIBLE, complet);

        assertThat(transport.appels).isEmpty();
        assertThat(resultat.assigneeRef()).isEqualTo("9");
    }

    /** L'echec de l'attribution rend un etat partiel qui porte l'opportunite mais pas le responsable. */
    @Test
    void lEchecDeLAttributionRendUnEtatPartielSansResponsable() {
        transport.echoueSurResponsable = true;
        CrmSyncState apresEchec = new CrmSyncState("42", "77", "99", null);

        assertThatThrownBy(() -> connecteur.sync(lead("Acme"), CIBLE, apresEchec))
                .isInstanceOf(CrmSyncException.class)
                .asInstanceOf(throwable(CrmSyncException.class))
                .satisfies(echec -> {
                    assertThat(echec.partialState().opportunityRef()).isEqualTo("99");
                    assertThat(echec.partialState().assigneeRef()).isNull();
                });
    }

    @Test
    void resoutLeCommercialParSonEmail() {
        String reference = connecteur.resolveAssignee(
                new CrmAssignee("Amina Bensalem", "amina@demo.test"), CIBLE);

        assertThat(reference).isEqualTo("9");
        assertThat(transport.appels).containsExactly("utilisateur");
    }

    @Test
    void reaffecteRelieLeResponsableAuProjetExistant() {
        TransportFactice transport = new TransportFactice();
        DolibarrConnector connecteur = new DolibarrConnector(transport);

        connecteur.reaffecte(new CrmSyncState("42", "77", "301", "7"), "9", CIBLE);

        assertThat(transport.responsableLie).isEqualTo("9");
        assertThat(transport.responsableRetire).isEqualTo("7");
        // Aucun appel de creation, et l'ORDRE est porteur de sens : on pose le nouveau lien
        // avant de retirer l'ancien. Une panne entre les deux laisse le bon responsable
        // present en plus de l'ancien — l'etat d'avant ce correctif, donc sans regression ;
        // l'ordre inverse pourrait laisser la fiche sans aucun chef de projet.
        assertThat(transport.appels).containsExactly("responsable", "retrait");
    }

    @Test
    void reaffecteNeRetireRienQuandLErpNePortaitAucunResponsable() {
        TransportFactice transport = new TransportFactice();
        DolibarrConnector connecteur = new DolibarrConnector(transport);

        // assigneeRef nul : la synchronisation d'origine n'avait lie personne. Il n'y a
        // alors aucun ancien lien a retirer, et inventer un segment d'URL a partir de null
        // ferait un appel absurde.
        connecteur.reaffecte(new CrmSyncState("42", "77", "301", null), "9", CIBLE);

        assertThat(transport.responsableLie).isEqualTo("9");
        assertThat(transport.appels).containsExactly("responsable");
    }

    @Test
    void reaffecteNeRetireRienQuandLeResponsableEstDejaLeBon() {
        TransportFactice transport = new TransportFactice();
        DolibarrConnector connecteur = new DolibarrConnector(transport);

        // Ancien et nouveau responsables confondus : retirer le lien qu'on vient de poser
        // laisserait la fiche sans chef de projet. Le cas n'est pas theorique — la livraison
        // est at-least-once, donc une meme reaffectation peut etre rejouee apres coup.
        connecteur.reaffecte(new CrmSyncState("42", "77", "301", "9"), "9", CIBLE);

        assertThat(transport.appels).containsExactly("responsable");
    }

    @Test
    void reaffecteRefuseUnLeadSansOpportunite() {
        TransportFactice transport = new TransportFactice();
        DolibarrConnector connecteur = new DolibarrConnector(transport);

        assertThatThrownBy(() ->
                        connecteur.reaffecte(CrmSyncState.VIERGE, "9", CIBLE))
                .isInstanceOf(CrmSyncException.class);
    }
}
