#!/data/data/com.termux/files/usr/bin/sh
# Starts text-to-calendar in the background, keeps the phone awake for it, and logs to ~/t2c.log
REPO="$HOME/text-to-calendar"
LOG="$HOME/t2c.log"

# Stop here if it is already running, so it never runs twice
if pgrep -f "boot/run-loop.sh" > /dev/null; then
    echo "text-to-calendar is already running"
    exit 0
fi

# Start a fresh log once the old one passes 1 MB
if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 1000000 ]; then
    mv "$LOG" "$LOG.old"
fi
termux-wake-lock
nohup sh "$REPO/boot/run-loop.sh" >> "$LOG" 2>&1 &
echo "text-to-calendar started, log: $LOG"
