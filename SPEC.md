# Anti-Freecam Plugin: Functional Specification (Clean-Room)

This document describes the externally observable behavior and interfaces of a Minecraft server plugin that hides underground terrain and entities from players who are above ground. It is meant for an engineer who will implement the plugin from scratch. It describes *what* happens, not how the reference implementation was structured.

Keywords: **MUST** = required for parity, **SHOULD** = recommended (often fixes a defect in the reference build), **MAY** = optional.

---

## 1. Platform and Build

### 1.1 Target runtime
- Server software: **Paper** (primary) and **Folia** (must be fully supported). Spigot is not a target.
- Minecraft: 1.20 and newer. Compiled against **Paper API `io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT`**. Declared `api-version` is `1.20`.
- Java: **21**. Both the toolchain and the bytecode target are 21.
- Reference language: Kotlin 2.2.20 (JVM). The reimplementation may use Java or Kotlin. If it uses Kotlin, the Kotlin stdlib MUST be bundled in the plugin jar.

### 1.2 Build tooling (reference values, may be reproduced)
- Gradle 9.2.1 (wrapper), Kotlin Gradle plugin 2.2.20, Shadow plugin `com.gradleup.shadow` 9.2.1, settings plugin `org.gradle.toolchains.foojay-resolver-convention` 0.8.0.
- Output: one shaded jar named `<PluginName>-<version>.jar` with no classifier. The plain (unshaded) jar task is disabled. Service files are merged. Nothing is relocated.
- Resource filtering: the plugin descriptor is processed with `version` and `name` properties, using UTF-8.
- Reference CI, informational only: on tag push or GitHub release, set up Temurin JDK 21, run the shadow jar task, and attach `build/libs/<PluginName>-*.jar` to the release.

### 1.3 Dependencies

| Coordinate | Version | Scope | Repository |
|---|---|---|---|
| `io.papermc.paper:paper-api` | 1.21.8-R0.1-SNAPSHOT | compile-only | https://repo.papermc.io/repository/maven-public/ (snapshots: …/maven-snapshots/) |
| `com.github.retrooper:packetevents-spigot` | 2.10.1 | compile-only (provided at runtime by the separate PacketEvents plugin) | https://repo.codemc.io/repository/maven-releases/ (snapshots: …/maven-snapshots/) |
| `com.github.ben-manes.caffeine:caffeine` | 3.1.8 | **shaded**, not relocated | Maven Central |
| Kotlin stdlib (if Kotlin) | matches compiler | **shaded**, not relocated | Maven Central |
| `org.geysermc.geyser:api` | 2.4.2-SNAPSHOT | compile-only, non-transitive (optional) | https://repo.opencollab.dev/maven-snapshots/ (releases: …/maven-releases/) |
| `org.geysermc.floodgate:api` | 2.2.3-SNAPSHOT | compile-only, non-transitive (optional) | same as above |

The Geyser and Floodgate APIs MUST be accessed in a way that does not fail class loading when those plugins are absent, for example through reflection or guarded class lookups. The reference used reflection only.

The bounded, expiring caches described in §9 may be built with Caffeine or any equivalent.

### 1.4 Load order
- `load: STARTUP` (the plugin loads before worlds).
- `depend: [packetevents]`. This is a hard dependency on the PacketEvents plugin (2.10.1 or newer).
- `softdepend: [Geyser, Floodgate]`.
- `folia-supported: true`.

---

## 2. Plugin Descriptor (what the server sees)

Use a classic `plugin.yml`. The reference build had no `paper-plugin.yml`.

- `name`: `ProjectAntiFreeCam`. The name also sets the data folder, `plugins/ProjectAntiFreeCam/`. Keep it if drop-in config compatibility is wanted.
- `version`: `1.0.0`
- `main`: a main class. The implementer chooses the package.
- `api-version`: `'1.20'`
- `authors`, `description`: free text.

### 2.1 Commands

| Command | Aliases | Usage string | Declared permission |
|---|---|---|---|
| `antifreecam` | `afc`, `freecam` | `/antifreecam <debug\|reload\|world\|stats\|test> [args]` | `antifreecam.admin` |
| `afcdebug` | `antifreecamdebug` | `/afcdebug` | `antifreecam.debug` |
| `afcreload` | `antifreecamreload` | `/afcreload` | `antifreecam.admin` |
| `afcworld` | `antifreecamworld` | `/afcworld <list\|add\|remove> [worldName]` | `antifreecam.admin` |

### 2.2 Permissions

| Node | Default | Children | Meaning |
|---|---|---|---|
| `antifreecam.admin` | op | `antifreecam.debug`, `antifreecam.world`, `antifreecam.reload` (all true) | All admin commands |
| `antifreecam.debug` | op | – | Toggle debug mode |
| `antifreecam.world` | op | – | Manage the world whitelist |
| `antifreecam.reload` | op | – | Reload the configuration |

Runtime check: every command handler, including `/afcdebug`, checks for **`antifreecam.admin`** and denies the sender if it is missing (see §8.1). A sender who has only `antifreecam.debug` is therefore denied. Keep this for parity, or see §12.

