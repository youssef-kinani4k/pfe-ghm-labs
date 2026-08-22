-- Jeu de demonstration, charge uniquement sous le profil dev. La production ne voit
-- jamais ce dossier : il n'est ajoute a spring.flyway.locations que par application-dev.yml.
--
-- Migration repetable (R__) : elle se rejoue a chaque changement de son contenu, sans
-- occuper de numero de version et sans entrer en conflit avec les migrations V.
--
-- Les valeurs chiffrees ci-dessous ont ete produites avec la cle maitre de dev fixee dans
-- application-dev.yml. Changer cette cle rend ce jeu de donnees illisible : il faudrait
-- alors regenerer ces valeurs.
--
-- Secret HMAC en clair, pour signer les requetes de test en F2 :
--   c6702b700b1673ae027ce903ff753c4239522e71b21297259c396540b9e5ec19
--
-- Contenu en clair de crm_config, pour la verification manuelle du pipeline en F4 :
--   {"baseUrl":"http://localhost:8081/api/index.php","apiKey":"cle-dolibarr-de-demo"}
-- La cle d'API doit etre celle posee sur l'utilisateur admin de Dolibarr (voir la
-- section 2.1 de docs/erp-integration-setup.md). Les deux valeurs se sont contredites
-- jusqu'a F4, et l'ecart produisait un 401 illisible cote backend.

INSERT INTO client (id, public_key, name, hmac_secret, crm_provider_id, crm_config,
                    assignment_strategy, scoring_config, active)
VALUES ('0198f3c2-0000-7000-8000-000000000001',
        'demo-cd253966049ebd76243248e8',
        'Boutique de demonstration',
        'NQJ2I5VrUBUdsNCWHykWXhHwezChXGrFPzW87u01Ogskc4ADoQrzD1dMXACMntSxBME2A/qxK1QSkJSRRPZHAK63Lo3wQGeDUBSNPiAaznq1fyPwQHbuyQYwlIA=',
        'dolibarr',
        'qUp5WleujVUipDxKEfYwi/43H+vOg+ZhuTGYNJ/9nX9dv5/HgVO8eeY8jF+Tj6R89uJ/Yt0BJnt98jzHe7ZosRoLKwQCc8zYbx64cBtd3KzoudQGk7+ImiSMDoJvQ+5d3qlXSs0Zt9Q83edz0g==',
        'ROUND_ROBIN',
        '{}'::jsonb,
        TRUE)
ON CONFLICT (id) DO UPDATE
    SET public_key      = EXCLUDED.public_key,
        name            = EXCLUDED.name,
        hmac_secret     = EXCLUDED.hmac_secret,
        crm_provider_id = EXCLUDED.crm_provider_id,
        crm_config      = EXCLUDED.crm_config,
        updated_at      = now();

INSERT INTO sales_rep (id, client_id, full_name, email, sector, zone, active)
VALUES ('0198f3c2-0000-7000-8000-000000000011',
        '0198f3c2-0000-7000-8000-000000000001',
        'Amina Bensalem', 'amina@demo.test', 'industrie', 'nord', TRUE),
       ('0198f3c2-0000-7000-8000-000000000012',
        '0198f3c2-0000-7000-8000-000000000001',
        'Karim Haddad', 'karim@demo.test', 'services', 'sud', TRUE)
ON CONFLICT (id) DO UPDATE
    SET full_name  = EXCLUDED.full_name,
        email      = EXCLUDED.email,
        sector     = EXCLUDED.sector,
        zone       = EXCLUDED.zone,
        updated_at = now();
