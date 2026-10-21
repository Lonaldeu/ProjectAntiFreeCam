// End-to-end check of ProjectAntiFreeCam against a flat world: bedrock -64, stone -63..36, grass 37.
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
  await sleep(2500)
  check('surface: underground armor stand hidden, surface one shown', JSON.stringify(stands()) === '[30]', JSON.stringify(stands()))
  check('surface: single block change below limit hidden', name(x + 2, 5, z + 2) === 'air', name(x + 2, 5, z + 2))
  check('surface: single block change above limit shown', name(x + 2, 30, z + 2) === 'gold_block', name(x + 2, 30, z + 2))
  check('surface: multi block change split at limit',
    [14, 16, 17, 20].map(y => name(x - 5, y, z - 5)).join() === 'air,air,emerald_block,emerald_block',
    [14, 16, 17, 20].map(y => name(x - 5, y, z - 5)).join())

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

  // 3. Back above ground, then down into the hysteresis band (29..31): still hidden.
  phase = 'surface'
  await tp(38)
  await tp(30)
  bad = scan(x, z, -64, 16, () => 'air')
  check('band: at Y 30 coming from above, still hidden', bad.length === 0, bad.join(' '))
  check('band: biome below the limit masked (same as surface)', biome(-30) === biome(30), `${biome(-30)} vs ${biome(30)}`)
  check('band: underground armor stand hidden', JSON.stringify(stands()) === '[30]', JSON.stringify(stands()))
  check('wire: no block entities at or below 16 sent while hidden', leakedBlockEntities === 0, `${leakedBlockEntities} leaked`)
  check('wire: no light for sections at or below 15 sent while hidden', leakedLight === 0, `${leakedLight} leaked`)

  // 4. Out of the band: depth view.
  phase = 'depth'
  await tp(28.5)
  check('band: at Y 28.5, the room is visible again', name(x + 9, 6, z + 9) === 'glowstone', name(x + 9, 6, z + 9))

  // 5. Line of sight: a second player and a pig behind a wall, seen from the surface (not as a spectator).
  phase = 'los'
  bot.chat('/gamemode creative')
  await tp(38)
  const bot2 = mineflayer.createBot({ ...connection, username: 'AfcBot2' })
  await new Promise(resolve => bot2.once('spawn', resolve))
  await sleep(1000)
  const wall = material => bot.chat(`/fill ${x + 3} 38 ${z - 6} ${x + 3} 44 ${z + 6} minecraft:${material}`)
  const pigSeen = () => Object.values(bot.entities).some(e => e.name === 'pig')
  const playerSeen = () => !!bot.players.AfcBot2?.entity
  const seen = () => `pig=${pigSeen()} player=${playerSeen()}`
  wall('stone')
  bot.chat(`/summon pig ${x + 6.5} 38 ${z + 0.5} {NoAI:1b,Invulnerable:1b}`)
  bot.chat(`/tp AfcBot2 ${x + 6.5} 38 ${z + 3.5}`)
  await sleep(2000)
  check('los: pig and player behind a stone wall hidden', !pigSeen() && !playerSeen(), seen())
  check('los: hidden player stays in the tab list', !!bot.players.AfcBot2)
  wall('glass')
  await sleep(1500)
  check('los: both visible through glass', pigSeen() && playerSeen(), seen())
  wall('stone')
  await sleep(1500)
  check('los: hidden again behind stone', !pigSeen() && !playerSeen(), seen())
  wall('air')
  await sleep(1500)
  check('los: both visible with the wall gone', pigSeen() && playerSeen(), seen())
  check('los: tab list still intact', !!bot.players.AfcBot2)

  // 6. A view change re-sends chunks to that player only, not to the player standing next to them.
  let ownChunks = 0, neighbourChunks = 0
  bot._client.on('map_chunk', () => ownChunks++)
  bot2._client.on('map_chunk', () => neighbourChunks++)
  await tp(20) // surface -> depth view
  await tp(38) // depth -> surface view
  check('refresh: the player whose view changed got their chunks again', ownChunks > 0, `${ownChunks} chunks`)
  check('refresh: the player next to them got none', neighbourChunks === 0, `${neighbourChunks} chunks`)
  bot2.quit()

  console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
  bot.quit()
  setTimeout(() => process.exit(failures ? 1 : 0), 500)
})
