package dev.reasonweave.evidence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("!openapi-export")
public class BlobStoreMaintenanceJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlobStoreMaintenanceJob.class);
    private static final Duration STAGING_MINIMUM_AGE = Duration.ofHours(1);
    private static final Duration ORPHAN_MINIMUM_AGE = Duration.ofHours(24);
    private final LocalBlobStore blobStore;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Counter removedStagingFiles;
    private final Counter removedOrphanBlobs;

    public BlobStoreMaintenanceJob(
        LocalBlobStore blobStore,
        JdbcClient jdbc,
        PlatformTransactionManager transactionManager,
        MeterRegistry meterRegistry
    ) {
        this.blobStore = blobStore;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.removedStagingFiles = meterRegistry.counter("reasonweave.blob.staging.removed");
        this.removedOrphanBlobs = meterRegistry.counter("reasonweave.blob.orphan.removed");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        cleanup();
    }

    @Scheduled(fixedDelayString = "${rw.blob-maintenance-interval-ms:3600000}")
    public void cleanup() {
        removedStagingFiles.increment(blobStore.cleanupStaging(STAGING_MINIMUM_AGE));
        removedOrphanBlobs.increment(cleanupOrphanedBlobs());
    }

    public int cleanupOrphanedBlobs() {
        int removed = 0;
        for (LocalBlobStore.BlobCandidate candidate
            : blobStore.findContentAddressedCandidates(ORPHAN_MINIMUM_AGE)) {
            try {
                Boolean deleted = transaction.execute(status -> {
                    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:material, 0))")
                        .param(
                            "material",
                            candidate.workspaceId() + "\n" + candidate.eventId() + "\n"
                                + candidate.checksumSha256()
                        )
                        .query((rs, rowNum) -> 1)
                        .single();
                    long references = jdbc.sql("""
                            select count(*) from evidence
                            where workspace_id = :workspaceId
                              and event_id = :eventId
                              and blob_key = :blobKey
                            """)
                        .param("workspaceId", candidate.workspaceId())
                        .param("eventId", candidate.eventId())
                        .param("blobKey", candidate.key())
                        .query(Long.class)
                        .single();
                    return references == 0 && blobStore.discardCandidate(candidate);
                });
                if (Boolean.TRUE.equals(deleted)) {
                    removed++;
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Unable to reconcile evidence blob {}", candidate.key(), exception);
            }
        }
        return removed;
    }
}
