package com.leafuke.minebackup.command;

import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.knotlink.KnotLinkCommunicationException;
import net.minecraft.text.Text;
import java.util.Locale;
import java.util.Optional;

/** Technical details stay in the API and logs; chat uses stable localized explanations. */
public final class FailureMessages {
    private FailureMessages() {}

    public static Text message(Throwable error) {
        return message(KnotLinkCommunicationException.failure(error).code());
    }

    public static Text message(Optional<OperationFailure> failure) {
        return message(failure.map(OperationFailure::code).orElse(OperationFailure.Code.COMMUNICATION_ERROR));
    }

    public static Text message(OperationFailure.Code code) {
        return Text.translatable("minebackup.message.failure." + code.name().toLowerCase(Locale.ROOT));
    }
}
