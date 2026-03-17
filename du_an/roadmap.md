# ROADMAP - Electric VLSI UI Modernization

**Cap nhat:** 2026-03-08 10:03

## Giai Doan 0: Setup & Research

| Status | Task | Ten |
|--------|------|-----|
| DONE | 0.1 | Scan UI codebase + Setup FlatLaf dependency |
| DONE | 0.2 | Tao custom Claude theme (.properties) |

## Giai Doan 1: Core Integration

| Status | Task | Ten |
|--------|------|-----|
| DONE | 1.1 | Tich hop FlatLaf vao TopLevel.OSInitialize() |
| DONE | 1.2 | Tao ElectricTheme.properties (Claude colors) |
| DONE | 1.3 | Fix hardcoded colors (splash screen, status bar) |

## Giai Doan 2: Component Polish

| Status | Task | Ten |
|--------|------|-----|
| DONE | 2.1 | Splash screen modernization (Claude colors) |
| DONE | 2.2 | StatusBar flat border |
| DONE | 2.3 | MessagesWindow font polish |
| SKIP | 2.4 | Waveform - all colors use User.getColor() preference, no changes needed |

## Giai Doan 3: Theme & Final

| Status | Task | Ten |
|--------|------|-----|
| DONE | 3.1 | Dark mode theme (FlatDarkLaf.properties) |
| DONE | 3.2 | Theme switcher UI (Window > Theme menu) |
| DONE | 3.3 | Build + test clean startup |

## Giai Doan 4: Grid Polish + Silicon Compiler

| Status | Task | Ten |
|--------|------|-----|
| DONE | 4.1 | TechPalette component grid modernization |
| DONE | 4.2 | Silicon Compiler improvements (net balance + diagnostics) |

## Giai Doan 5: SPICE Simulation Integration

| Status | Task | Ten |
|--------|------|-----|
| DONE | 5.1 | Auto-detect ngspice + configure defaults |
| DONE | 5.2 | One-click Write & Run SPICE menu |
| DONE | 5.3 | Built-in MOSFET/BJT model library |
| DONE | 5.4 | Build + test full simulation workflow |

## Giai Doan 6: Model Library Manager

| Status | Task | Ten |
|--------|------|-----|
| DONE | 6.1 | SpiceModelManager dialog (add/edit/remove/import models) |
| DONE | 6.2 | Embedded model storage in SpiceModels{doc} cell |
| DONE | 6.3 | SPICE writer integration (auto-include embedded models) |
| DONE | 6.4 | Menu integration + drag & drop .lib file import |

## Giai Doan 7: SPICE UX + Palette Fix

| Status | Task | Ten |
|--------|------|-----|
| DONE | 7.1 | Fix ngspice model warnings (MF/M^2 units, obsolete OPTIONS) |
| DONE | 7.2 | Fix .raw file detection (add working dir + JVM dir to search paths) |
| DONE | 7.3 | SPICE Simulation Setup dialog (.tran/.dc/.ac, VDD, preview) |
| DONE | 7.4 | Restore TechPalette arrow indicators (bigger, brighter triangle) |

## Giai Doan 8: Waveform Display + UI Modernization

| Status | Task | Ten |
|--------|------|-----|
| DONE | 8.1 | Auto-populate analog signals in waveform window (up to 8) |
| DONE | 8.2 | Modern rounded UI: larger arcs, pill scrollbars, rounded selection |
| DONE | 8.3 | Tree/List rounded selection with accent-colored icons |
| DONE | 8.4 | Toolbar hover effects + tab modernization |
| DONE | 8.5 | Popup menu rounding + tooltip arcs + table polish |

## Giai Doan 9: Waveform System Overhaul

| Status | Task | Ten |
|--------|------|-----|
| DONE | 9.1 | Fix analog signal auto-display: show ALL signals grouped by type |
| DONE | 9.2 | Explorer tree: right-click "Add to Panel" + "Add All Signals" context menu |
| DONE | 9.3 | Signal search/filter text field in explorer panel |
| DONE | 9.4 | Modern waveform panel rendering (anti-alias, colors, grid, axes) |
| DONE | 9.5 | Waveform toolbar modernization (FlatLaf styled buttons) |
| DONE | 9.6 | Double-click signal in explorer to add to new panel (already existed) |
| DONE | 9.7 | Build + test full waveform workflow |

