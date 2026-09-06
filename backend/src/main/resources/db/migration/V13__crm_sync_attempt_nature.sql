-- F15 : une ligne de crm_sync_attempt ne raconte plus forcement une synchronisation. Depuis
-- la propagation d'une reattribution, elle peut aussi raconter la correction d'un
-- responsable chez l'ERP, qui ne cree rien et ne touche qu'une reference sur quatre.
--
-- Sans cette colonne, l'ecran de detail montrerait une tentative n'ayant obtenu qu'une
-- reference et ne saurait pas dire que c'est normal.
ALTER TABLE crm_sync_attempt
    ADD COLUMN nature VARCHAR(20) NOT NULL DEFAULT 'SYNCHRONISATION';

-- Le defaut remplit l'historique, et il dit vrai — contrairement au routed_at de V7, laisse
-- nullable parce qu'aucune valeur retroactive n'aurait ete honnete. Toutes les lignes
-- ecrites avant F15 sont bel et bien des synchronisations : la propagation n'existait pas.
--
-- Aucun index : la table se lit toujours par lead_id, ce que sert deja l'index de V2, et la
-- nature ne filtre jamais une requete a elle seule.
