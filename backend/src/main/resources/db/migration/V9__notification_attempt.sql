-- Trace des notifications envoyees au commercial. Calquee sur crm_sync_attempt : meme role,
-- meme forme, meme raison d'exister. Sans elle, « le commercial a-t-il ete prevenu ? » n'a
-- pas de reponse — et c'est la question qu'on posera a la premiere reclamation.
CREATE TABLE notification_attempt (
    id            UUID         PRIMARY KEY,
    lead_id       UUID         NOT NULL REFERENCES lead (id) ON DELETE CASCADE,
    -- Porte par la ligne et non deduit de la configuration : si l'instance change de canal,
    -- l'historique reste lisible. Meme parti que crm_sync_attempt.provider_id.
    channel       VARCHAR(40)  NOT NULL,
    -- L'adresse telle qu'elle a servi, et non celle que sales_rep porte aujourd'hui : un
    -- commercial qui change d'e-mail ne doit pas reecrire l'histoire.
    recipient     VARCHAR(255) NOT NULL,
    -- Sans cle etrangere, deliberement : une suppression de commercial ne doit pas effacer
    -- la trace. Meme parti que lead_action.previous_sales_rep_id.
    sales_rep_id  UUID,
    -- IGNOREE est un statut, pas une absence de ligne. Un lead sous le seuil de sa boutique
    -- ecrit quand meme sa trace : c'est ce qui permet de repondre « score 55, seuil 70 » a
    -- la question « pourquoi n'ai-je pas ete prevenu ? ». Ne rien ecrire rendrait le silence
    -- indistinguable d'une panne.
    status        VARCHAR(32)  NOT NULL
        CONSTRAINT ck_notification_attempt_status
        CHECK (status IN ('ENVOYEE', 'ECHEC', 'IGNOREE')),
    -- Le score au moment de la decision et le seuil qui l'a tranchee, figes dans la ligne.
    -- Le seuil se regle depuis l'ecran « Bareme » : sans ces deux colonnes, le deplacer
    -- ferait mentir tout l'historique. C'est la lecon de lead.score, fige a la qualification
    -- pour exactement la meme raison.
    score         INTEGER      NOT NULL,
    seuil         INTEGER      NOT NULL,
    error_message TEXT,
    attempted_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- La chronologie d'un lead lit ses notifications de la plus ancienne a la plus recente, et
-- le journal des envois se consulte du plus recent au plus ancien. Un seul index sert les
-- deux : Postgres le parcourt dans les deux sens.
CREATE INDEX idx_notification_attempt_lead
    ON notification_attempt (lead_id, attempted_at DESC);
