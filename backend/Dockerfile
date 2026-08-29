# Etage 1 — build. Le pom est copie SEUL avant le source : la couche de dependances ne se
# reconstruit alors que si le pom change, ce qui fait la difference entre une minute et
# dix a chaque modification de code.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# -DskipTests, et c'est delibere : ./mvnw test demarre des conteneurs Postgres et RabbitMQ
# par Testcontainers, or ce build tourne deja dans Docker. Construire l'image et eprouver
# le code sont deux gestes distincts — c'est la CI de F11.3 qui les enchainera.
RUN mvn -B -q package -DskipTests

# Etage 2 — runtime. Ni JDK, ni Maven, ni depot .m2 : seulement un JRE et le jar.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S leadflow && adduser -S leadflow -G leadflow
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER leadflow
# 8090 et non 8080, dans le conteneur comme ailleurs : le port est une propriete du
# projet, pas une consequence du poste de developpement.
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
