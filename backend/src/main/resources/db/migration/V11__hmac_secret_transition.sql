-- F14 : une rotation de secret ne doit plus couper la capture. L'ancien secret reste
-- accepte pendant une fenetre bornee, le temps que la boutique mette son site a jour.
--
-- Nullables et sans remplissage retroactif, comme V7 : aucune boutique existante n'est en
-- transition, et l'absence de valeur est exactement le fait a representer. Les deux vont
-- toujours ensemble — un secret precedent sans date d'expiration serait un secret
-- permanent, soit l'inverse de la feature.
ALTER TABLE client
    -- Chiffre AES-256-GCM par l'application, comme hmac_secret. Jamais lisible en SQL.
    ADD COLUMN previous_hmac_secret       TEXT,
    ADD COLUMN previous_secret_expires_at TIMESTAMPTZ;

-- Pose a l'insertion de la ligne, qui a lieu de toute facon : le chemin chaud ne paie
-- aucune ecriture de plus. C'est ce qui permet a l'ecran de repondre « plus aucun lead
-- signe avec l'ancien secret », donc de dire quand la revocation est sans risque.
ALTER TABLE raw_lead_event
    ADD COLUMN signed_with_previous_secret BOOLEAN NOT NULL DEFAULT false;

-- Aucun index ajoute : la seule requete de lecture filtre sur client_id et trie par
-- received_at, ce que idx_raw_lead_event_client_received de V2 sert deja.
