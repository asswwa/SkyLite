# SkyLite

A tiny, performance-first client-side Fabric mod for Minecraft Java: a compact HUD and alerts for Hypixel SkyBlock. Everything is drawn from values the server already sends to your client — no packets of its own, no mixins, no textures, no custom models.

## Features

- **HP / mana / XP bars instead of the vanilla HUD.** Each bar can take over the spot of the element it replaces (hearts, hunger, XP bar): the value is drawn inside the bar (full / compact / percent), optional ❤ ✎ glyphs, outline, low-value pulse, adjustable width and thickness per bar.
- **In-game editor (F8).** Drag the bars and the Speed plate anywhere; wheel resizes the hovered bar (Ctrl+wheel — its thickness), right-click opens the colour picker. Soft snapping to the vanilla spot and to the screen centre; the layout survives GUI-scale and window-size changes.
- **Speed % plate.** SkyBlock's Speed stat as a bare percentage — movable in the editor, toggleable in the settings.
- **Info lines.** Defense, area/island, Cookie Buff and God Potion timers, purse, bits — parsed from the sidebar and action bar, each line on its own toggle.
- **FPS and real ping.** In any corner. The ping is measured with the same tiny status request the multiplayer server list uses, on a background thread — because Hypixel spoofs the tab-list ping on game servers (it always reads “1”).
- **Alerts (F7).** A sound and an on-screen flash when a chat message contains any of your keywords (`RARE DROP!`, …); keywords are comma-separated.
- **Its own config screen.** Vanilla widgets only — no Cloth Config needed; Mod Menu is optional and only adds an entry button.
- **Localized.** English and Russian UI.

## Keys & commands

| Key | Action |
| --- | --- |
| `F6` | toggle the whole SkyLite HUD |
| `F7` | toggle alerts |
| `F8` | open the bar/Speed editor (rebindable in Controls) |

`/skylite` — config screen · `/skylite edit` — editor · `/skylite hud`, `/skylite alerts` — toggles.

## Supported versions

Pick the file that matches your game version:

- **26.3** — `skylite-0.1.0+26.3.jar`

## Requirements

- **Fabric Loader 0.19.5+** and **Fabric API** for 26.3.
- **Java 25** — launchers usually manage this automatically.

## How to use

1. Drop Fabric API and the SkyLite file into your `mods` folder.
2. Launch the game. On SkyBlock the bars replace the vanilla HUD; `F6`/`F7` toggle the HUD and alerts.
3. `F8` (or `/skylite edit`) — arrange and colour everything; `/skylite` — the full settings screen.

## Building from source

JDK 25 and Gradle required:

```bash
bash tools/build_all.sh   # → dist/skylite-0.1.0+26.3.jar
```

## Disclosures

**Data & privacy.** While the FPS/ping widget is enabled, SkyLite sends the same tiny status request the multiplayer server list uses to the server you are connected to (about once every 3 seconds) to measure the real round-trip time. Nothing else is sent anywhere; no data is collected or transmitted to the author.

**AI-generated content.** This project was created by the author together with generative AI tools (ChatGPT and OpenCode). All product decisions, design choices, and in-game testing were done by the author; a substantial portion of the code was produced with AI assistance.

## License

MIT. See the [LICENSE](LICENSE) file.
