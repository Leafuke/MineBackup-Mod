package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.MessageSlot;
import com.leafuke.minebackup.api.v2.MessageTemplate;
import com.leafuke.minebackup.api.v2.OperationPresentation;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FeedbackRouterTest {
    @Test
    void callerManagedSuppressesOptionalDelivery() {
        AtomicInteger serverLookups = new AtomicInteger();
        FeedbackRouter router = new FeedbackRouter(() -> {
            serverLookups.incrementAndGet();
            return null;
        });
        router.optional(
                OperationPresentation.callerManaged(),
                MessageSlot.BACKUP_STARTED,
                Component.literal("default"));
        assertEquals(0, serverLookups.get());

        router.optional(
                OperationPresentation.defaults(),
                MessageSlot.BACKUP_STARTED,
                Component.literal("default"));
        assertEquals(1, serverLookups.get());
    }

    @Test
    void missingTemplateUsesDefaultAndCustomTemplateUsesFallback() {
        FeedbackRouter router = new FeedbackRouter(() -> null);
        Component defaultMessage = Component.literal("default");
        assertSame(
                defaultMessage,
                router.resolve(
                        OperationPresentation.defaults(),
                        MessageSlot.RESTORE_KICK,
                        defaultMessage));

        OperationPresentation custom = OperationPresentation.defaults().withTemplate(
                MessageSlot.RESTORE_KICK,
                new MessageTemplate("addon.restore.kick", Optional.of("Leave world %s")));
        assertEquals(
                "Leave world demo",
                router.resolve(
                                custom,
                                MessageSlot.RESTORE_KICK,
                                defaultMessage,
                                "demo")
                        .getString());
    }
}
