package dev.reasonweave.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.ids.IdGenerator;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter(new IdGenerator());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesRequestInstanceAndRunContextDuringInvestigationRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET",
            "/api/v1/investigations/inv_context_01/next-evidence"
        );
        request.addHeader("X-ReasonWeave-Request-Id", "req_context_123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Map<String, String>> captured = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
            captured.set(MDC.getCopyOfContextMap());

        filter.doFilter(request, response, chain);

        assertThat(captured.get())
            .containsEntry("request_id", "req_context_123")
            .containsEntry("instance_id", InstanceScope.ID)
            .containsEntry("run_id", "inv_context_01");
        assertThat(response.getHeader("X-ReasonWeave-Request-Id")).isEqualTo("req_context_123");
        assertThat(MDC.get("request_id")).isNull();
        assertThat(MDC.get("instance_id")).isNull();
        assertThat(MDC.get("run_id")).isNull();
    }

    @Test
    void acceptsRunIdFromGraphQueryWithoutLeakingItAfterTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET",
            "/api/v1/events/evt_context/graph"
        );
        request.setParameter("investigation_id", "inv_graph_01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> capturedRunId = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
            capturedRunId.set(MDC.get("run_id"))
        );

        assertThat(capturedRunId.get()).isEqualTo("inv_graph_01");
        assertThat(MDC.get("run_id")).isNull();
    }

    @Test
    void consolePatternIncludesAllRequiredContextKeys() throws Exception {
        try (var stream = Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream("logback-spring.xml")
        )) {
            String configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(configuration)
                .contains("%X{request_id:-none}")
                .contains("%X{instance_id:-none}")
                .contains("%X{run_id:-none}");
        }
    }
}
