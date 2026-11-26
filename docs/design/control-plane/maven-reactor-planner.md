# Deterministic Maven reactor planner

`scaleout-build:plan-reactor` derives a deterministic module graph from Maven's active reactor and
prints maximal parallel execution waves. It is the non-AI planning foundation for later Step
Functions and Fargate module orchestration.

The goal uses `MavenSession.getProjects()` and Maven's own `ProjectDependencyGraph`. This means
`-pl`, `-am`, resumed builds, parent relationships, plugin relationships, and other Maven ordering
rules are reflected by the graph Maven actually selected. External dependencies are not mistaken
for missing reactor modules.

For a graph where five modules are initially ready and another five depend on them, the planner
produces:

```text
Wave 1: foundation-1, foundation-2, foundation-3, foundation-4, foundation-5
Wave 2: dependent-1, dependent-2, dependent-3, dependent-4, dependent-5
```

Run it from the aggregator project:

```bash
mvn ai.codriverlabs:scaleout-build-maven-plugin:plan-reactor
```

The planner model is independent of Maven and AWS. It validates duplicate coordinates, references
to absent reactor modules, and cycles, then uses a level-by-level Kahn topological sort with lexical
GAV ordering. The result is stable even when input iteration order changes.

This first phase is intentionally read-only. It does not execute modules, stage intermediates,
contact AWS, or change `scaleout-build:build`. Remote wave execution requires an explicit contract
for lifecycle phases, build-scoped Maven artifacts, failure propagation, retries, and resume
behavior; those concerns belong in the Step Functions/Fargate orchestration phase.
