-- Schema metier multi-tenant. L'ordre de creation est impose par les cles etrangeres :
-- client, puis sales_rep, puis l'evolution de raw_lead_event, puis lead, puis les traces
-- de synchronisation.

CREATE TABLE client (
    id                  UUID         PRIMARY KEY,
    public_key          VARCHAR(64)  NOT NULL UNIQUE,
    name                VARCHAR(160) NOT NULL,
    -- Chiffre AES-256-GCM par l'application. Jamais lisible en SQL.
    hmac_secret         TEXT         NOT NULL,
    -- Cle du connecteur, resolue au runtime par CrmConnectorRegistry. Volontairement
    -- sans contrainte CHECK : ajouter un ERP ne doit demander aucune migration.
    crm_provider_id     VARCHAR(40)  NOT NULL,
    -- Document JSON chiffre : parametres de connexion a l'instance ERP de ce client.
    crm_config          TEXT         NOT NULL,
    assignment_strategy VARCHAR(32)  NOT NULL DEFAULT 'ROUND_ROBIN'
        CONSTRAINT ck_client_assignment_strategy
        CHECK (assignment_strategy IN ('ROUND_ROBIN', 'GEOGRAPHIC', 'SECTOR')),
    -- Regles de scoring propres au client. Non chiffre : ce n'est pas un secret.
    -- La forme du document est definie par F3.
    scoring_config      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sales_rep (
    id         UUID         PRIMARY KEY,
    client_id  UUID         NOT NULL REFERENCES client (id) ON DELETE CASCADE,
    full_name  VARCHAR(160) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    -- Reference du commercial dans l'ERP du client, inconnue tant qu'elle n'est pas
    -- resolue (F4/F5).
    crm_ref    VARCHAR(64),
    sector     VARCHAR(80),
    zone       VARCHAR(80),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_sales_rep_client_email UNIQUE (client_id, email)
);

CREATE INDEX idx_sales_rep_client_active ON sales_rep (client_id, active);

-- raw_lead_event existe depuis V1. On lui ajoute son tenant. La colonne est posee
-- directement NOT NULL : la table est vide et rien n'est deploye.
ALTER TABLE raw_lead_event
    ADD COLUMN client_id UUID NOT NULL REFERENCES client (id);

CREATE INDEX idx_raw_lead_event_client_received ON raw_lead_event (client_id, received_at DESC);

CREATE TABLE lead (
    id                    UUID         PRIMARY KEY,
    client_id             UUID         NOT NULL REFERENCES client (id),
    -- Unique : un evenement brut ne peut produire qu'un seul lead. C'est la base, et
    -- non le consommateur RabbitMQ, qui garantit l'idempotence de la qualification.
    raw_event_id          UUID         NOT NULL UNIQUE REFERENCES raw_lead_event (id),
    company_name          VARCHAR(160),
    first_name            VARCHAR(80),
    last_name             VARCHAR(80),
    email                 VARCHAR(255) NOT NULL,
    phone                 VARCHAR(32),
    message               TEXT,
    detected_intent       VARCHAR(64),
    -- Trace quel analyseur a repondu : rend le mode degrade observable.
    intent_source         VARCHAR(32)
        CONSTRAINT ck_lead_intent_source CHECK (intent_source IN ('RULES', 'GEMINI')),
    score                 INTEGER      NOT NULL DEFAULT 0,
    status                VARCHAR(32)  NOT NULL
        CONSTRAINT ck_lead_status
        CHECK (status IN ('QUALIFIED', 'ROUTED', 'SYNCED', 'REJECTED', 'FAILED')),
    assigned_sales_rep_id UUID         REFERENCES sales_rep (id),
    country_code          VARCHAR(2),
    sector                VARCHAR(80),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Deduplication de F3 : meme client, meme email, dans une fenetre temporelle.
CREATE INDEX idx_lead_client_email_created ON lead (client_id, email, created_at DESC);
-- Compteurs du dashboard de F6.
CREATE INDEX idx_lead_client_status ON lead (client_id, status);

CREATE TABLE crm_sync_attempt (
    id              UUID        PRIMARY KEY,
    lead_id         UUID        NOT NULL REFERENCES lead (id) ON DELETE CASCADE,
    -- Porte par la ligne et non deduit du client : si un client change d'ERP,
    -- l'historique reste lisible.
    provider_id     VARCHAR(40) NOT NULL,
    status          VARCHAR(32) NOT NULL
        CONSTRAINT ck_crm_sync_attempt_status CHECK (status IN ('SUCCESS', 'FAILED')),
    -- Typees en texte : Dolibarr et Odoo renvoient des entiers, d'autres ERP des UUID.
    account_ref     VARCHAR(64),
    contact_ref     VARCHAR(64),
    opportunity_ref VARCHAR(64),
    task_ref        VARCHAR(64),
    error_message   TEXT,
    attempted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_crm_sync_attempt_lead ON crm_sync_attempt (lead_id, attempted_at DESC);
