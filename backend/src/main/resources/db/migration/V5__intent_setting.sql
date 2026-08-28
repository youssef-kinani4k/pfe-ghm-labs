-- Reglage de l'analyse d'intention, jusqu'ici fige au demarrage par la variable
-- d'environnement GEMINI_API_KEY. Le porter en base permet de le changer depuis la console
-- sans redeploiement, comme toute autre operation courante de l'agence.
--
-- Ligne unique et non magasin cle/valeur generique : un seul reglage existe, et une table
-- generique inviterait a y ranger n'importe quoi sans contrainte ni type. La contrainte sur
-- l'identifiant rend la seconde ligne impossible plutot que de compter sur la discipline du
-- code appelant.
CREATE TABLE intent_setting (
    id         SMALLINT    PRIMARY KEY
        CONSTRAINT ck_intent_setting_ligne_unique CHECK (id = 1),
    -- Chiffree au repos en AES-256-GCM par EncryptedStringConverter, comme
    -- client.hmac_secret : la cle d'API est facturee a l'agence, un acces en lecture a la
    -- base ne doit pas suffire a s'en servir. NULL signifie « aucune cle en base », et le
    -- repli sur la variable d'environnement s'applique.
    api_key    TEXT,
    -- L'interrupteur de l'ecran. A false, la qualification reste en mode RULES sans que la
    -- cle soit effacee : couper l'analyse ne doit pas obliger a la ressaisir.
    enabled    BOOLEAN     NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
