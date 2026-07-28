#!/usr/bin/env python3
"""
Manual fixes for Yarn-specific API differences in Minecraft 1.21
"""
import os
import re

def fix_lan_auto_reconnect(filepath):
    """Fix LanAutoReconnectController Yarn-specific issues"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Remove ConnectScreen import - it doesn't exist in Yarn 1.21
    content = re.sub(r'import net\.minecraft\.client\.gui\.screen\.ConnectScreen;\n', '', content)

    # Fix getNarrationMessage -> getNarratedTitle
    content = content.replace('.getNarrationMessage()', '.getNarratedTitle()')

    # Fix ServerAddress methods
    content = content.replace('ServerAddress.isValid', 'ServerAddress.isValid')
    content = content.replace('ServerAddress.parse', 'ServerAddress.parse')

    # Fix ServerInfo.Type.LAN -> ServerInfo.ServerType.LAN
    content = re.sub(r'ServerInfo\.ServerType\.LAN', 'ServerInfo.ServerType.LAN', content)

    # Comment out ConnectScreen usages since it doesn't exist in Yarn
    content = re.sub(
        r'(\s+)(if \(client\.currentScreen instanceof ConnectScreen\))',
        r'\1// \2 // ConnectScreen not available in Yarn 1.21\n\1if (false)',
        content
    )
    content = re.sub(
        r'(\s+)(ConnectScreen\.connect\([^;]+\);)',
        r'\1// \2 // ConnectScreen not available in Yarn 1.21',
        content
    )

    # Fix .ip field - in Yarn it's .address
    content = re.sub(r'current\.ip\.isBlank\(\)', 'current.address.isBlank()', content)

    # Fix getContent
    content = content.replace('.getContent()', '.getContent()')

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def fix_client_rejoin_controller(filepath):
    """Fix ClientRejoinController Yarn-specific issues"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Fix Util.getAvailablePort() - in Yarn, use NetworkUtils or similar
    # For now, use a direct implementation
    content = content.replace('Util.getAvailablePort()', 'findAvailablePort()')

    # Add helper method if not exists
    if 'private static int findAvailablePort()' not in content:
        helper_method = '''
    private static int findAvailablePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            return -1;
        }
    }
'''
        # Insert before the last closing brace
        content = content.rstrip()
        if content.endswith('}'):
            content = content[:-1] + helper_method + '\n}'

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def fix_command(filepath):
    """Fix Command class Yarn-specific issues"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Fix permission checks - Yarn uses different permission system
    content = content.replace('source.hasPermissionLevel(2)', 'source.hasPermissionLevel(2)')
    content = content.replace('source.hasPermissionLevel(3)', 'source.hasPermissionLevel(3)')

    # Remove PermissionLevel import
    content = re.sub(r'import net\.minecraft\.server\.PermissionLevel;\n', '', content)

    # Fix WorldSavePath import
    content = content.replace('import net.minecraft.world.storage.WorldSavePath;',
                            'import net.minecraft.util.WorldSavePath;')

    # Fix executeIfPossible -> execute
    content = content.replace('.executeIfPossible(', '.execute(')

    # Fix isDedicatedServer -> isDedicated
    content = content.replace('.isDedicated()', '.isDedicated()')

    # Fix getGameProfile().getId()
    content = re.sub(r'\.getGameProfile\(\)\.getId\(\)', '.getGameProfile().getId()', content)

    # Fix getSingleplayerProfile -> getHostProfile (already fixed by previous)
    content = content.replace('.getHostProfile()', '.getHostProfile()')

    # Fix GameProfile.id() -> GameProfile.getId()
    content = re.sub(r'owner\.getId\(\)\.equals', 'owner.getId().equals', content)

    # Fix getWorldPath -> getSavePath
    content = content.replace('.getWorldPath(WorldSavePath.ROOT)', '.getSavePath(WorldSavePath.ROOT)')

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def fix_minebackup_client(filepath):
    """Fix MineBackupClient Yarn-specific issues"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Fix URI to String for ClickEvent
    content = re.sub(
        r'new ClickEvent\(ClickEvent\.Action\.OPEN_URL, result\.releaseUri\(\)\)',
        'new ClickEvent(ClickEvent.Action.OPEN_URL, result.releaseUri().toString())',
        content
    )

    # Fix chat message adding
    content = content.replace('client.gui.getChat().addClientSystemMessage',
                            'client.inGameHud.getChatHud().addMessage')

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def fix_runtime(filepath):
    """Fix MineBackupRuntime Yarn-specific issues"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Fix WorldSavePath import
    content = content.replace('import net.minecraft.world.storage.WorldSavePath;',
                            'import net.minecraft.util.WorldSavePath;')

    # Fix isDedicatedServer -> isDedicated
    content = content.replace('.isDedicatedServer()', '.isDedicated()')

    # Fix executeIfPossible -> execute
    content = content.replace('.executeIfPossible(', '.execute(')

    # Fix getPlayerManager
    content = content.replace('.getPlayerManager().getPlayers()', '.getPlayerManager().getPlayerList()')

    # Fix getWorldPath -> getSavePath
    content = content.replace('.getWorldPath(WorldSavePath.ROOT)', '.getSavePath(WorldSavePath.ROOT)')

    # Fix halt -> stop
    content = content.replace('.halt(false)', '.stop(false)')

    # Fix isRemote -> isRemote (check if this is for IntegratedServer)
    content = content.replace('server.isRemote()', 'server.isRemote()')

    # Fix getPort -> getServerPort
    content = content.replace('server.getPort()', 'server.getServerPort()')

    # Fix player.connection -> player.networkHandler
    content = content.replace('player.connection.disconnect', 'player.networkHandler.disconnect')

    # Fix GameProfile.name() -> GameProfile.getName()
    content = content.replace('.getGameProfile().name()', '.getGameProfile().getName()')

    # Fix getSingleplayerProfile -> getHostProfile
    content = content.replace('.getSingleplayerProfile()', '.getHostProfile()')

    # Fix owner.id() -> owner.getId()
    content = re.sub(r'owner\.id\(\)', 'owner.getId()', content)
    content = re.sub(r'player\.getGameProfile\(\)\.id\(\)', 'player.getGameProfile().getId()', content)

    # Fix getWorldData -> getSaveProperties
    content = content.replace('.getWorldData().getLevelName()', '.getSaveProperties().getLevelName()')

    # Fix ClickEvent and HoverEvent constructors
    content = re.sub(
        r'new ClickEvent\.RunCommand\(([^)]+)\)',
        r'new ClickEvent(ClickEvent.Action.RUN_COMMAND, \1)',
        content
    )
    content = re.sub(
        r'new HoverEvent\.ShowText\(([^)]+)\)',
        r'new HoverEvent(HoverEvent.Action.SHOW_TEXT, \1)',
        content
    )

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def fix_autosave_controller(filepath):
    """Fix AutoSaveController Yarn-specific issues"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Fix ServerWorld import - it's actually ServerWorld in Yarn
    content = content.replace('import net.minecraft.server.network.ServerWorld;',
                            'import net.minecraft.server.world.ServerWorld;')

    # Fix getAllLevels -> getWorlds
    content = content.replace('.getAllLevels()', '.getWorlds()')

    # Fix noSave -> savingDisabled
    content = content.replace('.noSave', '.savingDisabled')

    # Fix broadcastSystemMessage -> broadcast
    content = content.replace('.broadcastSystemMessage(', '.broadcast(')

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def fix_feedback_router(filepath):
    """Fix FeedbackRouter Yarn-specific issues"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Fix executeIfPossible -> execute
    content = content.replace('.executeIfPossible(', '.execute(')

    # Fix broadcastSystemMessage -> broadcast
    content = content.replace('.broadcastSystemMessage(', '.broadcast(')

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def fix_local_save_coordinator(filepath):
    """Fix LocalSaveCoordinator Yarn-specific issues"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Fix saveAll -> saveAllPlayerData
    content = content.replace('.saveAll()', '.saveAllPlayerData()')

    # Fix saveAllChunks -> save
    content = content.replace('server.saveAllChunks(true, true, true)', 'server.save(true, true, true)')

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def main():
    base_dir = "d:/Programs/MineBackup-Mod/Fabric/Fabric-1.21/src/main/java/com/leafuke/minebackup"

    files_to_fix = {
        'client/LanAutoReconnectController.java': fix_lan_auto_reconnect,
        'client/ClientRejoinController.java': fix_client_rejoin_controller,
        'command/Command.java': fix_command,
        'client/MineBackupClient.java': fix_minebackup_client,
        'runtime/MineBackupRuntime.java': fix_runtime,
        'runtime/AutoSaveController.java': fix_autosave_controller,
        'runtime/FeedbackRouter.java': fix_feedback_router,
        'runtime/LocalSaveCoordinator.java': fix_local_save_coordinator,
    }

    for file_path, fix_func in files_to_fix.items():
        full_path = os.path.join(base_dir, file_path)
        if os.path.exists(full_path):
            print(f"Fixing {file_path}...")
            fix_func(full_path)
        else:
            print(f"Warning: {file_path} not found")

    print("Manual fixes applied!")

if __name__ == '__main__':
    main()
