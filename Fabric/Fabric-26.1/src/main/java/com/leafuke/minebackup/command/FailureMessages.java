package com.leafuke.minebackup.command;

import com.leafuke.minebackup.api.v2.OperationFailure;
import com.leafuke.minebackup.knotlink.KnotLinkCommunicationException;

import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Optional;

/** Technical details stay in the API and logs; chat uses stable localized explanations. */
public final class FailureMessages {
    private FailureMessages() {}

    public static Component observation(com.leafuke.minebackup.interop.InteropClient.Observation observation) {
        var s=observation.snapshot();
        String summary;
        if(s.recoveryPending())summary="recovery_required";
        else if("openFailed".equals(s.followUp()))summary="rejoin_failed";
        else if("scriptStarted".equals(s.followUp()))summary="script_started";
        else if("unresolved".equals(s.state()))summary="result_unknown";
        else if(s.result()!=null)summary=s.result().get("outcome").getAsString().equals("succeeded")?"completed":"ended";
        else summary=s.state().equals("queued")?"pending":"running";
        return Component.translatable("minebackup.interop.stage",observation.backend(),
                s.operation().toString().substring(0,8),Component.translatable("minebackup.interop.stage."+summary));
    }

    public static Component message(Throwable error) {
        return detail(KnotLinkCommunicationException.failure(error));
    }

    public static Component message(Optional<OperationFailure> failure) {
        return failure.map(FailureMessages::detail)
                .orElseGet(() -> message(OperationFailure.Code.COMMUNICATION_ERROR));
    }

    public static Component detail(OperationFailure failure) {
        String k = failure.message();
        if (java.util.Set.of(
                        "result_unknown",
                        "stale_ref",
                        "stale_binding",
                        "target_unverified",
                        "target_ambiguous",
                        "upgrade_plugin",
                        "plugin_missing",
                        "plugin_disabled",
                        "response_timeout",
                        "protocol_mismatch",
                        "backend_offline",
                        "relay_unreachable",
                        "recovery_required")
                .contains(k)) return Component.translatable("minebackup.interop." + k, "");
        return message(failure.code());
    }

    public static Component message(OperationFailure.Code code) {
        return Component.translatable("minebackup.message.failure." + code.name().toLowerCase(Locale.ROOT));
    }
}
