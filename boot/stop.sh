#!/data/data/com.termux/files/usr/bin/sh
# Stops text-to-calendar and lets the phone sleep normally again
pkill -f "boot/run-loop.sh"
pkill -f "t2c.main"
termux-wake-unlock
echo "text-to-calendar stopped"
