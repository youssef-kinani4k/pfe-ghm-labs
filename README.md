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
3. **Routage & synchronisation** — attribution du commercial (geo, secteur ou round-robin),
   puis creation du tiers, du contact et de l'opportunite dans l'ERP via le connecteur
   configure. La tache d'agenda et l'alerte lead chaud restent a ecrire.
4. **Monitoring** — API REST authentifiee par jeton et dashboard Angular : flux temps reel,
   conversion, etat des files, journal des messages morts rejouable, sante des connecteurs.

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

Le profil `dev` porte un compte de demonstration : **`admin` / `leadflow-demo-2026`**. Hors
`dev`, le compte et la cle de signature des jetons viennent de l'environnement — voir
`docs/monitoring-api.md`.

## Documentation

| Document                       | Contenu                                              |
| ------------------------------ | ---------------------------------------------------- |
| `docs/webhook-integration.md`  | Contrat du webhook de capture, signature HMAC        |
| `docs/monitoring-api.md`       | API du dashboard : jeton, lecture, rejeu, flux SSE   |
| `docs/erp-integration-setup.md`| Mise en route de Dolibarr et Odoo pour les tests     |
| `CLAUDE.md`                    | Architecture et invariants a ne pas casser           |