---

## 3. Core Concepts

- **Whitelisted world**: a world whose name is in `worlds.whitelist`. Names are exact and case-sensitive. Protection applies only in these worlds.
- **Protection threshold `P`**: `antixray.protection-y-level`, a double with default 31.0.
- **Hide ceiling `H`**: `antixray.hide-below-y`, an int with default 16. Block or entity Y values **≤ H** count as "underground".
- **Hidden state**: a per-player boolean in a thread-safe map keyed by player UUID. It is `true` when the player is "above ground" and their view must be filtered. A player with no entry is treated as `false` by the packet filters.
  - Normal formula: `hidden = underground-protection.enabled AND playerY >= P`.
- **Replacement block**: the block state that every hidden block is shown as. It comes from `performance.replacement.block-type`.
- **View refresh**: making the server resend chunk data for a square of chunks around the player (Paper's `World#refreshChunk(x, z)`). The resent chunks go through the packet filter again (§5), so the client sees the result of the player's current hidden state.

---

## 4. Lifecycle

### 4.1 Load phase
1. Get the PacketEvents API instance. If it is null, or a `NoClassDefFoundError` or any other exception occurs, log a severe error saying that PacketEvents must be installed and loaded first (include the download location `https://github.com/retrooper/packetevents/releases`) and disable the plugin.
2. The reference build then called the API's `load()` and checked `isLoaded`, disabling the plugin on failure. See §12 for the standalone PacketEvents plugin.

### 4.2 Enable phase (order matters)
1. Print a startup banner to the console (free-form, colored). It names the plugin, version, detected platform ("Folia" or "Paper/Spigot"), and author. Log one line of platform info: platform, server version string, Minecraft version parsed from the Bukkit version (`X.Y.Z` before the first `-`, falling back to 1.21.0), and whether the Moonrise chunk system class exists.
2. Detect Folia by checking for the class `io.papermc.paper.threadedregions.scheduler.RegionScheduler`. Choose the chunk-refresh strategy: Folia strategy if on Folia, otherwise Paper strategy (§7).
3. Create the Bedrock detector (§10) and the API-level entity hider (§6.2), which registers its event listener.
4. Load the configuration (§4.4).
5. If the PacketEvents API is null or not loaded, log a severe message and disable the plugin.
6. Resolve the replacement block (§4.5). If no block state resolves, log a severe message and disable the plugin.
7. Turn on PacketEvents' update checking setting (reference: `true`). Register two packet listeners (chunk/block and entity), both at **NORMAL** priority. Register the Bukkit event listeners and the command executors and tab completers.
8. On the next tick (global scheduler), the reference called PacketEvents `init()` if the plugin was still enabled. See §12.
9. Initialize state (§4.6, `immediate = false`) for every online player in a whitelisted world. This covers plugin reloads made while players are online.

### 4.3 Disable phase
- Print a short shutdown banner to the console.
- Reference build: call PacketEvents `terminate()` if loaded (see §12).
- Stop the Paper refresh processor and clear its queue and throttles. Clear all hidden states.
- If RAM caching is enabled, log the cache statistics and then clear all caches.
- Log a line saying the plugin is disabled.

### 4.4 Configuration loading (runs on enable and on reload)
- Save the default `config.yml` if it is missing, then re-read the file.
- **Legacy migration**: if `worlds.whitelist` is empty and a top-level list `whitelisted-worlds` is non-empty, copy it to `worlds.whitelist`, delete `whitelisted-worlds`, save the file, and log that migration happened.
- Read every key in §11 into memory. Normalize the replacement block id: if it has no `minecraft:` prefix, add one.
- Apply the RAM-caching switch (§9). When it is disabled, clear the shared caches.
- Log one info line saying whether RAM caching is enabled or disabled.

### 4.5 Replacement block resolution
- Resolve the configured id to a PacketEvents block state, using a string lookup such as `minecraft:air`, and keep its global state id.
- On failure, log a warning and try `minecraft:air`, then `minecraft:stone`, then `minecraft:dirt`. Use the first that works, record it as the effective type, and log the fallback.
- If none resolve, disable the plugin (§4.2 step 6).

### 4.6 Initial state for a player (used on join, enable, reload, world change, world add)
Input: player and `immediate` flag.
- **Given** the player's world is not whitelisted, **then** remove their hidden-state entry. **If** an entry existed and `immediate` is true, request a view refresh.
- **Given** the world is whitelisted, **then** set `hidden = underground-protection.enabled AND y >= P`. Request a view refresh **if** `immediate` is true **or** `hidden` is true.

---

## 5. Feature: Packet-Level Terrain Hiding

Runs on the PacketEvents send pipeline, which is the netty thread and not the main or region thread. It MUST use only thread-safe state.

Preconditions, checked for every outgoing packet:
1. The packet's user has a UUID that resolves to an online player.
2. The player's current world is whitelisted.
3. The player's hidden state is `true`.

If any precondition fails, the packet is left untouched.

### 5.1 `CHUNK_DATA` (Play, server-bound to client)
Toggle: `advanced.packet-processing.modify-chunk-data` (default true).
- **Given** a hidden player receives a chunk, **then** every block position whose world Y is **≤ H** is rewritten to the replacement block in each non-null, non-empty chunk section. The section index maps to Y as `section_min_y = world_min_height + index × 16`. Positions already equal to the replacement state are skipped.
- **If** anything changed, set the chunk packet's "ignore old data" flag (where supported) and mark the packet for re-encoding.
- Blocks above H are untouched. Block entities, heightmaps, light data, and biomes are **not** changed by the reference build (§12).
- Errors on individual blocks are swallowed and logged only in debug mode. If the packet cannot be wrapped, or its column or sections are null, a severe or warning message is logged and the packet is sent unmodified.

### 5.2 `BLOCK_CHANGE`
Toggle: `advanced.packet-processing.modify-block-changes` (default true).
- **Given** a hidden player and a single block update at Y ≤ H whose state is not the replacement, **then** the state is rewritten to the replacement block and the packet re-encoded.
- **Given** Y > H, **then** the packet is untouched.

### 5.3 `MULTI_BLOCK_CHANGE`
Toggle: `advanced.packet-processing.modify-multi-block-changes` (default true).
- For each non-null record with Y ≤ H whose block id is not the replacement's global id, set the id to the replacement's global id. Re-encode if any record changed.
- If the record list is null, log a warning and leave the packet untouched.

### 5.4 Acceptance criteria
- **Given** a player standing at Y=64 in a whitelisted world with defaults, **when** chunks are sent, **then** everything from the world's minimum height through Y=16 inclusive shows as air, and Y≥17 is normal.
- **Given** the same player at Y=20 (below P), **then** chunks are sent unmodified.
- **Given** a non-whitelisted world, **then** no packet is modified.
- **Given** `performance.underground-protection.enabled: false`, **then** hidden state is never true through the normal paths, so nothing is hidden (but see the teleport exception in §6.4).

---

## 6. Feature: Hidden-State Transitions and Entity Hiding

### 6.1 Packet-level entity hiding
Applies only when `entities.hide-entities` is true, the viewer is online in a whitelisted world, and the viewer's hidden state is true.

"Hideable" means the entity type is in the configured type set (§11, `entities.hidden-types`), **or** the type is `PLAYER`. Players are always hideable, regardless of configuration.

Two global caches keyed by protocol entity id (global, not per viewer):
- entity id → last known Y. Expires 30 s after write. Capacity is 10,000 when RAM caching is off and 50,000 when it is on.
- entity id → entity type. Expires 60 s after write. Capacity 10,000.

These caches are only updated by packets that pass the preconditions above, so only when some hidden viewer is receiving them.

| Packet (Play, server) | Behavior |
|---|---|
| `SPAWN_ENTITY` | Record Y and type. To find the type, take the PacketEvents entity type name, strip `minecraft:`, uppercase it, turn spaces into `_`, and match a Bukkit `EntityType` constant by name. If the match fails, the type is unknown. **Cancel** the packet if Y ≤ H and the type is known and hideable. |
| `SPAWN_PLAYER` (legacy clients/versions) | Record Y and type PLAYER. **Cancel** if Y ≤ H. |
| `ENTITY_METADATA` | If the cached type is known and hideable, and the cached Y exists and is ≤ H, **cancel**. Otherwise pass it through. |
| `ENTITY_TELEPORT` | Update the cached Y. If the cached type is known and hideable and the new Y ≤ H, **cancel**. |

Already-rendered entities are **not** destroyed when the viewer becomes hidden. Only new spawn, metadata, and teleport packets are blocked. Relative-move, equipment, and similar packets are not filtered.

Any exception in these handlers is swallowed (debug log only), and the packet is then sent unmodified.

Acceptance:
- **Given** a hidden viewer and an armor stand spawning at Y=10, **then** the viewer never receives its spawn.
- **Given** a hidden viewer and another player at Y=5 coming into tracking range, **then** that player's spawn is cancelled.
- **Given** a hidden viewer and a zombie at Y=10 that is *not* in the configured list, **then** it is shown.
- **Given** the viewer is not hidden, **then** all entity packets pass through.

### 6.2 Server-API entity hiding (second layer)
Uses Paper's per-player `hideEntity(plugin, entity)` and `showEntity(plugin, entity)`. The plugin tracks each (viewer, entity) pair it has hidden, so it only shows entities it hid itself.

This layer uses its own "above" test: `viewerY > P`, strictly greater. That differs from the `>=` used for hidden state, so at exactly Y = P the two layers disagree (§12). It also needs `entities.hide-entities` to be true.

- **Crossing trigger**: called from the movement handler only when the hidden state flips (§6.3). It compares `from.y > P` with `to.y > P`. **If** they differ:
  - Now above: on the viewer's own scheduler, find entities around the viewer within a horizontal half-extent of `min(viewer client view distance, 10) × 16` blocks and a vertical half-extent of 256, keeping non-player entities whose type is in the configured set. Hide each one whose Y ≤ H, running on the entity's own scheduler on Folia.
  - Now not above: for the same box (non-player configured types), show each entity this plugin had hidden from this viewer.
- **Spawn trigger**: on `EntitySpawnEvent`, if the entity's Y ≤ H and its type is in the configured set, then 1 tick later on the entity's scheduler hide it from every online player whose Y > P. The reference did not check the viewer's world, whitelist, or underground-protection flag (§12).
- If the configured type list is empty, this layer keeps its built-in default set (§11).
- Failures to hide or show are logged as warnings.

### 6.3 Movement (`PlayerMoveEvent`, default priority)
- **Given** the player's world is not whitelisted, **then** remove their hidden-state entry. If one existed, request a refresh (§12: the reference refresh is a no-op here). Then stop.
- **Given** the block Y (floor of Y) did not change between `from` and `to`, **then** do nothing.
- Otherwise compute:
  - `old = stored state`, or if there is none, `to.y >= P`;
  - `new = underground-protection.enabled AND to.y >= P`.
- **If** `new ≠ old`, store `new`, run the §6.2 crossing trigger, and then:
  - **Becoming visible (new = false)**: after 2 ticks, if the player is still online, request a view refresh. After 2 further ticks, if still online, do an entity re-sync. On the player's region, gather all entities within a 64×64×64 half-extent box around the player (all types, including players). Hide each from the player on its own scheduler. After 2 ticks, show each again on its own scheduler, skipping invalid entities or an offline viewer. This forces the client to receive fresh spawns for entities that were filtered earlier.
  - **Becoming hidden (new = true)**: apply the per-player refresh cooldown. If `now ≥ cooldown expiry` (no entry means expired), request a view refresh and set expiry = now + `settings.refresh-cooldown-seconds × 1000` ms. Otherwise skip the refresh. The state is still updated, so new chunk packets are filtered.

Acceptance:
- **Given** a player climbs from Y=25 to Y=31.0, **then** they become hidden and nearby chunks are refreshed so underground blocks vanish.
- **Given** they bob between Y=30 and Y=31 several times within 3 s, **then** at most one hide-direction refresh happens per 3 s. Every reveal-direction transition still refreshes.
- **Given** a player descends from Y=40 to Y=30, **then** chunks refresh about 2 ticks later, and nearby entities re-appear about 2 to 4 ticks after that.

### 6.4 Teleport (`PlayerTeleportEvent`, priority HIGHEST, ignore cancelled)
- Teleports started by the plugin itself (see below) are ignored. They are tracked with a per-player "internally teleporting" flag.
- **Given** the destination world is not whitelisted: if the source world was whitelisted and the player had a state entry, remove it and schedule a refresh on the destination region (§12: no-op). Stop.
- In this handler the reference used a **hard-coded threshold 31.0** and ignored the underground-protection flag (§12):
  - `old = stored state`, or if there is none, `to.y >= 31`;
  - `new = to.y >= 31`.
- **If** `old == new`, do nothing.
- **If** `new` is true, store `true`. No refresh is requested.
- **If** `new` is false, store `false`. If `advanced.teleport-handling.manage-teleport-states` (default true) is on: **cancel** the event. After `advanced.teleport-handling.delay-ticks` ticks (default 1), on the destination's region, if the player is still online, set the internal flag and teleport the player to the same destination. Use async teleport on Folia and a normal teleport on Paper. Clear the flag when the teleport completes, and log failures in debug mode. Net effect: a downward teleport is delayed by about 1 tick so the state is already "visible" when the destination chunks are sent.

### 6.5 Join, Quit, World Change, Respawn, Gamemode
- **Join** (`PlayerJoinEvent`): run Bedrock detection, which only produces a debug or info log. If the world is whitelisted, initialize state (§4.6) with `immediate = false`.
- **Quit** (`PlayerQuitEvent`): remove hidden state, refresh cooldown, refresh throttles and queued refresh jobs (§7), Bedrock detection cache entries, and per-player shared-cache entries. The reference did **not** clear the API-layer (§6.2) hidden-pair tracking. It SHOULD be cleared (§12).
- **World change** (`PlayerChangedWorldEvent`): if the new world is whitelisted, initialize state with `immediate = true`. Otherwise remove the state and refresh if an entry existed (no-op, §12).
- **Respawn and gamemode change**: no dedicated handling. The state stays at its previous value until the next block-Y change, teleport, or world change. Spectator and creative players are treated like everyone else; there is no bypass permission.

---

## 7. Feature: View Refresh

A refresh request for a player:
1. Do nothing if the player is offline or their world is not whitelisted.
2. On Folia, if the current thread does not own the player's region, re-schedule the request onto the player's region.
3. Compute the radius in chunks:
   - Start with the server view distance.
   - If `performance.limited-area.enabled`, cap it at `performance.limited-area.chunk-radius`.
   - If the player is detected as Bedrock and Bedrock optimizations apply, use `max(minimum-chunk-radius, radius − chunk-radius-reduction)` (only when the reduction is > 0).
   - If `instant-protection.enabled` and `force-immediate-refresh`, and `playerY ≤ P + pre-load-distance` and `playerY > H`, use `max(radius, instant-load-radius)`.
4. Refresh every chunk in the square `[cx−r, cx+r] × [cz−r, cz+r]` around the player's chunk, using one of these strategies:

**Paper strategy (non-Folia):**
- Per-player throttle: a request within 50 ms of that player's previous accepted request is dropped.
- Enqueue the chunks in square rings from the center outward (ring 0, then ring 1, …).
- A repeating task runs every tick (start delay 1). Each run it takes up to `performance.max-chunks-per-tick` (default 50) queue entries and refreshes each one. Entries are skipped if the player is offline or in a different world. Entries whose chunk is not loaded are skipped and do not count toward the budget.

**Folia strategy:**
- Per-player throttle: a request within 100 ms of that player's previous accepted request is dropped.
- Group the chunks by Folia region (block coordinates shifted right by 9, i.e. 32×32-chunk areas, plus the world name). Schedule each group on the region scheduler at that location.
- Each run refreshes up to `max-chunks-per-tick` chunks, but only when the current thread owns the chunk. If it does not, the chunk is put back in the queue and this run ends. Leftovers are rescheduled 1 tick later.
- Failures are logged as warnings with the chunk coordinates.

Acceptance:
- **Given** view distance 10 and defaults, **when** a player crosses to Y=32, **then** the radius becomes max(10, 15) = 15, because 32 ≤ 31+10 and 32 > 16. Up to 31×31 loaded chunks are resent at ≤ 50 per tick.

---

## 8. Commands

### 8.1 Message conventions
- Player messages get a prefix: `[` + bold dark-aqua (`§3§l`) plugin short name "AntiFreeCam" + dark-aqua `]` + reset + space. Console messages get the plain prefix `[AntiFreeCam] ` and have all color codes stripped.
- Both `&` and `§` legacy codes in message text are translated to colors.
- Message kinds and their leading colors: info = gray `§7`, success = green `§a`, warning = gold `§6`, error = red `§c`. Plain stat lines use yellow labels (`§e`) and green values (`§a`). Section headers are bold dark aqua (`§3§l`).
- Permission denied: an error-kind message saying the sender lacks permission. It is sent when the sender lacks `antifreecam.admin`.
- All wording below is paraphrased. The implementer writes their own text, keeping the data and colors noted.

### 8.2 `/antifreecam` (`/afc`, `/freecam`)
- No arguments: an info line with the usage string from §2.1.
- The first argument is case-insensitive: `debug`, `reload`, `world`, `stats`, `test`. Remaining arguments are passed on.
- Any other first argument, including `help`: an error saying the subcommand is unknown, naming it.

### 8.3 Debug (`/afc debug`, `/afcdebug`)
- Toggle debug mode, write `settings.debug-mode` to the config file, and save it.
- Reply with a success message: debug mode is now "ENABLED" in green or "DISABLED" in gray.
- While debug is on, verbose log lines are written to the server log (state transitions, packet modifications, cache refreshes, entity hide/show). Their wording is free.

### 8.4 Reload (`/afc reload`, `/afcreload`)
1. Re-read the config (§4.4). Refresh all derived settings in the packet listeners, entity hider, and Bedrock detector. The Bedrock detector clears its detected-player cache.
2. Reply with a success message (configuration reloaded), an info line listing the active worlds in set notation (for example `[world, mining_world]`, value in aqua `§b`), and an info line with the number of Bedrock players currently detected (light purple label `§d`).
3. For every online player: if in a whitelisted world, initialize state with `immediate = true`. Otherwise remove the state and refresh if an entry existed.

Changing the replacement block takes effect only after a server restart or plugin re-enable. The reference reload did not re-resolve the block state (§12).

### 8.5 World whitelist (`/afc world …`, `/afcworld …`)
- No action: an info usage line (add, remove, list, and an optional world).
- `list`: if empty, an info line saying no worlds are whitelisted. Otherwise a success line with the names joined by `, `.
- `add <world>`:
  - Missing name: an error asking for a world name.
  - World not loaded on the server: an error saying the world was not found, naming it.
  - Name not yet whitelisted: add it, write `worlds.whitelist`, save, send a success "added" message, and initialize state with `immediate = true` for every online player in that world.
  - Name already whitelisted: **no output**.
- `remove <world>`:
  - Missing name: an error.
  - Name whitelisted: remove it, write and save, send a success "removed" message, and for every online player in that world clear the state and request a refresh (§12).
  - Name not whitelisted: **no output**.
- Any other action: **no output**.
- World names are case-sensitive.

### 8.6 Stats (`/afc stats`)
Output lines, in order:
1. Header: plugin statistics.
2. Version (the reference printed a hard-coded "1.3.0". Use the real plugin version.)
3. Platform: "Folia" or "Paper/Spigot".
4. Active players: number of entries in the hidden-state map, including entries that are `false`.
5. Active worlds: number of whitelisted worlds.
6. Debug: ON or OFF.
7. Header: RAM cache statistics.
8. RAM caching: ENABLED or DISABLED.
9. Only if enabled:
   - Cached entity positions count.
   - Chunk metadata count.
   - Visibility decision count. Both of these are always 0 in practice and may be shown as 0.
   - Estimated RAM in MB, 2 decimals. The reference estimated 16 bytes per entity-position entry plus fixed per-entry sizes for the other, empty caches, divided by 1,000,000.
   - Header: cache performance.
   - Hits.
   - Misses.
   - Hit rate %, 2 decimals, where hit rate = hits / (hits + misses) × 100 and 0 if there were no lookups. Hits and misses count only entity-Y lookups made while handling `ENTITY_METADATA` with RAM caching on.
   - Rating line: rate > 80 → green "excellent"; rate > 50 → yellow "moderate"; else red "poor".
10. Bedrock status, a multi-line block:
    - Disabled, or enabled with: Geyser API available ✓/✗, Floodgate API available ✓/✗, UUID-pattern detection on ✓/✗, name-prefix detection on ✓/✗ with the prefixes, and the count of detected Bedrock players online.
11. Refresh strategy stats:
    - Folia: number of tracked regions (always 0 in the reference) and number of players with a throttle entry.
    - Paper: queue length and number of players with a throttle entry.

### 8.7 Test (`/afc test …`), players only
- Console or other non-player sender: an error saying players only.
- `block`: an info header, then the effective replacement block id (for example `minecraft:air`) and its global state id.
- `state`: an info header, then the player's Y (1 decimal), P as read from config, and the player's hidden state (`true`/`false`, `false` when there is no entry).
- `refresh`: if the player's world is whitelisted, request a view refresh and send a success "view refreshed" message. Otherwise a warning that the world is not whitelisted.
- No argument or anything else: an info line listing `block, state, refresh`.

### 8.8 Tab completion
Prefix matching, case-insensitive (Bukkit `copyPartialMatches` semantics):

- `/antifreecam`:
  - argument 1 → `debug, reload, world, stats, test`.
  - argument 2 after `world` → `list, add, remove`.
  - argument 2 after `test` → `block, state, refresh`.
  - Anything else → none.
- `/afcworld`:
  - argument 1 → `list, add, remove`.
  - argument 2 after `add` → names of all loaded worlds.
  - argument 2 after `remove` → currently whitelisted names.
- `/afcdebug` and `/afcreload`: no suggestions.

---

## 9. Caching and Performance Behavior

`performance.ram-caching` (default true) chooses between two cache profiles. It is read on enable and on reload. Turning it off clears the shared caches.

| Data | RAM caching ON | RAM caching OFF |
|---|---|---|
| Entity id → Y | 50,000 entries, 30 s expire-after-write; hit/miss counted | 10,000 entries, 30 s; not counted |
| Bedrock yes/no per UUID (packet path) | 1,000 entries, 5 min expire-after-write | 500 entries, 30 s |
| Entity id → type | 10,000 entries, 60 s (same in both modes) | same |

- On quit, per-player entries are invalidated. On disable (RAM caching on), statistics are logged and everything is cleared.
- Config values used on the packet path MUST be held in memory (volatile fields or similar) and not read from the config object per packet.
- Packet handlers run off the main thread. They MUST NOT block, and MUST use concurrent collections for shared state.
- On Folia, every entity hide/show runs on the entity's scheduler. Nearby-entity queries run on the viewer's region or entity scheduler. Chunk refreshes run on the owning region. Teleports use async teleport.

---

## 10. Feature: Bedrock (Geyser/Floodgate) Detection

Purpose: find Bedrock-edition players so their refresh radius can be reduced (§7 step 3). Detection does not change hiding behavior in any other way.

- Master switch: `bedrock.enabled`. When it is false, nobody is Bedrock.
- Methods, tried in order until one matches. Each can be toggled:
  1. **Geyser API** (`use-geyser-api`): only if a plugin named `Geyser-Spigot` or `Geyser` is enabled. Get the API singleton from `org.geysermc.geyser.api.GeyserApi` (static `api()`) and call `isBedrockPlayer(UUID)`.
  2. **Floodgate API** (`use-floodgate-api`): only if a plugin named `floodgate` is enabled. Use `org.geysermc.floodgate.api.FloodgateApi.getInstance().isFloodgatePlayer(UUID)`.
  3. **UUID pattern** (`use-uuid-pattern`): the UUID string starts with `00000000-0000-0000-`.
  4. **Name prefix** (`use-name-prefix`): the player name starts with any entry of `name-prefixes`. If the list is empty, use the built-in prefixes `.`, `*`, `!`, `BE_`.
- API handles are looked up lazily on first use. An info line is logged when an API is found, and a warning when lookup fails. Errors during a check count as "not Bedrock".
- Positive results are cached until quit or reload. When `bedrock.logging.log-detection` is true, an info line is logged with the player name, plus the method name when `log-detection-method` is true.
- Radius reduction applies only if `bedrock.optimizations.enabled` (default true; not in the default file) and `chunk-radius-reduction > 0`.

Acceptance:
- **Given** a Floodgate player with UUID `00000000-0000-0000-0009-…` and no Geyser or Floodgate plugin installed, **then** they are detected by UUID pattern. With view distance 8 and the instant-load radius not applying, their refresh radius is max(2, 8−1) = 7.

---

## 11. Configuration Reference (`config.yml`)

The default file shipped in the jar contains the keys marked **(file)**. Keys marked **(code-only)** are read with the shown default but are absent from the shipped file. Keys marked **inert** are read or shipped but have no behavioral effect in the reference build. They MAY be kept for compatibility.

| Path | Type | Default | Effect |
|---|---|---|---|
| `settings.debug-mode` (file) | bool | false | Verbose logging. Toggled and persisted by the debug command. |
| `settings.refresh-cooldown-seconds` (file) | int | 3 | Per-player cooldown for refreshes when becoming hidden (§6.3). |
| `settings.view-distance` (code-only, inert) | int | -1 | Read but unused. |
| `worlds.whitelist` (file) | list<string> | `["world", "mining_world"]` | Worlds where protection is active. |
| `whitelisted-worlds` (legacy) | list<string> | – | Migrated into `worlds.whitelist` (§4.4). |
| `antixray.protection-y-level` (file) | double | 31.0 | Threshold P. |
| `antixray.hide-below-y` (file) | int | 16 | Ceiling H (inclusive). |
| `antixray.transition.enabled` (file, inert) | bool | true | Not read. |
| `antixray.transition.zone-size` (file, inert) | int | 5 | Not read. |
| `antixray.transition.smooth-transition` / `transition-zone-size` (code-only, inert) | bool / int | true / 5 | Read but no effect. |
| `entities.hide-entities` (file) | bool | true | Enables both entity-hiding layers (§6.1, §6.2). |
| `entities.hidden-types` (file) | list<string> | see below | Bukkit `EntityType` names, case-insensitive. Unknown names are silently ignored. |
| `performance.ram-caching` (file) | bool | true | Cache profile (§9). |
| `performance.instant-protection.enabled` (file) | bool | true | §7 step 3. |
| `performance.instant-protection.instant-load-radius` (file) | int | 15 | Minimum refresh radius in the pre-load band. |
| `performance.instant-protection.pre-load-distance` (file) | int | 10 | Band upper bound = P + this. |
| `performance.instant-protection.force-immediate-refresh` (file) | bool | true | Must also be true for the radius boost. |
| `performance.limited-area.enabled` (file) | bool | false | Caps the refresh radius. Does **not** limit packet filtering. |
| `performance.limited-area.chunk-radius` (file) | int | 3 | The cap. |
| `performance.limited-area.apply-only-near-player` (file, inert) | bool | true | Not used. |
| `performance.max-chunks-per-tick` (file in README; code default) | int | 50 | Refresh budget per tick (§7). Not in the shipped file but read. |
| `performance.max-entities-per-tick` (inert) | int | 100 | Not used. |
| `performance.replacement.block-type` (file) | string | `"air"` | Replacement block id. A `minecraft:` prefix is added if missing. |
| `performance.underground-protection.enabled` (file) | bool | true | Master switch for the hidden state (§3). |
| `advanced.teleport-handling.delay-ticks` (code-only) | int | 1 | §6.4 re-teleport delay. |
| `advanced.teleport-handling.manage-teleport-states` (code-only) | bool | true | §6.4 cancel and re-teleport. |
| `advanced.packet-processing.modify-chunk-data` (code-only) | bool | true | §5.1 |
| `advanced.packet-processing.modify-block-changes` (code-only) | bool | true | §5.2 |
| `advanced.packet-processing.modify-multi-block-changes` (code-only) | bool | true | §5.3 |
| `bedrock.enabled` (file) | bool | true | §10 |
| `bedrock.detection.use-geyser-api` (file) | bool | true | §10 |
| `bedrock.detection.use-floodgate-api` (file) | bool | true | §10 |
| `bedrock.detection.use-uuid-pattern` (file) | bool | true | §10 |
| `bedrock.detection.use-name-prefix` (file) | bool | true | §10 |
| `bedrock.detection.name-prefixes` (file) | list<string> | `["."]` (empty → `. * ! BE_`) | §10 |
| `bedrock.optimizations.enabled` (code-only) | bool | true | §10 |
| `bedrock.optimizations.chunk-radius-reduction` (file) | int | 1 | §7 |
| `bedrock.optimizations.minimum-chunk-radius` (file) | int | 2 | §7 |
| `bedrock.optimizations.lighter-entity-hiding` (code-only, inert) | bool | true | Not used. |
| `bedrock.logging.log-detection` (file) | bool | true | §10 |
| `bedrock.logging.log-detection-method` (file) | bool | false | §10 |
| `config-version` (file, inert) | int | 1 | Not read. |

**Shipped `entities.hidden-types`**, in order:

- Display entities: ARMOR_STAND, ITEM_FRAME, GLOW_ITEM_FRAME, PAINTING.
- Minecarts: MINECART, CHEST_MINECART, HOPPER_MINECART, TNT_MINECART, FURNACE_MINECART, SPAWNER_MINECART.
- Hostile mobs: ZOMBIE, SKELETON, CREEPER, SPIDER, CAVE_SPIDER, ENDERMAN, WITCH, SLIME, MAGMA_CUBE, SILVERFISH, ENDERMITE, GUARDIAN, ELDER_GUARDIAN, SHULKER, PHANTOM, DROWNED, HUSK, STRAY, ZOMBIE_VILLAGER, PILLAGER, VINDICATOR, EVOKER, VEX, RAVAGER, HOGLIN, ZOGLIN, PIGLIN, PIGLIN_BRUTE, WARDEN, BREEZE, BOGGED.
- Passive mobs: PIG, COW, SHEEP, CHICKEN, RABBIT, HORSE, DONKEY, MULE, LLAMA, TRADER_LLAMA, GOAT, FROG, TADPOLE, CAMEL, SNIFFER, ARMADILLO.
- Tameable mobs: WOLF, CAT, PARROT, FOX, OCELOT.
- Aquatic mobs: SQUID, GLOW_SQUID, DOLPHIN, COD, SALMON, TROPICAL_FISH, PUFFERFISH, TURTLE, AXOLOTL.
- Utility mobs: IRON_GOLEM, SNOW_GOLEM, VILLAGER, WANDERING_TRADER, ALLAY, BEE, BAT, STRIDER.

**Built-in fallback type set** (used when the list is empty or missing): ARMOR_STAND, ITEM_FRAME, GLOW_ITEM_FRAME, PAINTING, MINECART, CHEST_MINECART, HOPPER_MINECART, TNT_MINECART, FURNACE_MINECART, SPAWNER_MINECART.

The shipped file should explain each key in comments (written fresh). It should also advise against whitelisting Nether, End, or creative worlds.

---

## 12. Out of Scope / Unknowns / Known Reference Defects

The implementer decides whether to match each item exactly ("parity") or fix it. Recommendations are marked SHOULD.

1. **PacketEvents lifecycle.** The reference called `load()`, `init()`, and `terminate()` on the PacketEvents API even though it depends on the standalone PacketEvents plugin, which manages its own lifecycle. A reimplementation SHOULD only get the API and register listeners. The listeners are unregistered or ignored on disable.
2. **Refresh in non-whitelisted worlds is a no-op.** Refresh requests return early when the player's world is not whitelisted. So the "clear state and refresh" paths (world removed by command, moving or teleporting into a non-whitelisted world, reload) never actually resend chunks. After `/afcworld remove`, players keep seeing filtered terrain until chunks reload. SHOULD refresh unconditionally in those paths.
3. **Whitelist changes from the command are not seen by the packet filters** until `/afc reload`, because the filters cache the whitelist at reload time. SHOULD apply immediately.
4. **Teleport threshold** is hard-coded to 31.0 and ignores `underground-protection.enabled`. SHOULD use P and the switch.
5. **Threshold mismatch**: hidden state uses `y >= P` and the API entity layer uses `y > P`. SHOULD unify.
6. **Entity spawn hiding** (§6.2) ignores the viewer's world and whitelist. API-layer tracking is never cleared on quit, so after a rejoin, entities may be wrongly considered "already hidden". SHOULD filter by world and clear on quit.
7. **Respawn** and **gamemode changes** are not handled (§6.5). SHOULD re-evaluate state on `PlayerRespawnEvent` (after respawn). A bypass permission or a spectator exemption is not present. MAY be added.
8. **Block entities and lighting** inside hidden sections are not stripped from chunk packets. Chests, signs, etc. may still leak through the block-entity list. Out of scope for parity. SHOULD consider removing block-entity entries with Y ≤ H.
9. **Movement packets** (relative move, position sync on 1.21.2+, i.e. `ENTITY_POSITION_SYNC`) and equipment packets are not filtered. An entity whose spawn was cancelled stays invisible to that viewer until it is re-sent, for example by the reveal-direction entity re-sync (§6.3).
10. **Transition-zone settings** are advertised but have no effect. Limited-area only caps the refresh radius.
11. **The replacement block** is not re-resolved on reload.
12. **Version strings**: the descriptor says 1.0.0, while the banner and stats say 1.3.0. Use one source of truth.
13. **The `help` subcommand** is listed in documentation but is not implemented (it gives the unknown-subcommand error). MAY be added.
14. **Concurrency on Folia**: the reference packet handlers read the player's world from the network thread. A reimplementation SHOULD keep a thread-safe per-player world-name snapshot, updated on join, world change, and teleport.
15. The plugin does not change server-side blocks or entities in any persistent way. All hiding is per-viewer and client-side.
