#!/bin/bash
set -e

JAR="$(pwd)/app/build/libs/assistant.jar"
PLIST="$HOME/Library/LaunchAgents/com.assistant.plist"
mkdir -p "$HOME/.assistant"

sed "s|ASSISTANT_JAR_PATH|$JAR|g; s|ASSISTANT_WORKING_DIR|$(pwd)|g; s|ASSISTANT_HOME|$HOME|g" \
    scripts/com.assistant.plist > "$PLIST"

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"
echo "Assistant installed and running. Tail logs: tail -f ~/.assistant/assistant.log"
