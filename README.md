# LeadFlow

Middleware qui relie les canaux marketing (formulaires, landing pages) a l'outillage
commercial (Dolibarr). Un prospect capture sur un site client est valide, qualifie,
score, puis pousse automatiquement dans l'ERP et attribue a un commercial.

## Pipeline

1. **Capture** — webhook signe HMAC-SHA256, payload persiste puis publie sur RabbitMQ.
2. **Qualification** — nettoyage email/telephone, deduplication, analyse d'intention (NLP), scoring.
3. **Routage & synchronisation** — creation Tiers / Contact / Opportunite dans Dolibarr,
   attribution du commercial (geo, secteur ou round-robin), tache d'agenda et alerte lead chaud.
4. **Monitoring** — dashboard Angular : flux temps reel, conversion, etat de la file et de la DLQ.

## Stack

| Couche      | Technologie                                     |
| ----------- | ----------------------------------------------- |
| Frontend    | Angular 20 (standalone, signals, SCSS)          |
| Backend     | Spring Boot 4.1 / Java 21 / Maven               |
| Persistance | PostgreSQL 16 + Flyway                          |
| Messaging   | RabbitMQ 3 (avec dead-letter queue)             |
| ERP cible   | Dolibarr (API REST)                             |

## Demarrage

```bash
cp .env.example .env          # renseigner WEBHOOK_HMAC_SECRET et DOLIBARR_API_KEY
docker compose up -d          # Postgres + RabbitMQ
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm start      # http://localhost:4200, proxy /api -> :8080
```
