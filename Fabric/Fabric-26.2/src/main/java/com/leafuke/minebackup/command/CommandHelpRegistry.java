package com.leafuke.minebackup.command;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public final class CommandHelpRegistry {
    private static final List<HelpEntry> ENTRIES = List.of(
            entry(CommandCapability.BACKUP, "save", "", "minebackup.help.summary.save", "/mb save"),
            entry(CommandCapability.BACKUP, "backup", "[comment]", "minebackup.help.summary.backup_current", "/mb backup before_boss"),
            entry(CommandCapability.RESTORE, "restore", "[file]", "minebackup.help.summary.restore_current",
                    "/mb restore \"[Full] world backup.7z\""),
            entry(CommandCapability.RESTORE, "confirm", "", "minebackup.help.summary.restore_confirm", "/mb confirm"),
            entry(CommandCapability.RESTORE, "stop", "", "minebackup.help.summary.restore_stop", "/mb stop"),
            entry(CommandCapability.TARGET_BACKUP, "target backup", "<config_id> <folder> [comment]",
                    "minebackup.help.summary.backup_target",
                    "/mb target backup d34ab6e8 0 before_boss"),
            entry(CommandCapability.TARGET_RESTORE, "target restore", "<config_id> <folder> <file>",
                    "minebackup.help.summary.restore_target",
                    "/mb target restore d34ab6e8 0 \"[Full] world backup.7z\""),
            entry(CommandCapability.BROWSE, "list configs", "", "minebackup.help.summary.list_configs", "/mb list configs"),
            entry(CommandCapability.BROWSE, "list folders", "<config_id>", "minebackup.help.summary.list_folders",
                    "/mb list folders d34ab6e8"),
            entry(CommandCapability.BROWSE, "list backups", "[current [page] | <config_id> <folder>]",
                    "minebackup.help.summary.list_backups",
                    "/mb list backups"),
            entry(CommandCapability.AUTOMATION, "auto start", "<minutes> [backup|remind]",
                    "minebackup.help.summary.auto_start",
                    "/mb auto start 30 remind"),
            entry(CommandCapability.AUTOMATION, "auto stop", "", "minebackup.help.summary.auto_stop",
                    "/mb auto stop"),
            entry(CommandCapability.AUTOMATION, "auto status", "", "minebackup.help.summary.auto_status",
                    "/mb auto status")
    );
    private static final Map<String, HelpEntry> LOOKUP = buildLookup();

    private CommandHelpRegistry() {
    }

    public static CompletableFuture<Suggestions> suggestCommands(
            CommandSourceStack source,
            SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (HelpEntry entry : ENTRIES) {
            if (CommandPermissions.canUse(source, entry.capability())
                    && entry.path().startsWith(remaining)) {
                builder.suggest(entry.path(),
                        new LiteralMessage(Component.translatable(entry.summaryKey()).getString()));
            }
        }
        return CompletableFuture.completedFuture(builder.build());
    }

    public static Component buildRootHelp(CommandSourceStack source) {
        MutableComponent text = Component.translatable("minebackup.help.root.title");
        for (HelpEntry entry : ENTRIES) {
            if (!CommandPermissions.canUse(source, entry.capability())) {
                continue;
            }
            text.append(Component.translatable(
                    "minebackup.help.root.entry",
                    "/mb " + entry.path() + formatUsage(entry.usage()),
                    Component.translatable(entry.summaryKey())));
        }
        text.append(Component.translatable("minebackup.help.root.footer"));
        return text;
    }

    public static Component buildCommandHelp(
            CommandSourceStack source,
            String requestedPath) {
        HelpEntry entry = find(requestedPath);
        if (entry == null || !CommandPermissions.canUse(source, entry.capability())) {
            return Component.translatable("minebackup.help.command.unknown", requestedPath);
        }

        MutableComponent text = Component.translatable("minebackup.help.command.title", entry.path());
        text.append(Component.translatable(
                "minebackup.help.command.summary",
                Component.translatable(entry.summaryKey())));
        text.append(Component.translatable(
                "minebackup.help.command.usage",
                "/mb " + entry.path() + formatUsage(entry.usage())));
        text.append(Component.translatable("minebackup.help.command.example", entry.example()));
        return text;
    }

    private static HelpEntry find(String requestedPath) {
        if (requestedPath == null) {
            return null;
        }
        return LOOKUP.get(requestedPath.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, HelpEntry> buildLookup() {
        Map<String, HelpEntry> lookup = new LinkedHashMap<>();
        for (HelpEntry entry : ENTRIES) {
            lookup.put(entry.path(), entry);
        }
        return Map.copyOf(lookup);
    }

    static List<String> visiblePaths(Predicate<CommandCapability> allowed) {
        return ENTRIES.stream()
                .filter(entry -> allowed.test(entry.capability()))
                .map(HelpEntry::path)
                .toList();
    }

    private static HelpEntry entry(
            CommandCapability capability,
            String path,
            String usage,
            String summaryKey,
            String example) {
        return new HelpEntry(capability, path, usage, summaryKey, example);
    }

    private static String formatUsage(String usage) {
        return usage == null || usage.isBlank() ? "" : " " + usage;
    }

    private record HelpEntry(
            CommandCapability capability,
            String path,
            String usage,
            String summaryKey,
            String example) {
    }
}
