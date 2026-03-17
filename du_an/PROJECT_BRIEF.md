# TONG QUAN DU AN: Electric VLSI UI Modernization

## Muc Tieu
Dai tu giao dien Electric VLSI tu Swing co dien (Windows L&F / Motif) sang giao dien hien dai,
dep, de su dung voi theme mau nau/trang lay cam hung tu Claude AI.
Su dung FlatLaf lam Look & Feel engine - khong can rewrite UI code.

## Pham Vi
- **Trong:**
  - Tich hop FlatLaf vao Maven build
  - Tao custom theme Claude-inspired (nau/trang/kem)
  - Thay doi diem khoi tao L&F trong TopLevel.java
  - Xu ly hardcoded colors (166 cho `new Color(` trong 27 files)
  - Cap nhat toolbar, menu, dialog cho nhat quan
  - Dark mode toggle

- **Ngoai:**
  - Khong thay doi logic nghiep vu
  - Khong rewrite UI components
  - Khong thay doi database/technology/tool core
  - Khong doi cau truc file

## Quy Mo UI Hien Tai
- 291 Java files trong tool/user/
- 59 dialog Java files + 50 .form files (NetBeans GUI builder)
- 33 UI class files trong tool/user/ui/
- 13 menu files
- 166 hardcoded `new Color()` trong 27 files
- L&F hien tai: WindowsLookAndFeel (ca Windows va Linux), MacLookAndFeel

## Diem Khoi Tao UI
- Launcher.main() -> Main.main() -> new UserInterfaceMain() -> TopLevel.OSInitialize()
- TopLevel.java:280 la noi UIManager.setLookAndFeel() duoc goi

## Tech Stack
| Layer | Choice | Ly Do |
|-------|--------|-------|
| L&F Engine | FlatLaf 3.x | Drop-in, Java 8+, custom themes, HiDPI |
| Theme Format | .properties file | De chinh sua, FlatLaf native |
| Color Palette | Claude-inspired brown/cream/white | Theo yeu cau |
| Icons | FlatLaf icon pack hoac SVG | Hien dai, scalable |

## Rui Ro
- .form files (NetBeans GUI builder) co the hardcode colors/fonts
- Waveform/RoutingDebug co nhieu hardcoded colors cho functional purposes
- Mot so colors la semantic (layer colors) - khong nen thay doi

---

## Milestones (Lich su mo rong)

[2026-03-17] - M16: Icon System Modernization
- Muc tieu: Thay the 113 icon GIF cu bang SVG hien dai, flat design, scalable HiDPI
- In-scope: Toolbar icons (23), Tree/panel icons (26), Waveform icons (21), Dialog icons (17), Cursor icons (12), TecEditWizard icons (14)
- Out-scope: Help/documentation images (316 PNG), business logic changes
- Rui ro chinh: Cursor icons can co dinh khu vuc pixel -> can test ky
- Ghi chu: Su dung FlatSVGIcon (co san trong FlatLaf 3.7), SVG text files de tao/chinh sua

[2026-03-17] - M17: Component Palette Visual Polish
- Muc tieu: Nang cap visual component palette (TechPalette) cho hien dai, dep, nhat quan voi theme
- In-scope: Border styling, grid lines, spacing, entry padding, hover effects, text rendering, anti-aliasing, shadow/depth
- Out-scope: Component shapes (do Technology XML), layer colors (semantic), rendering pipeline core
- Rui ro chinh: Thap - chi thay doi visual chrome, khong dung rendering pipeline
- Ghi chu: Programmatic rendering via Graphics2D, chi can chinh parameter + drawing code

[2026-03-17] - M18: Size Check + Auto-Adjust Layout ↔ Schematic
- Muc tieu: Tu dong dong bo W/L giua schematic va layout, phat hien va sua mismatch
- In-scope: ALSE set W/L tu schematic, Post-NCC auto-fix, Size propagation menu command
- Out-scope: Layout geometry resize (chi set ATTR_width/ATTR_length), ML-based sizing
- Rui ro chinh: Trung - can test ky voi NCC size check enabled
- Ghi chu: API: ni.setPrimitiveNodeSize(w, l, ep), NCC: StratCheckSizes.java

[2026-03-17] - M19: 3D Visualization Modernization (JavaFX 3D)
- Muc tieu: Thay the Java3D 1.5.2 (dead) bang JavaFX 3D, nang Java target len 17
- In-scope: Java 17 upgrade, JavaFX 3D dependency, rewrite View3DWindow, layer stack visualization, camera/lighting
- Out-scope: VR/AR support, ray tracing, animation/movie export (phase 2)
- Rui ro chinh: Cao - rewrite 14 files, thay dependency chain, Java version upgrade
- Ghi chu: JDK 21 da co san, FlatLaf 3.7 OK voi Java 17+, khong co Java 8 API blocker
