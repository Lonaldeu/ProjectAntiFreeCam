# SPEC Addendum: Findings From a Second Reference Project ("levelsol")

Clean-room notes. This file describes the behavior and ideas of a third-party Y-level anti-xray plugin (repository `levelsol/TazAntixRAY`, default branch, single commit dated July 2025). No code, identifiers, comments or prose were copied. Everything below is behavior, numbers, config keys, and protocol and event names. Statements marked **(ours)** are recommendations from the spec writer, not features of that project.

Section numbers like "§6.3" refer to `SPEC.md`.

---

## 1. What the project is

- **Purpose:** Same idea as SPEC.md: while a player is "high", everything at or below a fixed Y is replaced by air in the terrain packets sent to that player. It looks like an early, much smaller ancestor of the design in SPEC.md. Most of it is a subset of SPEC.md, and the differences are listed in §2.
- **Platform:** Marketed for Folia. It uses only the region, global-region and "is owned by current region" scheduler APIs. Paper 1.20+ also has these, so it would most likely load on Paper too (untested). Descriptor: `api-version: 1.20`, `folia-supported: true`, `load: STARTUP`, `depend: [packetevents]`, version `1.0.1`.
- **Build:** Maven, Java 21. Compile-only dependencies: `dev.folia:folia-api:1.20.6-R0.1-SNAPSHOT` and `com.github.retrooper:packetevents-spigot:2.8.0`. The shade plugin is configured, but nothing is bundled. Resource filtering is on. CI builds on GitHub release creation and deploys to GitHub Packages.
- **License:** The README claims MIT, but the repository has no license file. Treated as all-rights-reserved.
- **Packet interception:** It registers one PacketEvents send-side listener at NORMAL priority. That listener rewrites `CHUNK_DATA`, `BLOCK_CHANGE` and `MULTI_BLOCK_CHANGE` in place and marks the packet for re-encode. Nothing is done on the receive side. It handles no entity, light, block-entity, sound or particle packets. Like the SPEC.md reference, it calls the PacketEvents API `load()` / `init()` / `terminate()` itself and turns on PacketEvents update checking (the same defect as §12.1).
- **Localization:** A multi-language message system exists. It is out of scope here.

---

## 2. Behaviors not covered by SPEC.md

### 2.1 Hysteresis band (the main new idea)
SPEC.md uses one threshold (`y >= P`), and its transition-zone keys are inert (§12.10). This project has a working **dead band**, although it is hard-coded:

- The rule is evaluated in `PlayerMoveEvent` only when the player's integer block Y changes.
- At evaluation time:
  - Y ≥ **31.0** makes the player hidden.
  - Y ≤ **30.0** makes the player visible.
  - A Y strictly between the two keeps the previous state. With no previous state, the player is hidden if Y ≥ 31.
- **Emergent effect of combining the band with the block-Y gate:**
  - A player who descends smoothly enters block 30 at some Y between 30 and 31. That Y is inside the band, so the player stays hidden.
  - Moves inside block 30 are never evaluated. So in practice the reveal happens only when the feet enter block 29 (Y < 30). The effective band is about two blocks for downward motion.
  - Example: the player walks on a surface at Y=30.0 and is visible. They jump once, reach the apex at about 31.25 and become hidden. They land back at 30.0 and **stay hidden**.
  - Visibility at Y 30.x therefore depends on history. That is acceptable for hysteresis, but it should be documented.
- **Thresholds the band ignores:** Joining, reloading, world entry and teleport all use plain `y >= 31` with no band. For example, a teleport to Y=30.5 always produces "visible".
- **Config keys:** `antixray.trigger-y-level` (31.0) and `antixray.transition.stop-hiding-y` (30.0) ship in the file and are read, but they only feed a debug log. The behavior uses the hard-coded 31.0 and 30.0.

### 2.2 Configuration keys (as shipped; effect in this project)

