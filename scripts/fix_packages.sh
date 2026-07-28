#!/bin/bash
# Fix incorrect sed replacements

TARGET_DIR="$1"

echo "Fixing incorrect replacements in: $TARGET_DIR"

find "$TARGET_DIR" -name "*.java" -exec sed -i \
  -e 's/net\.minecraft\.client\.gui\.currentScreen/net.minecraft.client.gui.screen/g' \
  -e 's/net\.minecraft\.client\.gui\.screen\.worldselection/net.minecraft.client.gui.screen.world/g' \
  -e 's/net\.minecraft\.world\.world/net.minecraft.world.level/g' \
  -e 's/net\.minecraft\.client\.network\.resolver/net.minecraft.client.network/g' \
  -e 's/net\.minecraft\.text\.contents/net.minecraft.text/g' \
  -e 's/\bServerPlayerEntity\b/ServerPlayerEntity/g' \
  {} \;

echo "Fixed package names"
