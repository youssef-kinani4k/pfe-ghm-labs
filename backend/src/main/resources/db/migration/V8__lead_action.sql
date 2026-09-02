-- Journal des actions humaines portees par un lead : reattribution, rejeu, ecart.
-- Il repond a une question qu'aucune table ne sait tenir aujourd'hui — qui a change quoi,
-- quand, et pourquoi. dead_letter ne retient d'un rejeu que replayed_by et replayed_at,
-- jamais son motif ni son resultat.
CREATE TABLE lead_action (
    id                    UUID         PRIMARY KEY,
    -- Cle etrangere, contrairement a dead_letter.lead_id : l'action part toujours d'un lead
    -- qu'on vient de lire, jamais d'un message corrompu.
    lead_id               UUID         NOT NULL REFERENCES lead(id),
    action                VARCHAR(32)  NOT NULL
        CONSTRAINT ck_lead_action_action CHECK (action IN ('REATTRIBUTION','REJEU','ECART')),
    -- Sujet du jeton JWT : l'operateur unique du dashboard.
    actor                 VARCHAR(120) NOT NULL,
    reason                TEXT         NOT NULL,
    -- Sans cle etrangere, deliberement : un commercial supprime ne doit pas effacer
    -- l'histoire. Le journal affiche l'identifiant tel quel.
    previous_sales_rep_id UUID,
    new_sales_rep_id      UUID,
    -- Nul pour une reattribution. Sans lui, la timeline afficherait deux fois un meme
    -- rejeu : une fois derive de dead_letter.replayed_at, une fois lu ici.
    dead_letter_id        UUID,
    -- Une reattribution vaut toujours SUCCES : la ligne n'est ecrite qu'apres le commit de
    -- l'ecriture, donc un echec ne produit aucune ligne. ECHEC ne concerne que le rejeu.
    outcome               VARCHAR(16)  NOT NULL
        CONSTRAINT ck_lead_action_outcome CHECK (outcome IN ('SUCCES','ECHEC')),
    detail                TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_lead_action_lead ON lead_action (lead_id, created_at);

-- La dette de F6, payee ici parce qu'on ecrit la migration de toute facon. Partiel et non
-- global sur le contenu : la livraison etant at-least-once, une meme mort livree deux fois
-- ecrirait deux lignes — mais une seconde mort APRES un rejeu qui a de nouveau echoue est
-- un fait reel qu'il faut garder. Restreindre aux lignes PENDING distingue ces deux cas, et
-- aligne le schema sur le garde-fou deja ecrit en Java : QualifiedLeadRelay et
-- RoutedLeadRelay ignorent les leads portant une mort PENDING.
CREATE UNIQUE INDEX uq_dead_letter_lead_pending
    ON dead_letter (lead_id) WHERE lead_id IS NOT NULL AND status = 'PENDING';