## Giai Doan 10: SPICE Simulation UX Overhaul

| Status | Task | Ten |
|--------|------|-----|
| DONE | 10.1 | Fix netlist skip bug + merge Write&Run with SimSetup into unified dialog |
| DONE | 10.2 | Net enumeration: list all cell nets with export/internal classification |
| DONE | 10.3 | Source assignment UI: per-net DC/PULSE/SIN/PWL source with parameter fields |
| DONE | 10.4 | SPICE code editor: editable preview of generated stimulus + analysis commands |
| DONE | 10.5 | Schematic cross-probe: highlight net on schematic when selected in dialog |
| DONE | 10.6 | Visual source indicators on schematic (icons/labels for assigned sources) |
| DONE | 10.7 | Build + test full simulation workflow end-to-end |

## Giai Doan 11: Waveform Interaction Overhaul

| Status | Task | Ten |
|--------|------|-----|
| DONE | 11.1 | Mouse wheel zoom (X-axis centered on cursor, Ctrl+wheel for Y-axis) |
| DONE | 11.2 | Ctrl+drag pan + middle-button pan (no toolbar mode switch needed) |
| DONE | 11.3 | Right-click context menu on panel (move signal to new panel, remove, zoom fit) |
| DONE | 11.4 | Fix DragButton MOVE/COPY bug + drag-over panel highlight feedback |
| SKIP | 11.5 | Panel resize drag handle — already existed (WaveTable row resize) |
| DONE | 11.6 | Build + test full waveform interaction workflow |

## Giai Doan 12: Silicon Compiler Overhaul

| Status | Task | Ten |
|--------|------|-----|
| DONE | 12.1 | Fix VHDL 4-port transistor port mismatch (component 4 vs instance 3) |
| DONE | 12.2 | Audit & fix CompileVHDL QUISC netlist generation for all gate primitives |
| DONE | 12.3 | Fix GetNetlist parser robustness (error handling, missing cells) |
| DONE | 12.4 | Fix Place.java edge cases (empty rows, single-cell placement) |
| DONE | 12.5 | Fix Route.java port direction detection and channel routing |
| DONE | 12.6 | Fix Maker.java layout generation (via placement, arc connections) |
| DONE | 12.7 | End-to-end test: NOT gate schematic -> layout via SC |
| DONE | 12.8 | End-to-end test: NAND/NOR/complex gates -> layout via SC |

## Giai Doan 13: Transistor-to-Gate Recognition (SC Enhancement)

| Status | Task | Ten |
|--------|------|-----|
| DONE | 13.1 | GateRecognizer: parse QUISC netlist into transistor graph |
| DONE | 13.2 | Pattern recognition: INV, NAND2-4, NOR2-4 from CMOS topology |
| DONE | 13.3 | Netlist transformation: replace transistors with standard cells |
| DONE | 13.4 | Integrate into SC pipeline (ToolMenu between compile and parse) |
| DONE | 13.5 | Edge cases: power/ground, unrecognized patterns, mixed designs |
| DONE | 13.6 | End-to-end test: transistor NOT gate -> layout via SC |
| DONE | 13.7 | End-to-end test: transistor NAND/NOR -> layout via SC |

## Giai Doan 14: Analog Layout Synthesis Engine (ALSE)

| Status | Task | Ten |
|--------|------|-----|
| DONE | 14.1 | Circuit graph builder: SPICE subcircuit → device connectivity graph |
| DONE | 14.2 | Constraint extraction: symmetry groups, matched pairs, proximity from topology |
| DONE | 14.3 | Parameterized MOSFET generator: multi-finger, interdigitated, W/L sizing |
| DONE | 14.4 | B*-tree floorplan representation with symmetric feasibility |
| DONE | 14.5 | Simulated Annealing placement engine with analog constraints |
| DONE | 14.6 | Common centroid array generator for matched device pairs |
| DONE | 14.7 | Multi-layer routing engine (Metal-1/2/3+, via optimization) |
| DONE | 14.8 | Symmetry-aware routing for differential nets |
| DONE | 14.9 | Guard ring + well tie + dummy device generation |
| DONE | 14.10 | Parasitic-aware optimization loop (place→estimate→refine) |
| DONE | 14.11 | Integration: menu entry + unified analog/digital SC flow |
| DONE | 14.12 | End-to-end test: current mirror → optimized symmetric layout |
| DONE | 14.13 | End-to-end test: differential pair → common centroid layout |

