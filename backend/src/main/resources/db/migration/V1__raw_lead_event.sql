-- Journal d'entree du middleware : chaque payload webhook accepte est stocke tel quel
-- AVANT toute qualification, afin de pouvoir rejouer un lead perdu ou mal traite.
-- Le schema metier (lead qualifie, commercial, synchronisation Dolibarr) s'ajoute
-- dans des migrations V2+ au fur et a mesure de l'implementation.

CREATE TABLE raw_lead_event (
    id              UUID         PRIMARY KEY,
    source          VARCHAR(120) NOT NULL,
    payload         JSONB        NOT NULL,
    signature       VARCHAR(255) NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    status          VARCHAR(32)  NOT NULL DEFAULT 'RECEIVED',
    failure_reason  TEXT
);

CREATE INDEX idx_raw_lead_event_received_at ON raw_lead_event (received_at DESC);
CREATE INDEX idx_raw_lead_event_status ON raw_lead_event (status);
