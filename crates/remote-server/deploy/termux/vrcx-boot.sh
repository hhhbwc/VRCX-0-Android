#!/data/data/com.termux/files/usr/bin/bash
# VRCX-0 remote server -- Termux:Boot autostart script (NO ROOT NEEDED)
#
# Install:
#   1. Put this file at ~/.termux/boot/vrcx-boot.sh inside Termux:
#        mkdir -p ~/.termux/boot
#        cp vrcx-boot.sh ~/.termux/boot/
#        chmod +x ~/.termux/boot/vrcx-boot.sh
#   2. Open the "Termux:Boot" app once (it only runs its boot scripts if it
#      has been launched at least once since install).
#   3. Disable battery optimisation for BOTH Termux and Termux:Boot, and
#      allow "auto-start" for them in your ROM's app manager (ColorOS/MIUI/
#      HyperOS all gate this; without it Android kills the server on lock).
#
# What it does: acquires a wake lock (so the CPU keeps serving while the
# screen is off), then starts the server if it is not already running.
# Logs go to ~/vrcx-data/server.log -- check there first when "it stopped
# working" overnight.

SERVER="$HOME/vrcx-server/vrcx-0-remote-server"
DATA_DIR="$HOME/vrcx-data"
LOG="$DATA_DIR/server.log"

mkdir -p "$DATA_DIR"

# Keep the CPU awake. Without this Android freezes the process within minutes
# of the screen turning off.
termux-wake-lock 2>/dev/null || echo "(termux-wake-lock unavailable; install Termux:API)"

if pgrep -f "$SERVER" > /dev/null 2>&1; then
    echo "$(date) already running" >> "$LOG"
    exit 0
fi

echo "$(date) starting server" >> "$LOG"
cd "$HOME/vrcx-server" || exit 1
nohup "$SERVER" --data-dir "$DATA_DIR" >> "$LOG" 2>&1 &
