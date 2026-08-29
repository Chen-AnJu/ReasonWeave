package dev.reasonweave.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reasonweave.config.ReasonWeaveProperties;
import dev.reasonweave.domainpack.DomainPackRegistry;
import dev.reasonweave.domainpack.DomainPackValidator;
import dev.reasonweave.domainpack.DomainEventValidator;
import dev.reasonweave.event.EventModels;
import dev.reasonweave.shared.ApiException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ObservationBundleValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObservationBundleValidator validator;
    private DomainPackRegistry registry;
    private DomainEventValidator domainEventValidator;
    private EventModels.EventDetail event;

    @BeforeEach
    void setUp() throws Exception {
        ReasonWeaveProperties properties = new ReasonWeaveProperties(
            "ReasonWeave Test", true, "./data/blob", "../domain-packs", "../fixtures",
            "http://localhost:5173",
            new ReasonWeaveProperties.Vision(
                "mock", "https://example.invalid", "/chat/completions", "mock", ""
            ),
            new ReasonWeaveProperties.Embedding(
                "mock", "http://localhost:11434", "/api/embed", "mock", 1024,
                "test-only", "query:", ""
            )
        );
        registry = new DomainPackRegistry(properties, new DomainPackValidator(mapper));
        registry.load();
        domainEventValidator = new DomainEventValidator();
        validator = new ObservationBundleValidator(mapper, registry, domainEventValidator);
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T00:00:00Z");
        event = new EventModels.EventDetail(
            "evt_test", "K8S-001", "kubernetes_pod_failure", "Pod failure",
            null, "COLLECTING", "kubernetes-pod-diagnostics/1.0.0",
            null, null, null, null, null, 0, mapper.readTree("""
                {
                  "subjects": [{
                    "type": "kubernetes_pod",
                    "label": "default/example",
                    "attributes": {"namespace":"default","pod_name":"example"}
                  }]
                }
                """), now, now
        );
    }

    @Test
    void acceptsDeclaredSourcePredicateAndValueSchema() throws Exception {
        var result = validator.validate(event, bundle());

        assertThat(result.domainPack().scopedKey())
            .isEqualTo("kubernetes-pod-diagnostics/1.0.0");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().sourceProfile().reliability()).isEqualTo(0.95);
    }

    @Test
    void rejectsUnknownSourcePredicateWrongValueAndUnsupportedVersion() throws Exception {
        JsonNode unknownSource = bundle();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknownSource.path("evidence_items").path(0))
            .put("source_type", "caller_claimed_reliable");
        assertThatThrownBy(() -> validator.validate(event, unknownSource))
            .isInstanceOf(ApiException.class)
            .extracting(value -> ((ApiException) value).code())
            .isEqualTo("OBSERVATION_BUNDLE_INVALID");

        JsonNode unknownPredicate = bundle();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknownPredicate.path("evidence_items")
            .path(0).path("observations").path(0)).put("predicate", "unknown_predicate");
        assertThatThrownBy(() -> validator.validate(event, unknownPredicate))
            .isInstanceOf(ApiException.class);

        JsonNode wrongValue = bundle();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongValue.path("evidence_items")
            .path(0).path("observations").path(0)).put("value", "yes");
        assertThatThrownBy(() -> validator.validate(event, wrongValue))
            .isInstanceOf(ApiException.class);

        JsonNode callerReliability = bundle();
        ((com.fasterxml.jackson.databind.node.ObjectNode) callerReliability.path("evidence_items").path(0))
            .put("reliability", 1.0);
        assertThatThrownBy(() -> validator.validate(event, callerReliability))
            .isInstanceOf(ApiException.class)
            .extracting(value -> ((ApiException) value).code())
            .isEqualTo("OBSERVATION_BUNDLE_INVALID");

        JsonNode oldVersion = bundle();
        ((com.fasterxml.jackson.databind.node.ObjectNode) oldVersion).put("target_version", "v1.34.9");
        assertThatThrownBy(() -> validator.validate(event, oldVersion))
            .isInstanceOf(ApiException.class)
            .extracting(value -> ((ApiException) value).code())
            .isEqualTo("TARGET_VERSION_UNSUPPORTED");
    }

    @Test
    void rejectsMismatchedSubjectIdentityAndCrossDomainBundle() throws Exception {
        JsonNode wrongSubject = bundle();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongSubject.path("subject").path("attributes"))
            .put("pod_name", "another-pod");
        assertThatThrownBy(() -> validator.validate(event, wrongSubject))
            .isInstanceOf(ApiException.class)
            .extracting(value -> ((ApiException) value).code())
            .isEqualTo("EVIDENCE_SUBJECT_MISMATCH");

        JsonNode crossDomain = bundle();
        ((com.fasterxml.jackson.databind.node.ObjectNode) crossDomain)
            .put("domain_pack", "equipment-fault-test/1.0.0")
            .put("event_type", "equipment_fault");
        assertThatThrownBy(() -> validator.validate(event, crossDomain))
            .isInstanceOf(ApiException.class)
            .extracting(value -> ((ApiException) value).code())
            .isEqualTo("EVENT_DOMAIN_MISMATCH");
    }

    @Test
    void acceptsColdHoldingCollectorFactsAtPackDefinedReliability() throws Exception {
        EventModels.EventDetail coldEvent = coldEvent();
        var result = validator.validate(coldEvent, coldBundle());

        assertThat(result.domainPack().scopedKey())
            .isEqualTo("cold-holding-excursion-diagnostics/1.0.0");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().sourceProfile().reliability()).isEqualTo(0.80);
    }

    @Test
    void coldHoldingEventRequiresAnOrderedTimeRange() throws Exception {
        JsonNode eventIr = coldEvent().eventIr().deepCopy();
        var definition = registry.require("cold-holding-excursion-diagnostics", "1.0.0");

        ((com.fasterxml.jackson.databind.node.ObjectNode) eventIr.path("event"))
            .remove("occurred_at");
        assertThatThrownBy(() -> domainEventValidator.validateEvent(
            eventIr, definition, "cold_holding_temperature_excursion"
        ))
            .isInstanceOf(ApiException.class)
            .extracting(value -> ((ApiException) value).code())
            .isEqualTo("EVENT_DOMAIN_SCHEMA_INVALID");

        JsonNode reversed = coldEvent().eventIr().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) reversed.at("/event/occurred_at"))
            .put("start", "2026-08-28T02:00:00Z")
            .put("end", "2026-08-28T01:00:00Z");
        assertThatThrownBy(() -> domainEventValidator.validateEvent(
            reversed, definition, "cold_holding_temperature_excursion"
        )).isInstanceOf(ApiException.class);
    }

    private JsonNode bundle() throws Exception {
        return mapper.readTree("""
            {
              "schema_version": "observation-bundle/1.0",
              "domain_pack": "kubernetes-pod-diagnostics/1.0.0",
              "event_type": "kubernetes_pod_failure",
              "target_version": "v1.37.0",
              "subject": {
                "type":"kubernetes_pod",
                "label":"default/example",
                "attributes":{"namespace":"default","pod_name":"example"}
              },
              "evidence_items": [{
                "external_id": "pod-status:uid:1",
                "source_type": "kubernetes_api",
                "captured_at": "2026-08-28T00:00:00Z",
                "observations": [{
                  "predicate": "image_pull_backoff",
                  "value": true,
                  "confidence": 1.0,
                  "source_locator": {"kind":"Pod","field":"status.containerStatuses"}
                }]
              }]
            }
            """);
    }

    private EventModels.EventDetail coldEvent() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T00:00:00Z");
        return new EventModels.EventDetail(
            "evt_cold", "COLD-001", "cold_holding_temperature_excursion", "Cold excursion",
            null, "COLLECTING", "cold-holding-excursion-diagnostics/1.0.0",
            null, null, null, null, null, 0, mapper.readTree("""
                {
                  "schema_version": "eventir/0.1",
                  "event": {
                    "type": "cold_holding_temperature_excursion",
                    "title": "一号冷藏间温度异常",
                    "domain_pack": "cold-holding-excursion-diagnostics/1.0.0",
                    "occurred_at": {
                      "start": "2026-08-28T00:00:00Z",
                      "end": "2026-08-28T01:00:00Z"
                    }
                  },
                  "subjects": [{
                    "type": "cold_holding_unit",
                    "label": "site-a/unit-1",
                    "attributes": {
                      "site_id": "site-a",
                      "unit_id": "unit-1",
                      "unit_type": "walk_in_cooler",
                      "temperature_limit_c": 5,
                      "minimum_excursion_minutes": 10,
                      "maximum_sample_gap_minutes": 10,
                      "sensor_tolerance_c": 1,
                      "policy_reference": "现场冷藏运行阈值 v1"
                    }
                  }]
                }
                """), now, now
        );
    }

    private JsonNode coldBundle() throws Exception {
        return mapper.readTree("""
            {
              "schema_version": "observation-bundle/1.0",
              "domain_pack": "cold-holding-excursion-diagnostics/1.0.0",
              "event_type": "cold_holding_temperature_excursion",
              "subject": {
                "type": "cold_holding_unit",
                "label": "site-a/unit-1",
                "attributes": {"site_id":"site-a","unit_id":"unit-1"}
              },
              "evidence_items": [{
                "external_id": "cold-holding:sha256",
                "source_type": "collector_derived",
                "captured_at": "2026-08-28T01:00:00Z",
                "observations": [{
                  "predicate": "power_or_control_interruption_detected",
                  "value": true,
                  "confidence": 1.0,
                  "source_locator": {"kind":"cold_holding_telemetry_summary"}
                }]
              }]
            }
            """);
    }
}
