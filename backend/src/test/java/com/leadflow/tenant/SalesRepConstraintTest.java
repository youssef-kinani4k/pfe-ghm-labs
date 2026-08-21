package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SalesRepConstraintTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private SalesRepRepository salesRepRepository;

    private Client clientEnregistre(String publicKey) {
        Client client = new Client();
        client.setPublicKey(publicKey);
        client.setName("Site de demonstration");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081"));
        return clientRepository.saveAndFlush(client);
    }

    private SalesRep commercial(Client client, String email) {
        SalesRep rep = new SalesRep();
        rep.setClient(client);
        rep.setFullName("Amina Bensalem");
        rep.setEmail(email);
        return rep;
    }

    @Test
    void refuseDeuxCommerciauxDeMemeEmailChezUnMemeClient() {
        Client client = clientEnregistre("cle-contrainte-1");
        salesRepRepository.saveAndFlush(commercial(client, "amina@exemple.test"));

        assertThatThrownBy(
                        () -> salesRepRepository.saveAndFlush(commercial(client, "amina@exemple.test")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accepteLeMemeEmailChezDeuxClientsDifferents() {
        Client premier = clientEnregistre("cle-contrainte-2");
        Client second = clientEnregistre("cle-contrainte-3");

        salesRepRepository.saveAndFlush(commercial(premier, "partage@exemple.test"));
        salesRepRepository.saveAndFlush(commercial(second, "partage@exemple.test"));

        assertThat(salesRepRepository.findByClientIdAndActiveTrue(second.getId())).hasSize(1);
    }

    @Test
    void ignoreUnCommercialDesactive() {
        Client client = clientEnregistre("cle-contrainte-4");
        SalesRep inactif = commercial(client, "inactif@exemple.test");
        inactif.setActive(false);
        salesRepRepository.saveAndFlush(inactif);

        assertThat(salesRepRepository.findByClientIdAndActiveTrue(client.getId())).isEmpty();
    }

    @Test
    void supprimerUnClientSupprimeSesCommerciaux() {
        Client client = clientEnregistre("cle-contrainte-5");
        salesRepRepository.saveAndFlush(commercial(client, "cascade@exemple.test"));
        UUID clientId = client.getId();

        clientRepository.delete(client);
        clientRepository.flush();

        assertThat(salesRepRepository.findByClientIdAndActiveTrue(clientId)).isEmpty();
    }
}
