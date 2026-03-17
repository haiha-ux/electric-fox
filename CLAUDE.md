# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Electric is the **Electric VLSI Design System** (Version 9.07/9.08-a-SNAPSHOT) — an integrated-circuit design system offering IC layout, schematic editing, textual hardware-description languages, synthesis/analysis tools, and EDA import/export. Licensed under GPLv3. Written primarily in Java (1,700+ source files) with a small Scala component (7 files).

## Build & Run

This is a **Maven** project (Java 1.8 target). There is also an Ant build under `packaging/`.

```bash
# Build (compile + package)
mvn package

# Build skipping tests
mvn package -DskipTests

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=com.sun.electric.tool.drc.SomeTest

# Run the application
java -jar target/electric-9.08-a-SNAPSHOT-jar-with-dependencies.jar

# Run with larger heap (for large designs)
java -Xmx2g -jar target/electric-9.08-a-SNAPSHOT-jar-with-dependencies.jar

# Alternative: Ant build (from packaging/ directory)
cd packaging && ant
```

Main class: `com.sun.electric.Launcher`

## Source Layout

| Directory | Purpose |
|-----------|---------|
| `electric-java/` | Main Java sources (Maven sourceDirectory) |
| `electric-scala/` | Scala sources (compiled via maven-scala-plugin) |
| `test/` | JUnit 4 test sources |
| `packaging/` | Ant build scripts, dependency JARs, platform packaging files |

All source lives under the `com.sun.electric` package namespace.

## Architecture

### Core Packages (`electric-java/com/sun/electric/`)

- **`database/`** — Core data model. Immutable cell/node/arc representations (`ImmutableCell`, `ImmutableNodeInst`, `ImmutableArcInst`, `ImmutableExport`), snapshots (`Snapshot`, `CellBackup`, `CellTree`), editing preferences, constraint system (`database/constraint/`), geometry primitives (`database/geometry/`), change tracking (`database/change/`), and ID management (`database/id/`).

- **`technology/`** — Technology definitions (layer rules, DRC templates, primitive nodes/arcs). Technologies are defined in XML (`technologies/` subdirectory) and loaded via `TechFactory`. Key classes: `Technology`, `Layer`, `PrimitiveNode`, `ArcProto`.

- **`tool/`** — All design tools, each in its own subpackage:
  - `drc/` — Design Rule Checking
  - `erc/` — Electrical Rule Checking
  - `ncc/` — Network Consistency Checking
  - `simulation/` — Circuit simulation interfaces
  - `routing/` — Auto-routing
  - `placement/` — Cell placement
  - `extract/` — Circuit extraction
  - `generator/` — Layout generators
  - `io/` — File I/O (import/export formats: CIF, GDS, LEF/DEF, EDIF, etc.)
  - `sc/` — Silicon compiler
  - `compaction/` — Layout compaction
  - `lang/` — Scripting (BeanShell, Jython)
  - `user/` — User interface and preferences
  - `logicaleffort/` — Logical effort analysis
  - `project/` — Project management

- **`util/`** — Shared utilities (math, collections, text processing).

- **`lib/`** — Built-in cell libraries.

- **`plugins/`** — Plugin interfaces (IRSIM simulator, etc.).

- **`api/`** — Public API interfaces (min-area checker, movie creator, IRSIM analyzer).

### Key Design Patterns

- **Immutable data model**: Core database objects (`ImmutableNodeInst`, `ImmutableArcInst`, etc.) are immutable. Changes produce new snapshots via `CellBackup`/`Snapshot`.
- **Job system**: Long-running operations use `Job`/`EJob` with server/client architecture (`ServerJobManager`, `ClientJobManager`). UI operations go through `AbstractUserInterface`.
- **Technology abstraction**: IC fabrication technologies are data-driven (XML) and loaded dynamically. `TechPool` manages available technologies.

## Dependencies

Key runtime dependencies (see `pom.xml`):
- BeanShell 2.0b4 — Java scripting
- Jython 2.7.0 — Python scripting
- Java3D 1.3.1 — 3D visualization (provided scope)
- JMF 2.1.1e — Media/animation
- SLF4J 1.7.7 — Logging
- JUnit 4.10 — Testing
