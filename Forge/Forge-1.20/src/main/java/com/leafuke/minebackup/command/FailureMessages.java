package com.leafuke.minebackup.command;

import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.knotlink.KnotLinkCommunicationException;
import net.minecraft.network.chat.Component;
import java.util.Locale;
import java.util.Optional;

/** Technical details stay in the API and logs; chat uses stable localized explanations. */
public final class FailureMessages {
    private FailureMessages() {}

    public static Component message(Throwable error) {
        return message(KnotLinkCommunicationException.failure(error).code());
    }

    public static Component message(Optional<OperationFailure> failure) {
        return message(failure.map(OperationFailure::code).orElse(OperationFailure.Code.COMMUNICATION_ERROR));
    }

    public static Component message(OperationFailure.Code code) {
        return Component.translatable("minebackup.message.failure." + code.name().toLowerCase(Locale.ROOT));
    }
}
