#!/usr/bin/env bash
# Heat, CPU and memory over a game-mode session, for V3 acceptance criterion 6
# (docs/notes/v3.md, "Throttling when still").
#
#   tools/game-soak/soak.sh <label> [minutes=30] [game package=com.YostarJP.BlueArchive]
#
# Writes build/game-soak/<label>.csv, one row every 30 s:
#   seconds, battery temperature (°C), thermal status (0 none … 6 shutdown),
#   CPU throttling (sum of the cpufreq cooling devices' states), Babel's and
#   the game's CPU % over the last interval (100 = one core), Babel's PSS (MB).
#
# Run it over Wi-Fi adb with the cable out: charging heats the battery and
# swamps what is being measured. Set ADB or put adb on PATH; set ANDROID_SERIAL
# when more than one device is attached.
set -euo pipefail

label=${1:?usage: soak.sh <label> [minutes] [game package]}
minutes=${2:-30}
game=${3:-com.YostarJP.BlueArchive}
babel=com.yizhiteamo.babel
interval=30
adb=${ADB:-adb}

out=build/game-soak/$label.csv
mkdir -p "$(dirname "$out")"
echo "seconds,battery_c,thermal_status,cpu_throttle,babel_cpu,game_cpu,babel_pss_mb" > "$out"

sh() { "$adb" shell "$@" | tr -d '\r'; }

# utime + stime of every process of a package's user, in clock ticks, or
# empty when none runs. By user, not by name: a game's process may be named
# anything (Blue Archive's is a random string).
user_of() {
  sh dumpsys package "$1" | awk -F= '/appId=/ { printf "u0_a%d", $2 - 10000; exit }'
}
ticks() {
  local pids
  pids=$(sh ps -A -o PID,USER | awk -v u="$1" '$2 == u { print $1 }')
  [ -z "$pids" ] && return 0
  for pid in $pids; do sh cat "/proc/$pid/stat" 2>/dev/null || true; done | awk '{ s += $14 + $15 } END { print s }'
}

hz=$(sh getconf CLK_TCK)
start=$(date +%s)
babel_user=$(user_of $babel); game_user=$(user_of "$game")
babel_was=$(ticks "$babel_user"); game_was=$(ticks "$game_user"); was=$start

while :; do
  sleep "$interval"
  now=$(date +%s)
  battery=$(sh dumpsys battery | awk '/temperature:/ { printf "%.1f", $2 / 10 }')
  thermal=$(sh dumpsys thermalservice | awk -F': ' '/^Thermal Status:/ { print $2 }')
  throttle=$(sh dumpsys thermalservice | grep -o 'mValue=[0-9]*, mType=2' | awk -F'[=,]' '{ s += $2 } END { print s + 0 }')
  pss=$(sh dumpsys meminfo $babel 2>/dev/null | awk '/TOTAL PSS:/ { printf "%.0f", $3 / 1024 }')
  babel_is=$(ticks "$babel_user"); game_is=$(ticks "$game_user")
  cpu() { # ticks then, ticks now → % of one core
    [ -n "$1" ] && [ -n "$2" ] && awk -v a="$1" -v b="$2" -v hz="$hz" -v s=$((now - was)) 'BEGIN { printf "%.0f", (b - a) * 100 / hz / s }'
  }
  echo "$((now - start)),$battery,$thermal,$throttle,$(cpu "$babel_was" "$babel_is"),$(cpu "$game_was" "$game_is"),$pss" | tee -a "$out"
  babel_was=$babel_is; game_was=$game_is; was=$now
  [ $((now - start)) -ge $((minutes * 60)) ] && break
done
echo "written $out"
