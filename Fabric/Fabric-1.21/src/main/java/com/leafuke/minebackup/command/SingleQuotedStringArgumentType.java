package com.leafuke.minebackup.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.Collection;
import java.util.List;

public class SingleQuotedStringArgumentType implements ArgumentType<String> {
    private static final Collection<String> EXAMPLES = List.of("'backup.zip'", "'Backup 01'");

    public static SingleQuotedStringArgumentType singleQuotedString() {
        return new SingleQuotedStringArgumentType();
    }

    public static String getSingleQuotedString(CommandContext<?> context, String name) {
        return context.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        if (!reader.canRead() || reader.peek() != '\'') {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedStartOfQuote().createWithContext(reader);
        }

        reader.skip();
        int start = reader.getCursor();

        while (reader.canRead() && reader.peek() != '\'') {
            reader.skip();
        }

        if (!reader.canRead()) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedEndOfQuote().createWithContext(reader);
        }

        String value = reader.getString().substring(start, reader.getCursor());
        reader.skip();
        return value;
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
