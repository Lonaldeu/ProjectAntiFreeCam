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
  reveals them within a few ticks, and sealing it again hides them again a few seconds later.
- **Chest and base ESP hiding.** In caves and bases a player *is* sent, chests, barrels, shulker boxes and spawners,
  and the beds, signs, banners and lecterns base finders look for, are disguised as what surrounds
  them (air, stone or water) while out of sight, so ESP and x-ray find nothing through walls. They come into view
  with their contents: a sign's text, a chest's lid open if someone has it open. Within 6 blocks they are always real.
  The list is yours to change.
- **Line-of-sight culling (anti-ESP).** Players and listed mobs behind solid blocks aren't sent until they come into
  view, checked from where the player is about to be so they don't pop in late. Something that walks behind a pillar
  stays shown for a moment instead of flickering, and hidden players stay in the tab list (unless a vanish plugin
  hides them too). Holograms other plugins show to some players only are held back the same way; see-through text
  (waypoints, markers) is drawn through walls anyway and stays. Arrows, ender pearls, tridents, fishing hooks and
  elytra fireworks of a hidden player don't give them away either: they are hidden with their owner unless they are
  in sight themselves (an arrow flying at the player over the wall shows) or have landed (one stuck in a wall no
  longer points at its owner).
- **Seed-cracker protection.** Players get a made-up hashed world seed, which seed-cracking mods use to check their
  guesses, and the console warns about protected worlds where Paper places ores from the world seed, where a
  cracked seed would give away the diamonds, gold, redstone, lapis, emeralds and debris.
- **Closes side channels.** In everything it hides it also removes block light (torches, lava), biomes (deep dark marks
  ancient cities), block entities (chests, spawners), block events, mining cracks, explosions, sounds and particles —
  including the footsteps and other sounds of entities hidden behind walls, and the glow a lava lake just under the
  cut would cast into the caves above it. Dropped items and XP orbs underground are hidden too.
- **Per-world settings.** Each protected world can have its own protection level, hidden range, view depth and on/off
  switches, e.g. in the Nether a cut at Y 24 for players on or above the lava sea, for ancient debris.
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
turn on Paper's built-in anti-xray (`anticheat.anti-xray` in `config/paper-world-defaults.yml`; `engine-mode: 2` also
adds fake ores). The two complement each other: this plugin removes whole areas and hides containers, Paper's
anti-xray hides ores in what's left. The console says so at startup for protected worlds where it is off.

## How it works

Every player has a *view*: everything at or below some Y is hidden from them, plus whatever cave occlusion hides.

| Where the player is | Hidden for them |
|---|---|
| At or above `protection-y-level` (31) | Everything at or below `hide-below-y` (16) |
| Below it | Whole 16-block sections more than `underground-view-depth` (48) blocks below them |
| Anywhere in a protected world | Sections they can't reach through see-through blocks (cave occlusion), and containers out of their sight |
| In a world that isn't protected | Nothing |

A per-player filter in the network pipeline rewrites chunk, block and light packets and drops entity spawns, sounds and
similar packets inside the hidden range. For cave occlusion it works out, from each chunk it sends, which faces of each
section are joined by see-through blocks, and searches outward from the player's own section; the 3×3×3 sections
around the player are always real. When a block update could open a new path, the server re-checks that section, and
newly reachable chunks are re-sent to that player only. When a player's view changes, their chunks are re-sent to
them. A separate check every 2 ticks hides entities that are in the hidden range or out of line of sight, and shows
them again when that changes; the same check reveals disguised containers that have come into sight. A listed entity
that moves into the hidden range in between is taken off the player's client by the filter before its new position
goes out.

## Configuration

Every key in `config.yml` is commented. The ones you're most likely to change:

