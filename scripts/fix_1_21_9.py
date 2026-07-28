#!/usr/bin/env python3
"""
Fix API changes for Minecraft 1.21.9 (Mojang mappings)
"""
import os
import re

def fix_client_rejoin_controller(filepath):
    """Fix ClientRejoinController for 1.21.9 API changes"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # In 1.21.9, sendSystemMessage doesn't take a boolean parameter
    # Just use sendSystemMessage as-is (no changes needed)
    # Actually, the method signature changed - use displayClientMessage instead
    content = content.replace('player.sendSystemMessage(', 'player.displayClientMessage(')

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def fix_minebackup_client(filepath):
    """Fix MineBackupClient for 1.21.9 API changes"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Fix addClientSystemMessage -> addMessage
    content = content.replace('.addClientSystemMessage(', '.addMessage(')

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def fix_command(filepath):
    """Fix Command for 1.21.9 permission API changes"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Remove Permissions import
    content = re.sub(r'import net\.minecraft\.server\.permissions\.Permissions;\n', '', content)

    # Fix permission checks - use hasPermission directly
    content = content.replace('source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)',
                            'source.hasPermission(2)')
    content = content.replace('source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)',
                            'source.hasPermission(3)')

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def main():
    base_dir = "d:/Programs/MineBackup-Mod/Fabric/Fabric-1.21.9/src/main/java/com/leafuke/minebackup"

    files_to_fix = {
        'client/ClientRejoinController.java': fix_client_rejoin_controller,
        'client/MineBackupClient.java': fix_minebackup_client,
        'command/Command.java': fix_command,
    }

    for file_path, fix_func in files_to_fix.items():
        full_path = os.path.join(base_dir, file_path)
        if os.path.exists(full_path):
            print(f"Fixing {file_path}...")
            fix_func(full_path)
        else:
            print(f"Warning: {file_path} not found")

    print("1.21.9 fixes applied!")

if __name__ == '__main__':
    main()
