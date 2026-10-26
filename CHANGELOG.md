# Changelog

## [0.3.0] — 2026-10-27

**HUD and info lines**
- Potion-effect timers: active effects with mm:ss countdowns, a configurable list limit and
  orange/red warning colours as they run out.
- Coordinates, real-time clock, session time, AFK timer and a session-drops line
  (`Drops: 3 · 12m`).
- Every info line is its own draggable block in the editor (F8): drag it, nudge it with the
  arrow keys (Shift — 10 px), double-click to return it to the stack; moved lines ignore the
  stack limit.
- Optional line labels (`Purse:`, `XYZ:`, …) — off by default, so values are bare numbers.
- Info-line limit slider (0–16) with automatic trimming by screen height; rows near the bars
  keep priority.
- LAG badge in the FPS/ping widget when ping passes a configurable threshold.
- The editor shows pixel offsets and resets the selected bar's position and size on
  double-click; the arrow keys fine-tune it.

**Alerts and the drop journal**
- Alert sound picker (bell, pling, XP orb, click), volume and a test button; sounds no longer
  overlap — 400 ms spacing, up to four queued.
- Configurable flash/toast duration and an optional dark background behind toasts and flashes.
- Toast queue: several alerts stack instead of overwriting each other.
- Alert cooldown (0–3000 ms) and duplicate suppression for the same text within 10 seconds.
- New warnings: Cookie Buff / God Pot expiring in under 5 minutes, potion effects about to
  expire, inventory almost full and nickname mentions.
- Alert presets for party invites, trade requests, island visits, a full inventory, server
  restarts and `RARE CATCH`.
- Rare-drop journal (`/skylite drops` or the Alerts tab): what dropped, when and where; optional
  screenshot on a rare drop.

**Chat**
- Chat timestamps (`[HH:MM]`), including Hypixel system messages.
- Nickname mention alert; your own messages and their server echoes are ignored.
- Chat rules can combine actions (`sound+toast+hl+hide`) and skip messages via an ignore list.
- Ready-made filter presets for island-visit ads, giveaways and lobby join/leave.

**Settings and polish**
- Settings search with match highlighting, a “found: N” counter and Esc to clear.
- FPS benchmark stores the result and shows the difference from the previous run.
- Location-profile rules are validated live (“bad rule: …”).

## [0.2.0] — 2026-10-18

**Interface**
- Six bar styles in the settings: classic, rounded, capsule, gloss, minimal, framed.
- Dropdowns unfold in place as a list of options.
- The SkyLite HUD hides itself while a GUI screen is open (the chat screen keeps it, like vanilla).
- Every setting row in the config screen has a hover tooltip with a short explanation.

**Chat**
- Your nickname is highlighted in chat.
- Spam filter: hide messages containing any of the chosen phrases and, optionally, exact repeats
  that arrive within 10 seconds.

**Optimization tab (new)**
- One-click graphics profiles — Balanced / Performance / Maximum. The first apply remembers the
  pre-optimization settings; the Rollback button restores them.
- Advisor: what is still worth turning down (particles, clouds, entity shadows, see-through leaves,
  biome blend, entity distance) — apply items one by one or all at once.
- A checklist of performance mods (Sodium, EntityCulling, ImmediatelyFast, FerriteCore, ModernFix,
  Krypton) with links to Modrinth; installed ones are marked.
- Location profiles: apply a profile automatically per area with simple place=profile rules.
- FPS benchmark: a 10-second measurement of the average FPS and the 1% lows, with a live graph and
  a before/after delta.

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