| Key | Default | What it does |
|---|---|---|
| `worlds.whitelist` | `world`, `mining_world` | Worlds where protection applies (exact names) |
| `worlds.overrides` | none | Settings that differ in one world (the keys below marked *per world*); see the example in `config.yml` |
| `antixray.protection-y-level` | `31.0` | At or above this Y a player counts as above ground (*per world*) |
| `antixray.hide-below-y` | `16` | What above-ground players can't see (*per world*) |
| `antixray.hysteresis` | `2.0` | How far under the protection level the full view returns; underground, how far past a section boundary the next section is hidden (*per world*) |
| `antixray.underground-view-depth` | `48` | How far below themselves underground players can see; `0` turns it off (*per world*) |
| `antixray.cave-occlusion` | `true` | Hide sections no see-through path leads to (sent as stone) (*per world*) |
| `entities.hidden-types` | hostile, passive and display entities, boats | Entity types that get hidden; players always are, an empty list hides only them |
| `entities.depth-only-types` | items, XP orbs | Hidden underground only, with no line-of-sight check |
| `entities.projectiles.enabled` | `true` | Hide projectiles below the cut, and with a hidden owner unless in sight |
| `entities.projectiles.types` | arrows, tridents, ender pearls, fireworks, fishing hooks, thrown items | Which entity types count as projectiles |
| `containers.enabled` | `true` | Chest and base ESP hiding (*per world*) |
| `containers.hidden-blocks` | chests, barrels, shulker boxes, spawners, beds, signs, banners, lecterns | What gets disguised out of sight; any block with block data that gives off no light (`*shulker_box` matches every color, `*chest` trapped and copper chests too) |
| `entities.line-of-sight.enabled` | `true` | Anti-ESP culling (*per world*) |
| `entities.line-of-sight.check-interval-ticks` | `2` | Raise this if the check costs too much on a busy server |
| `performance.replacement.block-type` | `air` | What hidden blocks look like; a solid block keeps light near the cut from spreading through it on the client, but is a floor there |
| `performance.underground-protection.enabled` | `true` | Master switch (*per world*) |
| `seed-protection.hide-hashed-seed` | `true` | Send players a made-up hashed seed |
| `seed-protection.check-feature-seeds` | `true` | Warn about worlds where ores are placed from the world seed |
| `advanced.packet-processing.strip-side-channels` | `true` | Light, biome, block entity, explosion, sound and particle hiding |
| `advanced.packet-processing.light-across-chunks` | `true` | Also take hidden light out of the next chunk over (now and then re-sends a chunk) |
| `settings.refresh-cooldown-seconds` | `1` | Minimum time between two view refreshes for one player; a refresh also waits for the last one to finish |
| `performance.max-chunks-per-tick` | `8` | How fast a player whose view changed gets their chunks again (and cave-occlusion reveals) |

Refreshes caused by a reload, `/afc world` or joining, and the extra one after a world change, are spread out
server-wide, a quarter of a second apart, so a full server doesn't get everyone's chunks at once. A refresh needed
because the player moved never waits for them.

### Upgrading from 1.1.0 or 1.2.0

An existing `config.yml` keeps its old values (keys it lacks, such as `containers` and `entities.depth-only-types`,
take their defaults, so the projectile, seed, per-world and light settings need nothing). To get the new defaults, add
`BOAT`, `RAFT`, `HAPPY_GHAST` and `NAUTILUS` to `entities.hidden-types`. From 1.1.0, also set
`performance.max-chunks-per-tick` to `8` and add `COPPER_GOLEM`, `TEXT_DISPLAY`, `ITEM_DISPLAY` and `BLOCK_DISPLAY` to
`entities.hidden-types`. The `bedrock`, `performance.limited-area` and `advanced.packet-processing.modify-*` keys are
no longer read and can be deleted (the console lists them): refreshes always cover the whole view distance, and every
packet type is always filtered.

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
| `antifreecam.bypass` | nobody | Sees entities through walls, unreachable caves and containers (skips line-of-sight culling, cave occlusion and chest ESP hiding) |

Spectators also skip line-of-sight culling, cave occlusion and chest ESP hiding, but not the height-based hiding.

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

- Light that spreads out of a hidden area is removed, also where it crossed into the next 16×16 column: that column
  is cleaned with what the first one's cleaning left along their shared side, and re-sent (at most twice) when it went
  out first. Light that crosses back again, or goes on through a third column, can still show a little, and where
  light from a visible source and from the hidden area met at a column's edge, that edge can look a little darker
  than it should.
- With the `air` replacement (the default), light from a torch or lava just above the cut can spread through the hidden
  area on the client and light up caves next to it that are dark on the server, until the chunk is sent again. A
  solid replacement such as `stone` avoids it, but makes a floor at the cut: a player falling down a shaft past the
  protection level can land on it before their view changes, and take fall damage there.
