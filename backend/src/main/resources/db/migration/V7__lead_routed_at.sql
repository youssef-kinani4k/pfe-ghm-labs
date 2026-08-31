-- Date d'attribution au commercial. Elle n'existait nulle part : lead.updated_at bouge a
-- chaque ecriture, donc il vaut la date d'attribution pour un lead reste ROUTED mais celle
-- de la synchronisation pour un lead SYNCED. La timeline de F9 avait besoin d'une date qui
-- ne mente pas.
--
-- Nullable et SANS remplissage retroactif, deliberement : remplir l'historique depuis
-- updated_at inventerait une date pour tout lead deja synchronise. La timeline affiche
-- « date inconnue » pour les leads attribues avant cette migration, et c'est la seule chose
-- vraie qu'on puisse en dire.
ALTER TABLE lead ADD COLUMN routed_at timestamptz;
