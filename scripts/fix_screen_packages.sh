#!/bin/bash
# Fix incorrect screen package replacements

TARGET_DIR="$1"

if [ -z "$TARGET_DIR" ]; then
    echo "Usage: $0 <target_directory>"
    exit 1
fi

echo "Fixing screen package names in: $TARGET_DIR"

# Fix the incorrect .currentScreen replacements
find "$TARGET_DIR" -name "*.java" -type f -exec sed -i '
s/net\.minecraft\.client\.gui\.currentScreen/net.minecraft.client.gui.screen/g
s/net\.minecraftutil\.WorldSavePath/net.minecraft.util.WorldSavePath/g
s/\.currentScreen\b/.currentScreen/g
' {} \;

echo "Package names fixed!"