- Cave occlusion works per 16×16×16 section: every section a player can reach is sent whole, ores included, and so
  is everything right around them. Paper's anti-xray covers ores there.
- Sealing a cave hides what it cut off again a few seconds later, except when more than 16 chunks go out of reach at
  once (a player sealing themselves in): those stay as they were sent until the view changes.
- Breaking into a hidden pocket far from the player (someone else mining, an explosion) shows stone for two or three
  ticks before the pocket appears (both go over the same connection, so the player's ping doesn't add to it), a few
  ticks more when that section also changed a moment before (busy redstone). Next to the player it is instant.
- An entity behind a wall can take up to about two ticks plus the player's ping to appear when they step into view.
- An entity that moves into the hidden range is taken off the client where it was last shown, before its new position
  is sent, and so are the listed entities riding it. These wait for the next check instead (2 ticks, 10 beyond
  `max-distance`): one that turned up since the last check, XP orbs before 1.21.5, and minecarts under the minecart
  experiment. Beyond `max-distance`, one that comes back out shows again within about ten ticks.
- Entities that exist only in another plugin's packets (packet holograms, model parts) are hidden by their spawn
  alone: one spawned in the hidden range stays off the client until that plugin spawns it again, and one that moves
  there afterwards is not hidden.
- A container, once shown, stays shown for that player until its chunk leaves their view. Blocks that give off light
  (ender chests, enchanting tables, lit furnaces) can't be disguised and aren't hidden; the more blocks are listed
  (signs in a big base), the longer the line-of-sight check takes to get round to each. Beyond
  `containers.max-distance` (64) a disguised barrel, spawner or lectern looks like its disguise. The line-of-sight check covers 64 disguised containers
  every 2 ticks: with N of them in range, one can take about N/32 ticks to appear when it comes into sight as the
  player moves, and up to 10 ticks while their eyes stay inside one block (standing still or sneaking, as a wall or
  door in front of them opens).
- A projectile of a hidden player that flies into sight can show up a couple of ticks late: its spawn is held back
  until the check sees it in sight.
- The made-up hashed seed only slows seed cracking down. Paper's `feature-seeds.generate-random-seeds-for-all` keeps
  the ores placed as features (diamonds, gold, redstone, lapis, emeralds, debris) from following the seed in newly
  generated chunks; the big iron and copper veins, caves and structures follow it either way (spigot.yml's `seed-*`
  keys move structures).
- A player hidden from someone can't be hit by that someone's arrows, tridents, snowballs or fishing hooks: Paper
  ignores projectile hits on players the shooter can't see. It matters for shots that reach a player out of the
  shooter's sight, such as an arc over a wall.
- Holograms other plugins show to some players only need the server's entity tracker, which the plugin reaches on
  every tested version; without it they are hidden by their spawn alone, like the packet-only entities above.
- On Folia, an entity that belongs to a different region than the player (near a region border) keeps its state
  until a check can reach it; the other entities are checked as usual.

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
occlusion (sealed pockets arrive as stone, opening one reveals it, sealing it hides it again, spectators see
everything), chests, signs, banners and beds behind walls (and an open chest's lid when it comes into sight), light
from lava under the cut (also across a chunk edge), sounds at hidden entities, hidden players who quit or relog,
vanished players, per-player holograms, dropped items and XP orbs, arrows of hidden players, entities moving below the
cut and riders of vehicles doing so, explosions, entities beyond `max-distance`, the Nether's own settings, the hashed
seed, and world changes. It runs with `air` as the replacement block, so hidden blocks can be told from the world's
stone. A run also fails when the server log shows the plugin sending anything unfiltered, or misses the startup
warnings.
It also loads a small test plugin (`e2e/testplugin`, built with the plugin into `build/e2e/`) that stands in for a
vanish plugin and a per-player hologram plugin.
It needs Docker, Node.js and curl.

```bash
e2e/run.sh                                 # every tested server build
e2e/run.sh paper:1.21.8 folia:1.21.8       # just these
e2e/run.sh --without-plugin paper:1.21.8   # control run: the hiding checks should fail
```

## License

MIT, see [LICENSE](LICENSE).
