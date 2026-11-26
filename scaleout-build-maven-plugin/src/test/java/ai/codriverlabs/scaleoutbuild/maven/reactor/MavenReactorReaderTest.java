/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.reactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenReactorReaderTest {

    @Test
    void readsActiveReactorAndDirectUpstreamProjectsOnly(@TempDir Path root) throws Exception {
        MavenProject foundation = project(root, "foundation");
        MavenProject app = project(root, "app");
        MavenProject external = project(root, "external");
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getUpstreamProjects(foundation, false)).thenReturn(List.of());
        when(graph.getUpstreamProjects(app, false)).thenReturn(List.of(external, foundation));
        MavenSession session = mock(MavenSession.class);
        when(session.getProjects()).thenReturn(List.of(app, foundation));
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        List<ReactorModule> modules = new MavenReactorReader().read(session);

        assertThat(modules).extracting(m -> m.coordinate().artifactId())
                .containsExactly("app", "foundation");
        ReactorModule appModule = modules.stream()
                .filter(module -> module.coordinate().artifactId().equals("app"))
                .findFirst().orElseThrow();
        assertThat(appModule.dependencies()).containsExactly(
                new ReactorModule.Coordinate("example", "foundation", "1"));
        assertThat(appModule.dependencies()).noneMatch(c -> c.artifactId().equals("external"));
    }

    @Test
    void fullGavKeepsEqualArtifactIdsDistinct(@TempDir Path root) throws Exception {
        MavenProject first = project(root.resolve("one"), "group.one", "shared");
        MavenProject second = project(root.resolve("two"), "group.two", "shared");
        ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
        when(graph.getUpstreamProjects(first, false)).thenReturn(List.of());
        when(graph.getUpstreamProjects(second, false)).thenReturn(List.of());
        MavenSession session = mock(MavenSession.class);
        when(session.getProjects()).thenReturn(List.of(second, first));
        when(session.getProjectDependencyGraph()).thenReturn(graph);

        List<ReactorModule> modules = new MavenReactorReader().read(session);

        assertThat(modules).hasSize(2);
        assertThat(modules).extracting(m -> m.coordinate().groupId())
                .containsExactly("group.one", "group.two");
    }

    private static MavenProject project(Path root, String artifactId) throws Exception {
        return project(root.resolve(artifactId), "example", artifactId);
    }

    private static MavenProject project(Path directory, String groupId, String artifactId)
            throws Exception {
        Files.createDirectories(directory);
        Path pom = directory.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        MavenProject project = new MavenProject();
        project.setGroupId(groupId);
        project.setArtifactId(artifactId);
        project.setVersion("1");
        project.setPackaging("jar");
        project.setName(artifactId);
        project.setFile(pom.toFile());
        return project;
    }
}
