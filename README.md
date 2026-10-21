# ProjectAntiFreeCam

A Paper and Folia plugin that stops freecam, x-ray and ESP clients from seeing what's underground. Hidden terrain
and entities are never sent to the client, so there is nothing for a modified client to reveal.

## Features

- **Hides the underground from players above ground.** At Y 31 and up, nothing at or below Y 16 is sent: no blocks,
  light, biomes, chests or mobs.
- **Limits how deep underground players can look.** Players below Y 31 don't get terrain more than 48 blocks below
  them, so digging a shaft to Y 30 doesn't expose bases at the bottom of the world.
- **Line-of-sight culling (anti-ESP).** Players and listed mobs behind solid blocks aren't sent until they come into
  view. Glass and leaves don't count as walls, and hidden players stay in the tab list.
- **Closes side channels.** In the hidden range it also removes block light (torches, lava), biomes (deep dark marks
  ancient cities), block entities (chests, spawners), block events, mining cracks, sounds and particles.
- **Smooth transitions.** The full view comes back 2 blocks under the protection level, so jumping on a ledge doesn't
  flicker. View refreshes are rate-limited but never skipped, and only the player whose view changed gets the chunks.
- **No dependencies.** It hooks the server's network pipeline itself; PacketEvents or ProtocolLib aren't needed.
- **Geyser/Floodgate aware.** Bedrock players get a smaller refresh radius.

## Requirements

- Paper or Folia **1.20.5 or newer**, Java 21.
- Nothing else. Geyser and Floodgate are optional.

## Installation

1. Put `ProjectAntiFreeCam-<version>.jar` in `plugins/`.
2. Start the server once to create `plugins/ProjectAntiFreeCam/config.yml`.
3. List your survival worlds under `worlds.whitelist` and run `/afc reload`.

For ores in the band players *can* see (Y 17–30, or just below an underground player), also turn on Paper's built-in
anti-xray (`anticheat.anti-xray` in `config/paper-world-defaults.yml`). The two complement each other.

## How it works

Every player has a *view*: everything at or below some Y is hidden from them.

| Where the player is | Hidden for them |
|---|---|
| At or above `protection-y-level` (31) | Everything at or below `hide-below-y` (16) |
| Below it | Whole 16-block sections more than `underground-view-depth` (48) blocks below them |
| In a world that isn't whitelisted | Nothing |

A per-player filter in the network pipeline rewrites chunk, block and light packets and drops entity spawns, sounds and
similar packets inside the hidden range. When a player's view changes, their chunks are re-sent to them. A separate
check every 2 ticks hides entities that are in the hidden range or out of line of sight, and shows them again when
that changes.

## Configuration

Every key in `config.yml` is commented. The ones you're most likely to change:

| Key | Default | What it does |
|---|---|---|
| `worlds.whitelist` | `world`, `mining_world` | Worlds where protection applies (exact names) |
| `antixray.protection-y-level` | `31.0` | At or above this Y a player counts as above ground |
| `antixray.hide-below-y` | `16` | What above-ground players can't see |
| `antixray.hysteresis` | `2.0` | How far under the protection level the full view returns |
| `antixray.underground-view-depth` | `48` | How far below themselves underground players can see; `0` turns it off |
| `entities.hidden-types` | hostile, passive and display entities | Entity types that get hidden; players always are |
| `entities.line-of-sight.enabled` | `true` | Anti-ESP culling |
| `entities.line-of-sight.check-interval-ticks` | `2` | Raise this if the check costs too much on a busy server |
| `performance.replacement.block-type` | `air` | What hidden blocks look like; `stone` looks less like a void |
| `advanced.packet-processing.strip-side-channels` | `true` | Light, biome, block entity, sound and particle hiding |
| `settings.refresh-cooldown-seconds` | `1` | Minimum time between two view refreshes for one player |

## Commands and permissions

| Command | Aliases | Permission | What it does |
|---|---|---|---|
| `/afc` | `/antifreecam`, `/freecam` | see below | Main command; lists its subcommands |
| `/afc reload` | `/afcreload` | `antifreecam.reload` | Reloads the config and re-checks every player |
| `/afc world <list\|add\|remove> [world]` | `/afcworld` | `antifreecam.world` | Manages the world whitelist |
| `/afc debug` | `/afcdebug` | `antifreecam.debug` | Toggles debug logging |
| `/afc stats` | | `antifreecam.admin` | Platform, players with a filtered view, Bedrock detection |
| `/afc test <block\|state\|refresh>` | | `antifreecam.admin` | Shows the replacement block or your view, or re-sends your chunks |

| Permission | Default | |
|---|---|---|
| `antifreecam.admin` | op | All of the above |
| `antifreecam.reload`, `antifreecam.world`, `antifreecam.debug` | op | The single commands |
| `antifreecam.bypass` | nobody | Sees entities through walls (skips line-of-sight culling) |

Spectators also skip line-of-sight culling, but not the underground hiding.

## Compatibility

Checked with the end-to-end test below on Paper 1.20.6, 1.21.4, 1.21.8, 1.21.11 and Folia 1.21.8. The plugin reads
server internals that can change between Minecraft versions; if something it needs is missing it logs which feature is
off, and if a core part is missing it disables itself with a clear error.

Not tested yet: ViaVersion, real Bedrock clients, and large player counts.

## Known limitations

- Light that spreads upward from the hidden range (a lava lake just under Y 16) still shows in the visible blocks
  above it.
- An entity behind a wall can take up to about two ticks plus the player's ping to appear when they step into view.
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

`./gradlew test` runs the unit tests (chunk rewriting, light, ray tracing).

`e2e/run.sh` starts real Paper and Folia servers in Docker with the built plugin, connects bot clients and checks what
they actually receive: hidden terrain, light, biomes and block entities on the wire, entities crossing the hidden
level, line of sight through stone and glass, the tab list, and that refreshes reach only the player who needs them.
It needs Docker, Node.js and curl.

```bash
e2e/run.sh                                 # every tested server build
e2e/run.sh paper:1.21.8 folia:1.21.8       # just these
e2e/run.sh --without-plugin paper:1.21.8   # control run: the hiding checks should fail
```

On Paper 1.20.6 the bot library can't see pigs, so the two pig checks fail there with or without the plugin.

## License and provenance

MIT, see [LICENSE](LICENSE).

This is a clean-room implementation. It was written only from behavior descriptions ([SPEC.md](SPEC.md),
[SPEC-ADDENDUM.md](SPEC-ADDENDUM.md)) produced by someone else who studied earlier projects. No source code from those
projects was used.
