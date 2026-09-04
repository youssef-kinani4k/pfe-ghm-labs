package com.leadflow.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acces a la trace des notifications. */
public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {

    /**
     * Dans le sens ou les faits se sont produits : c'est ainsi que la chronologie d'un lead
     * les raconte, un echec suivi d'un envoi reussi devant se lire dans cet ordre.
     */
    List<NotificationAttempt> findByLeadIdOrderByAttemptedAtAsc(UUID leadId);
}
