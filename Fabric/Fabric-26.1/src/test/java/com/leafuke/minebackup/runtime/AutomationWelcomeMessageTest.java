package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.CurrentWorldAutomationMode;
import com.leafuke.minebackup.api.v2.CurrentWorldAutomationState;
import com.leafuke.minebackup.config.Config.JoinStatusNotificationMode;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationWelcomeMessageTest {
    @Test
    void notificationPreferenceCoversAllNineModeCombinations() {
        for (JoinStatusNotificationMode preference : JoinStatusNotificationMode.values()) {
            for (CurrentWorldAutomationMode mode : CurrentWorldAutomationMode.values()) {
                boolean expected = switch (preference) {
                    case ALWAYS -> true;
                    case ENABLED_ONLY -> mode != CurrentWorldAutomationMode.OFF;
                    case NEVER -> false;
                };
                for (boolean canManage : new boolean[] {false, true}) {
                    assertEquals(expected,
                            AutomationWelcomeMessage.build(state(mode), preference, canManage).isPresent(),
                            preference + "/" + mode + "/" + canManage);
                }
            }
        }
    }

    @Test
    void unavailableWorldNeverProducesAWelcome() {
        for (JoinStatusNotificationMode preference : JoinStatusNotificationMode.values()) {
            assertTrue(AutomationWelcomeMessage.build(
                    CurrentWorldAutomationState.unavailable(), preference, true).isEmpty());
        }
    }

    @Test
    void ordinaryPlayersSeeTheCorrectStatusWithoutManagementActions() {
        for (CurrentWorldAutomationMode mode : CurrentWorldAutomationMode.values()) {
            Component message = AutomationWelcomeMessage.build(
                    state(mode), JoinStatusNotificationMode.ALWAYS, false).orElseThrow();
            String suffix = switch (mode) {
                case OFF -> "disabled";
                case BACKUP -> "enabled";
                case REMIND -> "remind";
            };
            assertEquals("minebackup.message.auto.welcome." + suffix, key(message));
            assertNull(message.getStyle().getClickEvent());
            assertTrue(message.getSiblings().isEmpty());
        }
    }

    @Test
    void authorizedPlayersReceiveTheActualCommandAndHoverEvents() {
        Component disabled = AutomationWelcomeMessage.build(
                state(CurrentWorldAutomationMode.OFF), JoinStatusNotificationMode.ALWAYS, true)
                .orElseThrow();
        assertEquals(2, disabled.getSiblings().size());
        assertButton(disabled.getSiblings().get(1), "enable",
                new ClickEvent.SuggestCommand("/mb auto start "));

        for (CurrentWorldAutomationMode mode : List.of(
                CurrentWorldAutomationMode.BACKUP, CurrentWorldAutomationMode.REMIND)) {
            Component enabled = AutomationWelcomeMessage.build(
                    state(mode), JoinStatusNotificationMode.ALWAYS, true).orElseThrow();
            assertEquals(4, enabled.getSiblings().size());
            assertButton(enabled.getSiblings().get(1), "disable",
                    new ClickEvent.RunCommand("/mb auto stop"));
            assertButton(enabled.getSiblings().get(3), "config",
                    new ClickEvent.SuggestCommand("/mb auto start "));
            Object[] arguments = assertInstanceOf(TranslatableContents.class, enabled.getContents()).getArgs();
            assertEquals(30L, arguments[1]);
            assertEquals("minebackup.message.auto.mode."
                    + (mode == CurrentWorldAutomationMode.BACKUP ? "backup" : "remind"),
                    key(assertInstanceOf(Component.class, arguments[0])));
        }
    }

    @Test
    void enabledPlanCanDisplayAPendingNextRun() {
        CurrentWorldAutomationState pending = new CurrentWorldAutomationState(
                true, Optional.of("world"), CurrentWorldAutomationMode.REMIND,
                Optional.of(Duration.ofMinutes(30)), Optional.empty());
        Component message = AutomationWelcomeMessage.build(
                pending, JoinStatusNotificationMode.ENABLED_ONLY, false).orElseThrow();
        Object[] arguments = assertInstanceOf(TranslatableContents.class, message.getContents()).getArgs();
        assertEquals(Component.translatable("minebackup.message.auto.status.next_pending").getString(),
                arguments[2]);
    }

    private static void assertButton(Component button, String name, ClickEvent expectedAction) {
        String key = "minebackup.message.auto.welcome.button." + name;
        assertEquals(key, key(button));
        assertEquals(expectedAction, button.getStyle().getClickEvent());
        assertEquals(new HoverEvent.ShowText(Component.translatable(key + ".hover")),
                button.getStyle().getHoverEvent());
    }

    private static String key(Component message) {
        return assertInstanceOf(TranslatableContents.class, message.getContents()).getKey();
    }

    private static CurrentWorldAutomationState state(CurrentWorldAutomationMode mode) {
        return mode == CurrentWorldAutomationMode.OFF
                ? CurrentWorldAutomationState.disabled("world")
                : new CurrentWorldAutomationState(true, Optional.of("world"), mode,
                        Optional.of(Duration.ofMinutes(30)), Optional.of(Instant.parse("2026-10-21T12:00:00Z")));
    }
}
