-- F14, revue finale : le drapeau raw_lead_event.signed_with_previous_secret est permanent,
-- il decrit une fenetre passee, jamais la fenetre courante. Sans borne, la fiche d'une
-- boutique qui a deja tourne son secret une premiere fois puis ferme sa fenetre continue de
-- montrer le vieux lead retardataire a la rotation suivante — le feu vert « plus aucun lead
-- signe avec l'ancien secret » ne peut alors plus jamais s'afficher pour cette boutique.
--
-- previous_secret_since ancre le debut de la fenetre courante : la lecture ne cherche plus
-- dans tout l'historique, seulement depuis cette date.
ALTER TABLE client
    ADD COLUMN previous_secret_since TIMESTAMPTZ;

-- Nullable et sans remplissage retroactif, comme ses deux voisines de V11. Les trois colonnes
-- vont desormais ensemble : posees par la rotation, effacees par la revocation, toujours dans
-- la meme transaction. Une alternative avait ete envisagee — deriver la borne par
-- previous_secret_expires_at moins leadflow.webhook.transition-secret — et ecartee : elle
-- deviendrait fausse des que ce reglage change entre la rotation et la lecture, et ce signal
-- sert precisement a decider de revoquer un secret ; il doit etre exact, pas presque juste.
--
-- Aucun index : la lecture filtre toujours sur client_id et trie par received_at, ce que
-- idx_raw_lead_event_client_received de V2 sert deja.
