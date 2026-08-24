-- Journal des messages morts. Remplace la DLQ RabbitMQ comme source de verite des leads
-- en echec : une file ne sait ni paginer, ni filtrer, ni trier, ni conserver un motif.
-- La DLQ reste le tuyau par lequel les morts arrivent, et sa profondeur doit rester nulle.
CREATE TABLE dead_letter (
    id              UUID        PRIMARY KEY,
    origin_queue    VARCHAR(80) NOT NULL,
    routing_key     VARCHAR(80) NOT NULL,
    -- Les octets d'origine, jamais deserialises puis re-serialises : un message illisible
    -- doit etre journalise quand meme, et un rejeu doit renvoyer exactement ce qui est
    -- parti.
    payload         TEXT        NOT NULL,
    content_type    VARCHAR(80),
    -- En-tete __TypeId__ du message. Sans lui, le convertisseur ne saurait pas dans quelle
    -- classe deserialiser au rejeu.
    type_id         VARCHAR(255),
    -- Ni client_id ni lead_id ne portent de cle etrangere : le message peut etre corrompu
    -- ou designer un client disparu, et une contrainte ferait echouer l'ecriture du journal
    -- exactement quand on en a le plus besoin. Le prix assume est une reference qui peut ne
    -- designer personne — l'ecran l'affiche telle quelle.
    client_id       UUID,
    lead_id         UUID,
    failure_reason  TEXT,
    dead_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    status          VARCHAR(32) NOT NULL
        CONSTRAINT ck_dead_letter_status CHECK (status IN ('PENDING','REPLAYED','DISCARDED')),
    replayed_at     TIMESTAMPTZ,
    replayed_by     VARCHAR(120)
);

-- Tri par defaut de l'ecran : les morts en attente, du plus recent au plus ancien.
CREATE INDEX idx_dead_letter_status_dead_at ON dead_letter (status, dead_at DESC);

-- Sert le garde-fou des filets de republication (T12) : un lead deja mort et en attente
-- d'action humaine n'est pas republie automatiquement.
CREATE INDEX idx_dead_letter_lead ON dead_letter (lead_id) WHERE lead_id IS NOT NULL;
