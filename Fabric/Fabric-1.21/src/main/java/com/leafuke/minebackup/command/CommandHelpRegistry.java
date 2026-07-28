package com.leafuke.minebackup.command;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class CommandHelpRegistry {
    private static final List<HelpEntry> ENTRIES = List.of(
            entry("save", "", "minebackup.help.summary.save", "/mb save"),
            entry("backup", "[comment]", "minebackup.help.summary.backup_current", "/mb backup before_boss"),
            entry("restore", "[file]", "minebackup.help.summary.restore_current",
                    "/mb restore \"[Full] world backup.7z\""),
            entry("confirm", "", "minebackup.help.summary.restore_confirm", "/mb confirm"),
            entry("stop", "", "minebackup.help.summary.restore_stop", "/mb stop"),
            entry("target backup", "<config_id> <folder> [comment]",
                    "minebackup.help.summary.backup_target",
                    "/mb target backup d34ab6e8 0 before_boss"),
            entry("target restore", "<config_id> <folder> <file>",
                    "minebackup.help.summary.restore_target",
                    "/mb target restore d34ab6e8 0 \"[Full] world backup.7z\""),
            entry("list configs", "", "minebackup.help.summary.list_configs", "/mb list configs"),
            entry("list folders", "<config_id>", "minebackup.help.summary.list_folders",
                    "/mb list folders d34ab6e8"),
            entry("list backups", "<config_id> <folder>", "minebackup.help.summary.list_backups",
                    "/mb list backups d34ab6e8 0"),
            entry("auto start", "<minutes>", "minebackup.help.summary.auto_start",
                    "/mb auto start 30"),
            entry("auto stop", "", "minebackup.help.summary.auto_stop",
                    "/mb auto stop")
    );
    private static final Map<String, HelpEntry> LOOKUP = buildLookup();

    private CommandHelpRegistry() {
    }

    public static CompletableFuture<Suggestions> suggestCommands(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (HelpEntry entry : ENTRIES) {
            if (entry.path().startsWith(remaining)) {
                builder.suggest(entry.path(),
                        new LiteralMessage(Text.translatable(entry.summaryKey()).getString()));
            }
        }
        return CompletableFuture.completedFuture(builder.build());
    }

    public static Text buildRootHelp() {
        MutableText text = Text.translatable("minebackup.help.root.title");
        for (HelpEntry entry : ENTRIES) {
            text.append(Text.translatable(
                    "minebackup.help.root.entry",
                    "/mb " + entry.path() + formatUsage(entry.usage()),
                    Text.translatable(entry.summaryKey())));
        }
        text.append(Text.translatable("minebackup.help.root.footer"));
        return text;
    }

    public static Text buildCommandHelp(String requestedPath) {
        HelpEntry entry = find(requestedPath);
        if (entry == null) {
            return Text.translatable("minebackup.help.command.unknown", requestedPath);
        }

        MutableText text = Text.translatable("minebackup.help.command.title", entry.path());
        text.append(Text.translatable(
                "minebackup.help.command.summary",
                Text.translatable(entry.summaryKey())));
        text.append(Text.translatable(
                "minebackup.help.command.usage",
                "/mb " + entry.path() + formatUsage(entry.usage())));
        text.append(Text.translatable("minebackup.help.command.example", entry.example()));
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

    private static HelpEntry entry(String path, String usage, String summaryKey, String example) {
        return new HelpEntry(path, usage, summaryKey, example);
    }

    private static String formatUsage(String usage) {
        return usage == null || usage.isBlank() ? "" : " " + usage;
    }

    private record HelpEntry(String path, String usage, String summaryKey, String example) {
    }
}
