# ProjectAntiFreeCam

A Paper and Folia plugin that stops freecam, x-ray and ESP clients from seeing what's underground. Hidden terrain
and entities are never sent to the client, so there is nothing for a modified client to reveal.

## Features

- **Hides the underground from players above ground.** At Y 31 and up, nothing at or below Y 16 is sent: no blocks,
  light, biomes, chests or mobs.
- **Limits how deep underground players can look.** Players below Y 31 don't get terrain more than 48 blocks below
  them, so digging a shaft to Y 30 doesn't expose bases at the bottom of the world.
- **Cave occlusion.** Like the client's own cave culling, but on the server: 16×16×16 sections no see-through path
  leads to from the player — sealed bases, caves with no opening nearby — are sent as solid stone. Opening a way in
  reveals them within a few ticks.
- **Line-of-sight culling (anti-ESP).** Players and listed mobs behind solid blocks aren't sent until they come into
  view, checked from where the player is about to be so they don't pop in late. Something that walks behind a pillar
  stays shown for a moment instead of flickering, and hidden players stay in the tab list (unless a vanish plugin
  hides them too).
- **Closes side channels.** In everything it hides it also removes block light (torches, lava), biomes (deep dark marks
  ancient cities), block entities (chests, spawners), block events, mining cracks, explosions, sounds and particles —
  including the footsteps and other sounds of entities hidden behind walls.
- **Smooth transitions.** The full view comes back 2 blocks under the protection level, so jumping on a ledge doesn't
  flicker. View refreshes are rate-limited but never skipped, and only the player whose view changed gets the chunks.
- **No dependencies.** It hooks the server's network pipeline itself; PacketEvents or ProtocolLib aren't needed.

## Requirements

- Paper or Folia **1.20.5 or newer**, including Minecraft 26.x. Java 21 (Java 25 for 26.x, as Minecraft itself needs).
- Nothing else.

## Installation

1. Put `ProjectAntiFreeCam-<version>.jar` in `plugins/`.
2. Start the server once to create `plugins/ProjectAntiFreeCam/config.yml`.
3. List your survival worlds under `worlds.whitelist` and run `/afc reload`.

For ores in sections players *can* reach (caves they could walk into, and the band just above the hidden range), also
turn on Paper's built-in anti-xray (`anticheat.anti-xray` in `config/paper-world-defaults.yml`). The two complement
each other: this plugin removes whole areas, Paper's anti-xray hides ores in what's left.

## How it works

Every player has a *view*: everything at or below some Y is hidden from them, plus whatever cave occlusion hides.

| Where the player is | Hidden for them |
|---|---|
| At or above `protection-y-level` (31) | Everything at or below `hide-below-y` (16) |
| Below it | Whole 16-block sections more than `underground-view-depth` (48) blocks below them |
| Anywhere in a protected world | Sections they can't reach through see-through blocks (cave occlusion) |
| In a world that isn't protected | Nothing |

A per-player filter in the network pipeline rewrites chunk, block and light packets and drops entity spawns, sounds and
similar packets inside the hidden range. For cave occlusion it works out, from each chunk it sends, which faces of each
section are joined by see-through blocks, and searches outward from the player's own section; the 3×3×3 sections
around the player are always real. When a block update could open a new path, the server re-checks that section, and
newly reachable chunks are re-sent to that player only. When a player's view changes, their chunks are re-sent to
them. A separate check every 2 ticks hides entities that are in the hidden range or out of line of sight, and shows
them again when that changes.

## Configuration

Every key in `config.yml` is commented. The ones you're most likely to change:

| Key | Default | What it does |
|---|---|---|
| `worlds.whitelist` | `world`, `mining_world` | Worlds where protection applies (exact names) |
| `antixray.protection-y-level` | `31.0` | At or above this Y a player counts as above ground |
| `antixray.hide-below-y` | `16` | What above-ground players can't see |
| `antixray.hysteresis` | `2.0` | How far under the protection level the full view returns; underground, how far past a section boundary the next section is hidden |
| `antixray.underground-view-depth` | `48` | How far below themselves underground players can see; `0` turns it off |
| `antixray.cave-occlusion` | `true` | Hide sections no see-through path leads to (sent as stone) |
| `entities.hidden-types` | hostile, passive and display entities | Entity types that get hidden; players always are, an empty list hides only them |
| `entities.line-of-sight.enabled` | `true` | Anti-ESP culling |
| `entities.line-of-sight.check-interval-ticks` | `2` | Raise this if the check costs too much on a busy server |
| `performance.replacement.block-type` | `air` | What hidden blocks look like; `stone` looks less like a void |
| `advanced.packet-processing.strip-side-channels` | `true` | Light, biome, block entity, explosion, sound and particle hiding |
| `settings.refresh-cooldown-seconds` | `1` | Minimum time between two view refreshes for one player; a refresh also waits for the last one to finish |
| `performance.max-chunks-per-tick` | `8` | How fast a player whose view changed gets their chunks again (and cave-occlusion reveals) |

Refreshes caused by a reload, `/afc world` or joining, and the extra one after a world change, are spread out
server-wide, a quarter of a second apart, so a full server doesn't get everyone's chunks at once. A refresh needed
because the player moved never waits for them.

