package dev.reasonweave.shared;

import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.ids.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    private static final Pattern INVESTIGATION_PATH = Pattern.compile(
        "^/api/v1/investigations/([A-Za-z0-9_-]{1,128})(?:/.*)?$"
    );
    private static final Pattern SAFE_RESOURCE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final IdGenerator ids;

    public RequestIdFilter(IdGenerator ids) {
        this.ids = ids;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String supplied = request.getHeader("X-ReasonWeave-Request-Id");
        String requestId = supplied != null && supplied.matches("[A-Za-z0-9_-]{8,64}")
            ? supplied
            : ids.next("req");
        request.setAttribute(RequestIds.ATTRIBUTE, requestId);
        response.setHeader("X-ReasonWeave-Request-Id", requestId);
        String runId = investigationRunId(request);
        try {
            MDC.put("request_id", requestId);
            MDC.put("instance_id", InstanceScope.ID);
            if (runId != null) {
                MDC.put("run_id", runId);
            }
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("run_id");
            MDC.remove("instance_id");
            MDC.remove("request_id");
        }
    }

    private static String investigationRunId(HttpServletRequest request) {
        Matcher path = INVESTIGATION_PATH.matcher(request.getRequestURI());
        if (path.matches()) {
            return path.group(1);
        }
        for (String parameter : new String[] {"investigation_id", "run_id"}) {
            String value = request.getParameter(parameter);
            if (value != null && SAFE_RESOURCE_ID.matcher(value).matches()) {
                return value;
            }
        }
        return null;
    }
}
