# Changelog

All notable changes to Lucario Morph. Versions are listed newest first; dates are commit dates
of the corresponding release. For the current feature set see [README.md](README.md).

## Unreleased

* **Sync traffic only when someone can see you** (1.8.1-pre9): the client now talks to the
  sync service only while at least `minPlayers` (default 2) players are on the server (the
  TAB list, all dimensions, self included). Alone in the world -- or in singleplayer -- the
  client is fully silent: no morph publishes, no polls, no /health probes, no WebSocket,
  no emote PUTs. A viewer joining wakes the channel up immediately (the pending morph publish
  goes out, a full poll runs, the WS reconnects instead of waiting out the backoff); the last
  viewer leaving tears it down after a 10-second grace period, so quick relogs do not flap
  the connection. Bonus: with one player on the server the sync service sees zero requests
  from you. Set `"minPlayers": 1` in `config/lucariomorph.json` to restore the old always-on
  behavior. Note: servers that hide players from the TAB list (vanish plugins) look "empty"
  to the gate -- use 1 there.
* **Prototype (1.8.1):** a **Cape: Yes/No** toggle in the form picker (next to Shiny): your own
  Minecraft cape is drawn as a hanging cloth on the morph's back (Lucario / Riolu / Mega alike)
  while the toggle is Yes and no elytra is worn; a worn elytra always renders its wings as before.
  The cloth is the classic rectangular cloak (the vanilla cloak box). Each form drapes it to its
  own standing angle: Riolu keeps the gentle one, regular Lucario tilts a bit more so the hem
  clears its fluffy hip fur, and Mega Lucario drapes strongest of the three -- and while walking
  or running the cloth billows gently behind the back: the hem lifts off and settles back (no
  side-to-side swing).
  Riolu tweaks: while riding it now sits with the legs extended forward like Lucario and Mega
  Lucario (the lower leg used to stay folded, digging the paws into the seat), the elytra wings
  hug the body even closer, and it sits a bit higher in boats/minecarts.
  Takes effect immediately, remembered for the session. Requires an actual account cape.
* **The Cape toggle is now synced** (1.8.1-pre8): your Yes/No choice travels with the morph key
  (a "_nocape" suffix, the same way "_shiny" does) through the usual sync service -- so other
  players render your cape exactly as you set it, and your toggle no longer controls every
  morph on your own screen. Flipping the toggle re-publishes immediately; the heartbeat keeps
  the flag alive. Players on older builds see a "_nocape" morph as no morph -- update together.
* The picker's **Done button now applies the Shiny toggle** to the currently worn morph:
  flip Shiny, press Done — the morph switches shiny/non-shiny without re-picking the variant
  (both directions; with no morph worn Done just closes).
* The morph-picker keybind **defaults to K** instead of L (26.3 SDL scancode 14).
  Existing players keep their saved binding — remap once in Options → Controls if you want K too.

* **Minecraft 26.4 support** (`mc26.4` branch): ported to `26.4-snapshot-6` — Fabric Loader 0.20.1,
  Fabric API 0.165.1+26.4, Mod Menu 22.0.0-alpha.3. The only code change required:
  `Mth.clamp` was removed upstream and replaced with the standard `Math.clamp` (Java 21+).
* The `minecraft` version predicate widened to the whole 26.4 line (`~26.4-alpha.1`):
  snapshot builds, pre-release alphas and the 26.4 release all match; 26.3 and 26.5+ are rejected.
* Dependency resolution hardened for restricted environments: mirrors are consulted before
  Maven Central (which rate-limits some networks with HTTP 429).
* All Russian code comments translated to English (22 files, code untouched).
* 14 new community languages — 16 in total: en_us, ru_ru, zh_cn, zh_tw, ja_jp, ko_kr, de_de,
  fr_fr, es_es, es_mx, pt_br, pt_pt, pl_pl, uk_ua, tr_tr, it_it.

## 1.8.0 — 2026-10-28

### Removed

* **The server side of the mod is gone.** The mod no longer runs on Minecraft servers at all
  (`"environment": "client"`): the `main` entrypoint, the `lucariomod:morph` payload, the
  server-side `/lucario`–`/riolu` registration and the per-world `lucariomod_morphs.json`
  storage were removed.

### Changed

* The external sync service is now the **only** sync channel. Morphs and emotes reach other
  players exactly as in 1.7.x (publish + WebSocket push + fallback poll). For players almost
  nothing changes: client commands worked since 1.6.x, and the world-folder storage only ever
  duplicated what the service already kept (`lastVariant` still follows you across servers).
  One code path instead of two — much simpler maintenance.

## 1.7.8 — 2026-10-28

* **Push-first sync released:** while the WebSocket is open, morph and emote changes arrive by
  push instantly; HTTP polling is only a fallback (`syncIntervalSeconds` pace while the socket
  is down) plus a rare safety re-sync every 60–120 s that heals frames lost across reconnects
  (compatible with services down to 1.5.1).
