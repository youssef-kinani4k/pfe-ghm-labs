package com.leadflow.routing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Le corps de la reattribution. <b>L'operateur n'y figure pas</b> : il vient du
 * {@code Principal}, donc du jeton. Un acteur transmis par le client serait un journal
 * falsifiable.
 *
 * <p>Le motif est obligatoire : un journal dont la moitie des lignes n'ont pas de motif ne
 * sert a rien six mois plus tard, et le geste est assez rare pour que la phrase ne coute
 * pas cher.
 */
public record ReassignmentForm(
        @NotNull UUID salesRepId,
        @NotBlank @Size(max = 500) String reason) {
}
