package dev.reasonweave.shared;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestIds {
    public static final String ATTRIBUTE = RequestIds.class.getName() + ".requestId";

    private RequestIds() {}

    public static String current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value == null ? "req_unknown" : value.toString();
    }
}
