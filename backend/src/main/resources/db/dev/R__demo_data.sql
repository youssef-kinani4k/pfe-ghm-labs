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
-- Cette valeur en clair est bien celle que hmac_secret contient, et DonneesDeDemoTest le
-- verifie a chaque ./mvnw test : il dechiffre la colonne avec la cle maitre de
-- application-dev.yml et la compare a la ligne ci-dessus. Les deux ne peuvent donc plus
-- diverger sans que la suite le dise.
--
-- Elle a ete tenue pour FAUSSE entre le 31 aout et le 4 septembre 2026, a la suite d'un 401
-- au webhook. C'etait une erreur : le 4 septembre, un webhook signe avec cette valeur a rendu
-- 202 sur cette base, les deux colonnes chiffrees n'ont pas bouge depuis F1, et la base
-- portait exactement le chiffre de ce fichier. La cause du 401 d'aout n'a jamais ete
-- etablie ; elle n'etait pas ici.
--
-- Le piege reel, lui, tient a la persistance : cette migration est repetable, donc elle ne se
-- rejoue que si son contenu change. Sur une base docker compose deja utilisee — elle survit
-- d'une session a l'autre —, un secret ou une clef publique tournes depuis l'ecran
-- « Boutiques » restent en place et ce sont eux qui font foi, pas les valeurs ci-dessous.
-- Cela vaut pour hmac_secret comme pour public_key. Le geste qui tranche : lire la fiche de
-- la boutique dans le dashboard, ou repartir d'une base neuve par docker compose down -v.
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
