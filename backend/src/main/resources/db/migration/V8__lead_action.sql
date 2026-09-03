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
-- Le nettoyage prealable, sans lequel l'index ci-dessous refuse de se creer sur toute base
-- ayant de l'historique. Le cas s'observe en developpement : un meme lead peut porter deux
-- morts PENDING, de charges utiles differentes, mortes a quelques secondes d'ecart sans
-- rejeu entre elles — le relais republie avant que la premiere mort ne soit journalisee, et
-- le message republie meurt a son tour. Le garde-fou Java est au mieux, pas atomique.
--
-- On garde la mort la plus recente : c'est celle dont la charge utile serait rejouee, et
-- c'est la seule que l'operateur ait a traiter. Les plus anciennes passent DISCARDED plutot
-- que d'etre supprimees — le journal des morts est une trace, et l'effacer ferait disparaitre
-- l'incident au lieu de le clore. C'est le meme geste que le rattrapage deja fait a
-- l'execution par DeadLetterListener depuis F10.
UPDATE dead_letter d
   SET status = 'DISCARDED'
 WHERE d.status = 'PENDING'
   AND d.lead_id IS NOT NULL
   AND EXISTS (SELECT 1
                 FROM dead_letter plus_recente
                WHERE plus_recente.lead_id = d.lead_id
                  AND plus_recente.status = 'PENDING'
                  AND plus_recente.dead_at > d.dead_at);

CREATE UNIQUE INDEX uq_dead_letter_lead_pending
    ON dead_letter (lead_id) WHERE lead_id IS NOT NULL AND status = 'PENDING';
