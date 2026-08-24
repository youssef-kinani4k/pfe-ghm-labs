package com.leadflow.monitoring.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Enveloppe de pagination du projet.
 *
 * <p>La serialisation directe d'un {@code Page} Spring est instable d'une version a l'autre
 * — Spring Boot lui-meme en avertit — et le frontend en dependrait. Ce record est un contrat
 * qui nous appartient, ecrit une fois pour toutes les listes du dashboard.
 */
public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <S, T> PageResponse<T> de(Page<S> page, List<T> contenu) {
        return new PageResponse<>(
                contenu, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