## Giai Doan 15: ALSE Algorithm-Driven Layout (Technology Independent)

| Status | Task | Ten |
|--------|------|-----|
| DONE | 15.1 | TechRules abstraction: query DRC rules from Technology via XMLRules API |
| DONE | 15.2 | Algorithm-driven spacing: compute device/contact/row gaps from TechRules |
| DONE | 15.3 | Contact placement algorithm: compute positions from layer geometry + DRC rules |
| DONE | 15.4 | Row layout algorithm: compute all Y positions from technology rules |
| DONE | 15.5 | Well generation algorithm: compute well regions from device bounding boxes + enclosure rules |
| DONE | 15.6 | Automated JUnit test: NOT gate synthesis + Well=0 (DRC needs GUI mode) |
| DONE | 15.7 | Test with NAND2/NOR2 gates to verify technology independence |
| DONE | 15.8 | Test with different MOCMOS foundry rules (MOSIS/TSMC/ST) |

## Giai Doan 16: Icon System Modernization (SVG Flat Design)

| Status | Task | Ten |
|--------|------|-----|
| DONE | 16.1 | SVG infrastructure: Resources.java SVG support + FlatSVGIcon integration |
| DONE | 16.2 | Toolbar icons: 23 modern flat SVG icons (undo/redo/zoom/pan/save/etc.) |
| DONE | 16.3 | Tree/Explorer icons: 28 SVG icons (library/errors/signals/views/etc.) |
| DONE | 16.4 | Waveform toolbar: 21 SVG icons (sim controls/VCR/grid/etc.) |
| DONE | 16.5 | Dialog icons: 6 SVG icons (new/delete/draw/increment/decrement/patterns) |
| DONE | 16.6 | Build + test full icon system (light/dark theme compatibility) |

## Giai Doan 17: Component Palette Visual Polish

| Status | Task | Ten |
|--------|------|-----|
| DONE | 17.1 | Entry borders: larger arc radius, softer color, subtle shadow/depth |
| DONE | 17.2 | Grid lines: subtle separators, skip edges, reduced alpha |
| DONE | 17.3 | Component scaling: 85% fill, better anti-aliasing |
| DONE | 17.4 | Text rendering: PLAIN weight, LCD anti-aliasing, better sizing |
| DONE | 17.5 | Hover/selection: larger radius, softer fill, thinner accent border |
| DONE | 17.6 | Build + test: clean compile, 10/10 ALSE tests, clean startup |

## Giai Doan 18: Size Check + Auto-Adjust Layout ↔ Schematic

| Status | Task | Ten |
|--------|------|-----|
| DONE | 18.1 | ALSE auto-sizing: set W/L on layout transistors from schematic values |
| DONE | 18.2 | Size propagation tool: menu command to sync schematic W/L → layout |
| DONE | 18.3 | Post-NCC auto-fix: NCC + Auto-Fix Size Mismatches menu command |
| DONE | 18.4 | Build + test: 10/10 ALSE tests PASS, clean compile, clean startup |

## Giai Doan 19: 3D Visualization Modernization (JavaFX 3D)

| Status | Task | Ten |
|--------|------|-----|
| DONE | 19.1 | Java 17 upgrade: pom.xml release=17, maven-compiler-plugin 3.13.0 |
| DONE | 19.2 | JavaFX 3D deps: javafx-controls/graphics/swing 17.0.13 |
| DONE | 19.3 | View3DWindowFX: JavaFX SubScene + Box-based layer stack + JFXPanel embed |
| DONE | 19.4 | Camera + interaction: orbit drag, right-drag pan, scroll zoom |
| DONE | 19.5 | Layer rendering: technology colors, 0.3 opacity, per-layer materials |
| DONE | 19.6 | Menu: Window > 3D Layer View (Modern) + JFrame wrapper |
| DONE | 19.7 | Build + test: 5/5 core tests PASS, clean startup, Java 17 OK |

## Tien Do: 118/118 tasks
