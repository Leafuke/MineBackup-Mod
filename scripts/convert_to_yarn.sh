#!/bin/bash
# Mojang to Yarn mappings conversion for Minecraft 1.21
# This script converts common Mojang mappings to Yarn mappings

TARGET_DIR="$1"

if [ -z "$TARGET_DIR" ]; then
    echo "Usage: $0 <target_directory>"
    exit 1
fi

echo "Converting Mojang mappings to Yarn mappings in: $TARGET_DIR"

# Find all Java files and apply sed replacements
find "$TARGET_DIR" -name "*.java" -type f -exec sed -i '
# Package mappings
s/net\.minecraft\.network\.chat/net.minecraft.text/g
s/net\.minecraft\.client\.gui\.screens/net.minecraft.client.gui.screen/g
s/net\.minecraft\.client\.multiplayer/net.minecraft.client.network/g
s/net\.minecraft\.client\.server/net.minecraft.server.integrated/g
s/net\.minecraft\.world\.level\.storage/net.minecraft.world.level.storage/g

# Class name mappings - Common classes
s/\bComponent\b/Text/g
s/\bMutableComponent\b/MutableText/g
s/\bMinecraft\b/MinecraftClient/g
s/\bIntegratedServer\b/IntegratedServer/g
s/\bGenericMessageScreen\b/MessageScreen/g
s/\bTitleScreen\b/TitleScreen/g
s/\bSelectWorldScreen\b/SelectWorldScreen/g
s/\bGameType\b/GameMode/g
s/\bHttpUtil\b/Util/g

# Method mappings - Common methods
s/\.sendSystemMessage\b/.sendMessage/g
s/\.getSingleplayerServer\b/.getServer/g
s/\.createWorldOpenFlows\b/.createIntegratedServerLoader/g
s/\.openWorld\b/.start/g
s/\.publishServer\b/.openToLan/g
s/\.getDefaultGameType\b/.getDefaultGameMode/g
s/\.isAllowCommandsForAllPlayers\b/.areCheatsAllowed/g
s/\.getPlayerList\b/.getPlayerManager/g
s/\.isPublished\b/.isRemote/g

# ClickEvent and HoverEvent
s/net\.minecraft\.network\.chat\.ClickEvent/net.minecraft.text.ClickEvent/g
s/net\.minecraft\.network\.chat\.HoverEvent/net.minecraft.text.HoverEvent/g
s/ClickEvent\.OpenUrl/ClickEvent.Action.OPEN_URL/g
s/HoverEvent\.ShowText/HoverEvent.Action.SHOW_TEXT/g

# Component/Text static methods
s/Component\.translatable/Text.translatable/g
s/Component\.literal/Text.literal/g
' {} \;

echo "Conversion complete!"
