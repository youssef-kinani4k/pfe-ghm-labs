# LeadFlow

Middleware qui relie les canaux marketing (formulaires, landing pages) a l'outillage
commercial (ERP/CRM). Un prospect capture sur un site client est valide, qualifie, score,
puis pousse automatiquement dans l'ERP cible et attribue a un commercial.

L'ERP n'est pas cable en dur : la synchronisation passe par un connecteur enfichable.
**Dolibarr** et **Odoo** sont les cibles de reference ; en ajouter une autre consiste a
implementer une interface, sans toucher au reste du pipeline.

## Pipeline

1. **Capture** — webhook signe HMAC-SHA256, payload persiste puis publie sur RabbitMQ.
2. **Qualification** — nettoyage email/telephone, deduplication, analyse d'intention (NLP), scoring.
3. **Routage & synchronisation** — creation du tiers, du contact et de l'opportunite dans
   l'ERP via le connecteur configure, attribution du commercial (geo, secteur ou
   round-robin), tache d'agenda et alerte lead chaud.
4. **Monitoring** — dashboard Angular : flux temps reel, conversion, etat de la file et de la DLQ.

## Stack

| Couche      | Technologie                                     |
| ----------- | ----------------------------------------------- |
| Frontend    | Angular 20 (standalone, signals, SCSS)          |
| Backend     | Spring Boot 4.1 / Java 21 / Maven               |
| Persistance | PostgreSQL 16 + Flyway                          |
| Messaging   | RabbitMQ 3 (avec dead-letter queue)             |
| ERP cibles  | Dolibarr (REST), Odoo (JSON-RPC) — enfichables  |

## Demarrage

```bash
cp .env.example .env          # renseigner LEADFLOW_MASTER_KEY (openssl rand -base64 32)
docker compose up -d          # Postgres + RabbitMQ
# ERP de test au besoin : docker compose --profile dolibarr up -d  (ou --profile odoo)
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm start      # http://localhost:4200, proxy /api -> :8090
```
