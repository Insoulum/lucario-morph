# Lucario Morph

Turns your player into Lucario (13 costumes, including Mega Lucario) or Riolu (3 looks) from the
[*Lucario Overhaul*](https://modrinth.com/datapack/lucario-overhaul) pack by JoeSeff.
**Client-side only**: servers don't need it — it works on vanilla, modded, everything.
Everyone who has the mod sees your morph, emotes and dance. Localized into 16 languages.

## Features

* Full Lucario/Riolu model with Bedrock-format animations: idle, walk, run, swim, sleep, blink,
  elytra flight, battle stance, sneaking, punching, death "faint".
* Emote menu (`J`) — dances and every animation of the current morph, visible to nearby players
  in real time.
* First-person Lucario/Riolu arm instead of Steve's; items held in both hands.
* Morphs and emotes sync between players through an external service; your record is protected
  by automatic Mojang authentication.
* Keybinds: `L` — choose a form, `J` — animation menu. Config screen in Mod Menu.

## Installation

1. Install Fabric Loader: https://fabricmc.net/use
2. Put the jar for your Minecraft version from [Releases](../../releases) plus
   [Fabric API](https://modrinth.com/mod/fabric-api) into the `mods` folder **of your client**.
3. Play. Sync settings live in `config/lucariomorph.json` (created on first launch).

## Commands

| Command | What it does |
|---|---|
| `/lucario` | turn into Lucario / turn back (a toggle) |
| `/lucario <variant>` | pick a costume: `/lucario captain`, `/lucario chef`, `/lucario mega` (Tab lists options) |
| `/lucario <variant> shiny` | the shiny texture — e.g. `/lucario captain shiny` |
| `/lucario off` | become a regular player again |
| `/riolu` | turn into Riolu / turn back |
| `/riolu <variant>` | `pmd`, `psmd`; `/riolu pmd shiny` for shiny |

Short names — Lucario: cafe, captain, chef, concert, costumeparty, holiday, martialarts, mega,
pmd, psmd, ruins, space; Riolu: pmd, psmd. Spaces and underscores are interchangeable
(`/lucario costume party` = `/lucario costumeparty`); full variant names (`lucariocaptain`) also work.
Commands are client-side; if a server has its own `/lucario`, that one wins.

## Sync and config

How it works: your client publishes "my UUID → my morph" to a service mirror on every change and
refreshes it every 15 minutes; other players receive changes instantly over WebSocket push
(HTTP polling is only a fallback). `/lucario off` removes the morph for everyone.
The default mirror is the author's service — you can run your own in a couple of minutes
(Docker): [`lucariomorph-server`](https://github.com/Insoulum/lucariomorph-server).

`config/lucariomorph.json`:

```json
{
  "syncEnabled": true,
  "rememberVariant": true,
  "autoAuth": true,
  "syncIntervalSeconds": 5,
  "heartbeatMinutes": 15,
  "debugChat": false,
  "serviceUrls": ["http://193.164.155.75:8080"],
  "serviceTokens": {}
}
```

* `syncEnabled` — sync on/off (the morph works locally without it);
* `rememberVariant` — remember the morph between sessions;
* `autoAuth` — automatic record protection via Mojang session servers (default `true`):
  a foreign UUID cannot write to your record, and the token is restored by itself after
  deleting the config; licensed accounts only;
* `syncIntervalSeconds` — fallback poll period (minimum 2);
* `heartbeatMinutes` — how often the current morph is silently re-published (0 — off);
* `debugChat` — show the sync trace in chat (default off; the log file always has it) —
  enable when asked for a diagnostic log;
* `serviceUrls` — mirrors: publishing goes to all, reading — from the first that responds;
  players see each other only if their mirror lists share a common mirror;
* `serviceTokens` — write tokens, filled in automatically, no need to edit.

## Known limitations

* The hitbox stays the player's (0.6×1.8); Riolu is smaller than Lucario but keeps the hitbox.
* Armor and the cape are not drawn (the skeleton doesn't match Steve's).
* A held item doesn't change the arm pose (bow, food, shield — simply held).
* Sleeping and falling on death have no special poses.
* Face emotions are hidden (enabled in the pack by separate animations).

## Building from source

Any JDK 17+ is enough to run Gradle — compilation uses Java 25, downloaded automatically via the
Foojay toolchain (`settings.gradle`/`build.gradle`). `./gradlew build` →
`build/libs/lucariomorph-fabric-<Minecraft>-<version>.jar`; `./gradlew runClient` for a dev client.
Check `gradle.properties` against https://fabricmc.net/develop before building.

## Links

* [CHANGELOG.md](CHANGELOG.md) — version history, tuning constants, detailed feature notes
* Sync server: [`lucariomorph-server`](https://github.com/Insoulum/lucariomorph-server)
* Models & animations: [*Lucario Overhaul*](https://modrinth.com/datapack/lucario-overhaul) by JoeSeff

## Licenses

* Mod code — [MIT](LICENSE).
* Models, textures and animations — from *Lucario Overhaul v1.2.1* by JoeSeff (MIT), used in
  modified form with attribution; JoeSeff is credited as a co-author.
* Lucario and Riolu are © Nintendo / Game Freak / Creatures Inc. Unofficial fan project,
  not affiliated with Nintendo, Game Freak, The Pokémon Company or Mojang/Microsoft.
