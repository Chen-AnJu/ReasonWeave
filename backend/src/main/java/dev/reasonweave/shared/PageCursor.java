package dev.reasonweave.shared;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatus;

public final class PageCursor {
    private static final int MAX_CURSOR_LENGTH = 4096;

    private PageCursor() {}

    public static String encode(String scope, String... values) {
        List<String> parts = new ArrayList<>();
        parts.add(scope);
        parts.addAll(List.of(values));
        String raw = String.join("\n", parts);
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static List<String> decode(String cursor, String scope, int expectedValues) {
        if (cursor == null || cursor.isBlank()) {
            return List.of();
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\n", -1);
            if (parts.length != expectedValues + 1 || !parts[0].equals(scope)) {
                throw invalidCursor();
            }
            List<String> values = new ArrayList<>(expectedValues);
            for (int index = 1; index < parts.length; index++) {
                if (parts[index].isBlank()) {
                    throw invalidCursor();
                }
                values.add(parts[index]);
            }
            return List.copyOf(values);
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    public static int limit(int requested) {
        if (requested < 1 || requested > 100) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_PAGE_LIMIT",
                "分页大小必须在 1 到 100 之间"
            );
        }
        return requested;
    }

    public static ApiException invalidCursor() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "分页游标无效或与当前筛选不匹配");
    }
}
