-- L'attribution du responsable est une etape a part entiere chez Dolibarr : l'ERP ignore
-- fk_user_resp a la creation comme en modification, il faut un second appel. Sans une
-- reference pour la memoriser, un echec de ce second appel apres une creation reussie
-- laissait l'opportunite sans chef de projet, et le rejeu sautait tout le bloc.
--
-- La colonne task_ref existante n'est pas detournee : elle est reservee a la tache
-- d'agenda de F9, qui est une autre etape.
ALTER TABLE crm_sync_attempt ADD COLUMN assignee_ref VARCHAR(64);

COMMENT ON COLUMN crm_sync_attempt.assignee_ref IS
    'Reference du responsable lie dans l ERP. Non nul = l attribution est faite, et le rejeu la saute.';
