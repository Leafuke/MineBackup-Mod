package com.leafuke.minebackup.api.v2;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record OperationPresentation(
        FeedbackPolicy feedbackPolicy,
        Map<MessageSlot, MessageTemplate> templates) {
    public OperationPresentation {
        Objects.requireNonNull(feedbackPolicy, "feedbackPolicy");
        Objects.requireNonNull(templates, "templates");
        templates.forEach((slot, template) -> {
            Objects.requireNonNull(slot, "message slot");
            Objects.requireNonNull(template, "message template");
        });
        templates = Map.copyOf(templates);
    }

    public static OperationPresentation defaults() {
        return new OperationPresentation(FeedbackPolicy.DEFAULT, Map.of());
    }

    public static OperationPresentation callerManaged() {
        return new OperationPresentation(FeedbackPolicy.CALLER_MANAGED, Map.of());
    }

    public Optional<MessageTemplate> template(MessageSlot slot) {
        return Optional.ofNullable(templates.get(Objects.requireNonNull(slot, "slot")));
    }

    public OperationPresentation withTemplate(MessageSlot slot, MessageTemplate template) {
        java.util.HashMap<MessageSlot, MessageTemplate> updated = new java.util.HashMap<>(templates);
        updated.put(Objects.requireNonNull(slot, "slot"), Objects.requireNonNull(template, "template"));
        return new OperationPresentation(feedbackPolicy, updated);
    }
}
