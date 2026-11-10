#!/data/data/com.termux/files/usr/bin/sh
# Runs text-to-calendar and starts it again 30 seconds after it stops or crashes
REPO="$HOME/text-to-calendar"

# Load the API key and settings when the file exists
if [ -f "$HOME/.t2c_env" ]; then
    . "$HOME/.t2c_env"
fi
cd "$REPO" || exit 1

# Run the app, and restart it whenever it ends
while true; do
    python -m t2c.main
    code=$?
    echo "$(date '+%Y-%m-%d %H:%M:%S') text-to-calendar stopped (exit code $code), restarting in 30 seconds"
    sleep 30
done
