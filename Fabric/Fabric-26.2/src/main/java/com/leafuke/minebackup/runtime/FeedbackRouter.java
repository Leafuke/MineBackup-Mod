package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.FeedbackPolicy;
import com.leafuke.minebackup.api.v2.MessageSlot;
import com.leafuke.minebackup.api.v2.MessageTemplate;
import com.leafuke.minebackup.api.v2.OperationPresentation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Owns delivery of MineBackup messages to the active server. Keeping delivery
 * behind this boundary lets API requests select a feedback policy without
 * leaking Minecraft types into the public API.
 */
final class FeedbackRouter {
    private final Supplier<MinecraftServer> server;

    FeedbackRouter(Supplier<MinecraftServer> server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    void broadcast(Component message) {
        Objects.requireNonNull(message, "message");
        MinecraftServer current = server.get();
        if (current != null) {
            current.executeIfPossible(() ->
                    current.getPlayerList().broadcastSystemMessage(message, false));
        }
    }

    void broadcastOnServer(MinecraftServer current, Component message) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(message, "message");
        current.getPlayerList().broadcastSystemMessage(message, false);
    }

    void optional(OperationPresentation presentation, MessageSlot slot, Component defaultMessage, Object... args) {
        if (presentation.feedbackPolicy() == FeedbackPolicy.CALLER_MANAGED) {
            return;
        }
        broadcast(resolve(presentation, slot, defaultMessage, args));
    }

    void mandatory(OperationPresentation presentation, MessageSlot slot, Component defaultMessage, Object... args) {
        broadcast(resolve(presentation, slot, defaultMessage, args));
    }

    Component resolve(
            OperationPresentation presentation,
            MessageSlot slot,
            Component defaultMessage,
            Object... args) {
        MessageTemplate template = presentation.template(slot).orElse(null);
        if (template == null) {
            return defaultMessage;
        }
        if (template.translationKey().isBlank()) {
            return template.literalFallback().isPresent()
                    ? Component.literal(template.literalFallback().get())
                    : defaultMessage;
        }
        return Component.translatableWithFallback(
                template.translationKey(),
                template.literalFallback().orElse(null),
                args);
    }
}
