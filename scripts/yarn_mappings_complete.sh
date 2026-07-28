#!/bin/bash
# Complete Yarn mappings conversion for Minecraft 1.21

TARGET_DIR="$1"

if [ -z "$TARGET_DIR" ]; then
    echo "Usage: $0 <target_directory>"
    exit 1
fi

echo "Applying complete Yarn mappings conversion in: $TARGET_DIR"

# Find all Java files and apply comprehensive replacements
find "$TARGET_DIR" -name "*.java" -type f -exec sed -i '
# Field and property mappings
s/\.level\b/.world/g
s/\.screen\b/.currentScreen/g
s/\.gui\.getChat()/.inGameHud.getChatHud()/g
s/\.getCurrentServer()/.getCurrentServerEntry()/g

# Class name mappings for screens
s/\bConnectScreen\b/ConnectScreen/g
s/\bDisconnectedScreen\b/DisconnectedScreen/g

# ServerInfo/ServerData mappings
s/\bServerInfo\b/ServerInfo/g
s/\.isLan()/.isLocal()/g
s/\.ip\b/.address/g

# Method name mappings
s/\.getNarrationMessage()/.getNarrationMessage()/g
s/\.getAvailablePort()/.findClosestFreePort(25564)/g
s/\.createIntegratedServerLoader()/.createIntegratedServerLoader()/g

# Server level mappings
s/net\.minecraft\.server\.network\.ServerLevel/net.minecraft.server.world.ServerWorld/g
s/\bServerLevel\b/ServerWorld/g

# Permission mappings
s/net\.minecraft\.server\.PermissionLevel/net.minecraft.server.command.CommandManager/g

# WorldSavePath mapping
s/\.util\.WorldSavePath/util.WorldSavePath/g

# URI to String for ClickEvent
s/URI\.create\(([^)]+)\)/\1/g
' {} \;

echo "Complete conversion done!"
