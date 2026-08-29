package dev.reasonweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reasonweave.config.ReasonWeaveProperties;
import dev.reasonweave.evidence.BlobStoreMaintenanceJob;
import dev.reasonweave.evidence.EvidenceModels;
import dev.reasonweave.evidence.EvidenceService;
import dev.reasonweave.evidence.LocalBlobStore;
import dev.reasonweave.investigation.InvestigationModels;
import dev.reasonweave.investigation.InvestigationService;
import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.ApiException;
import dev.reasonweave.shared.Hashing;
import dev.reasonweave.shared.ids.IdGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "rw.seed-fixtures=true",
    "rw.domain-pack-roots=../domain-packs;../fixtures/domain-packs",
    "rw.vision.provider=mock",
    "rw.embedding.provider=mock",
    "rw.embedding.dimension=1024",
    "rw.embedding.model-digest=test-only"
})
@AutoConfigureMockMvc
class ReasonWeaveConcurrencyIT {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired InvestigationService investigations;
    @Autowired EvidenceService evidence;
    @Autowired LocalBlobStore blobStore;
    @Autowired BlobStoreMaintenanceJob blobMaintenance;
    @Autowired IdGenerator ids;
    @Autowired ReasonWeaveProperties properties;

    @Test
    void serializesTwentyIdenticalEventCreatesAndRejectsKeyReuseWithDifferentBody() throws Exception {
        String suffix = suffix();
        String reference = "EVT-CONC-" + suffix;
        String payload = eventPayload(reference, "并发幂等事件", "kubernetes_pod_failure", "kubernetes-pod-diagnostics/1.0.0");
        String key = "event-concurrency-" + suffix;
        List<HttpOutcome> outcomes = concurrently(20, () -> performEventCreate(payload, key));

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).extracting(HttpOutcome::resourceId).doesNotContainNull().hasSize(20);
        assertThat(new HashSet<>(outcomes.stream().map(HttpOutcome::resourceId).toList())).hasSize(1);
        long count = jdbc.sql("select count(*) from events where workspace_id = :workspaceId and reference_code = :reference")
            .param("workspaceId", InstanceScope.ID)
            .param("reference", reference)
            .query(Long.class)
            .single();
        assertThat(count).isEqualTo(1);

        HttpOutcome reordered = performEventCreate(
            reorderedEventPayload(reference, "并发幂等事件", "kubernetes_pod_failure", "kubernetes-pod-diagnostics/1.0.0"),
            key
        );
        assertThat(reordered.status()).isEqualTo(201);
        assertThat(reordered.resourceId()).isEqualTo(outcomes.getFirst().resourceId());

