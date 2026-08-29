package dev.reasonweave.investigation;

import dev.reasonweave.audit.AuditService;
import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.JsonSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!openapi-export")
public class InvestigationRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(InvestigationRecoveryJob.class);
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final JsonSupport json;
    private final Counter interruptedRuns;
    private final AtomicInteger staleRunningRuns = new AtomicInteger();

    public InvestigationRecoveryJob(
        JdbcClient jdbc,
        AuditService audit,
        JsonSupport json,
        MeterRegistry meterRegistry
    ) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.interruptedRuns = meterRegistry.counter("reasonweave.investigation.interrupted");
        Gauge.builder("reasonweave.investigation.stale.running", staleRunningRuns, AtomicInteger::get)
            .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverOnStartup() {
        recover();
    }

    @Scheduled(fixedDelayString = "${rw.investigation.recovery-interval-ms:300000}")
    @Transactional
    public void recover() {
        try (
            MDC.MDCCloseable requestContext = MDC.putCloseable("request_id", "system-recovery");
            MDC.MDCCloseable workspaceContext = MDC.putCloseable(
                "workspace_id",
                InstanceScope.ID
            )
        ) {
            try {
                List<StaleRun> stale = jdbc.sql("""
                    select id, event_id, started_at
                    from investigation_runs
                    where workspace_id = :workspaceId and status = 'RUNNING'
                      and started_at < now() - interval '15 minutes'
                    order by started_at, id
                    for update skip locked
                    """)
                .param("workspaceId", InstanceScope.ID)
                .query((rs, rowNum) -> new StaleRun(
                    rs.getString("id"),
                    rs.getString("event_id"),
                    rs.getObject("started_at", OffsetDateTime.class)
                ))
                .list();
                staleRunningRuns.set(stale.size());
                for (StaleRun run : stale) {
                    try (MDC.MDCCloseable runContext = MDC.putCloseable("run_id", run.id())) {
                        int updated = jdbc.sql("""
                        update investigation_runs
                        set status = 'FAILED', error_code = 'INVESTIGATION_INTERRUPTED',
                            error_message = '调查运行被进程中断', completed_at = now()
                        where id = :id and status = 'RUNNING'
                        """)
                    .param("id", run.id())
                    .update();
                        if (updated != 1) {
                            continue;
                        }
                        Map<String, Object> response = Map.of(
                            "id", run.id(),
                            "event_id", run.eventId(),
                            "status", "FAILED",
                            "error_code", "INVESTIGATION_INTERRUPTED"
                        );
                        jdbc.sql("""
                        update idempotency_records
                        set state = 'COMPLETED', response_status = 500,
                            response_body = cast(:response as jsonb)
                        where workspace_id = :workspaceId and resource_id = :runId
                        """)
                    .param("response", json.write(response))
                    .param("workspaceId", InstanceScope.ID)
                    .param("runId", run.id())
                    .update();
                        audit.recordSystem(
                            run.eventId(),
                            "investigation.interrupted",
                            "investigation_run",
                            run.id(),
                            Map.of("status", "RUNNING", "started_at", run.startedAt()),
                            response,
                            "recovery-" + run.id()
                        );
                        interruptedRuns.increment();
                    }
                }
            } catch (RuntimeException exception) {
                log.error("Failed to recover interrupted investigation runs", exception);
                throw exception;
            }
        }
    }

    private record StaleRun(String id, String eventId, OffsetDateTime startedAt) {}
}
