-- V10 : les deux index dont les series quotidiennes de F13 ont besoin.
--
-- Aucune colonne, aucune table : F13 ne fait que lire ce qui existe, comme la chronologie
-- de F9 qui recompose six tables sans en creer aucune.
--
-- Un troisieme index aurait ete necessaire pour la courbe de volume, mais
-- idx_raw_lead_event_client_received (client_id, received_at DESC) existe depuis V2 et la
-- sert deja.

-- La serie de volume et celle des intentions balaient `lead` par date. L'index existant est
-- (client_id, email, created_at) : la colonne `email` au milieu le rend inutilisable pour un
-- regroupement par jour, Postgres ne pouvant sauter une colonne de tete.
CREATE INDEX idx_lead_client_created ON lead (client_id, created_at DESC);

-- La serie des delais balaie les succes sur une plage de dates, toutes boutiques confondues.
-- L'index existant est (lead_id, attempted_at) : il sert la fiche d'un lead, pas un balayage
-- par date.
--
-- Partiel, comme uq_dead_letter_lead_pending de V8 : la requete ne lit que les succes, et un
-- index global ferait payer les echecs, qui sont l'essentiel du volume quand un ERP tombe.
CREATE INDEX idx_crm_sync_attempt_success_at ON crm_sync_attempt (attempted_at DESC)
    WHERE status = 'SUCCESS';
