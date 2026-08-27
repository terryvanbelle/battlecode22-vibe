#!/usr/bin/env bash
set -euo pipefail

echo "===== [1/6] System prep ====="
sudo apt-get update -qq
sudo apt-get install -y -qq git curl unzip >/dev/null
echo "OK"

echo "===== [2/6] Install Temurin JDK 8 ====="
cd ~
if [ ! -d jdk8 ]; then
  curl -sSL -o jdk8.tar.gz "https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/OpenJDK8U-jdk_x64_linux_hotspot_8u504b01.tar.gz"
  mkdir -p jdk8
  tar xzf jdk8.tar.gz -C jdk8 --strip-components=1
fi
export JAVA_HOME=~/jdk8
export PATH="$JAVA_HOME/bin:$PATH"
java -version

echo "===== [3/6] Clone scaffold ====="
rm -rf ~/battlecode22-scaffold
git clone --depth 1 https://github.com/battlecode/battlecode22-scaffold.git ~/battlecode22-scaffold
cd ~/battlecode22-scaffold
cat version.txt

echo "===== [4/6] Build + list maps ====="
./gradlew --no-daemon build 2>&1 | tail -20
echo "--- available maps ---"
./gradlew --no-daemon listMaps -q 2>&1 | sort -u | tee ~/maps.txt

echo "===== [5/6] Run example bot vs itself on two boards ====="
mkdir -p ~/battlecode22-scaffold/matches ~/out
run_match () {
  local MAP="$1"
  echo ">>> Match on map: $MAP"
  ./gradlew --no-daemon run --no-build-cache \
    -PteamA=examplefuncsplayer -PteamB=examplefuncsplayer \
    -Pmaps="$MAP" \
    -Preplay="matches/examplefuncsplayer-vs-examplefuncsplayer-on-${MAP}.bc22" \
    2>&1 | tee "$HOME/out/match-${MAP}.log" | tail -30
}
MAP1=maptestsmall
MAP2=eckleburg
grep -qx "$MAP2" ~/maps.txt || MAP2=$(grep -vx "$MAP1" ~/maps.txt | head -1)
echo "Using MAP1=$MAP1  MAP2=$MAP2"
run_match "$MAP1"
run_match "$MAP2"

echo "===== [6/6] Collect artifacts ====="
mkdir -p ~/out/matches
cp ~/battlecode22-scaffold/matches/*.bc22 ~/out/matches/ || true
ls -la ~/out ~/out/matches
echo "DONE"
