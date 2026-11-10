#!/data/data/com.termux/files/usr/bin/sh
# Stops text-to-calendar and starts it again, for example after git pull or a new API key
sh "$HOME/text-to-calendar/boot/stop.sh"
sleep 2
sh "$HOME/text-to-calendar/boot/start.sh"