### Upgrading from 1.1.0

An existing `config.yml` keeps its old values. To get the new defaults, set `performance.max-chunks-per-tick` to `8`
and add `COPPER_GOLEM`, `TEXT_DISPLAY`, `ITEM_DISPLAY` and `BLOCK_DISPLAY` to `entities.hidden-types`. The
`bedrock`, `performance.limited-area` and `advanced.packet-processing.modify-*` keys are no longer read and can be
deleted: refreshes always cover the whole view distance, and every packet type is always filtered.

## Commands and permissions

| Command | Aliases | Permission | What it does |
|---|---|---|---|
| `/afc` | `/antifreecam`, `/freecam` | see below | Main command; lists its subcommands |
| `/afc reload` | `/afcreload` | `antifreecam.reload` | Reloads the config and re-checks every player |
| `/afc world <list\|add\|remove> [world]` | `/afcworld` | `antifreecam.world` | Manages the world whitelist |
| `/afc debug` | `/afcdebug` | `antifreecam.debug` | Toggles debug logging until the next reload |
| `/afc stats` | | `antifreecam.admin` | Platform, players with a filtered view |
| `/afc test <block\|state\|refresh>` | | `antifreecam.admin` | Shows the replacement block or your view, or re-sends your chunks |

| Permission | Default | |
|---|---|---|
| `antifreecam.admin` | op | All of the above |
| `antifreecam.reload`, `antifreecam.world`, `antifreecam.debug` | op | The single commands |
| `antifreecam.bypass` | nobody | Sees entities through walls and unreachable caves (skips line-of-sight culling and cave occlusion) |

Spectators also skip line-of-sight culling and cave occlusion, but not the height-based hiding.

## Compatibility

Checked with the end-to-end test below on Paper 1.20.6, 1.21.4, 1.21.8, 1.21.11, 26.1.2 and Folia 1.21.8, and by hand
on Paper 26.3. The plugin reads server internals that can change between Minecraft versions; if something it needs is
missing it logs which feature is off, and if a core part is missing it disables itself with a clear error.

A second after starting, it also runs one real chunk through its whole filter and logs the result:
`Self-check passed: chunks are filtered on this server version`. If a new Minecraft version breaks something, that line
says `Self-check FAILED` with the reason instead of chunks quietly going out unfiltered. If filtering ever fails for a
player later on, that is logged too.

Not tested yet: ViaVersion, real Bedrock clients, and large player counts.

## Known limitations

- Light that spreads out of a hidden area (a lava lake just under Y 16) still shows in the visible blocks next to it.
- Cave occlusion works per 16×16×16 section: every section a player can reach is sent whole, ores included, and so
  is everything right around them. Paper's anti-xray covers ores there.
- Once a section has been sent real it stays real for that player until their view changes; sealing a cave again
  hides it from the next time its chunk is sent.
- Breaking into a hidden pocket far from the player (someone else mining, an explosion) shows stone for a few ticks
  before the pocket appears. Next to the player it is instant.
- An entity behind a wall can take up to about two ticks plus the player's ping to appear when they step into view.
- Entities farther away than `max-distance` (64 blocks) are only checked against the hidden range, about every
  ten ticks: one that goes underground over there is sent for up to half a second longer.
- Dropped items, experience orbs and boats aren't in the default `hidden-types`: listing them costs a
  line-of-sight check each.
- Line of sight leaves entities that another plugin shows to some players only (per-player holograms and markers) to
  that plugin; the hidden range still applies to them.
- On Folia, if nearby entities belong to a different region than the player, that entity check is skipped and
  retried on the next one.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. Without a local JDK, build in Docker:

```bash
docker run --rm -v "$PWD:/work" -w /work gradle:9.2.1-jdk21 gradle build
```

## Testing

`./gradlew test` runs the unit tests (chunk rewriting, light, section connectivity, cave reachability, ray tracing).

`e2e/run.sh` starts real Paper and Folia servers in Docker with the built plugin, connects bot clients and checks what
they actually receive: hidden terrain, light, biomes and block entities on the wire, entities crossing the hidden
level, line of sight through stone and glass, the tab list, that refreshes reach only the player who needs them, cave
occlusion (sealed pockets arrive as stone, opening one reveals it, spectators see everything), sounds at hidden
entities, hidden players who quit or relog, vanished players, per-player holograms, explosions, entities beyond
`max-distance`, and world changes. A run also fails when the server log shows the plugin sending anything unfiltered.
It also loads a small test plugin (`e2e/testplugin`, built with the plugin into `build/e2e/`) that stands in for a
vanish plugin and a per-player hologram plugin.
It needs Docker, Node.js and curl.

```bash
e2e/run.sh                                 # every tested server build
e2e/run.sh paper:1.21.8 folia:1.21.8       # just these
e2e/run.sh --without-plugin paper:1.21.8   # control run: the hiding checks should fail
```

## License and provenance

MIT, see [LICENSE](LICENSE).

This is a clean-room implementation. It was written only from behavior descriptions ([SPEC.md](SPEC.md),
[SPEC-ADDENDUM.md](SPEC-ADDENDUM.md)) produced by someone else who studied earlier projects. No source code from those
projects was used.
