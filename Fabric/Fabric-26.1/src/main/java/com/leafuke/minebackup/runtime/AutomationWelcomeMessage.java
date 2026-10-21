package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.CurrentWorldAutomationMode;
import com.leafuke.minebackup.api.v2.CurrentWorldAutomationState;
import com.leafuke.minebackup.config.Config.JoinStatusNotificationMode;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

final class AutomationWelcomeMessage {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private AutomationWelcomeMessage() {
    }

    static Optional<MutableComponent> build(
            CurrentWorldAutomationState state,
            JoinStatusNotificationMode notificationMode,
            boolean canManage) {
        if (!state.currentWorldAvailable()
                || notificationMode == JoinStatusNotificationMode.NEVER
                || (notificationMode == JoinStatusNotificationMode.ENABLED_ONLY
                        && state.mode() == CurrentWorldAutomationMode.OFF)) {
            return Optional.empty();
        }

        MutableComponent message;
        if (state.mode() == CurrentWorldAutomationMode.OFF) {
            message = Component.translatable("minebackup.message.auto.welcome.disabled");
            if (canManage) {
                message.append(Component.literal(" ")).append(button(
                        "enable", ChatFormatting.GREEN,
                        new ClickEvent.SuggestCommand("/mb auto start ")));
            }
        } else {
            boolean backup = state.mode() == CurrentWorldAutomationMode.BACKUP;
            String nextRun = state.nextRun().map(TIME_FORMAT::format)
                    .orElseGet(() -> Component.translatable(
                            "minebackup.message.auto.status.next_pending").getString());
            message = Component.translatable(
                    backup ? "minebackup.message.auto.welcome.enabled"
                            : "minebackup.message.auto.welcome.remind",
                    Component.translatable(backup ? "minebackup.message.auto.mode.backup"
                            : "minebackup.message.auto.mode.remind"),
                    state.interval().orElseThrow().toMinutes(),
                    nextRun);
            if (canManage) {
                message.append(Component.literal(" ")).append(button(
                        "disable", ChatFormatting.RED,
                        new ClickEvent.RunCommand("/mb auto stop")));
                message.append(Component.literal(" ")).append(button(
                        "config", ChatFormatting.YELLOW,
                        new ClickEvent.SuggestCommand("/mb auto start ")));
            }
        }
        return Optional.of(message);
    }

    private static MutableComponent button(String name, ChatFormatting color, ClickEvent action) {
        String key = "minebackup.message.auto.welcome.button." + name;
        return Component.translatable(key).withStyle(style -> style
                .withColor(color)
                .withClickEvent(action)
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable(key + ".hover"))));
    }
}
