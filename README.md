<div align="center">

# 🛡️ ProjectAntiFreeCam

[![Folia](https://img.shields.io/badge/Folia-✓-green)](https://papermc.io/software/folia)
[![Paper](https://img.shields.io/badge/Paper-✓-green)](https://papermc.io/software/paper)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

</div>

## 🎮 What is ProjectAntiFreeCam?

ProjectAntiFreeCam is a **packet-based anti-freecam protection plugin** forked from [TazAntixRAY](https://github.com/MinhTaz/TazAntixRAY) with extensive enhancements for **Folia support**, **performance caching**, and **Bedrock player optimizations**. It hides underground content from players using freecam or spectator exploits. When players are above ground, everything below Y16 becomes invisible — preventing cheaters from scouting bases, finding hidden builds, or locating valuable resources.

> [!TIP]
> Perfect for **survival**, **factions**, **SMP**, or any server where protecting underground bases and builds is essential.

<br>

## ✨ Features

<table>
<tr>
<td width="50%">

### 🔒 Y-Level Protection
- Hide blocks below Y16 when player is above Y31
- Instant protection on Y-level changes
- Configurable protection thresholds
- Per-world whitelist support

</td>
<td width="50%">

### 📦 Packet-Based
- Uses PacketEvents for efficiency
- Modifies chunk data packets
- Block change packet handling
- Zero server-side block modifications

</td>
</tr>
<tr>
<td width="50%">

### 👻 Entity Hiding
- Hide armor stands, item frames, paintings
- Minecarts (chest, hopper, spawner, etc.)
- Dual-layer: Packet + Bukkit API
- Configurable entity types

</td>
<td width="50%">

### ⚡ Performance
- Native Folia support
- Paper optimized
- Caffeine caching for entity positions
- Region-aware chunk processing
- Configurable cache sizes

</td>
</tr>
<tr>
<td width="50%">

### 🌊 Transition Zones
- Smooth Y-level transitions
- Configurable zone size
- Prevents sudden pop-in
- Natural feeling protection

</td>
<td width="50%">

### 🎮 Bedrock Support
- Geyser/Floodgate integration
- Multiple detection methods
- Performance optimizations for mobile
- Configurable chunk radius reduction

</td>
</tr>
</table>

<br>

## 📥 Installation

```bash
# 1. Download PacketEvents from https://github.com/retrooper/packetevents/releases
# 2. Download the latest ProjectAntiFreeCam release
# 3. Drop both into plugins/ folder
# 4. Restart server
# 5. Configure in plugins/ProjectAntiFreeCam/config.yml
```

### Requirements

| Requirement | Version |
|-------------|---------|
| Minecraft | 1.20+ |
| Server | Paper or Folia |
| Java | 21+ |
| PacketEvents | 2.10.1+ |

### Optional
- [Geyser](https://geysermc.org/) — Bedrock Edition support
- [Floodgate](https://wiki.geysermc.org/floodgate/) — Bedrock player detection

> **Note:** If both are installed, detection methods are tried in order: Geyser API → Floodgate API → UUID pattern → Name prefix.

<br>

## ⚙️ Configuration

<details>
<summary><strong>📄 config.yml</strong> (click to expand)</summary>

```yaml
# ═══════════════════════════════════════════════════════════════
#                 ProjectAntiFreeCam Configuration
# ═══════════════════════════════════════════════════════════════

settings:
  debug-mode: false
  refresh-cooldown-seconds: 3

# ─────────────────────────────────────────────────────────────────
#                        WORLD CONFIG
# ─────────────────────────────────────────────────────────────────
worlds:
  whitelist:
    - "world"
    - "mining_world"

# ─────────────────────────────────────────────────────────────────
#                     PROTECTION SETTINGS
# ─────────────────────────────────────────────────────────────────
antixray:
  # When player is ABOVE this Y: hide blocks below hide-below-y
  protection-y-level: 31.0
  
  # All blocks at this Y and below are hidden
  hide-below-y: 16
  
  # Smooth transition settings
  transition:
    enabled: true
    zone-size: 5

# ─────────────────────────────────────────────────────────────────
#                       ENTITY HIDING
# ─────────────────────────────────────────────────────────────────
entities:
  hide-entities: true
  hidden-types:
    - ARMOR_STAND
    - ITEM_FRAME
    - GLOW_ITEM_FRAME
    - PAINTING
    - MINECART
    - CHEST_MINECART
    - HOPPER_MINECART

# ─────────────────────────────────────────────────────────────────
#                       PERFORMANCE
# ─────────────────────────────────────────────────────────────────
performance:
  # Enable RAM caching for entity positions
  ram-caching: true
  
  instant-protection:
    enabled: true
    instant-load-radius: 15
    pre-load-distance: 10
    force-immediate-refresh: true
  
  max-chunks-per-tick: 50
  max-entities-per-tick: 100

  replacement:
    block-type: "air"

  underground-protection:
    enabled: true

# ─────────────────────────────────────────────────────────────────
#                     BEDROCK SUPPORT
# ─────────────────────────────────────────────────────────────────
bedrock:
  enabled: true
  detection:
    use-geyser-api: true
    use-floodgate-api: true
    use-uuid-pattern: true
    use-name-prefix: true
    name-prefixes:
      - "."
  optimizations:
    chunk-radius-reduction: 1
    minimum-chunk-radius: 2
```

</details>

<br>

## 📜 Commands

| Command | Description |
|---------|-------------|
| `/antifreecam help` | Show all commands |
| `/antifreecam debug` | Toggle debug mode |
| `/antifreecam reload` | Reload configuration |
| `/antifreecam world <list\|add\|remove>` | Manage world whitelist |
| `/antifreecam stats` | **View plugin & cache statistics** |
| `/antifreecam test <block\|state\|refresh>` | Test protection status |

> [!NOTE]
> All commands require `antifreecam.admin` permission.

### Cache Statistics
The `/afc stats` command shows:
- **Cache Hit Rate** - Percentage of lookups served from cache
- **Entity Positions** - Number of cached entity locations
- **RAM Usage** - Memory consumed by caching
- **Performance Rating** - Cache effectiveness indicator

### Aliases
- `/antifreecam` → `/afc`, `/freecam`
- `/afcdebug` → Toggle debug
- `/afcreload` → Reload config
- `/afcworld` → World management

<br>

## 🔧 How It Works

```
Player Y > 31 (above ground)
    ↓
Chunks below Y16 → Replaced with AIR in packets
Entities below Y16 → Hidden (armor stands, item frames, etc.)
    ↓
Freecam users see nothing underground

Player Y ≤ 31 (going underground)
    ↓
Full chunk data restored
Entities become visible
    ↓
Normal gameplay resumes
```

### Protection Flow

1. **Player joins** → Check if world is whitelisted
2. **Y-level monitored** → Track when player crosses protection threshold
3. **Above Y31** → Modify outgoing chunk/entity packets, hide blocks & entities below Y16
4. **Below Y31** → Send full chunk data, normal visibility
5. **Instant refresh** → Chunks update immediately on Y-level change

<br>

## 🎮 Platform Support

| Platform | Status | Notes |
|----------|--------|-------|
| Paper | ✅ Full | Recommended |
| Folia | ✅ Full | Region-aware scheduling, entity thread-safety |
| Spigot | ⚠️ Limited | Use Paper for best results |
| Geyser | ✅ Full | Bedrock players supported |

### Key Enhancements Over Original

This fork adds significant improvements over [TazAntixRAY](https://github.com/MinhTaz/TazAntixRAY):

- ✅ **Full Folia Support** - Region-aware scheduling, thread-safe entity operations
- ✅ **RAM Caching System** - Entity position caching with Caffeine
- ✅ **PlatformCompatibility Layer** - Automatic detection and adaptation for Paper/Folia
- ✅ **Entity Hide/Show API** - Thread-safe entity visibility using `player.hideEntity()`
- ✅ **teleportAsync Support** - Folia-compliant teleportation
- ✅ **Cache Performance Metrics** - Real-time hit rate and effectiveness tracking
- ✅ **Bedrock Optimizations** - Geyser/Floodgate integration with performance tuning

<br>

## 🙏 Credits

- **Base Project**: [TazAntixRAY](https://github.com/MinhTaz/TazAntixRAY) by MinhTaz
- **PacketEvents**: [retrooper/packetevents](https://github.com/retrooper/packetevents)

<br>

## 📄 License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

Since this is a fork of [TazAntixRAY](https://github.com/MinhTaz/TazAntixRAY) (GPL-3.0), this project must also be distributed under GPL-3.0. This means:
- ✅ You can use, modify, and distribute this code
- ✅ You must provide source code with any distribution
- ✅ Any derivatives must also be GPL-3.0
- ✅ You must include this license with distributions

---

<div align="center">

**Forked from [TazAntixRAY](https://github.com/MinhTaz/TazAntixRAY) with Folia support & performance enhancements**

**Maintained by [Lonaldeu](https://github.com/lonaldeu)**

⭐ Star this repo if you find it useful!

</div>
