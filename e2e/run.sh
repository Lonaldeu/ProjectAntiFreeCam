#!/usr/bin/env bash
# End-to-end test: starts real Paper/Folia servers in Docker with the plugin, connects bot clients and checks
# what they actually receive. Needs Docker, Node.js and curl, and the built plugin jar (see README).
#
#   e2e/run.sh                                  every tested server build
#   e2e/run.sh paper:1.21.8 folia:1.21.8        just these
#   e2e/run.sh --without-plugin paper:1.21.8    control run: the hiding checks should fail
set -u
cd "$(dirname "$0")"
here=$(pwd -W 2>/dev/null || pwd) # Docker on Windows (Git Bash) needs the Windows form of the path

jar=$(ls ../build/libs/ProjectAntiFreeCam-*.jar 2>/dev/null | head -1)
testplugin=../build/e2e/AfcTestPlugin.jar # stands in for a vanish plugin and a per-player hologram plugin
if [ ! -f "$testplugin" ]; then
  echo "Build the plugin first (see README)."
  exit 2
elif [ "${1:-}" = --without-plugin ]; then
  jar=
  shift
elif [ -z "$jar" ]; then
  echo "Build the plugin first (see README)."
  exit 2
fi
[ $# -gt 0 ] || set -- paper:1.20.6 paper:1.21.4 paper:1.21.8 paper:1.21.11 paper:26.1.2 folia:1.21.8
[ -d bot/node_modules ] || (cd bot && npm install --silent) || exit 2

# Offline-mode UUID of the test bot, so it can be op.
uuid=$(node -e 'const h = require("crypto").createHash("md5").update("OfflinePlayer:AfcBot").digest();
h[6] = h[6] & 0x0f | 0x30; h[8] = h[8] & 0x3f | 0x80; const s = h.toString("hex");
console.log([s.slice(0, 8), s.slice(8, 12), s.slice(12, 16), s.slice(16, 20), s.slice(20)].join("-"))')

failed=0
for target in "$@"; do
  project=${target%%:*} version=${target#*:} dir=.servers/$project-$version
  mkdir -p "$dir/plugins"
  if [ ! -s "$dir/server.jar" ]; then
    url=$(curl -s "https://fill.papermc.io/v3/projects/$project/versions/$version/builds/latest" | grep -o '"url":"[^"]*"' | head -1 | cut -d'"' -f4)
    curl -sf -o "$dir/server.jar" "$url" || { echo "Could not download $target"; failed=1; continue; }
  fi
  # A fresh world and config every run: the defaults, but with air for hidden blocks (the test tells them apart from
  # the world's stone by name) and the Nether protected with settings of its own.
  rm -rf "$dir"/plugins/*.jar "$dir/plugins/ProjectAntiFreeCam" "$dir/world" "$dir/world_nether" "$dir/world_the_end"
  mkdir -p "$dir/plugins/ProjectAntiFreeCam"
  cat > "$dir/plugins/ProjectAntiFreeCam/config.yml" <<'EOF'
worlds:
  whitelist: [world, world_nether]
  overrides:
    world_nether:
      antixray:
        protection-y-level: 64.0
        hide-below-y: 40
performance:
  replacement:
    block-type: air
EOF
  [ -z "$jar" ] || cp "$jar" "$dir/plugins/"
  cp "$testplugin" "$dir/plugins/"
  cp server.properties "$dir/"
  echo eula=true > "$dir/eula.txt"
  echo "[{\"uuid\":\"$uuid\",\"name\":\"AfcBot\",\"level\":4,\"bypassesPlayerLimit\":false}]" > "$dir/ops.json"

  java=21
  [ "${version%%.*}" -ge 26 ] && java=25 # Minecraft 26.x needs Java 25
  docker rm -f afc-e2e >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 docker run -d --name afc-e2e -p 25566:25565 -v "$here/$dir:/w" -w /w \
    eclipse-temurin:$java-jre java -Xmx2G -jar server.jar --nogui >/dev/null
  for _ in $(seq 1 120); do docker logs afc-e2e 2>&1 | grep -q 'Done (' && break; sleep 3; done

  echo "===== $target"
  (cd bot && MC_PROJECT=$project MC_VERSION=$version PORT=25566 timeout 360 node test.js) || failed=1
  docker logs afc-e2e > "$dir/server.log" 2>&1
  grep -E 'Exception|SEVERE' "$dir/server.log" | head -5
  # The plugin logs these when something goes out unfiltered ("... on this version: " when a feature is off); no bot
  # check might notice.
  if [ -n "$jar" ] && grep -qE 'Self-check FAILED|Could not filter|Could not take hidden light|Unrecognized chunk format|on this version: ' "$dir/server.log"; then
    echo "FAIL $target: the plugin sent packets unfiltered (see $dir/server.log)"
    failed=1
  fi
  if [ -n "$jar" ] && ! grep -q "Paper's anti-xray is off" "$dir/server.log"; then
    echo "FAIL $target: no hint that Paper's anti-xray is off"
    failed=1
  fi
  if [ -n "$jar" ] && ! grep -q "placed from the world seed" "$dir/server.log"; then
    echo "FAIL $target: no warning that ores are placed from the world seed"
    failed=1
  fi
  docker rm -f afc-e2e >/dev/null
done
exit $failed
