#!/bin/bash
# Additional Yarn mappings fixes for Minecraft 1.21

TARGET_DIR="$1"

if [ -z "$TARGET_DIR" ]; then
    echo "Usage: $0 <target_directory>"
    exit 1
fi

echo "Applying additional Yarn mappings fixes in: $TARGET_DIR"

# Find all Java files and apply sed replacements
find "$TARGET_DIR" -name "*.java" -type f -exec sed -i '
# Fix package names
s/net\.minecraft\.client\.gui\.screen\.worldselection/net.minecraft.client.gui.screen.world/g
s/net\.minecraft\.world\.level\.GameMode/net.minecraft.world.GameMode/g
s/net\.minecraft\.commands/net.minecraft.server.command/g
s/net\.minecraft\.server\.level/net.minecraft.server.network/g
s/net\.minecraft\.server\.permissions/net.minecraft.server/g
s/net\.minecraft\.world\.level\.storage\.LevelResource/net.minecraft.util.WorldSavePath/g
s/net\.minecraft\.client\.network\.resolver/net.minecraft.client.network/g
s/net\.minecraft\.text\.contents/net.minecraft.text/g

# Fix class names
s/\bSelectWorldScreen\b/SelectWorldScreen/g
s/\bConnectScreen\b/ConnectScreen/g
s/\bServerData\b/ServerInfo/g
s/\bServerAddress\b/ServerAddress/g
s/\bCommandSourceStack\b/ServerCommandSource/g
s/\bCommands\b/CommandManager/g
s/\bServerPlayer\b/ServerPlayerEntity/g
s/\bPermissions\b/PermissionLevel/g
s/\bLevelResource\b/WorldSavePath/g
s/\bTranslatableContents\b/TranslatableTextContent/g

# Fix ClickEvent and HoverEvent constructors
s/new net\.minecraft\.text\.ClickEvent\.Action\.OPEN_URL(/ClickEvent.Action.OPEN_URL, /g
s/new net\.minecraft\.text\.HoverEvent\.Action\.SHOW_TEXT(/HoverEvent.Action.SHOW_TEXT, /g
s/\.withClickEvent(new ClickEvent\.Action\.OPEN_URL,/.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL,/g
s/\.withHoverEvent(new HoverEvent\.Action\.SHOW_TEXT,/.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,/g

# Fix method names
s/\.getAvailablePort\b/.getAvailablePort/g
s/\.disconnect\b/.disconnect/g
s/\.execute\b/.execute/g

# Fix import statements for ClickEvent/HoverEvent
s/import net\.minecraft\.text\.ClickEvent\.Action\.OPEN_URL;/import net.minecraft.text.ClickEvent;/g
s/import net\.minecraft\.text\.HoverEvent\.Action\.SHOW_TEXT;/import net.minecraft.text.HoverEvent;/g
' {} \;

echo "Additional fixes complete!"
