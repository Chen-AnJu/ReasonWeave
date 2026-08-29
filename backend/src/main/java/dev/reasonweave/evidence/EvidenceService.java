package dev.reasonweave.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reasonweave.audit.AuditService;
import dev.reasonweave.event.EventModels;
import dev.reasonweave.event.EventService;
import dev.reasonweave.domainpack.DomainPackDefinition;
import dev.reasonweave.domainpack.DomainPackRegistry;
import dev.reasonweave.model.ModelGateway;
import dev.reasonweave.model.ModelGateway.ModelGatewayException;
import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.ApiException;
import dev.reasonweave.shared.Hashing;
import dev.reasonweave.shared.JsonSupport;
import dev.reasonweave.shared.PageCursor;
import dev.reasonweave.shared.ids.IdGenerator;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EvidenceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceService.class);
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp", "text/plain", "application/json"
    );

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final JsonSupport json;
    private final ObjectMapper mapper;
    private final LocalBlobStore blobStore;
    private final ModelGateway model;
    private final EventService events;
    private final AuditService audit;
    private final ObservationBundleValidator bundleValidator;
    private final DomainPackRegistry domainPacks;
    private final TransactionTemplate transaction;

    public EvidenceService(
        JdbcClient jdbc,
        IdGenerator ids,
        JsonSupport json,
        ObjectMapper mapper,
        LocalBlobStore blobStore,
        ModelGateway model,
        EventService events,
        AuditService audit,
        ObservationBundleValidator bundleValidator,
        DomainPackRegistry domainPacks,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.json = json;
        this.mapper = mapper;
        this.blobStore = blobStore;
        this.model = model;
        this.events = events;
        this.audit = audit;
        this.bundleValidator = bundleValidator;
        this.domainPacks = domainPacks;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public EvidenceModels.ObservationBundleImportView importObservationBundle(
        String eventId,
        JsonNode request,
        String requestId
    ) {
        EventModels.EventDetail event = events.get(eventId);
        ObservationBundleValidator.ValidatedBundle validated = bundleValidator.validate(event, request);
        String canonicalBundle = json.canonicalWrite(validated.bundle());
        String bundleHash = Hashing.sha256(canonicalBundle);
        lockEvidenceChecksum(eventId, "bundle:" + bundleHash);

        List<ObservationBundleValidator.ValidatedItem> ordered = validated.items().stream()
            .sorted(java.util.Comparator.comparing(value -> value.value().path("external_id").asText()))
            .toList();
        for (ObservationBundleValidator.ValidatedItem item : ordered) {
            lockEvidenceChecksum(eventId, "external:" + item.value().path("external_id").asText());
        }

        List<String> evidenceIds = new ArrayList<>();
        boolean createdAny = false;
        for (ObservationBundleValidator.ValidatedItem validatedItem : ordered) {
            JsonNode item = validatedItem.value();
            String externalId = item.path("external_id").asText();
            String canonicalItem = json.canonicalWrite(item);
            String itemHash = Hashing.sha256(canonicalItem);
            ExistingBundleEvidence existing = findBundleEvidence(eventId, externalId);
            if (existing != null) {
                if (!itemHash.equals(existing.checksum())) {
                    throw new ApiException(
                        HttpStatus.CONFLICT,
                        "BUNDLE_EXTERNAL_ID_CONFLICT",
                        "相同 external_id 已对应不同证据内容",
                        Map.of("external_id", externalId, "evidence_id", existing.id())
                    );
                }
                evidenceIds.add(existing.id());
                continue;
            }

            String evidenceId = ids.next("ev");
            String sourceType = item.path("source_type").asText();
            insertEvidence(
                evidenceId,
                eventId,
                "OBSERVATION_BUNDLE",
                sourceType,
                "NEEDS_REVIEW",
                null,
                externalId + ".json",
                canonicalItem,
                "application/vnd.reasonweave.observation-bundle+json",
                itemHash,
                OffsetDateTime.parse(item.path("captured_at").asText()),
                null,
                null,
                validatedItem.sourceProfile().reliability(),
                Map.of(
                    "schema_version", ObservationBundleValidator.SCHEMA_VERSION,
                    "bundle_hash", bundleHash,
                    "external_id", externalId,
                    "source_type", sourceType,
                    "source_profile", validatedItem.sourceProfile().type(),
                    "domain_pack", validated.domainPack().scopedKey(),
                    "target_version", validated.bundle().path("target_version").asText(),
                    "subject", validated.bundle().path("subject")
                )
            );
            for (JsonNode observation : item.path("observations")) {
                insertObservation(
                    evidenceId,
                    observation.path("predicate").asText(),
                    observation.path("value").deepCopy(),
                    observation.path("description").isMissingNode()
                        ? null
                        : observation.path("description").asText(),
                    observation.path("confidence").asDouble(),
                    "PENDING",
                    Map.of(
                        "adapter", "observation-bundle/1.0",
                        "bundle_hash", bundleHash,
                        "external_id", externalId,
                        "source_type", sourceType,
                        "source_locator", observation.path("source_locator"),
                        "domain_pack", validated.domainPack().scopedKey()
                    )
                );
            }
            evidenceIds.add(evidenceId);
            createdAny = true;
        }

        if (createdAny) {
            events.markUpdated(eventId);
            audit.record(
                eventId,
                "evidence.bundle_imported",
                "observation_bundle",
                bundleHash,
                null,
                Map.of(
                    "bundle_hash", bundleHash,
                    "schema_version", ObservationBundleValidator.SCHEMA_VERSION,
                    "domain_pack", validated.domainPack().scopedKey(),
                    "evidence_ids", evidenceIds
                ),
                requestId
            );
        }
        List<EvidenceModels.EvidenceDetail> evidence = evidenceIds.stream().map(this::get).toList();
        return new EvidenceModels.ObservationBundleImportView(
            ObservationBundleValidator.SCHEMA_VERSION,
            bundleHash,
            !createdAny,
            evidence
        );
    }

    @Transactional
    public EvidenceModels.EvidenceDetail createText(
        String eventId,
        EvidenceModels.TextEvidenceRequest request,
        String requestId
    ) {
        EventModels.EventDetail event = events.get(eventId);
        DomainPackDefinition definition = domainPacks.requireForEvent(
            event.domainPackKey(), event.eventType()
        );
        DomainPackDefinition.EvidenceInput textInput = definition.evidenceInput(
            event.eventType(), "text"
        );
        if (!textInput.enabled()) {
            throw new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "DOMAIN_EVIDENCE_TYPE_UNSUPPORTED",
                "当前领域包未启用文本证据入口"
            );
        }
        JsonNode textConfig = textInput.configuration();
        DomainPackDefinition.SourceProfile sourceProfile = definition.requireSourceProfile(
            textConfig.path("source_profile").asText()
        );
        String verificationStatus = textConfig.path("verification_status").asText("PENDING");
        byte[] bytes = request.text().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String checksum = Hashing.sha256(bytes);
        lockEvidenceChecksum(eventId, checksum);
        var duplicate = findDuplicate(eventId, checksum);
        if (duplicate != null) {
            return get(duplicate);
        }

        String evidenceId = ids.next("ev");
        insertEvidence(
            evidenceId, eventId, "TEXT", sourceProfile.type(),
            "CONFIRMED".equals(verificationStatus) ? "NORMALIZED" : "NEEDS_REVIEW",
            null, "text-evidence.txt", request.text(), "text/plain", checksum,
            request.capturedAt(), request.latitude(), request.longitude(), sourceProfile.reliability(),
            Map.of("adapter", "text-v1", "source_profile", sourceProfile.type())
        );
        insertObservation(
            evidenceId,
            textConfig.path("predicate").asText(),
            mapper.getNodeFactory().textNode(request.text()),
            "通过领域包声明的文本证据入口提供",
            1.0,
            verificationStatus,
            Map.of(
                "adapter", "text-v1",
                "source_profile", sourceProfile.type(),
                "domain_pack", definition.scopedKey()
            )
        );
        events.markUpdated(eventId);
        EvidenceModels.EvidenceDetail created = get(evidenceId);
        audit.record(eventId, "evidence.created", "evidence", evidenceId, null, created, requestId);
        return created;
    }

    public EvidenceModels.EvidenceDetail upload(String eventId, MultipartFile file, String requestId) {
        EventModels.EventDetail event = events.get(eventId);
        DomainPackDefinition definition = domainPacks.requireForEvent(
            event.domainPackKey(), event.eventType()
        );
        String declaredContentType = file.getContentType() == null
            ? "application/octet-stream"
            : file.getContentType();
        String contentType = declaredContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "FILE_TYPE_NOT_ALLOWED",
                "不支持的证据文件类型",
                Map.of("content_type", declaredContentType)
            );
        }
        try {
            byte[] bytes = file.getBytes();
            validateFileContent(contentType, bytes);
            String inputType = contentType.startsWith("image/") ? "image" : "file";
            DomainPackDefinition.EvidenceInput evidenceInput = definition.evidenceInput(
                event.eventType(), inputType
            );
            if (!evidenceInput.enabled()) {
                throw new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "DOMAIN_EVIDENCE_TYPE_UNSUPPORTED",
                    "当前领域包未启用此类文件证据入口"
                );
            }
            JsonNode inputConfig = evidenceInput.configuration();
            if (!containsText(inputConfig.path("content_types"), contentType)) {
                throw new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "DOMAIN_EVIDENCE_TYPE_UNSUPPORTED",
                    "当前领域包未允许该文件内容类型",
                    Map.of("content_type", contentType)
                );
            }
            if (contentType.startsWith("image/")
                && !definition.content().manifest().path("capabilities").path("image_vision").asBoolean(false)) {
                throw new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "DOMAIN_EVIDENCE_TYPE_UNSUPPORTED",
                    "当前领域包未声明图片视觉证据能力"
                );
            }
            DomainPackDefinition.SourceProfile sourceProfile = definition.requireSourceProfile(
                inputConfig.path("source_profile").asText()
            );
            String checksum = Hashing.sha256(bytes);
            UploadReservation reservation = transaction.execute(status -> reserveUpload(
                eventId,
                file.getOriginalFilename(),
                contentType,
                checksum,
                bytes,
                sourceProfile.type(),
                sourceProfile.reliability()
            ));
            if (reservation == null) {
                throw new IllegalStateException("Evidence reservation returned no result");
            }
            if (reservation.duplicate()) {
                return get(reservation.evidenceId());
            }

            List<ModelGateway.ObservationDraft> drafts = List.of();
            String finalStatus = "NEEDS_REVIEW";
            String failureCode = null;
            String observationVerificationStatus = "PENDING";
            if (contentType.startsWith("image/")) {
                try {
                    drafts = model.inspectImage(bytes, contentType, event.domainPackKey());
                    finalStatus = "NORMALIZED";
                } catch (ModelGatewayException exception) {
                    failureCode = exception instanceof ModelGateway.ModelOutputValidationException
                        ? "VISION_OUTPUT_INVALID"
                        : "VISION_PROVIDER_FAILED";
                }
            } else if ("text/plain".equals(contentType)) {
                DomainPackDefinition.EvidenceInput textInput = definition.evidenceInput(
                    event.eventType(), "text"
                );
                JsonNode textConfig = textInput.configuration();
                if (textInput.enabled()) {
                observationVerificationStatus = textConfig.path("verification_status").asText("PENDING");
                drafts = List.of(new ModelGateway.ObservationDraft(
                    textConfig.path("predicate").asText(),
                    mapper.getNodeFactory().textNode(new String(bytes, StandardCharsets.UTF_8)),
                    "通过领域包声明的文本文件入口提供",
                    1.0
                ));
                }
            }
            List<ModelGateway.ObservationDraft> validatedDrafts = drafts;
            String completedStatus = finalStatus;
            String processingFailure = failureCode;
            String completedVerificationStatus = observationVerificationStatus;
            transaction.executeWithoutResult(status -> finalizeUpload(
                reservation.evidenceId(),
                eventId,
                checksum,
                contentType,
                validatedDrafts,
                completedVerificationStatus,
                completedStatus,
                processingFailure,
                requestId
            ));
            return get(reservation.evidenceId());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EVIDENCE_PROCESSING_FAILED", "证据处理失败");
        }
    }

    public EvidenceModels.EvidencePage list(String eventId, String cursor, int requestedLimit) {
        String eventFilter = eventId == null ? "" : eventId.trim();
        int limit = PageCursor.limit(requestedLimit);
        String scope = Hashing.sha256(eventFilter);
        List<String> decoded = PageCursor.decode(cursor, scope, 2);
        OffsetDateTime cursorAt = OffsetDateTime.parse("9999-12-31T23:59:59Z");
        String cursorId = "\uffff";
        if (!decoded.isEmpty()) {
            try {
                cursorAt = OffsetDateTime.parse(decoded.get(0));
                cursorId = decoded.get(1);
            } catch (RuntimeException exception) {
                throw PageCursor.invalidCursor();
            }
        }
        List<EvidenceModels.EvidenceSummary> rows = jdbc.sql("""
                select e.*,
                       (select count(*) from observations o
                        where o.evidence_id = e.id and o.generation = e.generation) as observation_count
                from evidence e
                where e.workspace_id = :workspaceId
                  and (:eventId = '' or e.event_id = :eventId)
                  and (e.created_at, e.id) < (:cursorAt, :cursorId)
                order by e.created_at desc, e.id desc
                limit :limit
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("eventId", eventFilter)
            .param("cursorAt", cursorAt)
            .param("cursorId", cursorId)
            .param("limit", limit + 1)
            .query(this::mapSummary)
            .list();
        String nextCursor = null;
        if (rows.size() > limit) {
            EvidenceModels.EvidenceSummary boundary = rows.get(limit - 1);
            nextCursor = PageCursor.encode(scope, boundary.createdAt().toString(), boundary.id());
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        long total = jdbc.sql("""
                select count(*) from evidence
                where workspace_id = :workspaceId
                  and (:eventId = '' or event_id = :eventId)
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("eventId", eventFilter)
            .query(Long.class)
            .single();
        return new EvidenceModels.EvidencePage(List.copyOf(rows), nextCursor, limit, total);
    }

    public EvidenceModels.EvidenceDetail get(String id) {
        EvidenceModels.EvidenceDetail base = jdbc.sql("""
                select e.*,
                       (select count(*) from observations o
                        where o.evidence_id = e.id and o.generation = e.generation) as observation_count
                from evidence e
                where e.id = :id and e.workspace_id = :workspaceId
                """)
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .query((rs, rowNum) -> {
                Number latitude = (Number) rs.getObject("latitude");
                Number longitude = (Number) rs.getObject("longitude");
                return new EvidenceModels.EvidenceDetail(
                    mapSummary(rs, rowNum), rs.getString("content_text"), rs.getString("blob_key"),
                    rs.getObject("captured_at", OffsetDateTime.class),
                    latitude == null ? null : latitude.doubleValue(),
                    longitude == null ? null : longitude.doubleValue(),
                    json.read(rs.getString("metadata")), List.of()
                );
            })
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "证据不存在"));

        List<EvidenceModels.ObservationView> observations = jdbc.sql("""
                select * from observations
                where evidence_id = :evidenceId and workspace_id = :workspaceId
                  and generation = (
                    select generation from evidence
                    where id = :evidenceId and workspace_id = :workspaceId
                  )
                order by created_at desc
                """)
            .param("evidenceId", id)
            .param("workspaceId", InstanceScope.ID)
            .query((rs, rowNum) -> new EvidenceModels.ObservationView(
                rs.getString("id"), rs.getString("predicate"), json.read(rs.getString("value")),
                rs.getString("description"), rs.getDouble("model_confidence"),
                rs.getString("verification_status"), json.read(rs.getString("provenance")),
                rs.getInt("generation"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
            ))
            .list();

        return new EvidenceModels.EvidenceDetail(
            base.evidence(), base.contentText(), base.blobKey(), base.capturedAt(),
            base.latitude(), base.longitude(), base.metadata(), observations
        );
    }

    @Transactional
    public EvidenceModels.ObservationView verifyObservation(
        String id,
        EvidenceModels.ObservationVerificationRequest request,
        long expectedVersion,
        String requestId
    ) {
        String status = request.verificationStatus().toUpperCase(Locale.ROOT);
        if (!Set.of("CONFIRMED", "REJECTED", "PENDING").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "不支持的 Observation 复核状态");
        }
        Map<String, Object> before = jdbc.sql("""
                select o.id, o.evidence_id, o.version, o.verification_status, e.event_id
                from observations o
                join evidence e on e.id = o.evidence_id
                where o.id = :id and o.workspace_id = :workspaceId
                """)
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .query((rs, rowNum) -> Map.<String, Object>of(
                "id", rs.getString("id"),
                "evidence_id", rs.getString("evidence_id"),
                "event_id", rs.getString("event_id"),
                "version", rs.getLong("version"),
                "verification_status", rs.getString("verification_status")
            ))
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Observation 不存在"));

        int updated = jdbc.sql("""
                update observations
                set verification_status = :status,
                    description = coalesce(:description, description),
                    version = version + 1,
                    updated_at = now()
                where id = :id and workspace_id = :workspaceId and version = :expectedVersion
                """)
            .param("status", status)
            .param("description", request.description())
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .param("expectedVersion", expectedVersion)
            .update();
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Observation 已被其他操作更新");
        }

        String evidenceId = before.get("evidence_id").toString();
        refreshEvidenceVerificationStatus(evidenceId);
        events.markUpdated(before.get("event_id").toString());
        EvidenceModels.ObservationView after = get(evidenceId).observations().stream()
            .filter(value -> value.id().equals(id))
            .findFirst()
            .orElseThrow();
        audit.record(
            before.get("event_id").toString(), "observation.updated", "observation", id,
            before, after, requestId
        );
        return after;
    }

    public EvidenceModels.EvidenceDetail reprocess(String id, String requestId) {
        EvidenceModels.EvidenceDetail evidence = get(id);
        if (evidence.evidence().contentType() == null || !evidence.evidence().contentType().startsWith("image/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EVIDENCE_REPROCESS_UNSUPPORTED", "仅图片证据支持自动重处理");
        }
        byte[] bytes = blobStore.read(evidence.blobKey());
        List<ModelGateway.ObservationDraft> drafts;
        try {
            drafts = model.inspectImage(
                bytes,
                evidence.evidence().contentType(),
                events.get(evidence.evidence().eventId()).domainPackKey()
            );
        } catch (ModelGatewayException exception) {
            String code = exception instanceof ModelGateway.ModelOutputValidationException
                ? "VISION_OUTPUT_INVALID"
                : "VISION_PROVIDER_FAILED";
            transaction.executeWithoutResult(status -> {
                updateEvidenceStatus(id, "NEEDS_REVIEW");
                audit.record(
                    evidence.evidence().eventId(),
                    "evidence.processing_failed",
                    "evidence",
                    id,
                    evidence.evidence().status(),
                    Map.of("status", "NEEDS_REVIEW", "error_code", code),
                    requestId
                );
            });
            throw new ApiException(
                HttpStatus.BAD_GATEWAY,
                code,
                "图片证据处理失败；原 Observation generation 保持不变",
                Map.of("evidence_id", id)
            );
        }
        transaction.executeWithoutResult(status -> finalizeReprocess(evidence, drafts, requestId));
        return get(id);
    }

    private UploadReservation reserveUpload(
        String eventId,
        String originalName,
        String contentType,
        String checksum,
        byte[] bytes,
        String sourceType,
        double reliability
    ) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:material, 0))")
            .param("material", InstanceScope.ID + "\n" + eventId + "\n" + checksum)
            .query((rs, rowNum) -> 1)
            .single();
        String duplicate = findDuplicate(eventId, checksum);
        if (duplicate != null) {
            return new UploadReservation(duplicate, true);
        }

        LocalBlobStore.StoredBlob storedBlob = blobStore.storeContentAddressed(
            InstanceScope.ID,
            eventId,
            checksum,
            bytes
        );
        registerRollbackCleanup(storedBlob, eventId, checksum);
        String evidenceId = ids.next("ev");
        String type = contentType.startsWith("image/") ? "IMAGE" : "DOCUMENT";
        insertEvidence(
            evidenceId,
            eventId,
            type,
            sourceType,
            "EXTRACTING",
            storedBlob.key(),
            originalName,
            null,
            contentType,
            checksum,
            null,
            null,
            null,
            reliability,
            Map.of(
                "original_size", bytes.length,
                "adapter", contentType.startsWith("image/")
                    ? model.providerName() + ":vision"
                    : "document-v1"
            )
        );
        return new UploadReservation(evidenceId, false);
    }

    private void finalizeUpload(
        String evidenceId,
        String eventId,
        String checksum,
        String contentType,
        List<ModelGateway.ObservationDraft> drafts,
        String verificationStatus,
        String finalStatus,
        String failureCode,
        String requestId
    ) {
        int generation = jdbc.sql("""
                select generation from evidence
                where id = :id and workspace_id = :workspaceId
                for update
                """)
            .param("id", evidenceId)
            .param("workspaceId", InstanceScope.ID)
            .query(Integer.class)
            .single();
        for (ModelGateway.ObservationDraft draft : drafts) {
            boolean image = contentType.startsWith("image/");
            insertObservation(
                evidenceId,
                generation,
                draft.predicate(),
                draft.value(),
                draft.description(),
                draft.confidence(),
                verificationStatus,
                image
                    ? Map.of(
                        "adapter", model.providerName() + ":vision",
                        "model", model.visionModel(),
                        "schema_version", model.visionSchemaVersion(),
                        "input_hash", checksum,
                        "processing_generation", generation
                    )
                    : Map.of(
                        "adapter", "document-v1",
                        "input_hash", checksum,
                        "processing_generation", generation
                    )
            );
        }
        updateEvidenceStatus(evidenceId, finalStatus);
        events.markUpdated(eventId);
        EvidenceModels.EvidenceDetail created = get(evidenceId);
        audit.record(eventId, "evidence.uploaded", "evidence", evidenceId, null, created, requestId);
        if (failureCode != null) {
            audit.record(
                eventId,
                "evidence.processing_failed",
                "evidence",
                evidenceId,
                null,
                Map.of(
                    "status", finalStatus,
                    "error_code", failureCode,
                    "provider", model.providerName(),
                    "model", model.visionModel(),
                    "schema_version", model.visionSchemaVersion()
                ),
                requestId
            );
        }
    }

    private void finalizeReprocess(
        EvidenceModels.EvidenceDetail evidence,
        List<ModelGateway.ObservationDraft> drafts,
        String requestId
    ) {
        int currentGeneration = jdbc.sql("""
                select generation from evidence
                where id = :id and workspace_id = :workspaceId
                for update
                """)
            .param("id", evidence.evidence().id())
            .param("workspaceId", InstanceScope.ID)
            .query(Integer.class)
            .single();
        int nextGeneration = currentGeneration + 1;
        jdbc.sql("""
                update evidence
                set generation = :generation, status = 'NORMALIZED'
                where id = :id and workspace_id = :workspaceId
                """)
            .param("generation", nextGeneration)
            .param("id", evidence.evidence().id())
            .param("workspaceId", InstanceScope.ID)
            .update();
        for (ModelGateway.ObservationDraft draft : drafts) {
            insertObservation(
                evidence.evidence().id(),
                nextGeneration,
                draft.predicate(),
                draft.value(),
                draft.description(),
                draft.confidence(),
                "PENDING",
                Map.of(
                    "adapter", model.providerName() + ":vision",
                    "model", model.visionModel(),
                    "schema_version", model.visionSchemaVersion(),
                    "input_hash", evidence.evidence().checksumSha256(),
                    "reprocessed", true,
                    "processing_generation", nextGeneration
                )
            );
        }
        events.markUpdated(evidence.evidence().eventId());
        audit.record(
            evidence.evidence().eventId(),
            "evidence.reprocess_requested",
            "evidence",
            evidence.evidence().id(),
            Map.of("generation", currentGeneration, "status", evidence.evidence().status()),
            Map.of("generation", nextGeneration, "status", "NORMALIZED"),
            requestId
        );
    }

    public void insertObservation(
        String evidenceId,
        String predicate,
        JsonNode value,
        String description,
        double confidence,
        String verificationStatus,
        Map<String, Object> provenance
    ) {
        int generation = jdbc.sql("""
                select generation from evidence
                where id = :evidenceId and workspace_id = :workspaceId
                """)
            .param("evidenceId", evidenceId)
            .param("workspaceId", InstanceScope.ID)
            .query(Integer.class)
            .single();
        insertObservation(
            evidenceId,
            generation,
            predicate,
            value,
            description,
            confidence,
            verificationStatus,
            provenance
        );
    }

    private void insertObservation(
        String evidenceId,
        int generation,
        String predicate,
        JsonNode value,
        String description,
        double confidence,
        String verificationStatus,
        Map<String, Object> provenance
    ) {
        jdbc.sql("""
                insert into observations(
                    id, evidence_id, workspace_id, predicate, value, description,
                    model_confidence, verification_status, provenance, generation
                ) values (
                    :id, :evidenceId, :workspaceId, :predicate, cast(:value as jsonb), :description,
                    :confidence, :verificationStatus, cast(:provenance as jsonb), :generation
                )
                """)
            .param("id", ids.next("obs"))
            .param("evidenceId", evidenceId)
            .param("workspaceId", InstanceScope.ID)
            .param("predicate", predicate)
            .param("value", json.write(value))
            .param("description", description)
            .param("confidence", confidence)
            .param("verificationStatus", verificationStatus)
            .param("provenance", json.write(provenance))
            .param("generation", generation)
            .update();
    }

    private void insertEvidence(
        String id,
        String eventId,
        String type,
        String source,
        String status,
        String blobKey,
        String originalName,
        String contentText,
        String contentType,
        String checksum,
        OffsetDateTime capturedAt,
        Double latitude,
        Double longitude,
        double reliability,
        Map<String, Object> metadata
    ) {
        jdbc.sql("""
                insert into evidence(
                    id, event_id, workspace_id, type, source, status, blob_key,
                    original_name, content_text, content_type, checksum_sha256,
                    captured_at, latitude, longitude, reliability, metadata
                ) values (
                    :id, :eventId, :workspaceId, :type, :source, :status, :blobKey,
                    :originalName, :contentText, :contentType, :checksum,
                    :capturedAt, :latitude, :longitude, :reliability, cast(:metadata as jsonb)
                )
                """)
            .param("id", id)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .param("type", type)
            .param("source", source)
            .param("status", status)
            .param("blobKey", blobKey)
            .param("originalName", originalName)
            .param("contentText", contentText)
            .param("contentType", contentType)
            .param("checksum", checksum)
            .param("capturedAt", capturedAt)
            .param("latitude", latitude)
            .param("longitude", longitude)
            .param("reliability", reliability)
            .param("metadata", json.write(metadata))
            .update();
    }

    private String findDuplicate(String eventId, String checksum) {
        return jdbc.sql("""
                select id from evidence
                where workspace_id = :workspaceId and event_id = :eventId and checksum_sha256 = :checksum
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("eventId", eventId)
            .param("checksum", checksum)
            .query(String.class)
            .optional()
            .orElse(null);
    }

    private ExistingBundleEvidence findBundleEvidence(String eventId, String externalId) {
        return jdbc.sql("""
                select id, checksum_sha256 from evidence
                where workspace_id = :workspaceId and event_id = :eventId
                  and metadata ->> 'external_id' = :externalId
                order by created_at, id
                limit 1
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("eventId", eventId)
            .param("externalId", externalId)
            .query((rs, rowNum) -> new ExistingBundleEvidence(
                rs.getString("id"), rs.getString("checksum_sha256")
            ))
            .optional()
            .orElse(null);
    }

    private void lockEvidenceChecksum(String eventId, String checksum) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:material, 0))")
            .param("material", InstanceScope.ID + "\n" + eventId + "\n" + checksum)
            .query((rs, rowNum) -> 1)
            .single();
    }

    private void registerRollbackCleanup(
        LocalBlobStore.StoredBlob storedBlob,
        String eventId,
        String checksum
    ) {
        if (!storedBlob.created()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            blobStore.discardIfCreated(storedBlob);
            throw new IllegalStateException("Evidence blob was created outside an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    cleanupBlobIfUnreferenced(storedBlob, eventId, checksum);
                }
            }
        });
    }

    private void cleanupBlobIfUnreferenced(
        LocalBlobStore.StoredBlob storedBlob,
        String eventId,
        String checksum
    ) {
        try {
            transaction.executeWithoutResult(status -> {
                lockEvidenceChecksum(eventId, checksum);
                long references = jdbc.sql("""
                        select count(*) from evidence
                        where workspace_id = :workspaceId and event_id = :eventId and blob_key = :blobKey
                        """)
                    .param("workspaceId", InstanceScope.ID)
                    .param("eventId", eventId)
                    .param("blobKey", storedBlob.key())
                    .query(Long.class)
                    .single();
                if (references == 0) {
                    blobStore.discardIfCreated(storedBlob);
                }
            });
        } catch (RuntimeException exception) {
            LOGGER.warn("Deferred cleanup failed for uncommitted evidence blob {}", storedBlob.key(), exception);
        }
    }

    private void validateFileContent(String contentType, byte[] bytes) {
        boolean valid = switch (contentType) {
            case "image/jpeg" -> startsWith(bytes, 0xff, 0xd8, 0xff);
            case "image/png" -> startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "image/webp" -> startsWithAscii(bytes, 0, "RIFF") && startsWithAscii(bytes, 8, "WEBP");
            case "application/json" -> isValidJson(bytes);
            case "text/plain" -> isValidUtf8Text(bytes);
            default -> false;
        };
        if (!valid) {
            throw new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "FILE_CONTENT_TYPE_MISMATCH",
                "文件实际内容与声明类型不一致",
                Map.of("content_type", contentType)
            );
        }
    }

    private boolean isValidJson(byte[] bytes) {
        try {
            return mapper.readTree(bytes) != null;
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isValidUtf8Text(byte[] bytes) {
        try {
            var decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            return decoded.toString().indexOf('\0') < 0;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[index] & 0xff) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] bytes, int offset, String expected) {
        byte[] signature = expected.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (bytes[offset + index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private void updateEvidenceStatus(String id, String status) {
        jdbc.sql("update evidence set status = :status where id = :id and workspace_id = :workspaceId")
            .param("status", status)
            .param("id", id)
            .param("workspaceId", InstanceScope.ID)
            .update();
    }

    private void refreshEvidenceVerificationStatus(String evidenceId) {
        long pending = jdbc.sql("""
                select count(*) from observations
                where evidence_id = :evidenceId and verification_status = 'PENDING'
                  and generation = (
                    select generation from evidence where id = :evidenceId
                  )
                """)
            .param("evidenceId", evidenceId)
            .query(Long.class)
            .single();
        updateEvidenceStatus(evidenceId, pending == 0 ? "VERIFIED" : "NEEDS_REVIEW");
    }

    private EvidenceModels.EvidenceSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new EvidenceModels.EvidenceSummary(
            rs.getString("id"), rs.getString("event_id"), rs.getString("type"),
            rs.getString("source"), rs.getString("status"), rs.getString("original_name"),
            rs.getString("content_type"), rs.getString("checksum_sha256"),
            rs.getInt("generation"), rs.getDouble("reliability"), rs.getInt("observation_count"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean containsText(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) {
            if (expected.equalsIgnoreCase(value.asText())) return true;
        }
        return false;
    }

    private record UploadReservation(String evidenceId, boolean duplicate) {}
    private record ExistingBundleEvidence(String id, String checksum) {}
}
