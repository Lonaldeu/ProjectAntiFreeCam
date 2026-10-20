# ProjectAntiFreeCam

Paper/Folia plugin that hides underground blocks and entities from players standing above ground, so freecam and x-ray clients see nothing below the configured level.

- Requires Paper (or Folia) 1.20.5+ on Java 21. No other plugins needed: it hooks the server network pipeline itself.
- Configure in `plugins/ProjectAntiFreeCam/config.yml`.
- Commands: `/afc <debug|reload|world|stats|test>`, `/afcworld <list|add|remove> [world]`, `/afcdebug`, `/afcreload`. Permission: `antifreecam.admin`.

Build: `./gradlew build` → `build/libs/ProjectAntiFreeCam-<version>.jar`.

## Provenance

This is a clean-room implementation written only from the behavior spec in [SPEC.md](SPEC.md). No source code from earlier projects was used. Licensed under MIT.
