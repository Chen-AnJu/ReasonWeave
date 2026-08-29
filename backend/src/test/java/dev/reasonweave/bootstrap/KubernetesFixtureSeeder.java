package dev.reasonweave.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reasonweave.config.ReasonWeaveProperties;
import dev.reasonweave.contracts.EventIrValidator;
import dev.reasonweave.investigation.InvestigationService;
import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Kubernetes-specific data seeding is test-only and never ships in the runtime artifact. */
@Component
@Order(3)
public class KubernetesFixtureSeeder implements ApplicationRunner {
    private final ReasonWeaveProperties properties;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final EventIrValidator validator;
    private final InvestigationService investigations;

    public KubernetesFixtureSeeder(
        ReasonWeaveProperties properties,
        JdbcClient jdbc,
        ObjectMapper mapper,
        EventIrValidator validator,
        InvestigationService investigations
    ) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.validator = validator;
        this.investigations = investigations;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.seedFixtures()) return;
        Path path = Path.of(properties.fixtureRoot()).toAbsolutePath().normalize()
            .resolve("eventir/kubernetes-pod-image-pull.json").normalize();
        JsonNode eventIr = mapper.readTree(Files.readString(path));
        validator.validate(eventIr);
        JsonNode event = eventIr.path("event");
        String eventId = event.path("id").asText("evt_fixture_k8s_pod_001");
        boolean exists = jdbc.sql("select exists(select 1 from events where id = :id)")
            .param("id", eventId)
            .query(Boolean.class)
            .single();
        if (!exists) {
            JsonNode occurred = event.path("occurred_at");
            JsonNode location = event.path("location");
            jdbc.sql("""
                    insert into events(
                        id, workspace_id, reference_code, event_type, title, description,
                        occurred_start, occurred_end, location_name, status, domain_pack_key,
                        event_ir
                    ) values (
                        :id, :workspaceId, :referenceCode, :eventType, :title, :description,
                        :occurredStart, :occurredEnd, :locationName, 'COLLECTING', :domainPackKey,
                        cast(:eventIr as jsonb)
                    )
                    """)
                .param("id", eventId)
                .param("workspaceId", InstanceScope.ID)
                .param("referenceCode", event.path("reference_code").asText())
                .param("eventType", event.path("type").asText())
                .param("title", event.path("title").asText())
                .param("description", event.path("description").asText())
                .param("occurredStart", OffsetDateTime.parse(occurred.path("start").asText()))
                .param("occurredEnd", OffsetDateTime.parse(occurred.path("end").asText()))
                .param("locationName", location.path("name").asText())
                .param("domainPackKey", event.path("domain_pack").asText())
                .param("eventIr", mapper.writeValueAsString(eventIr))
                .update();
            insertEvidence(eventId, eventIr.path("evidence"));
            insertObservations(eventIr.path("observations"));
        }
        long runCount = jdbc.sql("select count(*) from investigation_runs where event_id = :eventId")
            .param("eventId", eventId)
            .query(Long.class)
            .single();
        if (runCount == 0) {
            investigations.start(eventId, "fixture-investigation-" + eventId, "fixture-seeder");
        }
    }

    private void insertEvidence(String eventId, JsonNode evidenceValues) throws Exception {
        for (JsonNode evidence : evidenceValues) {
            String id = evidence.path("id").asText();
            String contentType = nullableText(evidence, "content_type");
            jdbc.sql("""
                    insert into evidence(
                        id, event_id, workspace_id, type, source, status, original_name,
                        content_type, checksum_sha256, captured_at, reliability, metadata
                    ) values (
                        :id, :eventId, :workspaceId, :type, :source, 'VERIFIED', :originalName,
                        :contentType, :checksum, :capturedAt, :reliability, cast(:metadata as jsonb)
                    ) on conflict (id) do nothing
                    """)
                .param("id", evidence.path("id").asText())
                .param("eventId", eventId)
                .param("workspaceId", InstanceScope.ID)
                .param("type", evidence.path("type").asText().toUpperCase())
                .param("source", evidence.path("source").asText().toUpperCase())
                .param("originalName", evidence.path("id").asText() + ".json")
                .param("contentType", contentType)
                .param("checksum", Hashing.sha256(mapper.writeValueAsBytes(evidence)))
                .param("capturedAt", nullableText(evidence, "captured_at") == null
                    ? null : OffsetDateTime.parse(evidence.path("captured_at").asText()))
                .param("reliability", evidence.path("reliability").asDouble(0.8))
                .param("metadata", mapper.writeValueAsString(evidence.path("metadata")))
                .update();
        }
    }

    private void insertObservations(JsonNode observations) throws Exception {
        for (JsonNode observation : observations) {
            jdbc.sql("""
                    insert into observations(
                        id, evidence_id, workspace_id, subject_id, predicate, value, description,
                        model_confidence, verification_status, provenance
                    ) values (
                        :id, :evidenceId, :workspaceId, :subjectId, :predicate,
                        cast(:value as jsonb), :description, :confidence, 'CONFIRMED',
                        cast(:provenance as jsonb)
                    ) on conflict (id) do nothing
                    """)
                .param("id", observation.path("id").asText())
                .param("evidenceId", observation.path("evidence_id").asText())
                .param("workspaceId", InstanceScope.ID)
                .param("subjectId", nullableText(observation, "subject_id"))
                .param("predicate", observation.path("predicate").asText())
                .param("value", mapper.writeValueAsString(observation.path("value")))
                .param("description", "随包合成 Fixture Observation")
                .param("confidence", observation.path("model_confidence").asDouble(1.0))
                .param("provenance", mapper.writeValueAsString(observation.path("provenance")))
                .update();
        }
    }

    private static String nullableText(JsonNode value, String field) {
        JsonNode node = value.path(field);
        return node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }
}
