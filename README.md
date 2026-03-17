# Electric VLSI Design System — Fox Edition

**Version 9.08-fox** | Based on Electric 9.07/9.08 by Static Free Software

Electric-Fox is a modernized fork of the [Electric VLSI Design System](http://www.staticfreesoft.com/), an open-source integrated-circuit design tool offering IC layout, schematic editing, hardware-description languages, synthesis/analysis tools, and EDA import/export. Licensed under GPLv3.

---

## What's New in Fox Edition

### Java 17 Upgrade
- Migrated from Java 1.8 target to **Java 17** (`--release 17`)
- maven-compiler-plugin upgraded to 3.13.0
- Fully compatible with JDK 17, 21, and later LTS releases

### Modern 3D Layer Visualization
- **JavaFX 3D** engine (replaces legacy Java3D 1.5.2 which is discontinued)
- **Java2D isometric fallback** — works on systems without GPU/hardware 3D support
- Accessible via **Window > 3D Layer View (Modern)**
- Interactive: left-drag to rotate, right-drag to pan, scroll to zoom

### ALSE — Analog Layout Synthesis Engine
A new module for automatic analog/mixed-signal layout generation:
- Reads SPICE netlists or Electric schematic cells
- Automatic transistor placement (NMOS/PMOS rows with series chain stacking)
- Automatic routing (gate, drain, source, power/ground buses)
- Technology-independent via `TechRules` API (DRC-aware spacing)
- **Auto-sizing**: layout transistors inherit W/L from schematic
- Well contact generation, guard rings, substrate taps
- Tested with NOT, NAND2, NOR2 gates

### NCC Enhancements
- **Size checking enabled by default** — transistor W/L comparison between schematic and layout
- **Propagate Schematic Sizes to Layout** — menu command to sync W/L (Tools > NCC)
- **NCC + Auto-Fix Size Mismatches** — one-click NCC check and auto-correction

### Modern UI (FlatLaf)
- **FlatLaf 3.7** look-and-feel with light/dark theme support
- SVG toolbar icons (scalable, resolution-independent)
- Improved component palette, layer tab, and status bar styling

### Silicon Compiler Improvements
- Gate recognition from schematic (AND, OR, NAND, NOR, NOT, XOR, MUX, DFF)
- SPICE netlist reader for direct layout synthesis from `.spi` files

---

## Build & Run

**Requirements:** JDK 17+ and Maven 3.6+

```bash
# Build (compile + package)
mvn package

# Build skipping tests
mvn package -DskipTests

# Run
java -jar target/electric-9.08-fox-jar-with-dependencies.jar

# Run with larger heap (for large designs)
java -Xmx2g -jar target/electric-9.08-fox-jar-with-dependencies.jar

# Run tests
mvn test
```

## Source Layout

```
electric-java/          Main Java sources
  com/sun/electric/
    database/           Core data model (immutable cells, snapshots, geometry)
    technology/         Technology definitions (MOCMOS, CMOS, etc.)
    tool/
      drc/              Design Rule Checking
      erc/              Electrical Rule Checking
      ncc/              Network Consistency Checking
      simulation/       Circuit simulation interfaces
      routing/          Auto-routing
      placement/        Cell placement
      sc/               Silicon compiler + ALSE
      io/               File I/O (SPICE, GDS, CIF, LEF/DEF, EDIF...)
      user/             User interface, preferences, menus
    plugins/
      j3d/              3D visualization (JavaFX + Java2D fallback)
test/                   JUnit 4 test sources
packaging/              Ant build, platform packaging
```

## Key Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| FlatLaf | 3.7 | Modern Swing look-and-feel |
| JavaFX | 17.0.13 | 3D visualization |
| BeanShell | 2.0b4 | Java scripting |
| Jython | 2.7.0 | Python scripting |
| JUnit | 4.10 | Testing |
| SLF4J | 1.7.7 | Logging |

## Screenshots

Open a layout cell and explore:
- **Tools > NCC** — Network Consistency Checking with size verification
- **Tools > Silicon Compiler > Synthesize Layout (ALSE)** — Automatic analog layout
- **Window > 3D Layer View (Modern)** — 3D/isometric layer stack visualization

## Credits

- **Electric VLSI Design System** by Static Free Software — [staticfreesoft.com](http://www.staticfreesoft.com/)
- **Fox Edition** enhancements developed with [Claude Code](https://claude.ai/claude-code)
- Original source: [GNU Savannah](http://savannah.gnu.org/projects/electric)

## License

GNU General Public License v3.0 — see [COPYING](COPYING) for details.
