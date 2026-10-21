#!/usr/bin/env bash
# Поднимает Paper + LuckPerms + FoolioTitles и гоняет ботов. Аргумент: версия Minecraft.
set -euo pipefail
VER="$1"; HERE="$(cd "$(dirname "$0")" && pwd)"; JAR="$2"
mkdir -p server/plugins && cd server

# --- Paper (сначала новый API fill v3, затем старый v2)
URL=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$VER/builds/latest" 2>/dev/null | jq -r '.downloads["server:default"].url // empty' || true)
if [ -z "$URL" ]; then
  B=$(curl -fsSL "https://api.papermc.io/v2/projects/paper/versions/$VER/builds" | jq -r '.builds | map(select(.channel=="default")) | last | .build')
  URL="https://api.papermc.io/v2/projects/paper/versions/$VER/builds/$B/downloads/paper-$VER-$B.jar"
fi
echo "Paper: $URL"; curl -fsSL -o paper.jar "$URL"

# --- LuckPerms
LP=$(curl -fsSL https://metadata.luckperms.net/data/all | jq -r '.downloads.bukkit')
echo "LuckPerms: $LP"; curl -fsSL -o plugins/LuckPerms.jar "$LP"
cp "$JAR" plugins/FoolioTitles.jar

# --- конфиг плагина для тестов: короткий кулдаун
mkdir -p plugins/FoolioTitles
sed 's/submit-cooldown-seconds: 30/submit-cooldown-seconds: 3/' "$HERE/../../FoolioTitles/FoolioTitles-1.0.0/plugin/src/main/resources/config.yml" > plugins/FoolioTitles/config.yml

echo "eula=true" > eula.txt
cat > server.properties <<P
online-mode=false
enforce-secure-profile=false
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=3
simulation-distance=3
max-players=10
P

mkfifo console.in
sleep infinity > console.in 2>/dev/null </dev/null &
HOLDER=$!
java -Xms1G -Xmx2G -Dfile.encoding=UTF-8 -jar paper.jar --nogui < console.in > server.log 2>&1 &
SERVER=$!
for i in $(seq 1 180); do grep -q 'Done (' server.log && break; sleep 1; done
grep -q 'Done (' server.log || { tail -50 server.log; exit 1; }
grep -E "FoolioTitles|LuckPerms" server.log | head -20
cd "$HERE"
set +e
SERVER_LOG="$HERE/server/server.log" SERVER_CONSOLE="$HERE/server/console.in" PLUGIN_CONFIG="$HERE/server/plugins/FoolioTitles/config.yml" MC_VERSION="$VER" node e2e.mjs
CODE=$?
echo stop > server/console.in; sleep 8; kill $SERVER $HOLDER 2>/dev/null; wait 2>/dev/null
echo "----- FoolioTitles / ошибки в логе сервера -----"
grep -nE "FoolioTitles|dev\.foolio|Exception|ERROR" server/server.log | grep -v "Done (" | head -80
exit $CODE
