# SkyLite

> ⚠️ **Early alpha.** The mod is at an early stage of development and may be unstable: expect bugs and notable changes between versions.

A tiny, performance-first client-side Fabric mod for Minecraft Java: a compact HUD for Hypixel SkyBlock. HP, mana and XP bars (six styles) instead of the vanilla elements, info lines from the sidebar (Defense, area, Cookie Buff and God Potion timers, purse, bits), a Speed % plate, FPS and real ping in a corner, rare-drop alerts, chat tools, and an Optimization tab with one-click graphics profiles. Everything is drawn from values the server already sends to your client — no mixins, no textures, no packets of its own.

Supported game version: **26.3** (Fabric).

> **По-русски:** [README на русском](README.ru.md) · [page text (EN)](modrinth/description.md) · [текст страницы (RU)](modrinth/description.ru.md).

## Features

- **HP / mana / XP bars instead of the vanilla HUD.** Six styles (classic, rounded, capsule, gloss, minimal, framed); each bar can take over the spot of the element it replaces (hearts, hunger, XP bar): the value is drawn inside the bar (full / compact / percent), optional ❤ ✎ glyphs, outline, low-value pulse, adjustable width and thickness per bar.
- **In-game editor (F8).** Drag the bars and the Speed plate anywhere; wheel resizes the hovered bar (Ctrl+wheel — its thickness), right-click opens the colour picker. Soft snapping to the vanilla spot and to the screen centre; the layout survives GUI-scale and window-size changes.
- **Speed % plate.** SkyBlock's Speed stat as a bare percentage — movable in the editor, toggleable in the settings.
- **Info lines.** Defense, area/island, Cookie Buff and God Potion timers, purse, bits — parsed from the sidebar and action bar, each line on its own toggle.
- **FPS and real ping.** In any corner. The ping is measured with the same tiny status request the multiplayer server list uses, on a background thread — because Hypixel spoofs the tab-list ping on game servers (it always reads “1”).
- **Alerts (F7).** A sound and an on-screen flash when a chat message contains any of your keywords (`RARE DROP!`, …); keywords are comma-separated.
- **Chat tools.** Your nickname is highlighted in chat; a filter can hide chosen phrases and exact repeats arriving within 10 seconds.
- **Optimization tab.** One-click vanilla-graphics profiles (Balanced / Performance / Maximum) with a rollback to the pre-optimization state, an advisor for whatever is still worth turning down, suggested performance mods, per-location profile rules and a 10-second FPS benchmark.
- **Its own config screen.** A flat dark UI with a category sidebar (Bars, Info lines, Alerts, Chat, Vanilla HUD, Optimization) and a modal colour picker — vanilla widgets only, no Cloth Config; Mod Menu is optional and only adds an entry button. Every setting has a hover tooltip with a short explanation.
- **Localized.** English and Russian UI.

## Keys & commands

| Key | Action |
| --- | --- |
| `F6` | toggle the whole SkyLite HUD |
| `F7` | toggle alerts |
| `F8` | open the bar/Speed editor (rebindable in Controls) |

`/skylite` — config screen · `/skylite edit` — editor · `/skylite hud`, `/skylite alerts` — toggles.

## Requirements

- **Minecraft 26.3** — the only supported version (Fabric); no other game lines for now.
- **Fabric Loader 0.19.5 or newer.**
- **Fabric API** — required; without it the mod doesn't load.
- **Java 25 or newer** — launchers usually install it automatically for this game version.
- **Client-side only** — install it on the client; the server needs nothing.

Optional: **Mod Menu** — adds a SkyLite button to the in-game mod list (SkyLite works fine without it).

## Installation

1. Fabric Loader for 26.3 and **Fabric API** — into the `mods` folder.
2. The SkyLite file — there too (see “Building” below or the release files).
3. Launch the game. On SkyBlock the bars replace the vanilla HUD; `F6`/`F7` toggle the HUD and alerts, `F8` opens the editor.

## Building

JDK 25 and Gradle are required. For now the only target is 26.3:

```bash
bash tools/build_all.sh   # → dist/skylite-0.2.0+26.3.jar
```

`libs/modmenu-21.0.0.jar` is a compile-time-only dependency (the Mod Menu entry button) and is never bundled into the jar.

## Performance notes

- Zero mixins — only public Fabric API events.
- Bars and info lines are composed once and re-composed only when a value actually changes.
- The sidebar is polled twice a second; the ping meter runs on a background daemon thread and stops entirely while the FPS/ping widget is hidden.
- No textures, models, custom fonts or sounds — vanilla assets only.

## Disclosures

**Data & privacy.** While the FPS/ping widget is enabled, SkyLite sends the same tiny status request the multiplayer server list uses to the server you are connected to (about once every 3 seconds) to measure the real round-trip time. Nothing else is sent anywhere; no data is collected or transmitted to the author.

**AI-generated content.** This project was created by the author together with generative AI tools (ChatGPT and OpenCode). All product decisions, design choices, and in-game testing were done by the author; a substantial portion of the code was produced with AI assistance.

## License

MIT — see the [LICENSE](LICENSE) file.
