-- Idempotence du webhook (F2). La fenetre de tolerance sur l'horodatage borne le rejeu
-- mais ne l'empeche pas : dans les cinq minutes, une requete captee peut etre renvoyee
-- telle quelle. L'unicite est posee en base et non verifiee en Java, sinon deux requetes
-- concurrentes passeraient toutes les deux le controle applicatif.
--
-- La colonne signature porte la valeur d'en-tete complete (t=...,v1=...) : c'est ce
-- couple, et non le seul hexadecimal, qui identifie un rejeu exact.
CREATE UNIQUE INDEX uk_raw_lead_event_client_signature
    ON raw_lead_event (client_id, signature);

-- Le filet de republication cherche les lignes non publiees ANTERIEURES a un instant.
-- L'index simple sur status de V1 ne sait pas borner par age ; celui-ci le remplace.
DROP INDEX idx_raw_lead_event_status;

CREATE INDEX idx_raw_lead_event_status_received
    ON raw_lead_event (status, received_at);
