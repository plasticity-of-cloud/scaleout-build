/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.reactor;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** One active Maven reactor module and its direct dependencies inside that reactor. */
public record ReactorModule(Coordinate coordinate, String displayName, String packaging,
                            Path baseDirectory, List<Coordinate> dependencies) {
    public ReactorModule {
        Objects.requireNonNull(coordinate, "coordinate");
        displayName = displayName == null || displayName.isBlank()
                ? coordinate.artifactId() : displayName;
        packaging = packaging == null || packaging.isBlank() ? "jar" : packaging;
        Objects.requireNonNull(baseDirectory, "baseDirectory");
        dependencies = dependencies == null ? List.of() : dependencies.stream().sorted().distinct().toList();
    }

    /** Effective Maven GAV, used instead of artifactId so independent groups cannot collide. */
    public record Coordinate(String groupId, String artifactId, String version)
            implements Comparable<Coordinate> {
        public Coordinate {
            groupId = require(groupId, "groupId");
            artifactId = require(artifactId, "artifactId");
            version = require(version, "version");
        }

        @Override
        public int compareTo(Coordinate other) {
            int group = groupId.compareTo(other.groupId);
            if (group != 0) return group;
            int artifact = artifactId.compareTo(other.artifactId);
            return artifact != 0 ? artifact : version.compareTo(other.version);
        }

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version;
        }

        private static String require(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
        }
    }
}