| Key | Default | Effect |
|---|---|---|
| `settings.language` | `"en"` | Localization (out of scope). |
| `settings.debug-mode` | false | Verbose logging. Toggled and persisted by the debug command. |
| `settings.refresh-cooldown-seconds` | 3 | Cooldown on hide-direction refreshes (same as §6.3). **If the key is missing, the default is written back to the file and saved.** |
| `settings.view-distance` | (commented out) | Not read. The refresh radius is always the server view distance. |
| `worlds.whitelist` | `["world","mining_world"]` | Active worlds, matched exactly and case-sensitively. If the list is empty and the legacy `whitelisted-worlds` list exists, the legacy list is migrated, as in §4.4. If both are empty, a warning is logged. |
| `antixray.trigger-y-level` | 31.0 | Read, then ignored (hard-coded 31.0). |
| `antixray.hide-below-y` | 16 | Read, then ignored (hard-coded 16, inclusive). |
| `antixray.transition.stop-hiding-y` | 30.0 | Read, then ignored (hard-coded 30.0, see §2.1). |
| `antixray.transition.smooth-transition` | true | Not read. |
| `performance.folia-optimizations` | true | Read, then ignored. |
| `performance.region-aware-processing` | true | Read, then ignored. |
| `performance.max-chunks-per-tick` | 50 | Read, then ignored. There is no per-tick budget. |
| `performance.async-processing` | true | Not read. |
| `advanced.packet-processing.modify-chunk-data` / `modify-block-changes` / `modify-multi-block-changes` | true | Not read. The three filters are always on. |
| `advanced.teleport-handling.delay-ticks` | 1 | Not read. The deferral is always the next scheduler run. |
| `advanced.teleport-handling.manage-teleport-states` | true | Not read. Always on. |
| `compatibility.other-antixray-plugins` | false | Not read. |
| `compatibility.world-management-plugins` | true | Not read. |
| `compatibility.permission-plugins` | true | Not read. |
| `config-version` | 1 | Not read. |

The following are not configurable: the replacement block (always air, `minecraft:air`), a master on/off switch, entity hiding, and Bedrock handling.

The shipped file also advises against whitelisting Nether, End or creative worlds. SPEC.md already covers this.

### 2.3 Commands and permissions

| Command | Aliases | Declared permission (checked by the server first) | Extra runtime check |
|---|---|---|---|
| `/tazantixray <debug\|reload\|world> [args]` | `tar`, `antixray` | `tazantixray.admin` | admin, then the sub-handler's own node |
| `/tardebug` | `tazantixraydebug` | `tazantixray.debug` | `tazantixray.debug` |
| `/tarreload` | `tazantixrayreload` | `tazantixray.admin` | `tazantixray.reload` |
| `/tarworld <list\|add\|remove> [worldName]` | `tazantixrayworld` | `tazantixray.admin` | `tazantixray.world` |

The permission nodes are `.admin`, `.debug`, `.world` and `.reload`, all default op. Admin grants the other three as children.

**Difference from SPEC.md §2.2 / §8.1:** Each handler checks its *own* node. A sender with only the debug node can use `/tardebug`. The main command still requires admin before any sub-handler runs.

- **Debug toggle:** Flips the flag, saves it to the config, replies ON or OFF, and logs the name of the sender who toggled it.
- **Reload:** Re-reads the config, replies with the list of active worlds, and logs the sender's name. Then, for every online player:
  - In a whitelisted world: the initial state is computed and an immediate refresh is requested.
  - Otherwise: the state is cleared and a refresh is requested. That refresh is a no-op (the same defect as §12.2).
- **The main command only has `debug`, `reload` and `world`.** There is no `stats`, `test` or `help`. An unknown subcommand gets an error that names it. Running it with no arguments gives a usage line.
- **Tab completion:**
  - `/tazantixray`: argument 1 completes `debug, reload, world`. Argument 2 after `world` completes `list, add, remove`. Argument 3 after `world add` completes all loaded world names. Argument 3 after `world remove` completes the whitelisted names.
  - `/tarworld`: completes in the same way, shifted one argument left.

### 2.4 World whitelist command details (differences from §8.5)
- `list` always replies, even when the list is empty (the joined string is empty).
- **Missing world argument** for add or remove: a usage reply naming `<add|remove> <worldName>`.
- `add` on a name that is already listed: a warning-style "already whitelisted" reply. SPEC.md gives no output here.
- `remove` on a name that is not listed: a "was not in the whitelist" reply. SPEC.md gives no output here.
- An unknown action gives an error with usage. SPEC.md gives no output here.
- `remove` does not require the world to be loaded. `add` does.
- **Edits are visible to the packet filter immediately,** because the filter reads the live set. This is the opposite of §12.3.
  - The cost: a plain, non-thread-safe set is mutated on the main thread while netty threads read it.
  - Reload swaps in a new set through a non-volatile field.