* No more duplicate publishes: each emote start/stop/refresh sends exactly one request per
  window, and every request carries its cause.
* The debug builds' permanent chat trace became the opt-in `debugChat` option (off by default —
  the chat stays fully quiet; the log file always carries the full trace plus a world-join
  status line with build version, sync state and mirrors).

## 1.7.7 — 2026-10-28

* Push-first sync groundwork: the HTTP poll demoted to a fallback while the WebSocket is open.

## 1.7.6 — 2026-10-26

* **One animation set for all Lucario variants:** every variant except Mega now plays the
  standard Lucario animations. 13 per-variant animation files removed from the jar (six were
  byte-identical copies) — roughly a third less animation payload.
* A variant's own `animations/<variant>.animation.json` is still picked up when present (that's
  how Mega keeps its WIP set); without one, the species standard (`lucario` / `riolu`) loads.
* Side effects: `lucariocafe` regains `specialist`/`specialist_full` and `lucarioholiday`
  regains `dance` (their old files lacked these emotes); `lucariocafe.geo.json` received the
  two `guiding_plate` bones that the standard `specialist` animates (textures already carried
  the pixels, no texture edits needed).

## 1.7.5 — 2026-10-25

* **Clean mid-session toggle:** turning sync OFF in the settings while on a server now closes
  the WebSocket channel immediately and clears remote emotes — before, a live socket stayed
  connected until the next relog, incoming events kept arriving and `live` in `/health` stayed
  inflated.
* WS messages are sent strictly one at a time — fixes the 1.7.0 "hello then watch" race that
  could trip the JDK's "Send pending" error and silently drop the channel to fallback polling.

## 1.7.4 — 2026-10-25

* **Morph sync over WebSocket:** the same socket that carries emotes now also carries morphs.
  The service (1.5.1+) pushes a `morph` event to sockets watching that player and includes the
  watched set's morphs in every snapshot — a morph change and a newly joined player's morph
  arrive instantly instead of within the next poll cycle (older services keep working: HTTP
  poll stays as the fallback).
* **Presence marker:** with no morph chosen (fresh install, "turn back to normal", remember
  disabled) the client publishes the special variant `__present__` instead of leaving no record
  (service 1.5.3+). This lets the service tell "mod installed, default skin" apart from "no mod
  at all" — the `mods` counter in `/health`. Incoming markers are treated as "no morph" and
  never rendered; the marker is heartbeated like a morph.
* Sync is enabled by default (`syncEnabled: true`).

## 1.7.3

* Presence marker groundwork (see 1.7.4).

## 1.7.2

* Instant morph updates groundwork (see 1.7.4).

## 1.7.1

* **WebSocket reconnect fix:** 1.7.0 could spawn several parallel WS handshakes (one per client
  tick until the callback arrived). Every surplus socket stayed connected as an "orphan" (the
  JDK auto-answers server pings), so the service counted dead connections — the inflated
  `live: N` in `/health`. Now a single handshake is kept in flight, a replaced socket is closed
  and orphan deaths are ignored. No service-side changes needed.

## 1.7.0 — 2026-10-25

* **Emote menu (`J`)** — animation preview screen: every manually playable animation of the
  current morph (dance, specialist, battle idle alt, faint, sense aura, T-pose for rigging
  checks) with localized labels and a `▶` marker on the playing one.
* **Emote sync:** emotes are visible to OTHER players — start/stop published the moment it
  happens (one network round trip; the real-time channel is a WebSocket on the same port,
  measured p50 ≈ 11 ms server time + RTT). Only nearby players receive emotes. Delivery is
  layered: push → backup HTTP poll every 2 s → snapshot when the nearby set changes → a crashed
  client's emote is extinguished by the service after ~30 s. Each viewer keeps its own clock;
  stopping holds the last frame during the crossfade. No separate switch: emote sync rides
  `syncEnabled`. Mirrors older than 1.5.0 don't carry emotes — the mod silently uses the
  mirrors that do.

## 1.6.0 / 1.6.1 — 2026-10-24

* GUI form picker with Type/Shiny toggles that remember their state; keybinds `L`/`J` in their
  own "Lucario Morph" category in Controls; config screen registered in Mod Menu.
* Dance animation; attack pose now plays on left-click attacks only (breaking blocks keeps the
  plain arm swing).
* Smooth pose transitions: every pose switch (glide, stance, dance, walk↔flight) eases through
  exponential blend weights instead of snapping; smooth elytra transition on takeoff/landing.
* 1.6.1: picker buttons show localized display names ("Lucario (PMD)" instead of "pmd").

## 1.4.4 — 2026-10-23

* Fixed a stale morph model after switching variants.

## 1.4.3 — 2026-10-23

* Fixed per-player models.

## 1.4.2 — 2026-10-22

* Record TTL on the service reduced from 30 days to 6 hours; added the client heartbeat — the
  current morph is silently re-published every 15 minutes (`heartbeatMinutes`).
* English localization of the mod itself.

