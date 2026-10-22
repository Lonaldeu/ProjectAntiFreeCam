// End-to-end check of ProjectAntiFreeCam against a flat world: bedrock -64, stone -63..36, grass 37. View distance 6.
// AfcTestPlugin (e2e/testplugin) provides /afcvanish and /afcprivate, standing in for other plugins. run.sh sets the
// replacement block to air (stone would look like the world) and gives the Nether settings of its own.
// Defaults: above ground at Y >= 31 (full view back below 29): Y <= 16 hidden.
// Below that: sections more than 48 blocks below the player are hidden (at Y 20: Y <= -33).
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const crypto = require('crypto')

const connection = { host: 'localhost', port: +(process.env.PORT || 25565), version: process.env.MC_VERSION || '1.21.8' }
const bot = mineflayer.createBot({ ...connection, username: 'AfcBot' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
let failures = 0
const check = (label, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ' (' + detail + ')' : ''}`)
  if (!ok) failures++
}
const block = (x, y, z) => bot.blockAt(new Vec3(x, y, z))
const name = (x, y, z) => block(x, y, z)?.name
const stands = () => Object.values(bot.entities).filter(e => e.name === 'armor_stand').map(e => Math.round(e.position.y)).sort((a, b) => a - b)

// Every block in [from, to] across a 3x3-chunk area must equal expected(y).
function scan (cx, cz, from, to, expected) {
  const bad = []
  for (let x = cx - 24; x <= cx + 24; x += 3)
    for (let z = cz - 24; z <= cz + 24; z += 3)
      for (let y = from; y <= to; y++) {
        const n = name(x, y, z)
        if (n !== undefined && n !== expected(y)) { bad.push(`${x},${y},${z}=${n}`); if (bad.length > 5) return bad }
      }
  return bad
}

// Raw wire checks. Light sections start one below the world (-80), so bits 0..5 are sections fully at or below 15.
function bitsOf (mask) {
  const out = []
  ;(mask || []).forEach((l, li) => {
    const big = typeof l === 'bigint' ? BigInt.asUintN(64, l)
      : Array.isArray(l) ? (BigInt.asUintN(32, BigInt(l[0])) << 32n) | BigInt.asUintN(32, BigInt(l[1])) : BigInt(l)
    for (let b = 0; b < 64; b++) if ((big >> BigInt(b)) & 1n) out.push(li * 64 + b)
  })
  return out
}
// Block light of cell (x, y, z) in a map_chunk packet, 0 when its section carries none.
function blockLightAt (p, x, y, z) {
  const k = bitsOf(p.blockLightMask).indexOf(((y + 64) >> 4) + 1)
  const a = k < 0 ? null : p.blockLight[k]
  if (!a) return 0
  const index = ((y + 64) & 15) << 8 | (z & 15) << 4 | (x & 15)
  return index & 1 ? a[index >> 1] >> 4 & 15 : a[index >> 1] & 15
}
let phase = 'surface'
let leakedBlockEntities = 0, leakedLight = 0, roomLightSeen = false, roomChunk = null
function onLight (p, cx, cz) {
  const sky = bitsOf(p.skyLightMask), blk = bitsOf(p.blockLightMask)
  if (phase === 'surface') leakedLight += [...sky, ...blk].filter(i => i <= 5).length
  if (phase === 'depth' && roomChunk && cx === roomChunk[0] && cz === roomChunk[1]) {
    const k = blk.indexOf(5)
    if (k >= 0 && [...(p.blockLight[k] || [])].some(v => v !== 0)) roomLightSeen = true
  }
}
bot._client.on('map_chunk', p => {
  if (phase === 'surface') leakedBlockEntities += (p.blockEntities || []).filter(be => be.y <= 16).length
  onLight(p, p.x, p.z)
})
bot._client.on('update_light', p => onLight(p, p.chunkX, p.chunkZ))
const sounds = []
bot._client.on('sound_effect', p => sounds.push([p.x / 8, p.y / 8, p.z / 8]))
// Positions at or below the cut (Y 16) of carts and text displays that reached the bot while watched (the bot on the
// surface). By entity id, as mineflayer forgets the name of an entity it was told is gone.
const sunk = [], watched = new Map()
let watchSunk = false
bot.on('entitySpawn', e => { if (['minecart', 'text_display'].includes(e.name)) watched.set(e.id, e.name) })
bot.on('entityMoved', e => {
  if (watchSunk && e.position.y <= 16 && watched.has(e.id)) sunk.push(`${watched.get(e.id)}@${e.position.y}`)
})
// Hashed seeds the server sent (login and respawns), and the real one, from /seed.
const hashedSeeds = []
let realSeed = null
const toSeed = v => BigInt.asIntN(64, Array.isArray(v) ? BigInt(v[0]) << 32n | BigInt(v[1]) & 0xffffffffn : BigInt(v))
for (const n of ['login', 'respawn']) bot._client.on(n, p => { if (p.worldState?.hashedSeed !== undefined) hashedSeeds.push(toSeed(p.worldState.hashedSeed)) })
bot.on('messagestr', m => { const s = /Seed: \[(-?\d+)\]/.exec(m); if (s) realSeed = BigInt(s[1]) })
const hashOf = seed => { // the game's own: the first 8 bytes of SHA-256 over the seed, both little-endian
  const b = Buffer.alloc(8)
  b.writeBigInt64LE(seed)
  return crypto.createHash('sha256').update(b).digest().readBigInt64LE(0)
}
// Block events (chest lids) sent to the bot.
const lids = []
bot._client.on('block_action', p => lids.push(p))
let pocketChunk = null, eastChest = null
const pocketChests = {}, eastChests = {} // block entities sent per phase: the sealed pocket's chest, the opened one's
bot._client.on('map_chunk', p => {
  if (pocketChunk && p.x === pocketChunk[0] && p.z === pocketChunk[1])
    pocketChests[phase] = (pocketChests[phase] || 0) + (p.blockEntities || []).filter(be => be.y >= 20 && be.y <= 24).length
  if (eastChest && p.x === eastChest[0] >> 4 && p.z === eastChest[2] >> 4)
    eastChests[phase] = (eastChests[phase] || 0) + (p.blockEntities || [])
      .filter(be => be.x === (eastChest[0] & 15) && be.y === eastChest[1] && be.z === (eastChest[2] & 15)).length
})

bot.on('kicked', r => { console.log('KICKED', JSON.stringify(r)); process.exit(2) })
bot.on('error', e => { console.log('ERROR', e.message); process.exit(2) })

bot.once('spawn', async () => {
  await sleep(4000)
  await bot.waitForChunksToLoad()
  const { x, z } = bot.entity.position.floored()
  const tp = async y => { bot.chat(`/tp @s ${x + 0.5} ${y} ${z + 0.5}`); await sleep(3500) }
  console.log('spawned at', bot.entity.position.toString())

  bot.chat('/seed')
  await sleep(1000)
  check('seed: the login carries a made-up hashed seed', realSeed !== null && hashedSeeds.length > 0 && hashedSeeds[0] !== hashOf(realSeed),
    `real ${realSeed}, sent ${hashedSeeds[0]}`)

  // 1. Above ground: everything at or below 16 is air, the rest is normal.
  let bad = scan(x, z, -64, 37, y => (y <= 16 ? 'air' : y <= 36 ? 'stone' : 'grass_block'))
  check('surface: Y<=16 is air, Y>16 intact', bad.length === 0, bad.join(' '))

  bot.chat('/gamemode spectator')
  bot.chat(`/summon armor_stand ${x + 3} 10 ${z + 3} {NoGravity:1b,Invulnerable:1b}`)
  bot.chat(`/summon armor_stand ${x - 3} 30 ${z - 3} {NoGravity:1b,Invulnerable:1b}`)
  bot.chat(`/setblock ${x + 2} 5 ${z + 2} minecraft:diamond_block`)
  bot.chat(`/setblock ${x + 2} 30 ${z + 2} minecraft:gold_block`)
  bot.chat(`/fill ${x - 6} 12 ${z - 6} ${x - 4} 20 ${z - 4} minecraft:emerald_block`)
  // a lit room with a chest below the limit, and deep dark under it
  bot.chat(`/fill ${x + 8} 6 ${z + 8} ${x + 10} 10 ${z + 10} minecraft:air`)
  bot.chat(`/setblock ${x + 9} 6 ${z + 9} minecraft:glowstone`)
  bot.chat(`/setblock ${x + 9} 7 ${z + 9} minecraft:chest`)
  bot.chat(`/fillbiome ${x - 8} -48 ${z - 8} ${x + 8} -16 ${z + 8} minecraft:deep_dark`)
  roomChunk = [(x + 9) >> 4, (z + 9) >> 4]
  // a hologram another plugin shows to this player only, below the cut
  bot.chat(`/afcprivate AfcBot ${x + 5.5} 10 ${z + 5.5}`)
  const privateSeen = () => Object.values(bot.entities).some(e => e.name === 'text_display'
    && Math.abs(e.position.x - (x + 5.5)) < 1 && Math.abs(e.position.z - (z + 5.5)) < 1)
  // a dropped item and an XP orb in the room below the cut
  bot.chat(`/summon item ${x + 9.5} 7 ${z + 8.5} {Item:{id:"minecraft:diamond",count:1},PickupDelay:32767,Age:-32768}`)
  bot.chat(`/summon experience_orb ${x + 8.5} 7 ${z + 10.5} {Value:3}`)
  const dropsSeen = () => Object.values(bot.entities).filter(e => (e.name === 'item' || e.name === 'experience_orb') && e.position.y < 16
    && Math.abs(e.position.x - (x + 9)) < 2 && Math.abs(e.position.z - (z + 9.5)) < 2).length // in the room only: not the TNT's drops
  await sleep(2500)
  check('surface: underground armor stand hidden, surface one shown', JSON.stringify(stands()) === '[30]', JSON.stringify(stands()))
  check('surface: single block change below limit hidden', name(x + 2, 5, z + 2) === 'air', name(x + 2, 5, z + 2))
  check('surface: single block change above limit shown', name(x + 2, 30, z + 2) === 'gold_block', name(x + 2, 30, z + 2))
  check('surface: multi block change split at limit',
    [14, 16, 17, 20].map(y => name(x - 5, y, z - 5)).join() === 'air,air,emerald_block,emerald_block',
    [14, 16, 17, 20].map(y => name(x - 5, y, z - 5)).join())
  check('surface: a per-player hologram below the cut is hidden', !privateSeen())
  check('surface: a dropped item and an XP orb below the cut are hidden', dropsSeen() === 0, `${dropsSeen()} seen`)

  // Entities that cross the cut after spawning: one cart goes down into the hidden range, one comes up out of it.
  // which side of the bot each visible cart is on: -1 for the one summoned at x-2, 1 for the one at x+2
  const carts = () => Object.values(bot.entities).filter(e => e.name === 'minecart').map(e => Math.sign(e.position.x - x)).sort((a, b) => a - b)
  bot.chat(`/summon minecart ${x - 2} 45 ${z + 2} {NoGravity:1b,Tags:["falls"]}`)
  bot.chat(`/summon minecart ${x + 2} 10 ${z - 2} {NoGravity:1b,Tags:["rises"]}`)
  await sleep(1500)
  check('crossing: only the cart above the cut is visible', JSON.stringify(carts()) === '[-1]', JSON.stringify(carts()))
  sunk.length = 0
  watchSunk = true
  bot.chat(`/tp @e[tag=falls] ${x - 2} 10 ${z + 2}`)
  bot.chat(`/tp @e[tag=rises] ${x + 2} 45 ${z - 2}`)
  await sleep(1500)
  check('crossing: the cart that went down vanished, the one that came up appeared', JSON.stringify(carts()) === '[1]', JSON.stringify(carts()))
  check('crossing: and its position below the cut was never sent', sunk.length === 0, sunk.join(' '))
  // A see-through text display (drawn through walls, so no line of sight applies) moved in short steps, which go out
  // as relative moves: shown at Y 19; moved down to Y 13 and back a few times, each round an odd number of ticks so
  // some moves land between two checks of the visibility pass, it leaves the client without any position at or below
  // the cut. (Folia's /tp spawns it anew, so only Paper proves the filter here.)
  const stepsY = () => Object.values(bot.entities).find(e => e.name === 'text_display'
    && Math.abs(e.position.x - (x - 3.5)) < 1 && Math.abs(e.position.z - (z - 6.5)) < 1)?.position.y
  bot.chat(`/summon text_display ${x - 3.5} 22 ${z - 6.5} {see_through:1b,Tags:["steps"]}`)
  await sleep(1000)
  bot.chat(`/tp @e[tag=steps] ${x - 3.5} 19 ${z - 6.5}`)
  await sleep(1000)
  check('crossing: a text display moved to Y 19 is shown there', stepsY() === 19, `${stepsY()}`)
  let gone = 0
  for (let i = 0; i < 4; i++) {
    bot.chat(`/tp @e[tag=steps] ${x - 3.5} 13 ${z - 6.5}`)
    await sleep(400 + 50 * i)
    if (stepsY() === undefined) gone++
    bot.chat(`/tp @e[tag=steps] ${x - 3.5} 19 ${z - 6.5}`)
    await sleep(650 + 50 * i) // with the 400 above: 21, 23, 25, 27 ticks a round
  }
  check('crossing: moved down to Y 13, it leaves the client without its new position', gone === 4 && sunk.length === 0,
    `gone ${gone}/4, ${sunk.join(' ')}`)
  check('crossing: and back at Y 19, it is shown again', stepsY() === 19, `${stepsY()}`)
  watchSunk = false
  // A see-through text display riding an entity of a type that isn't hidden (an interaction): riders get no moves of
  // their own, so each time the mount goes down to Y 13 the rider must be off the client before the mount's new
  // position arrives.
  const riderSeen = () => Object.values(bot.entities).some(e => e.name === 'text_display'
    && Math.abs(e.position.x - (x - 6.5)) < 1.5 && Math.abs(e.position.z - (z + 3.5)) < 1.5)
  let mountId = null, mountDown = 0, riderLeaks = 0
  bot.on('entitySpawn', e => { if (e.name === 'interaction') mountId = e.id })
  bot.on('entityMoved', e => {
    if (e.id !== mountId || e.position.y > 16) return
    mountDown++
    if (riderSeen()) riderLeaks++
  })
  bot.chat(`/summon interaction ${x - 6.5} 19 ${z + 3.5} {Tags:["mount"],Passengers:[{id:"minecraft:text_display",see_through:1b}]}`)
  await sleep(1000)
  const riderAtStart = riderSeen()
  for (let i = 0; i < 4; i++) {
    bot.chat(`/tp @e[tag=mount] ${x - 6.5} 13 ${z + 3.5}`)
    await sleep(400 + 50 * i)
    bot.chat(`/tp @e[tag=mount] ${x - 6.5} 19 ${z + 3.5}`)
    await sleep(650 + 50 * i)
  }
  if (process.env.MC_PROJECT === 'folia') { // Folia's /tp ejects a vehicle's passengers first
    console.log('SKIP crossing: riders (Folia\'s /tp can\'t move a vehicle with its rider)')
  } else {
    check('crossing: a rider of an entity that is not hidden leaves the client before its mount goes below the cut',
      riderAtStart && mountDown > 0 && riderLeaks === 0, `rider ${riderAtStart}, mount down ${mountDown}x, rider still there ${riderLeaks}x`)
    check('crossing: and is back with it above the cut', riderSeen())
  }

  // Explosions: one below the cut isn't sent; one just above it is, without the blocks it destroyed at or below the
  // cut (which the packet only lists before 1.21.2).
  const explosions = []
  bot._client.on('explosion', p => explosions.push(p))
  bot.chat(`/summon tnt ${x + 40.5} 5 ${z + 0.5} {fuse:0}`)
  await sleep(1500)
  check('explosion: one below the cut is not sent', explosions.length === 0, `${explosions.length} sent`)
  bot.chat(`/fill ${x + 39} 14 ${z - 1} ${x + 41} 16 ${z + 1} minecraft:dirt`) // weak blocks under it: the blast surely
  bot.chat(`/fill ${x + 39} 17 ${z - 1} ${x + 41} 17 ${z + 1} minecraft:air`) // reaches below the cut
  bot.chat(`/summon tnt ${x + 40.5} 17 ${z + 0.5} {fuse:0}`)
  await sleep(1500)
  const blown = explosions.flatMap(p => (p.affectedBlockOffsets || []).map(o => Math.floor(p.y) + o.y))
  check('explosion: one above the cut is sent, without blocks at or below it', explosions.length === 1 && blown.every(y => y > 16),
    `${explosions.length} sent, destroyed Y ${[...new Set(blown)].sort((a, b) => a - b)}`)

  // 2. Below the protection level: depth-limited view. At Y 20 the cut is Y <= -33.
  phase = 'depth'
  await tp(20)
  bad = scan(x, z, -64, 4, y => (y <= -33 ? 'air' : 'stone'))
  check('depth: Y<=-33 is air, -32..4 intact', bad.length === 0, bad.join(' '))
  check('depth: placed blocks visible', name(x + 2, 5, z + 2) === 'diamond_block' && name(x - 5, 14, z - 5) === 'emerald_block')
  check('depth: room and chest visible', name(x + 9, 6, z + 9) === 'glowstone' && name(x + 9, 7, z + 9) === 'chest',
    `${name(x + 9, 6, z + 9)},${name(x + 9, 7, z + 9)}`)
  check('wire: room light sent when within view', roomLightSeen)
  const biome = y => block(x, y, z)?.biome?.id
  check('depth: real biome within view (deep dark differs from surface plains)', biome(-30) !== biome(30), `${biome(-30)} vs ${biome(30)}`)
  check('depth: underground armor stand visible', JSON.stringify(stands()) === '[10,30]', JSON.stringify(stands()))
  check('depth: the per-player hologram is sent within view', privateSeen())
  check('depth: the dropped item and XP orb are sent within view', dropsSeen() === 2, `${dropsSeen()} seen`)
  const privateId = Object.values(bot.entities).find(e => e.name === 'text_display' && Math.abs(e.position.x - (x + 5.5)) < 1)?.id

  // 3. Back above ground, then down into the hysteresis band (29..31): still hidden.
  phase = 'surface'
  await tp(38)
  await tp(30)
  bad = scan(x, z, -64, 16, () => 'air')
  check('band: at Y 30 coming from above, still hidden', bad.length === 0, bad.join(' '))
  check('band: biome below the limit masked (same as surface)', biome(-30) === biome(30), `${biome(-30)} vs ${biome(30)}`)
  check('band: underground armor stand hidden', JSON.stringify(stands()) === '[30]', JSON.stringify(stands()))
  check('band: the per-player hologram is hidden again', !privateSeen())
  check('band: the dropped item and XP orb are hidden again', dropsSeen() === 0, `${dropsSeen()} seen`)
  // ...and updates about it (data, movement) don't reach the client either, though the server keeps it paired.
  const privateUpdates = []
  const noteUpdate = p => { if (p.entityId === privateId) privateUpdates.push(p) }
  for (const n of ['entity_metadata', 'entity_teleport', 'sync_entity_position', 'rel_entity_move', 'entity_move_look']) bot._client.on(n, noteUpdate)
  const privateSel = `@e[type=text_display,x=${x + 5.5},y=10,z=${z + 5.5},distance=..1,limit=1]`
  bot.chat(`/data merge entity ${privateSel} {billboard:"center"}`)
  bot.chat(`/tp ${privateSel} ${x + 5.5} 11 ${z + 5.5}`)
  await sleep(2000)
  check('band: no updates about the hidden per-player hologram are sent', privateId !== undefined && privateUpdates.length === 0,
    `id ${privateId}, ${privateUpdates.length} sent`)
  check('wire: no block entities at or below 16 sent while hidden', leakedBlockEntities === 0, `${leakedBlockEntities} leaked`)
  check('wire: no light for sections at or below 15 sent while hidden', leakedLight === 0, `${leakedLight} leaked`)

  // 4. Out of the band: depth view.
  phase = 'depth'
  await tp(28.5)
  check('band: at Y 28.5, the room is visible again', name(x + 9, 6, z + 9) === 'glowstone', name(x + 9, 6, z + 9))

  // 5. Line of sight: a second player and an armor stand behind a wall, seen from the surface (not as a spectator).
  phase = 'los'
  bot.chat('/gamemode creative')
  await tp(38)
  const joinBot2 = async () => {
    const b = mineflayer.createBot({ ...connection, username: 'AfcBot2' })
    await new Promise(resolve => b.once('spawn', resolve))
    return b
  }
  let bot2 = await joinBot2()
  await sleep(1000)
  const wall = material => bot.chat(`/fill ${x + 3} 38 ${z - 6} ${x + 3} 44 ${z + 6} minecraft:${material}`)
  const standAt = [x + 6.5, 38, z + 0.5]
  const summonStand = () => bot.chat(`/summon armor_stand ${standAt.join(' ')} {Invulnerable:1b,UUID:[I;1,2,3,4]}`)
  const standSeen = () => Object.values(bot.entities).some(e => e.name === 'armor_stand' && e.position.y > 35)
  const playerSeen = () => !!bot.players.AfcBot2?.entity
  const seen = () => `stand=${standSeen()} player=${playerSeen()}`
  // Whether a sound played right at the armor stand reaches the bot.
  const soundHeard = async () => {
    sounds.length = 0
    bot.chat(`/playsound minecraft:entity.armor_stand.hit neutral AfcBot ${standAt.join(' ')}`)
    await sleep(1000)
    return sounds.some(([sx, sy, sz]) => Math.hypot(sx - standAt[0], sy - standAt[1], sz - standAt[2]) < 2)
  }
  wall('stone')
  summonStand()
  bot.chat(`/tp AfcBot2 ${x + 6.5} 38 ${z + 3.5}`)
  await sleep(2000)
  check('los: stand and player behind a stone wall hidden', !standSeen() && !playerSeen(), seen())
  check('los: hidden player stays in the tab list', !!bot.players.AfcBot2)
  check('los: a sound at a hidden entity is not sent', !(await soundHeard()))
  // Behind the wall too: a hologram another plugin shows to this player only (held back), a see-through text display
  // (drawn through walls anyway, so shown), and a chest (disguised); a chest right next to the player stays real.
  bot.chat(`/afcprivate AfcBot ${x + 6.5} 39 ${z - 2.5}`)
  bot.chat(`/summon text_display ${x + 6.5} 39 ${z + 5.5} {see_through:1b}`)
  bot.chat(`/setblock ${x + 6} 38 ${z - 4} minecraft:chest`)
  const copper = !!bot.registry.blocksByName.copper_chest // 1.21.9 and newer
  if (copper) bot.chat(`/setblock ${x + 6} 38 ${z + 4} minecraft:waxed_copper_chest`) // waxed: it can't oxidize meanwhile
  bot.chat(`/setblock ${x + 2} 38 ${z + 2} minecraft:chest`)
  // What base finders look for: a sign, a banner and a bed behind the wall.
  bot.chat(`/setblock ${x + 7} 38 ${z - 1} minecraft:oak_sign`)
  bot.chat(`/setblock ${x + 7} 38 ${z + 1} minecraft:white_banner`)
  bot.chat(`/setblock ${x + 8} 38 ${z - 3} minecraft:red_bed[part=foot,facing=north]`)
  bot.chat(`/setblock ${x + 8} 38 ${z - 4} minecraft:red_bed[part=head,facing=north]`)
  // A chest the hidden player has open (far enough from the bot not to be shown anyway).
  bot.chat(`/setblock ${x + 7} 38 ${z + 4} minecraft:chest`)
  // Arrows the hidden player shot: one behind the wall with them, one in plain sight in front of it.
  const ownerInts = (bot.players.AfcBot2?.uuid ?? '0'.repeat(32)).replace(/-/g, '').match(/.{8}/g).map(h => parseInt(h, 16) | 0)
  bot.chat(`/summon arrow ${x + 6.5} 39.5 ${z + 2} {Owner:[I;${ownerInts.join(',')}],NoGravity:1b,Tags:["shot"]}`)
  bot.chat(`/summon arrow ${x + 1.5} 39.5 ${z - 1.5} {Owner:[I;${ownerInts.join(',')}],NoGravity:1b,Tags:["shot"]}`)
  const arrowAt = ax => Object.values(bot.entities).some(e => e.name === 'arrow' && Math.abs(e.position.x - ax) < 1)
  const textAt = dz => Object.values(bot.entities).some(e => e.name === 'text_display' && e.position.y > 35 && Math.abs(e.position.z - (z + dz)) < 1)
  await sleep(1500)
  check('los: a per-player hologram behind the wall is hidden', !textAt(-2.5))
  check('los: a see-through text display behind the wall is shown', textAt(5.5))
  check('chest: one behind a wall arrives disguised', name(x + 6, 38, z - 4) === 'air', name(x + 6, 38, z - 4))
  if (copper) check('chest: a copper one too', name(x + 6, 38, z + 4) === 'air', name(x + 6, 38, z + 4))
  check('chest: one right next to the player is real', name(x + 2, 38, z + 2) === 'chest', name(x + 2, 38, z + 2))
  const baseBlocks = () => [name(x + 7, 38, z - 1), name(x + 7, 38, z + 1), name(x + 8, 38, z - 3), name(x + 8, 38, z - 4)].join()
  check('base: a sign, a banner and a bed behind the wall arrive disguised', baseBlocks() === 'air,air,air,air', baseBlocks())
  check('projectile: an arrow of the hidden player behind the wall is not sent', !arrowAt(x + 6.5))
  check('projectile: one of theirs in plain sight is', arrowAt(x + 1.5))
  bot.chat('/kill @e[tag=shot]')
  let lidChest = null
  try {
    lidChest = await bot2.openContainer(bot2.blockAt(new Vec3(x + 7, 38, z + 4)))
  } catch (e) {
    console.log('could not open the chest as AfcBot2:', e.message)
  }
  await sleep(500)
  lids.length = 0 // (the game repeats an open chest's lid state every 5 ticks; it must get through once shown)
  wall('glass')
  await sleep(1500)
  const lidEvents = lids.filter(p => p.location.x === x + 7 && p.location.y === 38 && p.location.z === z + 4)
  check('chest: one someone has open is shown open', lidChest !== null && lidEvents.some(p => p.byte2 > 0),
    JSON.stringify(lidEvents.map(p => [p.byte1, p.byte2])))
  if (lidChest) bot2.closeWindow(lidChest)
  check('base: in sight through glass, they are shown', baseBlocks() === 'oak_sign,white_banner,red_bed,red_bed', baseBlocks())
  check('los: both visible through glass', standSeen() && playerSeen(), seen())
  check('los: the per-player hologram in sight through glass is shown', textAt(-2.5))
  check('chest: in sight through glass, it is shown', name(x + 6, 38, z - 4) === 'chest', name(x + 6, 38, z - 4))
  if (copper) check('chest: the copper one too', name(x + 6, 38, z + 4) === 'waxed_copper_chest', name(x + 6, 38, z + 4))
  wall('stone')
  await sleep(1500)
  check('los: hidden again behind stone', !standSeen() && !playerSeen(), seen())
  check('los: the per-player hologram is held back again behind stone', !textAt(-2.5))
  // Removed and back with the same UUID, as after a chunk reload: the server forgot its hide, the plugin must not.
  bot.chat('/kill 00000001-0000-0002-0000-000300000004')
  await sleep(1000)
  summonStand()
  await sleep(2000)
  check('los: an entity back after the server forgot hiding it is hidden again', !standSeen(), seen())
  bot2.quit()
  await sleep(1500)
  check('los: a hidden player who quits leaves the tab list', !bot.players.AfcBot2)
  bot2 = await joinBot2() // comes back where they left: behind the wall
  await sleep(2000)
  check('los: a hidden player who relogs stays hidden', !playerSeen() && !!bot.players.AfcBot2, seen())
  // A vanish plugin hiding a player this plugin hides too: off the tab list, and back on when unvanished. The entity is
  // looked up by UUID, as mineflayer can't name a player that isn't listed.
  const bot2Uuid = bot.players.AfcBot2?.uuid
  const bot2Seen = () => Object.values(bot.entities).some(e => e.type === 'player' && e.uuid === bot2Uuid)
  bot.chat('/afcvanish AfcBot2 on')
  await sleep(1500)
  check('vanish: a vanished player hidden by line of sight leaves the tab list', !bot.players.AfcBot2)
  bot.chat('/afcvanish AfcBot2 off')
  await sleep(1500)
  check('vanish: unvanished behind the wall, listed again and still hidden', !!bot.players.AfcBot2 && !bot2Seen(), seen())
  bot.chat('/afcvanish AfcBot2 on')
  await sleep(1500)
  wall('air')
  await sleep(1500)
  check('vanish: in sight, a vanished player stays hidden and unlisted', !bot.players.AfcBot2 && !bot2Seen(), `seen=${bot2Seen()}`)
  bot.chat('/afcvanish AfcBot2 off')
  await sleep(1500)
  wall('air')
  await sleep(1500)
  check('los: both visible with the wall gone', standSeen() && playerSeen(), seen())
  check('los: tab list still intact', !!bot.players.AfcBot2)
  check('los: a sound at a visible entity is sent', await soundHeard())

  // Beyond max-distance (64) there is no line-of-sight check, but the hidden range still applies.
  const farSeen = () => Object.values(bot.entities).some(e => e.name === 'text_display' && e.position.x < x - 60)
  bot.chat(`/summon text_display ${x - 69.5} 38 ${z + 0.5} {Tags:["far"]}`)
  await sleep(2000)
  check('far: an entity beyond max-distance above the cut is sent', farSeen())
  sunk.length = 0
  watchSunk = true
  bot.chat(`/tp @e[tag=far] ${x - 69.5} 10 ${z + 0.5}`) // (on Paper the filter takes it off; Folia's /tp spawns it anew)
  await sleep(2000)
  check('far: an entity beyond max-distance that goes below the cut is hidden', !farSeen())
  check('far: and its position below the cut was never sent', sunk.length === 0, sunk.join(' '))
  watchSunk = false

  // 6. A view change re-sends chunks to that player only, not to the player standing next to them. As a spectator,
  // so cave occlusion (which re-sends what it reveals) can't stand in for the refresh.
  // First let the second player's own chunk loading finish (2 s without a chunk, at most 20 s).
  bot.chat('/gamemode spectator')
  let lastChunk = Date.now()
  const noteChunk = () => { lastChunk = Date.now() }
  bot2._client.on('map_chunk', noteChunk)
  for (const start = Date.now(); Date.now() - lastChunk < 2000 && Date.now() - start < 20000;) await sleep(100)
  bot2._client.removeListener('map_chunk', noteChunk)
  let ownChunks = 0, neighbourChunks = 0
  bot._client.on('map_chunk', () => ownChunks++)
  bot2._client.on('map_chunk', () => neighbourChunks++)
  await tp(20) // surface -> depth view
  // The far display, still at Y 10, never moves again: only the far pass shows and hides it as the view changes.
  check('far: shown again once a depth view reaches it', farSeen())
  await tp(38) // depth -> surface view
  check('far: one beyond max-distance that never moved is hidden when the view changes', !farSeen())
  check('refresh: the player whose view changed got their chunks again', ownChunks > 0, `${ownChunks} chunks`)
  check('refresh: the player next to them got none', neighbourChunks === 0, `${neighbourChunks} chunks`)
  bot2.quit()
  // Down and straight back up: the second refresh falls inside the cooldown and must run late, not never.
  bot.chat(`/tp @s ${x + 0.5} 20 ${z + 0.5}`)
  await sleep(500)
  bot.chat(`/tp @s ${x + 0.5} 38 ${z + 0.5}`)
  await sleep(5000)
  bad = scan(x, z, -64, 16, () => 'air')
  check('refresh: one asked for inside the cooldown still happens', bad.length === 0, bad.join(' '))

  // Light: lava just under the cut lights a cave above it that is open to the sky. The chunk carries none of that
  // light to a surface player. (Light updates reach only players at the edge of their view: not tested.)
  const LX = x - 40, LZ = z - 40
  let chunkLight = null
  bot._client.on('map_chunk', p => { if (p.x === LX >> 4 && p.z === LZ >> 4) chunkLight = blockLightAt(p, LX, 17, LZ) })
  bot.chat(`/fill ${LX - 1} 17 ${LZ - 1} ${LX + 1} 20 ${LZ + 1} minecraft:air`)
  bot.chat(`/fill ${LX} 21 ${LZ} ${LX} 37 ${LZ} minecraft:air`)
  bot.chat(`/setblock ${LX} 16 ${LZ} minecraft:lava`)
  await sleep(2000)
  await tp(20) // depth view: the light is real...
  check('light: real within view', chunkLight > 0, `block light ${chunkLight} at Y 17`)
  await tp(38) // ...surface view: the chunk comes again, without it
  check('light: the chunk carries no light from lava under the cut', chunkLight === 0, `block light ${chunkLight} at Y 17`)

  // The same at a chunk's east edge, the cave above the lava running on into the next chunk: there too.
  const CX = ((x - 70) >> 4 << 4) + 15, CZ = z - 40
  let acrossLight = null
  bot._client.on('map_chunk', p => { if (p.x === (CX + 1) >> 4 && p.z === CZ >> 4) acrossLight = blockLightAt(p, CX + 2, 17, CZ) })
  bot.chat(`/fill ${CX} 17 ${CZ} ${CX + 4} 20 ${CZ} minecraft:air`)
  bot.chat(`/fill ${CX} 21 ${CZ} ${CX} 37 ${CZ} minecraft:air`)
  bot.chat(`/setblock ${CX} 16 ${CZ} minecraft:lava`)
  await sleep(2000)
  await tp(20)
  check('light: across a chunk edge, real within view', acrossLight > 0, `block light ${acrossLight} at Y 17`)
  await tp(38)
  check('light: the next chunk carries none of the light from lava under the cut', acrossLight === 0,
    `block light ${acrossLight} at Y 17`)
  const again = []
  for (let i = 0; i < 2; i++) { // and still after more view changes (each one a new round of cleaning)
    await tp(20)
    await tp(38)
    again.push(acrossLight)
  }
  check('light: and after two more view changes', again.every(l => l === 0), `block light ${again.join(', ')} at Y 17`)
  bot.chat('/gamemode creative')

  // 7. Cave occlusion (the bot is in creative, so it applies): sealed pockets arrive as stone, opening one reveals
  // it, a pocket right next to the player is real, spectators see everything.
  phase = 'occlusion'
  const X0 = ((x + 160) >> 4) << 4, Z0 = (z >> 4) << 4 // a fresh area, chunk-aligned
  const at = (dx, y, dz) => name(X0 + dx, y, Z0 + dz)
  bot.chat(`/tp @s ${X0 + 8.5} 38 ${Z0 + 8.5}`)
  await sleep(4000)
  await bot.waitForChunksToLoad()
  const pocket = (dx, dz, fx, fz) => { // a 5x5x5 air pocket at Y 20..24 with gold in its floor, sealed in stone
    bot.chat(`/fill ${X0 + dx - 2} 20 ${Z0 + dz - 2} ${X0 + dx + 2} 24 ${Z0 + dz + 2} minecraft:air`)
    bot.chat(`/setblock ${X0 + fx} 20 ${Z0 + fz} minecraft:gold_block`)
  }
  pocket(40, 8, 40, 8) // two chunks east
  bot.chat(`/setblock ${X0 + 41} 21 ${Z0 + 8} minecraft:chest`) // in it, out of sight from the surface
  bot.chat(`/setblock ${X0 + 44} 21 ${Z0 + 8} minecraft:chest`) // in the stone next to it, walled in
  eastChest = [X0 + 41, 21, Z0 + 8]
  pocket(-24, 8, -24, 8) // two chunks west
  bot.chat(`/setblock ${X0 - 24} 21 ${Z0 + 8} minecraft:chest`)
  pocketChunk = [(X0 - 24) >> 4, Z0 >> 4]
  bot.chat(`/fill ${X0 + 20} 26 ${Z0 + 6} ${X0 + 22} 28 ${Z0 + 8} minecraft:air`) // next chunk, just below the eyes
  bot.chat(`/setblock ${X0 + 21} 26 ${Z0 + 7} minecraft:diamond_block`)
  await sleep(1500)
  check('occlusion: sealed pocket two chunks away arrives as stone', at(40, 20, 8) === 'stone' && at(40, 22, 8) === 'stone',
    `${at(40, 20, 8)},${at(40, 22, 8)}`)
  check('occlusion: the other sealed pocket too', at(-24, 20, 8) === 'stone', at(-24, 20, 8))
  check('occlusion: a pocket right next to the player is real', at(21, 26, 7) === 'diamond_block', at(21, 26, 7))

  bot.chat(`/fill ${X0 + 40} 25 ${Z0 + 8} ${X0 + 40} 37 ${Z0 + 8} minecraft:air`) // a shaft from the surface into it
  const opened = Date.now()
  while (Date.now() - opened < 3000 && at(40, 20, 8) !== 'gold_block') await sleep(50)
  check('occlusion: opening a shaft into the pocket reveals it', at(40, 20, 8) === 'gold_block', `${Date.now() - opened} ms`)
  check('chest: one in a reachable cave out of sight arrives disguised', at(41, 21, 8) === 'air', at(41, 21, 8))
  check('wire: and without its block entity', eastChests.occlusion === 0, JSON.stringify(eastChests))
  check('chest: a walled-in one arrives as stone, not an air pocket', at(44, 21, 8) === 'stone', at(44, 21, 8))
  bot.chat(`/setblock ${X0 + 44} 21 ${Z0 + 8} minecraft:chest[facing=east]`) // its state changes
  // A marker higher up in the same chunk: block changes go out bottom to top, so once it is here the chest's would be.
  bot.chat(`/setblock ${X0 + 44} 38 ${Z0 + 8} minecraft:gold_block`)
  const marked = Date.now()
  while (Date.now() - marked < 3000 && at(44, 38, 8) !== 'gold_block') await sleep(50)
  check('chest: and stays stone when its state changes', at(44, 21, 8) === 'stone' && at(44, 38, 8) === 'gold_block',
    `${at(44, 21, 8)},${at(44, 38, 8)}`)

  phase = 'spectator'
  bot.chat('/gamemode spectator')
  await sleep(3000)
  check('occlusion: spectators see sealed pockets', at(-24, 20, 8) === 'gold_block', at(-24, 20, 8))
  check('wire: spectators get the chest in the sealed pocket', pocketChests.spectator > 0, `${pocketChests.spectator} sent`)
  check('chest: spectators get the disguised one real', at(41, 21, 8) === 'chest' && eastChests.spectator > 0,
    `${at(41, 21, 8)} ${JSON.stringify(eastChests)}`)
  phase = 'occluded'
  bot.chat('/gamemode creative')
  await sleep(3000)
  check('occlusion: hidden again after leaving spectator', at(-24, 20, 8) === 'stone', at(-24, 20, 8))
  check('occlusion: the opened pocket stays visible', at(40, 20, 8) === 'gold_block', at(40, 20, 8))

  // 8. To the Nether and back to the very same spot: the world must not arrive as solid stone.
  bot.chat('/execute in minecraft:the_nether run tp @s 0.5 100 0.5')
  await sleep(500)
  bot.creative.startFlying() // stay at Y 100, above the Nether's protection level
  await sleep(3500)
  // The Nether's own settings (run.sh): from Y 100 everything at or below Y 40 is hidden there.
  let netherHidden = 0, netherBad = [], netherAbove = 0
  for (let nx = -8; nx <= 8; nx += 4)
    for (let nz = -8; nz <= 8; nz += 4)
      for (let y = 0; y <= 126; y += 2) { // up to the bedrock roof: something above the cut is always solid
        const n = name(nx, y, nz)
        if (n === undefined) continue
        if (y > 40) netherAbove += n !== 'air' ? 1 : 0
        else if (n === 'air') netherHidden++
        else if (netherBad.length < 5) netherBad.push(`${nx},${y},${nz}=${n}`)
      }
  check('per-world: the Nether hides everything at or below its own cut (Y 40)', netherHidden > 0 && netherBad.length === 0 && netherAbove > 0,
    `${netherHidden} hidden, above ${netherAbove} solid, ${netherBad.join(' ')}`)
  bot.chat(`/execute in minecraft:overworld run tp @s ${X0 + 8.5} 38 ${Z0 + 8.5}`)
  await sleep(5000)
  await bot.waitForChunksToLoad()
  check('world change: own surroundings are real after coming back', at(8, 37, 8) === 'grass_block' && at(8, 40, 8) === 'air',
    `${at(8, 37, 8)},${at(8, 40, 8)}`)
  check('world change: the sealed pocket is still hidden', at(-24, 20, 8) === 'stone', at(-24, 20, 8))
  check('world change: the opened pocket is visible again', at(40, 20, 8) === 'gold_block', at(40, 20, 8))
  check('wire: no chest sent from the sealed pocket while it is occluded', !pocketChests.occluded && !pocketChests.occlusion,
    JSON.stringify(pocketChests))
  check('seed: respawns into other worlds carry the made-up hashed seed too',
    realSeed !== null && hashedSeeds.length >= 3 && hashedSeeds.every(s => s !== hashOf(realSeed)), hashedSeeds.join(' '))
  check('chest: disguised again after spectator and coming back', at(41, 21, 8) === 'air' && eastChests.occluded === 0,
    `${at(41, 21, 8)} ${JSON.stringify(eastChests)}`)

  // Sealing the shaft again hides the pocket again, a few seconds later.
  bot.chat(`/fill ${X0 + 40} 25 ${Z0 + 8} ${X0 + 40} 37 ${Z0 + 8} minecraft:stone`)
  const sealed = Date.now()
  while (Date.now() - sealed < 25000 && at(40, 20, 8) !== 'stone') await sleep(250)
  check('occlusion: sealing the shaft hides the pocket again', at(40, 20, 8) === 'stone', `${Date.now() - sealed} ms`)

  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  bot.quit()
  setTimeout(() => process.exit(failures ? 1 : 0), 500)
})
