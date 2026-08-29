package dev.reasonweave.shared;

import java.util.Map;

public record ApiError(ErrorBody error, ApiEnvelope.Meta meta) {
    public record ErrorBody(String code, String message, Map<String, Object> details) {}
}
