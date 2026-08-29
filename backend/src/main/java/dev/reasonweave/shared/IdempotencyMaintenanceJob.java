package dev.reasonweave.shared;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!openapi-export")
public class IdempotencyMaintenanceJob {
    private static final int BATCH_SIZE = 1000;
    private static final int MAX_BATCHES = 10;
    private final JdbcClient jdbc;
    private final Counter removedRecords;

    public IdempotencyMaintenanceJob(JdbcClient jdbc, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.removedRecords = meterRegistry.counter("reasonweave.idempotency.expired.removed");
    }

    @PostConstruct
    public void cleanupOnStartup() {
        cleanup();
    }

    @Scheduled(fixedDelayString = "${rw.idempotency-cleanup-interval-ms:3600000}")
    public int cleanup() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int removed = jdbc.sql("""
                    delete from idempotency_records target
                    using (
                        select workspace_id, endpoint, idempotency_key
                        from idempotency_records
                        where expires_at <= now()
                        order by expires_at, workspace_id, endpoint, idempotency_key
                        limit :limit
                    ) expired
                    where target.workspace_id = expired.workspace_id
                      and target.endpoint = expired.endpoint
                      and target.idempotency_key = expired.idempotency_key
                    """)
                .param("limit", BATCH_SIZE)
                .update();
            total += removed;
            if (removed < BATCH_SIZE) {
                break;
            }
        }
        removedRecords.increment(total);
        return total;
    }
}
