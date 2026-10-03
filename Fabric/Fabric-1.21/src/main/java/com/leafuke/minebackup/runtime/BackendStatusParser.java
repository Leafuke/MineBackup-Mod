package com.leafuke.minebackup.runtime;

import com.leafuke.minebackup.api.v2.BackendStatusResult;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkCodec;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkProtocolException;
import com.leafuke.minebackup.knotlink.protocol.KnotLinkResponse;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

final class BackendStatusParser {
    private BackendStatusParser() {}

    static BackendStatusResult parse(KnotLinkResponse response, BackendStatusResult.ChannelState signal)
            throws KnotLinkProtocolException {
        Map<String, String> fields = response.fields();
        if (!fields.containsKey("enabled") && !fields.containsKey("initialized")
                && !fields.containsKey("active_tasks") && !fields.containsKey("active_auto_backups")) {
            String payload = response.data() != null ? response.data() : response.message();
            fields = payload == null || payload.isBlank() ? Map.of() : KnotLinkCodec.parse(payload);
        }
        return new BackendStatusResult(BackendStatusResult.Outcome.SUCCESS,
                BackendStatusResult.ChannelState.CONNECTED, signal, BackendStatusResult.ResponderState.ONLINE,
                bool(fields.get("enabled")), bool(fields.get("initialized")),
                count(fields.get("active_tasks")), count(fields.get("active_auto_backups")), Optional.empty());
    }

    private static Optional<Boolean> bool(String value) throws KnotLinkProtocolException {
        if (value == null) return Optional.empty();
        if ("true".equalsIgnoreCase(value)) return Optional.of(true);
        if ("false".equalsIgnoreCase(value)) return Optional.of(false);
        throw new KnotLinkProtocolException("Invalid backend status boolean");
    }

    private static OptionalInt count(String value) throws KnotLinkProtocolException {
        if (value == null) return OptionalInt.empty();
        try {
            int count = Integer.parseInt(value);
            if (count >= 0) return OptionalInt.of(count);
        } catch (NumberFormatException ignored) { }
        throw new KnotLinkProtocolException("Invalid backend status task count");
    }
}