- **Edge case:** The server's world lookup is case-insensitive, but the whitelist is case-sensitive. `add WORLD` therefore passes the "world exists" check and stores `WORLD`, which never matches the real world `world`. The add silently has no effect.

### 2.5 Lifecycle and state differences
- **Join:** If the player is in a whitelisted world, the state is set to `y >= 31` with no refresh. The plugin relies on the initial chunk stream being filtered.
- **Quit:** The state and cooldown entries are removed.
- **Disable:** The state map is cleared.
- **World change:** Into a whitelisted world, the initial state is set and an immediate refresh is requested. Out of one, the state is cleared and a refresh is requested (a no-op).
- **No master switch:** Nothing like `underground-protection.enabled` exists. Hiding is always active in whitelisted worlds.
- **Reveal refresh is immediate**, in the same tick as the move event. There is no 2-tick delay and no entity re-sync, unlike §6.3. Entities are never hidden at all.

### 2.6 Refresh strategy (differences from §7)
- **Radius:** Always the **server** view distance. There are no Bedrock, limited-area or instant-load adjustments.
- **Ownership check:** If the calling thread does not own the player's location, the whole refresh is re-scheduled on the player's region.
- **The square is walked once, synchronously:**
  - A chunk owned by the current region is refreshed inline.
  - Any other chunk gets **its own region-scheduler task**, one task per chunk.
  - There is no ring ordering, no per-tick budget, no loaded-chunk check and no throttle besides the hide-direction cooldown.
- **On Paper,** the main thread owns every location. A transition therefore resends the whole (2·vd+1)² square in one tick: 441 chunks at vd 10, 4,225 at vd 32.
- **Shared with SPEC.md:** A world-level chunk refresh resends the chunk to *every* player tracking it, not only the player who transitioned. Each recipient gets it filtered by their own state, so the result is correct, but bandwidth multiplies in crowded areas.

### 2.7 Teleport handling (differences from §6.4)
- Same overall shape: HIGHEST priority, ignore-cancelled, a per-player "own teleport" flag, and a hard-coded 31.0 with no band.
  - Hide direction: the state is set to hidden and the teleport proceeds.
  - Reveal direction: the state is set to visible, the event is cancelled, and the same destination is re-teleported on the next scheduler run.
- **Scheduling:** The deferred teleport is scheduled on the **destination's** region and uses the **synchronous** teleport call. On Folia, synchronous teleport is unsupported, so the re-teleport fails. Result: on Folia, every hidden-to-visible teleport (for example `/home` into a cave) is swallowed.
  - The player stays where they were, now marked visible.
  - The next block-Y change flips them back to hidden, which is cooldown-gated.
- **Portal travel:** Portal events are teleport events, so the handler also sees them. Cross-world portal moves mostly fall through to the world-change handler.
- **Neither direction requests a chunk refresh**, the same as SPEC.md. See §4.2 for why this is a real leak.

---

## 3. Hiding and culling algorithm, conceptually

### 3.1 Visibility model
There is exactly one model: a **per-player boolean driven by the player's own feet Y**, with the hard-coded band from §2.1. When the boolean is set, a **fixed horizontal plane** (world Y ≤ 16) is erased from everything sent to that player, at any horizontal distance.

The project does **not** have any of the following:
- distance falloff, vertical or horizontal
- line-of-sight or ray casting
- an air-exposure test (hiding only blocks with no transparent neighbor)
- ore-only obfuscation or fake-ore noise (it erases everything, stone included, to air)
- view cones or frustum logic
- per-chunk or per-section decisions
- any entity, block-entity or light handling

The only "transition zone" is the hysteresis band.

### 3.2 Block rewriting
- **Chunk packets:**
  - For each non-empty section, the plugin derives the section's bottom Y as the *player's current world* minimum height plus 16 × the section index.
  - Every cell whose world Y is ≤ 16 and whose state is not already air is overwritten with air.
  - Empty sections are skipped.
  - The packet is re-encoded only if at least one cell changed. In that case the legacy "ignore old data" flag is also set.
  - Sections fully above 16 are still scanned row by row before being rejected. This wastes a little work.
