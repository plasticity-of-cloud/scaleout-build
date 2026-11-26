/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.reactor;

import java.util.List;

/** Deterministic module order, maximal parallel waves, and validation diagnostics. */
public record ReactorPlan(List<ReactorModule> modules, List<List<ReactorModule>> executionWaves,
                          List<ReactorDiagnostic> diagnostics) {
    public ReactorPlan {
        modules = modules == null ? List.of() : List.copyOf(modules);
        executionWaves = executionWaves == null ? List.of()
                : executionWaves.stream().map(List::copyOf).toList();
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean isValid() {
        return diagnostics.isEmpty();
    }
}
