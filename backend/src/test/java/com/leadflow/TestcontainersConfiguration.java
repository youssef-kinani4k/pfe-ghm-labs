package com.leadflow;

import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/**
	 * Le delai d'attente par defaut de Testcontainers (60 s) est trop court sur un poste
	 * charge : la suite ouvre plusieurs contextes, donc plusieurs conteneurs, et RabbitMQ
	 * met parfois plus d'une minute a annoncer « Server startup complete ». Le symptome
	 * est un ContainerLaunchException sans rapport avec le code teste. Allonger l'attente
	 * ne ralentit rien quand le demarrage est rapide : c'est un plafond, pas une pause.
	 */
	private static final Duration DEMARRAGE = Duration.ofMinutes(3);

	@Bean
	@ServiceConnection
	public PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
				.withStartupTimeout(DEMARRAGE);
	}

	@Bean
	@ServiceConnection
	public RabbitMQContainer rabbitContainer() {
		return new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine"))
				.withStartupTimeout(DEMARRAGE);
	}

}