- **Single block updates:** A position with Y ≤ 16 and a non-air state is rewritten to air.
- **Multi-block updates:** Each record with Y ≤ 16 and a non-air id gets the air global id. The packet is re-encoded if any record changed.

### 3.3 When data is resent
- **Becoming hidden** by movement: a full-square refresh, unless the 3-second per-player cooldown is active. In that case the refresh is **dropped, not deferred**.
- **Becoming visible** by movement: a full-square refresh, always, with no throttle.
- **World entry and reload:** an immediate refresh.
- **Join:** none.
- **Teleport:** none, in either direction. In the reveal direction there is only the 1-tick deferral of the teleport itself.

### 3.4 Performance techniques
- The plugin does only cheap early exits before decoding a packet: unknown user, offline player, non-whitelisted world, then (per handler) a not-hidden player. It does not wrap or re-encode a packet unless the player is hidden and a cell actually changes.
- State lives in a concurrent map. Hide-direction refreshes have a cooldown.
- Refreshes inside the player's own region run inline. Other regions get per-chunk scheduled tasks.
- The plugin has nothing else: no budgets, batching, caching, async work or section-level shortcuts. Every outgoing packet of any type triggers a server player lookup and a world-name check *before* the packet type is tested, which is wasteful on a busy connection.

---

## 4. Honest assessment

### 4.1 What works
- **Surface scanning by a freecam or x-ray client that stays above Y 31 sees nothing at or below 16.** No blocks are sent there, so a client cannot render, scan or cache them. The default of 16 lines up with the top of 1.18+ diamond ore generation. Ancient cities, trial chambers and most deepslate-level bases fall below it.
- **The hysteresis band is a real improvement over a single threshold.** Walking along a Y 30 or 31 surface, or jumping on it, no longer causes a hide/reveal refresh on every step.
- **Skipping work when nothing changed** keeps the packet path cheap for players who are not hidden.

### 4.2 Weak or buggy, with concrete scenarios
1. **Dropping the cooldown refresh is a deterministic bypass.** SPEC.md §6.3 has the same flaw.
   - Scenario: jump on a Y 30 ledge (becomes hidden; refresh; the cooldown starts). Step down into block 29 (becomes visible; full unfiltered refresh). Climb back to 31 or higher within 3 s (becomes hidden; refresh **skipped**).
   - The client now holds full underground data for every loaded chunk, and nothing ever strips it. Freecam then works from the surface.
   - **(ours)** When throttled, schedule a trailing refresh at cooldown expiry if the player is still hidden. Never drop it.
2. **Teleporting upward leaks.**
   - Scenario: from a Y 25 cave mouth, throw an ender pearl or use chorus fruit, `/home`, `/tpa` or `/spawn` to reach Y 40 or higher **within view distance**. The state becomes hidden, but no chunk is resent, and no later move changes the state. The client keeps the unfiltered underground.
   - **(ours)** Every state change, whatever triggers it, must queue a refresh.
3. **Teleporting downward shows an empty world.**
   - Scenario: at Y 100, use `/home` to reach Y 0 nearby. The state becomes visible, but the chunks already on the client are still air-filtered and are not resent. The player sees and stands over emptiness, and likely rubber-bands until the chunks reload. The 1-tick deferral only helps for destinations farther away than view distance.
   - **(ours)** Refresh in both directions after a teleport. The deferral can then be dropped.
4. **The re-teleport is broken on Folia** (§2.7). Downward teleports are swallowed.
5. **Refresh storms.**
   - The reveal direction has no throttle, and every refresh resends the full square synchronously to every tracker.
   - Scenario: a player alternates between block 29 and a jump on the Y 30 ledge about once per second. Each cycle forces about 441 chunk resends (vd 10) to them and to every nearby player. It is a cheap amplification vector.
   - **(ours)** Keep SPEC.md's budgeted ring queue. Add a reveal-direction debounce of a few ticks, and coalesce duplicate chunk requests.
6. **Everything is exposed below 31.**
   - A player in blocks 17–30 is *never* hidden, so any player who digs a 1×1 shaft to Y 30 receives the full world within view distance, including bases at −50.
   - That data stays on the client after they climb back up if the hide refresh is skipped (item 1) or they teleport up (item 2).
   - This is the fundamental limit of a global per-player switch.
