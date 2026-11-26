/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.reactor;

import java.util.stream.Collectors;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * {@code scaleout-build:plan-reactor} prints deterministic maximal parallel execution waves.
 *
 * <p>This goal is deliberately read-only: it performs no staging, AWS call, module execution, or
 * artifact attachment. A later orchestrator can consume the same planner model after lifecycle and
 * intermediate-artifact semantics are designed explicitly.
 */
@Mojo(name = "plan-reactor", aggregator = true, threadSafe = true)
public final class PlanReactorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Override
    public void execute() throws MojoFailureException {
        ReactorPlan plan = new ReactorPlanner().plan(new MavenReactorReader().read(session));
        if (!plan.isValid()) {
            String messages = plan.diagnostics().stream()
                    .map(ReactorDiagnostic::message)
                    .collect(Collectors.joining(System.lineSeparator() + "  - ", "  - ", ""));
            throw new MojoFailureException("Reactor plan is invalid:" + System.lineSeparator() + messages);
        }
        getLog().info("Reactor plan: " + plan.modules().size() + " module(s), "
                + plan.executionWaves().size() + " maximal parallel wave(s)");
        for (int i = 0; i < plan.executionWaves().size(); i++) {
            String modules = plan.executionWaves().get(i).stream()
                    .map(module -> module.coordinate().toString())
                    .collect(Collectors.joining(", "));
            getLog().info("Wave " + (i + 1) + ": " + modules);
        }
    }
}
