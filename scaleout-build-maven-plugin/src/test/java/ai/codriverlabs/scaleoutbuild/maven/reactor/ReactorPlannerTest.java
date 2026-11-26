/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.reactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReactorPlannerTest {
    private final ReactorPlanner planner = new ReactorPlanner();

    @Test
    void tenModulesProduceTwoMaximalWavesOfFive() {
        List<ReactorModule> modules = new ArrayList<>();
        List<ReactorModule.Coordinate> foundation = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            var coordinate = c("foundation-" + i);
            foundation.add(coordinate);
            modules.add(module(coordinate));
        }
        for (int i = 1; i <= 5; i++) {
            modules.add(module(c("dependent-" + i), foundation.toArray(ReactorModule.Coordinate[]::new)));
        }

        ReactorPlan plan = planner.plan(modules);

        assertThat(plan.isValid()).isTrue();
        assertThat(plan.executionWaves()).hasSize(2);
        assertThat(plan.executionWaves().get(0)).extracting(m -> m.coordinate().artifactId())
                .containsExactly("foundation-1", "foundation-2", "foundation-3", "foundation-4",
                        "foundation-5");
        assertThat(plan.executionWaves().get(1)).extracting(m -> m.coordinate().artifactId())
                .containsExactly("dependent-1", "dependent-2", "dependent-3", "dependent-4",
                        "dependent-5");
    }

    @Test
    void diamondDependenciesProduceMaximalParallelWaves() {
        var foundation = module(c("foundation"));
        var api = module(c("api"), foundation.coordinate());
        var implementation = module(c("implementation"), foundation.coordinate());
        var app = module(c("app"), api.coordinate(), implementation.coordinate());

        ReactorPlan plan = planner.plan(List.of(app, implementation, foundation, api));

        assertThat(plan.executionWaves()).extracting(w -> w.stream()
                        .map(m -> m.coordinate().artifactId()).toList())
                .containsExactly(List.of("foundation"), List.of("api", "implementation"),
                        List.of("app"));
    }

    @Test
    void inputPermutationDoesNotChangeThePlan() {
        var a = module(c("a"));
        var b = module(c("b"));
        var c = module(c("c"), a.coordinate(), b.coordinate());

        ReactorPlan first = planner.plan(List.of(a, b, c));
        ReactorPlan second = planner.plan(List.of(c, b, a));

        assertThat(second.modules()).isEqualTo(first.modules());
        assertThat(second.executionWaves()).isEqualTo(first.executionWaves());
    }

    @Test
    void missingDependencyNamesBothModulesAndIsNotACycle() {
        var app = module(c("app"), c("missing"));

        ReactorPlan plan = planner.plan(List.of(app));

        assertThat(plan.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.kind()).isEqualTo(ReactorDiagnostic.Kind.MISSING_DEPENDENCY);
            assertThat(diagnostic.message()).contains("app", "missing");
        });
        assertThat(plan.diagnostics()).noneMatch(d -> d.kind() == ReactorDiagnostic.Kind.CYCLE);
    }

    @Test
    void cycleDiagnosticContainsAConcreteClosedPath() {
        var a = module(c("a"), c("b"));
        var b = module(c("b"), c("a"));

        ReactorPlan plan = planner.plan(List.of(b, a));

        assertThat(plan.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.kind()).isEqualTo(ReactorDiagnostic.Kind.CYCLE);
            assertThat(diagnostic.coordinates().get(0))
                    .isEqualTo(diagnostic.coordinates().get(diagnostic.coordinates().size() - 1));
            assertThat(diagnostic.message()).contains("a", "b", "->");
        });
    }

    @Test
    void duplicateCoordinatesAreRejected() {
        ReactorPlan plan = planner.plan(List.of(module(c("same")), module(c("same"))));

        assertThat(plan.diagnostics()).anyMatch(d -> d.kind()
                == ReactorDiagnostic.Kind.DUPLICATE_MODULE);
    }

    @Test
    void returnedCollectionsAreImmutable() {
        ReactorPlan plan = planner.plan(List.of(module(c("a"))));

        assertThatThrownBy(() -> plan.modules().add(module(c("b"))))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.executionWaves().get(0).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ReactorModule module(ReactorModule.Coordinate coordinate,
                                        ReactorModule.Coordinate... dependencies) {
        return new ReactorModule(coordinate, coordinate.artifactId(), "jar",
                Path.of("/tmp", coordinate.artifactId()), List.of(dependencies));
    }

    private static ReactorModule.Coordinate c(String artifactId) {
        return new ReactorModule.Coordinate("example", artifactId, "1");
    }
}