7. **Hard-coded values ignore config.** Admins who edit `trigger-y-level`, `hide-below-y` or `stop-hiding-y` get no effect and no warning.
8. **Blocks between 17 and 30 are always visible to everyone.** That includes caves, mineshafts, dungeons with spawners, and builds in that band.
9. **Threading.**
   - Netty threads read the player's world and the non-thread-safe whitelist set.
   - Section Y mapping uses the player's *current* world minimum height. During a world change, a chunk of the new world can be mapped with the old world's minimum, which shifts the hidden band by the height difference (for example 64 blocks between a −64 world and a 0 world). See SPEC.md §12.14.
   - All paths that reveal on world exit are no-ops (SPEC.md §12.2).
10. **Gate edge case.** A hidden player who steps from a slab top (30.5) down to a full block top (30.0) stays in block 30 and is not re-evaluated. The "≤ 30.0" rule only fires on block-boundary crossings.

### 4.3 Leaks that remain (in this project; most also apply to SPEC.md)
- **Block entities:** Chunk packets keep their block-entity list, and `BLOCK_ENTITY_DATA` is not filtered. Chest, spawner, sign, banner and skull data below 16 reaches the client, and chest-ESP style mods read it directly.
- **Light:** Chunk packets carry light arrays for every section, and `UPDATE_LIGHT` is not filtered. Torches, lava and glowstone at depth show up as exact block-light values. Light from below also spreads into the visible sections above 16. This is a strong locator for bases and lava lakes.
- **Biomes:** Biome data in chunk packets, and `CHUNKS_BIOMES` on 1.19.4+, reveal deep dark (ancient cities), lush caves and dripstone caves.
- **Heightmaps:** Minor.
- **Entities:** None of them are filtered in this project. Spawns, metadata, movement, equipment and passengers of mobs, players, item frames, armor stands and minecarts below 16 are all visible to entity ESP.
- **Positional side channels at depth:** `BLOCK_BREAK_ANIMATION`, `BLOCK_ACTION` (chests opening, pistons, note blocks), `WORLD_EVENT` (break particles, doors, dispensers), `SOUND_EFFECT` / `ENTITY_SOUND_EFFECT`, `PARTICLE`, `EXPLOSION`. These reveal where someone is mining or where an active base is.
- **Chunk-boundary artifacts:** Fluids, falling blocks and dropped items rising from the hidden band appear to float. This is cosmetic, but it hints that something is below.

### 4.4 Ideas worth carrying over (ours, prioritized)
1. **Adopt the hysteresis band, but make it configurable and wider than a jump.**
   - Use hide at Y ≥ P and reveal at Y < P − W, with W ≥ 1.5 (a jump peaks about 1.25 blocks up). Alternatively, evaluate against the last on-ground Y.
   - Apply the same rule in move, join, teleport and reload, so no path skips the band.
2. **Never drop a refresh, debounce it instead.**
   - Every state flip, whether from movement, a teleport, a respawn, a world change or a command, must result in a refresh, possibly deferred to a trailing edge.
   - The refresh must target the chunks the client actually holds.
3. **Strip everything tied to hidden sections:** block entities with Y ≤ H, light arrays (zeroed or set to a neutral value) for sections fully ≤ H plus the boundary section, and biomes for those sections (set to a neutral biome). Also filter `BLOCK_ENTITY_DATA`, `BLOCK_ACTION`, `BLOCK_BREAK_ANIMATION`, `WORLD_EVENT`, and positional sounds and particles at Y ≤ H for hidden viewers.
4. **Cheaper rewriting:**
   - Test the packet type before any player lookup.
   - Keep a per-player snapshot of the world (name and min height) that netty threads can read safely.
   - Replace whole sections that lie fully ≤ H with a single-value palette. Only rewrite cells in the one boundary section, and stop scanning at the first section above H.
5. **Shrink what a player gets for being below P:**
   - For example, keep hiding sections more than N blocks *below* the player's own Y even while the player is not "hidden", so someone at Y 30 does not receive the −64 to −20 band.
   - Or move per-section decisions onto vertical distance from the player.
   - This closes the "dig a shaft to 30" bypass at the cost of extra refreshes as the player descends, which should be budgeted.
