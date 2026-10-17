# Changelog

## [0.1.5] — 2026-10-18

**Interface**
- The config screen is restyled into a flat dark UI: a category sidebar (Bars / Info lines /
  Alerts / Vanilla HUD), 1px-bordered panels, flat buttons, sliders, toggles and dropdowns;
  the layout adapts to the window size.
- The colour picker is now a modal dialog with channel gradients, a live preview and Cancel / Save.
- Both screens are still vanilla widgets only (SkyLite ships its own tiny widget toolkit) — no
  Cloth Config, no textures; Mod Menu remains optional.

## [0.1.0] — 2026-10-18

First public alpha (Minecraft 26.3, Fabric).

**HUD**
- HP / mana / XP bars that replace the vanilla hearts, hunger and XP bar (each element optional):
  the value is drawn inside the bar — full, compact or percent; ❤ ✎ glyphs; outline; low-value pulse;
  per-bar width and thickness.
- Info lines from the sidebar: area/island, Cookie Buff and God Potion timers, purse, bits — each
  toggleable; Hypixel's action-bar stats line can be hidden as well.
- Standalone Speed % plate (movable in the editor, toggleable).
- FPS and real ping in any corner — the ping comes from a standard server-list status request,
  because Hypixel spoofs the tab-list ping on game servers.

**Editor & settings**
- In-game editor (F8): drag the bars and the Speed plate, wheel = width, Ctrl+wheel = thickness,
  right-click = colour picker, soft snapping, reset button. Offsets are stored relative to the
  vanilla spots, so the layout survives GUI-scale and window-size changes.
- Own config screen on vanilla widgets — no Cloth Config; Mod Menu only adds an entry button.
- English and Russian interface.

**Alerts**
- Keyword alerts: a sound and an on-screen flash when a chat message contains any of the
  comma-separated keywords (case-insensitive, with a short cooldown).

**Performance**
- Zero mixins, no textures or custom sounds.
- Per-frame work reduced to cached strings: bars, info lines, the FPS/ping corner and toasts
  are re-composed only when a value actually changes.
- Sidebar polled at 10-tick intervals; the ping meter stops entirely while its widget is hidden;
  editor wheel resizes are written to disk at most twice a second.

**Requirements**
- Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API, Java 25.
