package com.leafuke.minebackup;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class CommandHelpRegistry {
    private static final List<HelpEntry> ENTRIES = List.of(
            entry("quickbackup", "[comment]", "Backup the current world", "/mb quickbackup before_boss", "quicksave"),
            entry("quickrestore", "[backup_file]", "Restore the current world", "/mb quickrestore '[Full][2026-03-24]world.7z'"),
            entry("save", "", "Save world data locally", "/mb save"),
            entry("auto", "<config_id> <world_index> <minutes>", "Start auto backup", "/mb auto d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0 30"),
            entry("stop", "<config_id> <world_index>", "Stop auto backup", "/mb stop d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0"),
            entry("list_configs", "", "List backup configs", "/mb list_configs"),
            entry("list_worlds", "<config_id>", "List worlds in a config", "/mb list_worlds d34ab6e8-68fd-42e8-8dd9-a0648003a5a2"),
            entry("list_backups", "<config_id> <world_index>", "List backups for a world", "/mb list_backups d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0"),
            entry("backup", "<config_id> <world_index> [comment]", "Create a backup for a selected world", "/mb backup d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0 before_boss"),
            entry("restore", "<config_id> <world_index> <backup_file>", "Restore a selected world", "/mb restore d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0 '[Full][2026-03-24]world.7z'"),
            entry("freeze", "", "Save and freeze autosave", "/mb freeze"),
            entry("unfreeze", "", "Resume autosave", "/mb unfreeze"),
            entry("snap", "<config_id> <world_index> <backup_file>", "Add a backup to WE snapshot", "/mb snap d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0 '[Full][2026-03-24]world.7z'")
    );

    private static final Map<String, HelpEntry> LOOKUP = buildLookup();

    private CommandHelpRegistry() {
    }

    public static CompletableFuture<Suggestions> suggestCommands(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (HelpEntry entry : ENTRIES) {
            if (matches(entry.name(), remaining)) {
                builder.suggest(entry.name(), new LiteralMessage(entry.summary()));
            }
            for (String alias : entry.aliases()) {
                if (matches(alias, remaining)) {
                    builder.suggest(alias, new LiteralMessage("Alias of " + entry.name()));
                }
            }
        }
        return CompletableFuture.completedFuture(builder.build());
    }

    public static Component buildRootHelp() {
        MutableComponent text = Component.literal("MineBackup commands");
        for (HelpEntry entry : ENTRIES) {
            text.append(Component.literal("\n/mb " + entry.name() + formatUsage(entry.usage()) + " - " + entry.summary()));
        }
        text.append(Component.literal("\nUse /mb help <command> for details"));
        return text;
    }

    public static Component buildCommandHelp(String requestedName) {
        HelpEntry entry = find(requestedName);
        if (entry == null) {
            return Component.literal("Unknown command: " + requestedName);
        }

        MutableComponent text = Component.literal("Help: /mb " + entry.name());
        text.append(Component.literal("\n" + entry.summary()));
        text.append(Component.literal("\nUsage: /mb " + entry.name() + formatUsage(entry.usage())));
        if (!entry.aliases().isEmpty()) {
            text.append(Component.literal("\nAliases: " + String.join(", ", entry.aliases())));
        }
        text.append(Component.literal("\nExample: " + entry.example()));
        return text;
    }

    public static HelpEntry find(String requestedName) {
        if (requestedName == null) {
            return null;
        }
        return LOOKUP.get(requestedName.toLowerCase(Locale.ROOT));
    }

    private static Map<String, HelpEntry> buildLookup() {
        Map<String, HelpEntry> lookup = new LinkedHashMap<>();
        for (HelpEntry entry : ENTRIES) {
            lookup.put(entry.name(), entry);
            for (String alias : entry.aliases()) {
                lookup.put(alias.toLowerCase(Locale.ROOT), entry);
            }
        }
        return lookup;
    }

    private static HelpEntry entry(String name, String usage, String summary, String example, String... aliases) {
        return new HelpEntry(name, usage, summary, example, List.of(aliases));
    }

    private static String formatUsage(String usage) {
        return usage == null || usage.isBlank() ? "" : " " + usage;
    }

    private static boolean matches(String candidate, String remaining) {
        return remaining.isEmpty() || candidate.toLowerCase(Locale.ROOT).startsWith(remaining);
    }

    public record HelpEntry(String name, String usage, String summary, String example, List<String> aliases) {
    }
}
