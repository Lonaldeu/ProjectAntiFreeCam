# ProjectAntiFreeCam

Paper/Folia plugin that hides underground blocks and entities from players standing above ground, so freecam and x-ray clients see nothing below the configured level.

- Requires Paper (or Folia) 1.20.5+ on Java 21. No other plugins needed: it hooks the server network pipeline itself.
- Tested with a bot client on Paper 1.20.6, 1.21.4, 1.21.8, 1.21.11 and Folia 1.21.8. The platform and version are detected at startup and logged.
- Configure in `plugins/ProjectAntiFreeCam/config.yml`.

How it hides:
- **Above ground** (Y ≥ 31): nothing at or below Y 16 is sent. The full view returns 2 blocks under Y 31, so jumping on a ledge doesn't flicker.
- **Underground**: terrain more than 48 blocks below the player isn't sent either, so digging a shaft to Y 30 doesn't reveal the deep underground.
- In the hidden range the client also gets no light, real biomes, block entities (chests, spawners), block events, mining cracks, sounds, particles or listed entities.
- **Line of sight**: players and listed mobs behind solid blocks aren't sent at all (glass and leaves don't count as walls), so ESP clients can't see them. Hidden players stay in the tab list. Spectators and `antifreecam.bypass` holders see everything.
- Every change of view re-sends the player's chunks; refreshes are rate-limited but never skipped.
- Ores in the band players *can* see are best covered by Paper's built-in anti-xray (`anticheat.anti-xray` in `paper-world-defaults.yml`).
- Commands: `/afc <debug|reload|world|stats|test>`, `/afcworld <list|add|remove> [world]`, `/afcdebug`, `/afcreload`. Permission: `antifreecam.admin`.

Build: `./gradlew build` → `build/libs/ProjectAntiFreeCam-<version>.jar`. Without a local JDK, build in Docker:
`docker run --rm -v "$PWD:/work" -w /work gradle:9.2.1-jdk21 gradle build`.

## Testing

`./gradlew test` runs the unit tests (chunk rewriting, light, ray tracing).

`e2e/run.sh` runs the end-to-end test: it starts real Paper and Folia servers in Docker with the built plugin, connects bot
clients and checks what they actually receive — hidden terrain, light, biomes and block entities on the wire, entities
crossing the cut, line of sight through stone and glass, the tab list, and that refreshes go only to the player who
needs them. It needs Docker, Node.js and curl. Pass server builds to test only those (`e2e/run.sh paper:1.21.8`), or
`--without-plugin` for a control run in which the hiding checks should fail. On Paper 1.20.6 the bot library can't see
pigs, so the two pig checks fail there with or without the plugin.

## Provenance

This is a clean-room implementation written only from the behavior spec in [SPEC.md](SPEC.md). No source code from earlier projects was used. Licensed under MIT.
