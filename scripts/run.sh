#!/bin/sh

# Default to root if no PUID/PGID parameters are provided
PUID=${PUID:-0}
PGID=${PGID:-0}

if [ "$PUID" -ne 0 ] && [ "$PGID" -ne 0 ]; then
    echo "Configuring permissions to run application as user ID $PUID..."
    
    # 1. Force the mounted runtime volumes to match your exact host owner
    chown -R "$PUID:$PGID" /data /media
    
    # 2. Seamlessly execute the Kotlin app as the mapped unprivileged user
    exec su-exec "$PUID:$PGID" bin/app "$@"
else
    # Fallback to standard execution if variables are missing
    exec bin/app "$@"
fi