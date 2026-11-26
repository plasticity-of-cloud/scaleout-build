/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.reactor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Builds deterministic maximal parallel waves with no Maven, AWS, or AI dependency. */
public final class ReactorPlanner {

    public ReactorPlan plan(Collection<ReactorModule> input) {
        List<ReactorDiagnostic> diagnostics = new ArrayList<>();
        TreeMap<ReactorModule.Coordinate, ReactorModule> modules = new TreeMap<>();
        if (input != null) {
            for (ReactorModule module : input) {
                ReactorModule previous = modules.putIfAbsent(module.coordinate(), module);
                if (previous != null) {
                    diagnostics.add(new ReactorDiagnostic(ReactorDiagnostic.Kind.DUPLICATE_MODULE,
                            "Duplicate reactor coordinate " + module.coordinate(),
                            List.of(module.coordinate())));
                }
            }
        }

        for (ReactorModule module : modules.values()) {
            for (ReactorModule.Coordinate dependency : module.dependencies()) {
                if (!modules.containsKey(dependency)) {
                    diagnostics.add(new ReactorDiagnostic(ReactorDiagnostic.Kind.MISSING_DEPENDENCY,
                            "Module " + module.coordinate() + " references missing reactor dependency "
                                    + dependency,
                            List.of(module.coordinate(), dependency)));
                }
            }
        }

        Map<ReactorModule.Coordinate, Integer> indegree = new TreeMap<>();
        Map<ReactorModule.Coordinate, TreeSet<ReactorModule.Coordinate>> dependents = new TreeMap<>();
        for (ReactorModule module : modules.values()) {
            int presentDependencies = 0;
            for (ReactorModule.Coordinate dependency : module.dependencies()) {
                if (modules.containsKey(dependency)) {
                    presentDependencies++;
                    dependents.computeIfAbsent(dependency, ignored -> new TreeSet<>())
                            .add(module.coordinate());
                }
            }
            indegree.put(module.coordinate(), presentDependencies);
        }

        TreeSet<ReactorModule.Coordinate> ready = new TreeSet<>();
        indegree.forEach((coordinate, count) -> {
            if (count == 0) ready.add(coordinate);
        });
        List<List<ReactorModule>> waves = new ArrayList<>();
        Set<ReactorModule.Coordinate> scheduled = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            List<ReactorModule.Coordinate> waveCoordinates = List.copyOf(ready);
            ready.clear();
            List<ReactorModule> wave = waveCoordinates.stream().map(modules::get).toList();
            waves.add(wave);
            for (ReactorModule.Coordinate completed : waveCoordinates) {
                scheduled.add(completed);
                for (ReactorModule.Coordinate dependent :
                        dependents.getOrDefault(completed, new TreeSet<>())) {
                    int remaining = indegree.compute(dependent, (ignored, count) -> count - 1);
                    if (remaining == 0) ready.add(dependent);
                }
            }
        }

        TreeSet<ReactorModule.Coordinate> residual = new TreeSet<>(modules.keySet());
        residual.removeAll(scheduled);
        diagnostics.addAll(cycleDiagnostics(modules, residual));
        diagnostics.sort(java.util.Comparator.comparing(ReactorDiagnostic::message));
        return new ReactorPlan(List.copyOf(modules.values()), waves, diagnostics);
    }

    private static List<ReactorDiagnostic> cycleDiagnostics(
            Map<ReactorModule.Coordinate, ReactorModule> modules,
            Set<ReactorModule.Coordinate> residual) {
        List<ReactorDiagnostic> result = new ArrayList<>();
        Map<ReactorModule.Coordinate, Integer> state = new HashMap<>();
        List<ReactorModule.Coordinate> path = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (ReactorModule.Coordinate coordinate : residual) {
            findCycles(coordinate, modules, residual, state, path, emitted, result);
        }
        return result;
    }

    private static void findCycles(ReactorModule.Coordinate coordinate,
                                   Map<ReactorModule.Coordinate, ReactorModule> modules,
                                   Set<ReactorModule.Coordinate> residual,
                                   Map<ReactorModule.Coordinate, Integer> state,
                                   List<ReactorModule.Coordinate> path,
                                   Set<String> emitted,
                                   List<ReactorDiagnostic> diagnostics) {
        if (state.getOrDefault(coordinate, 0) == 2) return;
        state.put(coordinate, 1);
        path.add(coordinate);
        for (ReactorModule.Coordinate dependency : modules.get(coordinate).dependencies()) {
            if (!residual.contains(dependency)) continue;
            int dependencyState = state.getOrDefault(dependency, 0);
            if (dependencyState == 0) {
                findCycles(dependency, modules, residual, state, path, emitted, diagnostics);
            } else if (dependencyState == 1) {
                int start = path.indexOf(dependency);
                List<ReactorModule.Coordinate> cycle = new ArrayList<>(path.subList(start, path.size()));
                cycle.add(dependency);
                String message = "Reactor cycle: " + String.join(" -> ",
                        cycle.stream().map(Object::toString).toList());
                if (emitted.add(message)) {
                    diagnostics.add(new ReactorDiagnostic(ReactorDiagnostic.Kind.CYCLE,
                            message, cycle));
                }
            }
        }
        path.remove(path.size() - 1);
        state.put(coordinate, 2);
    }
}
