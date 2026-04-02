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
            entry("quickbackup", "[comment]", "minebackup.help.summary.quickbackup", "/mb quickbackup before_boss", "quicksave"),
            entry("quickrestore", "[backup_file]", "minebackup.help.summary.quickrestore", "/mb quickrestore '[Full][2026-03-24]world.7z'"),
            entry("save", "", "minebackup.help.summary.save", "/mb save"),
            entry("auto", "<config_id> <world_index> <minutes>", "minebackup.help.summary.auto", "/mb auto d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0 30"),
            entry("stop", "<config_id> <world_index>", "minebackup.help.summary.stop", "/mb stop d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0"),
            entry("list_configs", "", "minebackup.help.summary.list_configs", "/mb list_configs"),
            entry("list_worlds", "<config_id>", "minebackup.help.summary.list_worlds", "/mb list_worlds d34ab6e8-68fd-42e8-8dd9-a0648003a5a2"),
            entry("list_backups", "<config_id> <world_index>", "minebackup.help.summary.list_backups", "/mb list_backups d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0"),
            entry("backup", "<config_id> <world_index> [comment]", "minebackup.help.summary.backup", "/mb backup d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0 before_boss"),
            entry("restore", "<config_id> <world_index> <backup_file>", "minebackup.help.summary.restore", "/mb restore d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0 '[Full][2026-03-24]world.7z'"),
            entry("freeze", "", "minebackup.help.summary.freeze", "/mb freeze"),
            entry("unfreeze", "", "minebackup.help.summary.unfreeze", "/mb unfreeze"),
            entry("snap", "<config_id> <world_index> <backup_file>", "minebackup.help.summary.snap", "/mb snap d34ab6e8-68fd-42e8-8dd9-a0648003a5a2 0 '[Full][2026-03-24]world.7z'")
    );

    private static final Map<String, HelpEntry> LOOKUP = buildLookup();

    private CommandHelpRegistry() {
    }

    public static CompletableFuture<Suggestions> suggestCommands(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (HelpEntry entry : ENTRIES) {
            if (matches(entry.name(), remaining)) {
                builder.suggest(entry.name(), new LiteralMessage(Component.translatable(entry.summaryKey()).getString()));
            }
            for (String alias : entry.aliases()) {
                if (matches(alias, remaining)) {
                    builder.suggest(alias, new LiteralMessage(Component.translatable("minebackup.help.suggest.alias", entry.name()).getString()));
                }
            }
        }
        return CompletableFuture.completedFuture(builder.build());
    }

    public static Component buildRootHelp() {
        MutableComponent text = Component.translatable("minebackup.help.root.title");
        for (HelpEntry entry : ENTRIES) {
            text.append(Component.translatable("minebackup.help.root.entry",
                    "/mb " + entry.name() + formatUsage(entry.usage()),
                    Component.translatable(entry.summaryKey())));
        }
        text.append(Component.translatable("minebackup.help.root.footer"));
        return text;
    }

    public static Component buildCommandHelp(String requestedName) {
        HelpEntry entry = find(requestedName);
        if (entry == null) {
            return Component.translatable("minebackup.help.command.unknown", requestedName);
        }

        MutableComponent text = Component.translatable("minebackup.help.command.title", entry.name());
        text.append(Component.translatable("minebackup.help.command.summary", Component.translatable(entry.summaryKey())));
        text.append(Component.translatable("minebackup.help.command.usage", "/mb " + entry.name() + formatUsage(entry.usage())));
        if (!entry.aliases().isEmpty()) {
            text.append(Component.translatable("minebackup.help.command.aliases", String.join(", ", entry.aliases())));
        }
        text.append(Component.translatable("minebackup.help.command.example", entry.example()));
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

    private static HelpEntry entry(String name, String usage, String summaryKey, String example, String... aliases) {
        return new HelpEntry(name, usage, summaryKey, example, List.of(aliases));
    }

    private static String formatUsage(String usage) {
        return usage == null || usage.isBlank() ? "" : " " + usage;
    }

    private static boolean matches(String candidate, String remaining) {
        return remaining.isEmpty() || candidate.toLowerCase(Locale.ROOT).startsWith(remaining);
    }

    public record HelpEntry(String name, String usage, String summaryKey, String example, List<String> aliases) {
    }
}