        HttpOutcome conflict = performEventCreate(
            eventPayload("EVT-DIFF-" + suffix, "不同请求", "kubernetes_pod_failure", "kubernetes-pod-diagnostics/1.0.0"),
            key
        );
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void deduplicatesTwentyConcurrentTextEvidenceCreates() throws Exception {
        String eventId = createEvent("EVT-TEXT-" + suffix(), "并发文本证据事件");
        String text = "完全相同的文本证据 " + suffix();
        List<String> evidenceIds = concurrently(20, () -> evidence.createText(
            eventId,
            new EvidenceModels.TextEvidenceRequest(text, null, null, null),
            "parallel-text-test"
        ).evidence().id());

        assertThat(new HashSet<>(evidenceIds)).hasSize(1);
        long rows = jdbc.sql("""
                select count(*) from evidence
                where event_id = :eventId and workspace_id = :workspaceId and checksum_sha256 = :checksum
                """)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .param("checksum", Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .query(Long.class)
            .single();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void rejectsUnsupportedDomainAtCreateAndInvestigationBoundaries() throws Exception {
        String suffix = suffix();
        HttpOutcome invalid = performEventCreate(
            eventPayload("EVT-GEN-" + suffix, "通用事件", "general_incident", "kubernetes-pod-diagnostics/1.0.0"),
            "domain-mismatch-" + suffix
        );
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("EVENT_DOMAIN_MISMATCH");

        String legacyId = ids.next("evt");
        jdbc.sql("""
                insert into events(
                    id, workspace_id, reference_code, event_type, title, status,
                    domain_pack_key, event_ir
                ) values (
                    :id, :workspaceId, :reference, 'general_incident', '非法组合事件',
                    'DRAFT', 'kubernetes-pod-diagnostics/1.0.0', cast(:eventIr as jsonb)
                )
                """)
            .param("id", legacyId)
            .param("workspaceId", InstanceScope.ID)
            .param("reference", "EVT-LEG-" + suffix)
            .param("eventIr", eventPayloadNode("EVT-LEG-" + suffix, "非法组合事件", "general_incident", "kubernetes-pod-diagnostics/1.0.0").toString())
            .update();
        HttpOutcome investigation = performInvestigationStart(legacyId, "legacy-investigation-" + suffix);
        assertThat(investigation.status()).isEqualTo(400);
        assertThat(investigation.code()).isEqualTo("EVENT_DOMAIN_MISMATCH");
    }

    @Test
    void allocatesUniqueSequencesForParallelInvestigationsAndCreatesOneRunForOneKey() throws Exception {
        String sameKeyEvent = createEvent("EVT-IR-IDEM-" + suffix(), "调查幂等事件");
        String sameKey = "investigation-same-" + suffix();
        List<HttpOutcome> repeated = concurrently(20, () -> performInvestigationStart(sameKeyEvent, sameKey));
        assertThat(repeated).allMatch(outcome -> outcome.status() == 201 || outcome.status() == 409);
        long repeatedCount = jdbc.sql("select count(*) from investigation_runs where event_id = :eventId")
            .param("eventId", sameKeyEvent)
            .query(Long.class)
            .single();
        assertThat(repeatedCount).isEqualTo(1);

        String eventId = createEvent("EVT-IR-SEQ-" + suffix(), "并行调查序号事件");
        List<InvestigationModels.InvestigationRunView> runs = concurrently(
            10,
            new IndexedCallable<>() {
                @Override
                public InvestigationModels.InvestigationRunView call(int index) {
                    return investigations.start(
                        eventId,
                        "parallel-run-" + index + "-" + suffix(),
                        "parallel-investigation-test"
                    );
                }
            }
        );
        assertThat(runs).allMatch(run -> "COMPLETED".equals(run.status()));
        assertThat(runs).extracting(InvestigationModels.InvestigationRunView::sequenceNo)
            .doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 10).boxed().toList());
    }

