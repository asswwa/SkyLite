# SkyLite

> ⚠️ **Early alpha.** The mod is at an early stage of development and may be unstable: expect bugs and notable changes between versions.

A tiny, performance-first client-side Fabric mod for Minecraft Java: a compact HUD, alerts and chat tools for Hypixel SkyBlock. Everything is drawn from values the server already sends to your client — no packets of its own, no mixins, no textures, no custom models.

## Features

- **HP / mana / XP bars instead of the vanilla HUD.** Each bar can take over the spot of the element it replaces (hearts, hunger, XP bar): the value is drawn inside the bar (full / compact / percent), optional ❤ ✎ glyphs, outline, low-value pulse, adjustable width and thickness per bar.
- **In-game editor (F8).** Drag the bars, the Speed plate and every info line; wheel resizes the hovered bar (Ctrl+wheel — its thickness), right-click opens the colour picker. The arrow keys nudge the selection by 1 px (Shift — 10), double-click resets it; soft snapping to the vanilla spot and the screen centre. The layout survives GUI-scale and window-size changes.
- **Speed % plate.** SkyBlock's Speed stat as a bare percentage — movable in the editor, toggleable in the settings.
- **Info lines.** Defense, area/island, Cookie Buff and God Potion timers, purse, bits, active potion effects with mm:ss timers, coordinates, real-time clock, session time, AFK timer and session drops — parsed from the sidebar and action bar, each line its own draggable block with an optional label. A line limit (0–16) trims the stack by screen height.
- **FPS and real ping.** In any corner, with an optional LAG badge when ping passes a configurable threshold. The ping is measured with the same tiny status request the multiplayer server list uses, on a background thread — because Hypixel spoofs the tab-list ping on game servers (it always reads “1”).
- **Alerts (F7).** Choose the sound (bell, pling, XP orb, click), volume and duration; alerts queue instead of overlapping, with a cooldown, duplicate suppression and a dark toast/flash background. Built-in alerts for Cookie Buff/God Pot expiry, potion effects, low HP/mana, a full inventory, nickname mentions and a kick to Limbo/lobby, plus presets for party invites, trades, visits, server restarts and `RARE CATCH`.
- **Rare-drop journal.** Every rare drop is logged with the time and area; open it with `/skylite drops` or from the Alerts tab. Optional screenshot on a rare drop.
- **Chat tools.** Nickname highlighting and mention alerts, chat timestamps, a spam filter, custom `phrase => sound+toast+hl+hide` rules with an ignore list, and ready-made filter presets for visit ads, giveaways and lobby messages.
- **Optimization tab.** One-click vanilla-graphics profiles (Balanced / Performance / Maximum) with a rollback to the pre-optimization state, an advisor for whatever is still worth turning down, suggested performance mods, per-location profile rules (validated live) and a 10-second FPS benchmark that remembers the previous result and shows the difference.
- **Its own config screen.** A flat dark UI with a category sidebar (Bars, Info lines, Alerts, Chat, Vanilla HUD, Optimization) and a modal colour picker — vanilla widgets only, no Cloth Config; Mod Menu is optional and only adds an entry button. Every setting has a hover tooltip; the search box highlights matches and shows a result count.
- **Localized.** English and Russian UI.

## Keys & commands

| Key | Action |
| --- | --- |
| `F6` | toggle the whole SkyLite HUD |
| `F7` | toggle alerts |
| `F8` | open the HUD editor — bars, Speed plate and info lines (rebindable in Controls) |

`/skylite` — config screen · `/skylite edit` — editor · `/skylite hud`, `/skylite alerts` — toggles · `/skylite drops` — rare-drop journal · `/skylite session reset` — reset session stats.

## Supported versions

Pick the file that matches your game version:

- **26.3** — `skylite-0.3.0+26.3.jar`

## Requirements

- **Minecraft 26.3** — the only supported version (Fabric); no other game lines for now.
- **Fabric Loader 0.19.5 or newer.**
- **Fabric API** — required; without it the mod doesn't load.
- **Java 25 or newer** — launchers usually install it automatically for this game version.
- **Client-side only** — install it on the client; the server needs nothing.

Optional: **Mod Menu** — adds a SkyLite button to the in-game mod list (SkyLite works fine without it).

## How to use

1. Drop Fabric API and the SkyLite file into your `mods` folder.
2. Launch the game. On SkyBlock the bars replace the vanilla HUD; `F6`/`F7` toggle the HUD and alerts.
3. `F8` (or `/skylite edit`) — arrange and colour everything; `/skylite` — the full settings screen.

## Building from source

JDK 25 and Gradle required:

```bash
bash tools/build_all.sh   # → dist/skylite-0.3.0+26.3.jar
```

## Disclosures

**Data & privacy.** While the FPS/ping widget is enabled, SkyLite sends the same tiny status request the multiplayer server list uses to the server you are connected to (about once every 3 seconds) to measure the real round-trip time. Nothing else is sent anywhere; no data is collected or transmitted to the author.

**AI-generated content.** This project was created by the author together with generative AI tools (ChatGPT and OpenCode). All product decisions, design choices, and in-game testing were done by the author; a substantial portion of the code was produced with AI assistance.

## License

MIT. See the [LICENSE](LICENSE) file.
