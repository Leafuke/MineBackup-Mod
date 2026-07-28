#!/bin/bash
# Comprehensive Mojang to Yarn mappings conversion for Minecraft 1.21
# Based on Fabric mappings for MC 1.21

TARGET_DIR="$1"

if [ -z "$TARGET_DIR" ]; then
    echo "Usage: $0 <target_directory>"
    exit 1
fi

echo "Converting Mojang mappings to Yarn mappings (MC 1.21) in: $TARGET_DIR"

# Process all Java files
find "$TARGET_DIR" -name "*.java" -type f -print0 | while IFS= read -r -d '' file; do
    # Package name conversions
    sed -i 's/net\.minecraft\.network\.chat/net.minecraft.text/g' "$file"
    sed -i 's/net\.minecraft\.commands/net.minecraft.server.command/g' "$file"
    sed -i 's/net\.minecraft\.server\.level/net.minecraft.server.world/g' "$file"
    sed -i 's/net\.minecraft\.server\.permissions/net.minecraft.server/g' "$file"
    sed -i 's/net\.minecraft\.world\.level\.storage/net.minecraft.world.level.storage/g' "$file"
    sed -i 's/net\.minecraft\.client\.gui\.screens/net.minecraft.client.gui.screen/g' "$file"
    sed -i 's/net\.minecraft\.client\.server/net.minecraft.server.integrated/g' "$file"
    sed -i 's/net\.minecraft\.client\.multiplayer/net.minecraft.client.network/g' "$file"
    sed -i 's/net\.minecraft\.client\.gui\.screens\.worldselection/net.minecraft.client.gui.screen.world/g' "$file"
    sed -i 's/net\.minecraft\.client\.multiplayer\.resolver/net.minecraft.client.network/g' "$file"
    sed -i 's/net\.minecraft\.network\.chat\.contents/net.minecraft.text/g' "$file"

    # Class name conversions (using word boundaries)
    sed -i 's/\bComponent\b/Text/g' "$file"
    sed -i 's/\bMutableComponent\b/MutableText/g' "$file"
    sed -i 's/\bMinecraft\b/MinecraftClient/g' "$file"
    sed -i 's/\bGameType\b/GameMode/g' "$file"
    sed -i 's/\bHttpUtil\b/Util/g' "$file"
    sed -i 's/\bGenericMessageScreen\b/MessageScreen/g' "$file"
    sed -i 's/\bCommandSourceStack\b/ServerCommandSource/g' "$file"
    sed -i 's/\bCommands\b/CommandManager/g' "$file"
    sed -i 's/\bServerPlayer\b/ServerPlayerEntity/g' "$file"
    sed -i 's/\bPermissions\b/Permissions/g' "$file"
    sed -i 's/\bLevelResource\b/WorldSavePath/g' "$file"
    sed -i 's/\bServerLevel\b/ServerWorld/g' "$file"
    sed -i 's/\bServerData\b/ServerInfo/g' "$file"
    sed -i 's/\bServerAddress\b/ServerAddress/g' "$file"
    sed -i 's/\bDisconnectedScreen\b/DisconnectedScreen/g' "$file"
    sed -i 's/\bConnectScreen\b/ConnectScreen/g' "$file"
    sed -i 's/\bTranslatableContents\b/TranslatableTextContent/g' "$file"

    # Method name conversions
    sed -i 's/\.sendSystemMessage\b/.sendMessage/g' "$file"
    sed -i 's/\.getSingleplayerServer\b/.getServer/g' "$file"
    sed -i 's/\.createWorldOpenFlows\b/.createIntegratedServerLoader/g' "$file"
    sed -i 's/\.openWorld\b/.start/g' "$file"
    sed -i 's/\.publishServer\b/.openToLan/g' "$file"
    sed -i 's/\.getDefaultGameType\b/.getDefaultGameMode/g' "$file"
    sed -i 's/\.isAllowCommandsForAllPlayers\b/.areCheatsAllowed/g' "$file"
    sed -i 's/\.getPlayerList\b/.getPlayerManager/g' "$file"
    sed -i 's/\.isPublished\b/.isRemote/g' "$file"
    sed -i 's/\.withStyle\b/.styled/g' "$file"
    sed -i 's/\.withUnderlined\b/.withUnderline/g' "$file"

    # Field name conversions
    sed -i 's/\.level\b/.world/g' "$file"
    sed -i 's/\.screen\b/.currentScreen/g' "$file"
    sed -i 's/\.gui\.getChat()/.inGameHud.getChatHud()/g' "$file"
    sed -i 's/\.getCurrentServer()/.getCurrentServerEntry()/g' "$file"
    sed -i 's/\.isLan()/.isLocal()/g' "$file"
    sed -i 's/\.ip\b/.address/g' "$file"

    # ClickEvent and HoverEvent API changes
    sed -i 's/Text\.OpenUrl/Text.Action.OPEN_URL/g' "$file"
    sed -i 's/Text\.ShowText/Text.Action.SHOW_TEXT/g' "$file"

    # Command-related method changes
    sed -i 's/\.sendSuccess(/.sendFeedback(/g' "$file"
    sed -i 's/\.sendFailure(/.sendError(/g' "$file"

    # Static method conversions
    sed -i 's/Text\.translatable/Text.translatable/g' "$file"
    sed -i 's/Text\.literal/Text.literal/g' "$file"

    # Utility method conversions
    sed -i 's/Util\.getAvailablePort()/Util.getIoWorkerCount()/g' "$file"  # Placeholder - needs manual fix
    sed -i 's/ServerAddress\.parseString/ServerAddress.parse/g' "$file"
    sed -i 's/ServerAddress\.isValidAddress/ServerAddress.isValid/g' "$file"

    # Text content methods
    sed -i 's/\.getContents()/.getContent()/g' "$file"
done

echo "Conversion complete! Manual fixes may still be required for:"
echo "  - Util.getAvailablePort() -> needs platform-specific implementation"
echo "  - ClickEvent/HoverEvent constructor syntax"
echo "  - Command feedback methods"
echo "  - Screen field access patterns"