    @Test
    void allowsOnlyOneConcurrentObservationUpdate() throws Exception {
        String eventId = createEvent("EVT-OBS-" + suffix(), "观察并发事件");
        EvidenceModels.EvidenceDetail created = evidence.createText(
            eventId,
            new EvidenceModels.TextEvidenceRequest("并发复核 " + suffix(), null, null, null),
            "observation-concurrency-test"
        );
        var observation = created.observations().getFirst();
        List<String> outcomes = concurrently(2, new IndexedCallable<>() {
            @Override
            public String call(int index) {
                try {
                    evidence.verifyObservation(
                        observation.id(),
                        new EvidenceModels.ObservationVerificationRequest(
                            index == 0 ? "CONFIRMED" : "REJECTED",
                            "并发复核 " + index
                        ),
                        observation.version(),
                        "observation-concurrency-" + index
                    );
                    return "OK";
                } catch (ApiException exception) {
                    return exception.code();
                }
            }
        });
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "VERSION_CONFLICT");
        assertThat(evidence.get(created.evidence().id()).observations().getFirst().version()).isEqualTo(1);
    }

    @Test
    void deduplicatesParallelFileUploadsWithoutOrphanBlob() throws Exception {
        String eventId = createEvent("EVT-UP-" + suffix(), "并发上传事件");
        byte[] bytes = ("并发上传证据 " + suffix()).getBytes(StandardCharsets.UTF_8);
        String checksum = Hashing.sha256(bytes);
        List<String> evidenceIds = concurrently(20, () -> evidence.upload(
            eventId,
            new MockMultipartFile("file", "evidence.txt", "text/plain", bytes),
            "parallel-upload-test"
        ).evidence().id());
        assertThat(new HashSet<>(evidenceIds)).hasSize(1);
        long rows = jdbc.sql("""
                select count(*) from evidence
                where event_id = :eventId and workspace_id = :workspaceId and checksum_sha256 = :checksum
                """)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .param("checksum", checksum)
            .query(Long.class)
            .single();
        assertThat(rows).isEqualTo(1);

        Path root = Path.of(properties.blobRoot()).toAbsolutePath().normalize();
        long blobs;
        try (var paths = Files.walk(root)) {
            blobs = paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals(checksum))
                .count();
        }
        assertThat(blobs).isEqualTo(1);
    }

    @Test
    void rejectsFilesWhoseDeclaredTypeDoesNotMatchTheirContent() throws Exception {
        String eventId = createEvent("EVT-MIME-" + suffix(), "文件签名校验事件");
        List<MockMultipartFile> invalidFiles = List.of(
            new MockMultipartFile("file", "fake.jpg", "image/jpeg", "not-a-jpeg".getBytes()),
            new MockMultipartFile("file", "broken.json", "application/json", "{".getBytes()),
            new MockMultipartFile("file", "broken.txt", "text/plain", new byte[] {(byte) 0xc3, 0x28})
        );

        for (MockMultipartFile invalidFile : invalidFiles) {
            assertThatThrownBy(() -> evidence.upload(eventId, invalidFile, "content-type-test"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(415);
                    assertThat(exception.code()).isEqualTo("FILE_CONTENT_TYPE_MISMATCH");
                });
        }
        long rows = jdbc.sql("select count(*) from evidence where event_id = :eventId")
            .param("eventId", eventId)
            .query(Long.class)
            .single();
        assertThat(rows).isZero();
    }

    @Test
    void removesNewBlobWhenDatabaseCommitFailsAfterReservation() throws Exception {
        String eventId = createEvent("EVT-COMMIT-" + suffix(), "提交失败清理事件");
        byte[] bytes = ("强制提交失败 " + suffix()).getBytes(StandardCharsets.UTF_8);
        String checksum = Hashing.sha256(bytes);
        Path target = Path.of(properties.blobRoot()).toAbsolutePath().normalize()
            .resolve(InstanceScope.ID)
            .resolve(eventId)
            .resolve("sha256")
            .resolve(checksum);

        jdbc.sql("drop trigger if exists rw_test_evidence_commit_failure on evidence").update();
        jdbc.sql("drop function if exists rw_test_fail_evidence_commit()").update();
        jdbc.sql("""
                create function rw_test_fail_evidence_commit() returns trigger
                language plpgsql as $$
                begin
                    if new.original_name = 'commit-failure.txt' then
                        raise exception 'forced deferred evidence commit failure';
                    end if;
                    return new;
                end
                $$
                """).update();
        jdbc.sql("""
                create constraint trigger rw_test_evidence_commit_failure
                after insert on evidence
                deferrable initially deferred
                for each row execute function rw_test_fail_evidence_commit()
                """).update();
        try {
            assertThatThrownBy(() -> evidence.upload(
                eventId,
                new MockMultipartFile("file", "commit-failure.txt", "text/plain", bytes),
                "commit-failure-test"
            )).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.code()).isEqualTo("EVIDENCE_PROCESSING_FAILED")
            );
            assertThat(Files.exists(target)).isFalse();
            long rows = jdbc.sql("select count(*) from evidence where event_id = :eventId")
                .param("eventId", eventId)
                .query(Long.class)
                .single();
            assertThat(rows).isZero();
        } finally {
            jdbc.sql("drop trigger if exists rw_test_evidence_commit_failure on evidence").update();
            jdbc.sql("drop function if exists rw_test_fail_evidence_commit()").update();
        }
    }

    @Test
    void orphanMaintenanceRemovesOnlyUnreferencedContentAddressedFiles() throws Exception {
        String eventId = createEvent("EVT-ORPHAN-" + suffix(), "孤儿文件清理事件");
        byte[] referencedBytes = ("已引用证据 " + suffix()).getBytes(StandardCharsets.UTF_8);
        EvidenceModels.EvidenceDetail referenced = evidence.upload(
            eventId,
            new MockMultipartFile("file", "referenced.txt", "text/plain", referencedBytes),
            "referenced-blob-test"
        );
        byte[] orphanBytes = jpegBytes("orphan-" + suffix());
        LocalBlobStore.StoredBlob orphan = blobStore.storeContentAddressed(
            InstanceScope.ID,
            eventId,
            Hashing.sha256(orphanBytes),
            orphanBytes
        );
        Path root = Path.of(properties.blobRoot()).toAbsolutePath().normalize();
        Path referencedPath = root.resolve(referenced.blobKey());
        Path orphanPath = root.resolve(orphan.key());
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(25)));
        Files.setLastModifiedTime(referencedPath, old);
        Files.setLastModifiedTime(orphanPath, old);

        assertThat(blobMaintenance.cleanupOrphanedBlobs()).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(referencedPath)).isTrue();
        assertThat(Files.exists(orphanPath)).isFalse();
    }

    @Test
    void evidenceSnapshotV2ChangesForPendingEvidenceObservationValueVersionAndGeneration() throws Exception {
        String eventId = createEvent("EVT-SNAP-" + suffix(), "快照版本事件");
        var first = investigations.start(eventId, "snapshot-empty-" + suffix(), "snapshot-test");
        assertThat(first.evidenceSnapshotSchemaVersion()).isEqualTo(2);
        assertThat(first.evidenceSnapshot().evidence()).isEmpty();

        String evidenceId = ids.next("ev");
        jdbc.sql("""
                insert into evidence(
                    id, event_id, workspace_id, type, source, status,
                    content_type, checksum_sha256, generation, reliability
                ) values (
                    :id, :eventId, :workspaceId, 'IMAGE', 'TEST', 'EXTRACTING',
                    'image/jpeg', :checksum, 1, 0.8
                )
                """)
            .param("id", evidenceId)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .param("checksum", Hashing.sha256(evidenceId))
            .update();
        touchEvent(eventId);
        var pendingEvidence = investigations.start(eventId, "snapshot-evidence-" + suffix(), "snapshot-test");
        assertThat(pendingEvidence.evidenceSnapshotHash()).isNotEqualTo(first.evidenceSnapshotHash());
        assertThat(pendingEvidence.evidenceSnapshot().evidence().getFirst().observations()).isEmpty();

        String observationId = ids.next("obs");
        insertObservation(observationId, evidenceId, 1, 0, false);
        touchEvent(eventId);
        var observationAdded = investigations.start(eventId, "snapshot-observation-" + suffix(), "snapshot-test");
        assertThat(observationAdded.evidenceSnapshotHash()).isNotEqualTo(pendingEvidence.evidenceSnapshotHash());

        jdbc.sql("""
                update observations
                set value = 'true'::jsonb, version = version + 1, updated_at = now()
                where id = :id
                """)
            .param("id", observationId)
            .update();
        touchEvent(eventId);
        var valueChanged = investigations.start(eventId, "snapshot-value-" + suffix(), "snapshot-test");
        assertThat(valueChanged.evidenceSnapshotHash()).isNotEqualTo(observationAdded.evidenceSnapshotHash());
        var changedObservation = valueChanged.evidenceSnapshot().evidence().getFirst().observations().getFirst();
        assertThat(changedObservation.version()).isEqualTo(1);
        assertThat(changedObservation.value()).isEqualTo(true);

        jdbc.sql("update evidence set generation = 2, status = 'NORMALIZED' where id = :id")
            .param("id", evidenceId)
            .update();
        insertObservation(ids.next("obs"), evidenceId, 2, 0, true);
        touchEvent(eventId);
        var regenerated = investigations.start(eventId, "snapshot-generation-" + suffix(), "snapshot-test");
        assertThat(regenerated.evidenceSnapshotHash()).isNotEqualTo(valueChanged.evidenceSnapshotHash());
        assertThat(regenerated.evidenceSnapshot().evidence().getFirst().generation()).isEqualTo(2);
        assertThat(regenerated.evidenceSnapshot().evidence().getFirst().observations())
            .allMatch(value -> value.generation() == 2);

        var original = investigations.get(first.id());
        assertThat(original.evidenceSnapshotHash()).isEqualTo(first.evidenceSnapshotHash());
        assertThat(original.evidenceSnapshot().evidence()).isEmpty();
    }

    private HttpOutcome performEventCreate(String payload, String key) throws Exception {
        var response = mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andReturn().getResponse();
        JsonNode body = mapper.readTree(response.getContentAsString());
        return new HttpOutcome(
            response.getStatus(),
            body.at("/data/id").textValue(),
            body.at("/error/code").textValue()
        );
    }

    private HttpOutcome performInvestigationStart(String eventId, String key) throws Exception {
        var response = mockMvc.perform(post("/api/v1/events/{eventId}/investigations", eventId)
                .header("Idempotency-Key", key))
            .andReturn().getResponse();
        JsonNode body = mapper.readTree(response.getContentAsString());
        return new HttpOutcome(
            response.getStatus(),
            body.at("/data/id").textValue(),
            body.at("/error/code").textValue()
        );
    }

    private String createEvent(String reference, String title) throws Exception {
        HttpOutcome outcome = performEventCreate(
            eventPayload(reference, title, "kubernetes_pod_failure", "kubernetes-pod-diagnostics/1.0.0"),
            "create-" + reference
        );
        assertThat(outcome.status()).isEqualTo(201);
        return outcome.resourceId();
    }

    private String eventPayload(String reference, String title, String type, String pack) {
        return mapper.createObjectNode()
            .set("event_ir", eventPayloadNode(reference, title, type, pack))
            .toString();
    }

    private String reorderedEventPayload(String reference, String title, String type, String pack) {
        var event = mapper.createObjectNode();
        event.put("domain_pack", pack);
        event.put("reference_code", reference);
        event.put("title", title);
        event.put("type", type);
        var subject = mapper.createObjectNode();
        subject.put("label", "default/test-pod");
        subject.put("type", "kubernetes_pod");
        var attributes = mapper.createObjectNode();
        attributes.put("pod_name", "test-pod");
        attributes.put("namespace", "default");
        subject.set("attributes", attributes);
        var root = mapper.createObjectNode();
        root.set("subjects", mapper.createArrayNode().add(subject));
        root.set("event", event);
        root.put("schema_version", "eventir/0.1");
        return mapper.createObjectNode().set("event_ir", root).toString();
    }

    private JsonNode eventPayloadNode(String reference, String title, String type, String pack) {
        var event = mapper.createObjectNode();
        event.put("type", type);
        event.put("title", title);
        event.put("reference_code", reference);
        event.put("domain_pack", pack);
        var subject = mapper.createObjectNode();
        subject.put("type", "kubernetes_pod");
        subject.put("label", "default/test-pod");
        var attributes = mapper.createObjectNode();
        attributes.put("namespace", "default");
        attributes.put("pod_name", "test-pod");
        subject.set("attributes", attributes);
        var root = mapper.createObjectNode();
        root.put("schema_version", "eventir/0.1");
        root.set("event", event);
        root.set("subjects", mapper.createArrayNode().add(subject));
        return root;
    }

    private void insertObservation(String id, String evidenceId, int generation, long version, boolean value) {
        jdbc.sql("""
                insert into observations(
                    id, evidence_id, workspace_id, predicate, value, description,
                    model_confidence, verification_status, provenance, generation, version
                ) values (
                    :id, :evidenceId, :workspaceId, 'image_pull_backoff',
                    cast(:value as jsonb), '快照测试观察', 0.8, 'PENDING',
                    '{"adapter":"snapshot-test"}'::jsonb, :generation, :version
                )
                """)
            .param("id", id)
            .param("evidenceId", evidenceId)
            .param("workspaceId", InstanceScope.ID)
            .param("value", Boolean.toString(value))
            .param("generation", generation)
            .param("version", version)
            .update();
    }

    private void touchEvent(String eventId) {
        jdbc.sql("update events set version = version + 1, updated_at = now() where id = :id")
            .param("id", eventId)
            .update();
    }

    private static String suffix() {
        return Long.toUnsignedString(System.nanoTime(), 36);
    }

    private static byte[] jpegBytes(String payload) {
        byte[] tail = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes = new byte[tail.length + 3];
        bytes[0] = (byte) 0xff;
        bytes[1] = (byte) 0xd8;
        bytes[2] = (byte) 0xff;
        System.arraycopy(tail, 0, bytes, 3, tail.length);
        return bytes;
    }

    private static <T> List<T> concurrently(int count, Callable<T> callable) throws Exception {
        return concurrently(count, index -> callable.call());
    }

    private static <T> List<T> concurrently(int count, IndexedCallable<T> callable) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(count, 20));
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int taskIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return callable.call(taskIndex);
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return List.copyOf(results);
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IndexedCallable<T> {
        T call(int index) throws Exception;
    }

    private record HttpOutcome(int status, String resourceId, String code) {}
}
