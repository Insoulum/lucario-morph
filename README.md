# Lucario Morph — a Fabric mod for Minecraft 26.3

Turns your player into Lucario (13 costumes, including Mega Lucario) or Riolu (3 looks) from the *Lucario Overhaul* pack by [JoeSeff](https://modrinth.com/datapack/lucario-overhaul).
The Lucario look and animations are visible to every player on the server who has the mod.

## Installation
Requires Minecraft 26.3, Fabric Loader ≥ 0.19.5 and [Fabric API](https://modrinth.com/mod/fabric-api) 0.165.0+26.3.
1. Install Fabric Loader via the official installer: https://fabricmc.net/use
2. Put `lucariomorph-fabric-26.3-1.6.1.jar` and Fabric API into the `mods` folder.
3. Then pick one of two options:
   * **Modded server** (classic mode): put the jar on the server as well. The `/lucario` and `/riolu`
     commands are registered by the server, the choice is saved in the world folder, and the morph is visible
     to everyone who has the mod.
   * **Server without the mod** (sync mode): the client alone is enough. The commands work right from the client,
     the morph is remembered in the config and passed between players through an external service —
     see the section below.

Ready-made jars are in [Releases](../../releases).

## Playing on servers without the mod (service-based sync)
Since 1.3.0 the mod does not require installation on the server: player morphs are synced through
an external HTTP service. It works like this:
* your client publishes "my UUID → my morph" to a service mirror on morph change and on world join,
  and then every 15 minutes (the `heartbeatMinutes` setting) silently refreshes the record — the "pulse";
* every few seconds the client asks the mirror for the morphs of everyone currently on the server;
* when you clear your morph (`/lucario off`), it disappears for everyone else too.

The default mirror is `http://193.164.155.75:8080` (the author's service). Everything is configurable in
`config/lucariomorph.json`:

```json
{
  "syncEnabled": true,
  "rememberVariant": true,
  "autoAuth": true,
  "syncIntervalSeconds": 5,
  "heartbeatMinutes": 15,
  "serviceUrls": [
    "http://193.164.155.75:8080",
    "http://my-mirror.example.org:8080"
  ],
  "lastVariant": "lucariocaptain_shiny",
  "serviceTokens": {
    "http://193.164.155.75:8080": "…the token, the service issues it automatically…"
  }
}
```

* `syncEnabled` — turn syncing on/off (the morph works locally without it as well);
* `rememberVariant` — remember the morph between sessions (no need to type `/lucario` again);
* `autoAuth` — automatic record authorization via Mojang (default `true`); `false` —
  the Mojang session token is not used at all, records stay open;
* `syncIntervalSeconds` — polling period (minimum 2);
* `heartbeatMinutes` — how often the client silently re-publishes the current morph,
  so the record doesn't go stale on mirrors with a short TTL (0 — disable; default 15);
* `serviceUrls` — mirrors: publishing goes to all of them, reading — from the first one that responds.
  Your own server takes a couple of minutes to set up (Docker): the
  [`lucariomorph-server`](https://github.com/Insoulum/lucariomorph-server) repository;
* `serviceTokens` — the write tokens of mirrors (see below); filled in automatically,
  no need to edit by hand;
* for players to see each other, their mirror lists must share a common mirror.

### Record protection (since 1.4.0)

Without protection, any player who knows your UUID can "draw" someone else's morph onto you on a mirror.
Since 1.4.0 the record is bound to your account automatically — as soon as a mirror answers 401
("record protected"), the mod itself proves ownership through the Mojang session servers (the same
mechanism a vanilla server uses to verify players) — no command needs to be typed:

1. the mod runs the challenge → join → verify chain;
2. the `accessToken` is sent **only directly to Mojang over HTTPS** — mirrors never see it;
3. mirrors issue a write token and afterwards accept your PUT/DELETE only with it.

If an attempt fails (the mirror is unreachable, Mojang doesn't answer), the mod retries by itself:
two attempts with a 30-second pause, two with a one-minute pause, then every 5 minutes — until it
succeeds. One message is posted to chat when the retries start and one on success.

What this gives you:
* a foreign UUID cannot be protected, and yours cannot be changed by anyone else;
* **the config can be deleted**: at the next launch the mod will re-run the authorization by itself
  and get the same token — the record is not lost;
* protection is optional (opt-in): whoever hasn't authorized writes to the mirror as before;
* works only with a licensed Minecraft session; on offline clients authorization is unavailable
  (Mojang will reject the join), but all the rest of the sync keeps working;
* everything can be disabled with `"autoAuth": false` in the config — then the Mojang session
  token is not used at all.

Deployment details: [the lucariomorph-server repository README](https://github.com/Insoulum/lucariomorph-server).

### Emote animations between players (since 1.7.0)

Since 1.7.0 emotes are visible to OTHER players: everything you play from the emote menu (`J` —
dances, any emote of the current model, the T-pose) is played on the morphs of everyone who sees you.
How it works:

* a start/stop is published the moment it happens — the "ping" between your key press and the
  animation on other screens is one network round trip (the real-time channel is a WebSocket on the
  same port; measured: p50 ≈ 11 ms of server time + your RTT);
* only players NEARBY see the emote — the same set Minecraft already renders for you;
* delivery is guaranteed in layers: the push → a backup HTTP poll every 2 s while the socket is
  down → a snapshot whenever the set of nearby players changes → if a game crashes mid-emote, the
  animation is extinguished everywhere by the service after ~30 s;
* the animation starts from its first keyframe on every screen (each viewer keeps its own clock —
  no clock sync needed); stopping holds the last frame during the crossfade, like locally;
* no separate switch: emote sync rides the same opt-in as morph sync (`syncEnabled` + the consent
  screen). Without sync the emote works locally as before.

Mirrors older than 1.5.0 simply don't carry emotes — the mod silently uses the mirrors that do.

## Commands
| Command | What it does |
|---|---|
| `/lucario` | turn into Lucario / turn back (a toggle) |
| `/lucario <variant>` | pick a costume by its short name: `/lucario captain`, `/lucario chef`, `/lucario mega` (Tab lists the options) |
| `/lucario mega` | Mega Lucario (instead of a separate `/mega` command) |
| `/lucario <variant> shiny` or `/lucario <variant>_shiny` | the shiny texture — e.g. `/lucario captain shiny` = `/lucario captain_shiny`; just `/lucario shiny` — the default shiny |
| `/lucario off` | become a regular player again |
| `/riolu` | turn into Riolu / turn back (a toggle) |
| `/riolu <variant>` | short names: `pmd`, `psmd` (and `/riolu pmd shiny`); just `/riolu shiny` — shiny Riolu |

Words in the argument can be separated by spaces as well as underscores: `/lucario costume party` = `/lucario costumeparty`.
Full variant names also work (backwards compatibility): `lucariocaptain`, `lucariomega_shiny`, etc.

Lucario short names: cafe, captain, chef, concert, costumeparty, holiday, martialarts, mega, pmd, psmd, ruins, space
(no argument — regular Lucario). Riolu: pmd, psmd (no argument — regular Riolu).

The commands work in two modes: on a modded server — as server-side ones (the choice is saved in the world
folder, `lucariomod_morphs.json`), on a server without the mod — as client-side ones (the morph is remembered
in the client config and passed through the sync service). Fabric resolves the priority itself:
if the server knows the command, the server-side version runs.

## Building from source

What you need:
* **Any JDK 17+ to launch Gradle** — compilation itself uses Java 25: the project declares a toolchain
  (`build.gradle`) and Gradle, via the Foojay resolver (`settings.gradle`), will find and download JDK 25
  automatically on the first run. If you prefer to install JDK 25 yourself (e.g.
  [Temurin](https://adoptium.net)), that also works.
* **Internet** — the first build downloads the Gradle distribution, Minecraft 26.3, Fabric Loader and
  Fabric API (a few hundred MB; later builds are fast, everything is cached in `~/.gradle`).

Build:
1. `./gradlew build` (Windows: `gradlew.bat build`) → the ready mod:
   `build/libs/lucariomorph-fabric-<Minecraft version>-<mod version>.jar`
   (the version is taken from `gradle.properties`; to run a dev client without installing: `./gradlew runClient`).
2. Before building, check `minecraft_version` / `loader_version` / `fabric_version` in `gradle.properties`
   against https://fabricmc.net/develop.

Troubleshooting: if the build fails with `error: release version 25 not supported`, compilation ran on an
old JDK — normally the toolchain prevents this. Check internet access (the Foojay resolver downloads JDK 25
from the network), or explicitly run Gradle itself on JDK 25: point `JAVA_HOME` at the JDK 25 installation
and repeat `./gradlew build`.

## What is implemented
* **First-person arm:** instead of Steve's arm, the Lucario/Riolu arm is drawn (with their texture).
  The swinging, the swing and the position are taken from the vanilla arm renderer, so everything moves as usual.
* **Punching:** the full strike pose (arm swing, torso twist, chest lean, the paw closes into a fist)
  plays on left-click attacks — hitting mobs and air punches. Breaking blocks uses the same arm swing;
  right-click item use (doors, food, placing) keeps only a plain vanilla-like arm swing.
* **Battle stance:** after attacking or taking damage Lucario holds a combat stance for ~2 seconds;
  it crossfades in/out and works while walking (the legs blend stance ↔ walk).
* **Emote menu (J):** J opens the animation preview screen — every manually playable animation of the
  current morph (dance, specialist, battle idle alt, faint, sense aura, T-pose for rigging checks…),
  with localized button labels and a `▶` marker on the one that is playing. An emote loops until
  interrupted (moving, mounting, water, elytra, damage); the camera switches to third person for the
  duration and returns afterwards.
* **Emote sync (1.7.0):** other players see your emotes — start/stop are pushed instantly through the
  sync service over WebSocket (a 2-second HTTP poll covers the case when the socket is down), only
  nearby players receive them, and a crashed client's emote is extinguished everywhere after ~30 s.
  See "Emote animations between players" above.
* **Death animation:** on death the model plays its "faint" animation
  (Mega Lucario's pack ships it as "battle_faintwip" — both names are tried).
* **Sneaking (Shift):** the torso leans forward, the legs bend, the body lowers, the feet stay on the ground.
  The angles are computed from the leg lengths of the model itself (`LucarioModel.computeCrouch`),
  so it fits both Lucario and Riolu.
* **Held items:** an item is drawn in the Lucario/Riolu hand (both hands: right and left).
* **Swimming / crawling / flying:** both arms stretch forward (`ARMS_FORWARD_*` in `LucarioRenderer`).
* **Elytra:** if the chestplate slot holds an elytra, vanilla wings are drawn on the back (vanilla texture;
  custom elytra textures are not picked up). In flight the wings spread, the body stretches along the
  motion, arms forward, legs behind. Size and position — `ELYTRA_*`. The texture is taken like in vanilla:
  elytra from the skin, otherwise the player's cape.
* **Riolu and Mega Lucario:** Riolu assets (model, animations, textures) are taken from the pack;
  Mega Lucario is enabled with `/lucario mega`.
* Rendering: Bedrock `.geo.json` → ModelPart (including cubes with their own rotation, mirror, inflate).
* Animations: ground_idle, ground_walk, ground_run (running), water_idle / water_swim (in water), sleep, blink.
  Molang expressions (`math.sin/abs/clamp`, `q.anim_time`) are evaluated by a built-in mini-interpreter.
* The head follows the camera; the player name, the shadow and the hurt flash work as usual.
* **Smooth pose transitions:** every pose switch (glide, stance, dance, walk↔flight) eases through
  exponential blend weights instead of snapping — the elytra tilt ramps up on takeoff and settles on landing.
* **GUI:** keybinds (L — pick a form, J — animation menu) live in their own "Lucario Morph" category in Controls;
  a config screen is registered in Mod Menu (sync settings, consent, keybind summary, "choose a form"
  picker with Type/Shiny toggles that remember their state). Since 1.6.1 the picker buttons show the
  localized display names ("Lucario (PMD)" instead of the raw "pmd").
* Network sync (server → all clients), saved to the world.
* **Servers without the mod:** the morph is remembered in the client config and synced through an
  external HTTP service with mirror support (`config/lucariomorph.json`, the
  [`lucariomorph-server`](https://github.com/Insoulum/lucariomorph-server) repository); the record
  is refreshed automatically by the "pulse" every `heartbeatMinutes` minutes.
* **Record protection** (automatic): account ownership is proven through the Mojang session servers
  (challenge → join → verify), mirrors store the token and require it on write.
  The token is recoverable: after deleting the config, re-authorization returns the same one.

## Known limitations
* The hitbox stays the player's (0.6×1.8); the model is scaled to 1.8 blocks.
* Armor and the cape are not drawn on Lucario/Riolu (the skeleton doesn't match Steve's).
* A held item doesn't change the arm pose: a bow, crossbow, food, shield etc. — Lucario simply holds it in the hand.
* Riolu is rendered at an "honest" scale (smaller than Lucario, ~1 block), while the hitbox and camera stay
  the player's (0.6×1.8).
* Swimming/crawling: simple body rotation; sleeping and falling on death have no special poses.
* Face emotions (anger, a smile, an open mouth, etc.) are hidden — in the original they are enabled by
  separate animations. The list of hidden bones is `LucarioModel.hiddenByDefault`.

## If something looks off
* The first-person arm is too big/small/sticks sideways — `FIRST_PERSON_ARM_SCALE` and
  `FIRST_PERSON_ARM_ROLL_DEG` in `LucarioRenderer`.
* The held item is too big/shifted — `HELD_ITEM_SCALE_*`, `HAND_ITEM_OFFSET_*` and `HELD_ITEM_INWARD_*`
  in `LucarioRenderer` (and the rotation order in `renderHeldItem`).
* Sneaking is too deep/shallow — `CROUCH_HIP_DEG`, `CROUCH_TORSO_LEAN_DEG`, `CROUCH_GROUND_FIX` in `LucarioModel`.
* The swing is too sharp/weak — `SWING_ARM_PITCH`, `SWING_BODY_TWIST` in `LucarioRenderer`.
* The elytra sits too far from the back/clips into the body — `ELYTRA_BACK_*` in `LucarioRenderer`
  (separately for Lucario, Mega and Riolu); the wing size — `ELYTRA_SCALE_*`.
* Legs/arms bend "the wrong way" — in `LucarioModel` change `ROT_SIGN_X / Y / Z` to `-1f`.
* Lucario is too big/small — `TARGET_HEIGHT_BLOCKS` in `LucarioModel`.
* The model "levitates" above the ground or the feet sink in — `GROUND_SINK_PX` in `LucarioRenderer`
  (how many 1/16-block pixels to lower the model in the standing/walking/sneaking poses; not applied in
  flight, swimming and vehicles).
* Arms/tail "drift" the wrong way during animation — the sign of the `position` offset in
  `LucarioRenderer.pose` (the `part.setOrigin(...)` line): change `b[0] + px` to `b[0] - px`.

## Licenses and attribution
* **The mod code** — [MIT](LICENSE).
* **Models, textures and animations** — from the [*Lucario Overhaul v1.2.1*](https://modrinth.com/datapack/lucario-overhaul)
  pack by **JoeSeff**, distributed under the MIT license. Used in modified form with attribution kept —
  thanks to JoeSeff for a great pack! JoeSeff is credited as a co-author of this mod
  (see `fabric.mod.json`).
* **Lucario and Riolu** — characters © Nintendo / Game Freak / Creatures Inc. This mod is an unofficial
  fan project, not affiliated with Nintendo, Game Freak, The Pokémon Company or Mojang/Microsoft.
