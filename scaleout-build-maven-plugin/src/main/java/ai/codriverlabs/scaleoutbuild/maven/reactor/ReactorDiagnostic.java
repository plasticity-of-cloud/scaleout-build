/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.reactor;

import java.util.List;
import java.util.Objects;

/** Structured reason a reactor cannot be safely scheduled. */
public record ReactorDiagnostic(Kind kind, String message,
                                List<ReactorModule.Coordinate> coordinates) {
    public ReactorDiagnostic {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(message, "message");
        coordinates = coordinates == null ? List.of() : List.copyOf(coordinates);
    }

    public enum Kind {
        DUPLICATE_MODULE,
        MISSING_DEPENDENCY,
        CYCLE
    }
}
