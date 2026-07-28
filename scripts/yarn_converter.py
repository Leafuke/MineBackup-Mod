#!/usr/bin/env python3
"""
Precise Mojang to Yarn mappings converter for Minecraft 1.21
"""
import os
import re
import sys

# Mapping tables
PACKAGE_MAPPINGS = {
    'net.minecraft.network.chat': 'net.minecraft.text',
    'net.minecraft.commands': 'net.minecraft.server.command',
    'net.minecraft.server.level': 'net.minecraft.server.network',
    'net.minecraft.server.permissions': 'net.minecraft.server',
    'net.minecraft.client.gui.screens.worldselection': 'net.minecraft.client.gui.screen.world',
    'net.minecraft.client.gui.screens': 'net.minecraft.client.gui.screen',
    'net.minecraft.client.server': 'net.minecraft.server.integrated',
    'net.minecraft.client.multiplayer.resolver': 'net.minecraft.client.network',
    'net.minecraft.client.multiplayer': 'net.minecraft.client.network',
    'net.minecraft.world.level.storage': 'net.minecraft.world.level.storage',
    'net.minecraft.world.level': 'net.minecraft.world',
    'net.minecraft.text.contents': 'net.minecraft.text',
}

CLASS_MAPPINGS = {
    'Component': 'Text',
    'MutableComponent': 'MutableText',
    'Minecraft': 'MinecraftClient',
    'GameType': 'GameMode',
    'HttpUtil': 'Util',
    'GenericMessageScreen': 'MessageScreen',
    'CommandSourceStack': 'ServerCommandSource',
    'Commands': 'CommandManager',
    'ServerPlayer': 'ServerPlayerEntity',
    'Permissions': 'PermissionLevel',
    'LevelResource': 'WorldSavePath',
    'ServerLevel': 'ServerWorld',
    'ServerData': 'ServerInfo',
    'TranslatableContents': 'TranslatableTextContent',
    'ConnectScreen': 'ConnectScreen',
    'DisconnectedScreen': 'DisconnectedScreen',
}

METHOD_MAPPINGS = {
    '.sendSystemMessage': '.sendMessage',
    '.getSingleplayerServer': '.getServer',
    '.createWorldOpenFlows': '.createIntegratedServerLoader',
    '.openWorld': '.start',
    '.publishServer': '.openToLan',
    '.getDefaultGameType': '.getDefaultGameMode',
    '.isAllowCommandsForAllPlayers': '.areCheatsAllowed',
    '.getPlayerList': '.getPlayerManager',
    '.isPublished': '.isRemote',
    '.withStyle': '.styled',
    '.withUnderlined': '.withUnderline',
    '.sendSuccess': '.sendFeedback',
    '.sendFailure': '.sendError',
    '.getCurrentServer': '.getCurrentServerEntry',
    '.isLan': '.isLocal',
}

FIELD_MAPPINGS = {
    '.level': '.world',
    '.screen': '.currentScreen',
    '.ip': '.address',
}

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    original_content = content

    # Apply package mappings
    for old_pkg, new_pkg in PACKAGE_MAPPINGS.items():
        content = content.replace(old_pkg, new_pkg)

    # Apply class mappings (word boundaries)
    for old_class, new_class in CLASS_MAPPINGS.items():
        content = re.sub(r'\b' + re.escape(old_class) + r'\b', new_class, content)

    # Apply method mappings
    for old_method, new_method in METHOD_MAPPINGS.items():
        content = content.replace(old_method, new_method)

    # Apply field mappings (careful with .screen -> .currentScreen)
    # Only replace when followed by specific patterns
    content = re.sub(r'client\.screen\b', 'client.currentScreen', content)
    content = re.sub(r'\.level\b(?!\w)', '.world', content)
    content = re.sub(r'\.ip\b(?=\s|;|,|\))', '.address', content)

    # Fix ClickEvent and HoverEvent
    content = re.sub(r'new\s+net\.minecraft\.text\.ClickEvent\.OpenUrl\(([^)]+)\)',
                     r'new ClickEvent(ClickEvent.Action.OPEN_URL, \1)', content)
    content = re.sub(r'new\s+net\.minecraft\.text\.HoverEvent\.ShowText\(([^)]+)\)',
                     r'new HoverEvent(HoverEvent.Action.SHOW_TEXT, \1)', content)

    # Add imports if ClickEvent/HoverEvent are used
    if 'ClickEvent' in content and 'import net.minecraft.text.ClickEvent' not in content:
        content = re.sub(r'(import net\.minecraft\.text\.Text;)',
                        r'\1\nimport net.minecraft.text.ClickEvent;', content, count=1)
    if 'HoverEvent' in content and 'import net.minecraft.text.HoverEvent' not in content:
        content = re.sub(r'(import net\.minecraft\.text\.Text;)',
                        r'\1\nimport net.minecraft.text.HoverEvent;', content, count=1)

    # Write back only if changed
    if content != original_content:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        return True
    return False

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 yarn_converter.py <directory>")
        sys.exit(1)

    target_dir = sys.argv[1]
    changed_count = 0

    for root, dirs, files in os.walk(target_dir):
        for file in files:
            if file.endswith('.java'):
                filepath = os.path.join(root, file)
                if process_file(filepath):
                    changed_count += 1

    print(f"Processed and changed {changed_count} Java files")

if __name__ == '__main__':
    main()