## 1.4.1 — 2026-10-22

* Sync updates; mod icon added.

## 1.4.0

* **Automatic record protection:** without it any player who knows your UUID could "draw"
  someone else's morph onto you on a mirror. The record is bound to your account as soon as a
  mirror answers 401: the mod proves ownership through the Mojang session servers
  (challenge → join → verify; the `accessToken` goes only directly to Mojang over HTTPS —
  mirrors never see it) and receives a write token; afterwards mirrors accept its PUT/DELETE
  only with the token. Failed attempts are retried automatically (2×30 s, 2×60 s, then every
  5 min). Opt-out: `"autoAuth": false`. Licensed accounts only (Mojang rejects offline joins);
  everything else keeps working on offline clients. The config can be deleted —
  re-authorization returns the same token.

## 1.3.0

* **External sync service introduced:** player morphs are synced through an external HTTP
  service instead of server-side world storage (which remained as a duplicate channel until
  1.8.0). Mirrors are configurable in `config/lucariomorph.json`.

## 1.2.2 — 2026-10-22

* Various fixes.

## 1.2.1 — 2026-10-22

* Various fixes.

## 1.2.0 — 2026-10-22

* Ported the mod to Minecraft 26.3 (alpha).

## 1.1.7 — 2026-10-22

* Elytra fix; versioning fix.

## 1.1.6 — 2026-10-22

* Initial public version.

---

## Tuning reference (advanced)

Render proportions and poses are driven by named constants in the source — useful if you want
to fine-tune the look locally:

* First-person arm too big/small/sticks sideways — `FIRST_PERSON_ARM_SCALE`,
  `FIRST_PERSON_ARM_ROLL_DEG` in `LucarioRenderer`.
* Held item too big/shifted — `HELD_ITEM_SCALE_*`, `HAND_ITEM_OFFSET_*`, `HELD_ITEM_INWARD_*`
  in `LucarioRenderer` (and the rotation order in `renderHeldItem`).
* Sneaking too deep/shallow — `CROUCH_HIP_DEG`, `CROUCH_TORSO_LEAN_DEG`, `CROUCH_GROUND_FIX`
  in `LucarioModel` (sneak angles are computed from the model's own leg lengths, so they fit
  both Lucario and Riolu).
* Swing too sharp/weak — `SWING_ARM_PITCH`, `SWING_BODY_TWIST` in `LucarioRenderer`.
* Elytra sits too far from the back / clips — `ELYTRA_BACK_*` (separately for Lucario, Mega and
  Riolu); wing size — `ELYTRA_SCALE_*`. Texture: elytra from the skin, otherwise the player's
  cape (vanilla rules; custom elytra textures are not picked up).
* Legs/arms bend "the wrong way" — flip `ROT_SIGN_X / Y / Z` to `-1f` in `LucarioModel`.
* Lucario too big/small — `TARGET_HEIGHT_BLOCKS` in `LucarioModel`.
* Model "levitates" or feet sink — `GROUND_SINK_PX` in `LucarioRenderer` (lowering in
  standing/walking/sneaking poses only; not applied in flight, swimming and vehicles).
* Arms/tail drift the wrong way during animation — flip the sign of the `position` offset in
  `LucarioRenderer.pose` (`b[0] + px` → `b[0] - px`).
* Swimming/crawling pose — both arms stretch forward, constants `ARMS_FORWARD_*` in
  `LucarioRenderer`.

## Detailed feature notes

* **First-person arm:** the Lucario/Riolu arm is drawn with its own texture; swinging, swing
  and position are taken from the vanilla arm renderer, so everything moves as usual.
* **Punching:** the full strike pose (arm swing, torso twist, chest lean, fist) plays on
  left-click attacks — hitting mobs and air punches; breaking blocks uses the same arm swing;
  right-click item use keeps a plain vanilla-like swing.
* **Battle stance:** after attacking or taking damage Lucario holds a combat stance for ~2 s;
  it crossfades in/out and works while walking (legs blend stance ↔ walk).
* **Emote menu:** an emote loops until interrupted (moving, mounting, water, elytra, damage);
  the camera switches to third person for the duration and returns afterwards.
* **Death animation:** the model plays its "faint" animation (Mega Lucario's pack ships it as
  `battle_faintwip` — both names are tried).
* **Rendering pipeline:** Bedrock `.geo.json` → `ModelPart` (including cubes with their own
  rotation, mirror, inflate). Animations: ground_idle, ground_walk, ground_run, water_idle,
  water_swim, sleep, blink. Molang expressions (`math.sin/abs/clamp`, `q.anim_time`) are
  evaluated by a built-in mini-interpreter.
* **Head and HUD:** the head follows the camera; the player name, shadow and hurt flash work
  as usual.
* **Hidden face emotions:** anger, a smile, an open mouth etc. are disabled by default —
  in the original pack they are enabled by separate animations. The list of hidden bones is
  `LucarioModel.hiddenByDefault`.
