package com.leadflow.qualification;

import org.springframework.data.jpa.repository.JpaRepository;

/** Acces a la ligne unique de {@code intent_setting}. */
public interface IntentSettingRepository extends JpaRepository<IntentSetting, Short> {

    /** L'identifiant de la seule ligne que la table puisse contenir. */
    Short LIGNE = 1;
}
