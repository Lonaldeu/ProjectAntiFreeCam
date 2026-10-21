// End-to-end check of ProjectAntiFreeCam against a flat world: bedrock -64, stone -63..36, grass 37. View distance 6.
// AfcTestPlugin (e2e/testplugin) provides /afcvanish and /afcprivate, standing in for other plugins.
// Defaults: above ground at Y >= 31 (full view back below 29): Y <= 16 hidden.
// Below that: sections more than 48 blocks below the player are hidden (at Y 20: Y <= -33).
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

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
let pocketChunk = null
const pocketChests = {}
bot._client.on('map_chunk', p => {
  if (pocketChunk && p.x === pocketChunk[0] && p.z === pocketChunk[1])
    pocketChests[phase] = (pocketChests[phase] || 0) + (p.blockEntities || []).filter(be => be.y >= 20 && be.y <= 24).length
})

bot.on('kicked', r => { console.log('KICKED', JSON.stringify(r)); process.exit(2) })
bot.on('error', e => { console.log('ERROR', e.message); process.exit(2) })

bot.once('spawn', async () => {
  await sleep(4000)
  await bot.waitForChunksToLoad()
  const { x, z } = bot.entity.position.floored()
  const tp = async y => { bot.chat(`/tp @s ${x + 0.5} ${y} ${z + 0.5}`); await sleep(3500) }
  console.log('spawned at', bot.entity.position.toString())

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
  await sleep(2500)
  check('surface: underground armor stand hidden, surface one shown', JSON.stringify(stands()) === '[30]', JSON.stringify(stands()))
  check('surface: single block change below limit hidden', name(x + 2, 5, z + 2) === 'air', name(x + 2, 5, z + 2))
  check('surface: single block change above limit shown', name(x + 2, 30, z + 2) === 'gold_block', name(x + 2, 30, z + 2))
  check('surface: multi block change split at limit',
    [14, 16, 17, 20].map(y => name(x - 5, y, z - 5)).join() === 'air,air,emerald_block,emerald_block',
    [14, 16, 17, 20].map(y => name(x - 5, y, z - 5)).join())
  check('surface: a per-player hologram below the cut is hidden', !privateSeen())

  // Entities that cross the cut after spawning: one cart goes down into the hidden range, one comes up out of it.
  // which side of the bot each visible cart is on: -1 for the one summoned at x-2, 1 for the one at x+2
  const carts = () => Object.values(bot.entities).filter(e => e.name === 'minecart').map(e => Math.sign(e.position.x - x)).sort((a, b) => a - b)
  bot.chat(`/summon minecart ${x - 2} 45 ${z + 2} {NoGravity:1b,Tags:["falls"]}`)
  bot.chat(`/summon minecart ${x + 2} 10 ${z - 2} {NoGravity:1b,Tags:["rises"]}`)
  await sleep(1500)
  check('crossing: only the cart above the cut is visible', JSON.stringify(carts()) === '[-1]', JSON.stringify(carts()))
  bot.chat(`/tp @e[tag=falls] ${x - 2} 10 ${z + 2}`)
  bot.chat(`/tp @e[tag=rises] ${x + 2} 45 ${z - 2}`)
  await sleep(1500)
  check('crossing: the cart that went down vanished, the one that came up appeared', JSON.stringify(carts()) === '[1]', JSON.stringify(carts()))

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
  wall('glass')
  await sleep(1500)
  check('los: both visible through glass', standSeen() && playerSeen(), seen())
  wall('stone')
  await sleep(1500)
  check('los: hidden again behind stone', !standSeen() && !playerSeen(), seen())
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
  bot.chat(`/tp @e[tag=far] ${x - 69.5} 10 ${z + 0.5}`) // (Folia's /tp spawns it anew, so only Paper proves the far pass)
  await sleep(2000)
  check('far: an entity beyond max-distance that goes below the cut is hidden', !farSeen())

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
  await tp(38) // depth -> surface view
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

  phase = 'spectator'
  bot.chat('/gamemode spectator')
  await sleep(3000)
  check('occlusion: spectators see sealed pockets', at(-24, 20, 8) === 'gold_block', at(-24, 20, 8))
  check('wire: spectators get the chest in the sealed pocket', pocketChests.spectator > 0, `${pocketChests.spectator} sent`)
  phase = 'occluded'
  bot.chat('/gamemode creative')
  await sleep(3000)
  check('occlusion: hidden again after leaving spectator', at(-24, 20, 8) === 'stone', at(-24, 20, 8))
  check('occlusion: the opened pocket stays visible', at(40, 20, 8) === 'gold_block', at(40, 20, 8))

  // 8. To the Nether and back to the very same spot: the world must not arrive as solid stone.
  bot.chat('/execute in minecraft:the_nether run tp @s 0.5 100 0.5')
  await sleep(4000)
  bot.chat(`/execute in minecraft:overworld run tp @s ${X0 + 8.5} 38 ${Z0 + 8.5}`)
  await sleep(5000)
  await bot.waitForChunksToLoad()
  check('world change: own surroundings are real after coming back', at(8, 37, 8) === 'grass_block' && at(8, 40, 8) === 'air',
    `${at(8, 37, 8)},${at(8, 40, 8)}`)
  check('world change: the sealed pocket is still hidden', at(-24, 20, 8) === 'stone', at(-24, 20, 8))
  check('world change: the opened pocket is visible again', at(40, 20, 8) === 'gold_block', at(40, 20, 8))
  check('wire: no chest sent from the sealed pocket while it is occluded', !pocketChests.occluded && !pocketChests.occlusion,
    JSON.stringify(pocketChests))

  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  bot.quit()
  setTimeout(() => process.exit(failures ? 1 : 0), 500)
})
