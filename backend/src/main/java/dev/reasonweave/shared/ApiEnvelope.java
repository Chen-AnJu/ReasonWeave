package dev.reasonweave.shared;

import dev.reasonweave.config.ApiOptional;

public record ApiEnvelope<T>(T data, Meta meta) {

    public static <T> ApiEnvelope<T> of(T data, String requestId) {
        return new ApiEnvelope<>(data, new Meta(requestId, null));
    }

    public static <T> ApiEnvelope<T> eventIr(T data, String requestId) {
        return new ApiEnvelope<>(data, new Meta(requestId, "eventir/0.1"));
    }

    public record Meta(String requestId, @ApiOptional String schemaVersion) {}
}
