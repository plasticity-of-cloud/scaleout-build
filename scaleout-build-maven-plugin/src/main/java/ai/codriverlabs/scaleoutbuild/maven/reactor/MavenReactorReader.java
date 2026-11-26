/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.reactor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/** Adapts Maven's selected project dependency graph to the Maven-independent planner model. */
final class MavenReactorReader {

    List<ReactorModule> read(MavenSession session) {
        if (session == null || session.getProjectDependencyGraph() == null) {
            throw new IllegalArgumentException("Maven session has no project dependency graph");
        }
        List<MavenProject> projects = session.getProjects() == null ? List.of() : session.getProjects();
        Map<ReactorModule.Coordinate, MavenProject> active = new TreeMap<>();
        for (MavenProject project : projects) {
            active.put(coordinate(project), project);
        }

        List<ReactorModule> modules = new ArrayList<>();
        for (MavenProject project : active.values()) {
            List<ReactorModule.Coordinate> dependencies = session.getProjectDependencyGraph()
                    .getUpstreamProjects(project, false).stream()
                    .map(MavenReactorReader::coordinate)
                    .filter(active::containsKey)
                    .sorted()
                    .toList();
            if (project.getBasedir() == null) {
                throw new IllegalArgumentException("Reactor module " + coordinate(project)
                        + " has no base directory");
            }
            modules.add(new ReactorModule(coordinate(project), project.getName(),
                    project.getPackaging(), project.getBasedir().toPath().toAbsolutePath(),
                    dependencies));
        }
        return List.copyOf(modules);
    }

    private static ReactorModule.Coordinate coordinate(MavenProject project) {
        return new ReactorModule.Coordinate(project.getGroupId(), project.getArtifactId(),
                project.getVersion());
    }
}
