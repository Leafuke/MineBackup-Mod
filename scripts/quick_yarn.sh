#!/bin/bash
# Quick Yarn mappings conversion

find "$1" -name "*.java" -exec sed -i \
  -e 's/net\.minecraft\.network\.chat/net.minecraft.text/g' \
  -e 's/net\.minecraft\.commands/net.minecraft.server.command/g' \
  -e 's/net\.minecraft\.server\.level/net.minecraft.server.world/g' \
  -e 's/net\.minecraft\.client\.gui\.screens/net.minecraft.client.gui.screen/g' \
  -e 's/net\.minecraft\.client\.server/net.minecraft.server.integrated/g' \
  -e 's/net\.minecraft\.client\.multiplayer/net.minecraft.client.network/g' \
  -e 's/\bComponent\b/Text/g' \
  -e 's/\bMutableComponent\b/MutableText/g' \
  -e 's/\bMinecraft\b/MinecraftClient/g' \
  -e 's/\bGameType\b/GameMode/g' \
  -e 's/\bHttpUtil\b/Util/g' \
  -e 's/\bGenericMessageScreen\b/MessageScreen/g' \
  -e 's/\bCommandSourceStack\b/ServerCommandSource/g' \
  -e 's/\bCommands\b/CommandManager/g' \
  -e 's/\bServerPlayer\b/ServerPlayerEntity/g' \
  -e 's/\bLevelResource\b/WorldSavePath/g' \
  -e 's/\bServerLevel\b/ServerWorld/g' \
  -e 's/\bServerData\b/ServerInfo/g' \
  -e 's/\.sendSystemMessage/.sendMessage/g' \
  -e 's/\.getSingleplayerServer/.getServer/g' \
  -e 's/\.level\b/.world/g' \
  -e 's/\.withStyle/.styled/g' \
  -e 's/\.withUnderlined/.withUnderline/g' \
  -e 's/\.sendSuccess/.sendFeedback/g' \
  -e 's/\.sendFailure/.sendError/g' \
  {} \;

echo "Quick conversion done"
