package dev.reasonweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reasonweave.audit.AuditService;
import dev.reasonweave.domainpack.DomainPackService;
import dev.reasonweave.event.EventService;
import dev.reasonweave.evidence.EvidenceModels;
import dev.reasonweave.evidence.EvidenceService;
import dev.reasonweave.explainability.GraphService;
import dev.reasonweave.investigation.InvestigationModels;
import dev.reasonweave.investigation.InvestigationService;
import dev.reasonweave.knowledge.KnowledgeModels;
import dev.reasonweave.knowledge.KnowledgeService;
import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.ids.IdGenerator;
import dev.reasonweave.shared.ApiException;
import dev.reasonweave.shared.IdempotencyMaintenanceJob;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
class ReasonWeaveIntegrationIT {
    @Autowired JdbcClient jdbc;
    @Autowired KnowledgeService knowledge;
    @Autowired InvestigationService investigations;
    @Autowired IdGenerator ids;
    @Autowired GraphService graphs;
    @Autowired AuditService audit;
    @Autowired DomainPackService domainPacks;
    @Autowired EventService events;
    @Autowired EvidenceService evidence;
    @Autowired IdempotencyMaintenanceJob idempotencyMaintenance;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @Test
    void completesGoldenRetrievalAndAppendOnlyInvestigationChain() {
        String eventId = "evt_fixture_k8s_pod_001";
        long sourceCount = jdbc.sql("""
                select count(*) from knowledge_sources
                where workspace_id = :workspaceId and not fixture_only and production_allowed
                """)
            .param("workspaceId", InstanceScope.ID)
            .query(Long.class).single();
        assertThat(sourceCount).isEqualTo(2);

        assertGoldenQuery(
            "0/3 nodes are available Pod 无法调度",
            "kubernetes-scheduling-debug",
            "pod_unschedulable"
        );
        assertGoldenQuery(
            "ImagePullBackOff ErrImagePull 镜像拉取失败",
            "kubernetes-images",
            "image_pull_backoff"
        );
        assertGoldenQuery(
            "CreateContainerConfigError FailedMount 配置卷挂载失败",
            "kubernetes-scheduling-debug",
            "volume_mount_failed"
        );
        assertGoldenQuery(
            "CrashLoopBackOff OOMKilled 容器反复重启",
            "kubernetes-pod-lifecycle",
            "crash_loop_backoff"
        );
        assertGoldenQuery(
            "readiness probe failed 就绪探针失败",
            "kubernetes-probes",
            "readiness_probe_failed"
        );

        var initialRuns = investigations.listForEvent(eventId, null, 100).items();
        assertThat(initialRuns).isNotEmpty();
        var first = initialRuns.getFirst();
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.result().hypotheses())
            .allMatch(value -> value.groundingStatus().equals("GROUNDED")
                && value.knowledgeLimitations().isEmpty());
        List<String> citationPairs = jdbc.sql("""
                select distinct h.code || ':' || d.external_id
                from knowledge_citations c
                join hypotheses h on h.id = c.target_id
                join knowledge_units u on u.id = c.knowledge_unit_id
                join knowledge_documents d on d.id = u.document_id
                where c.investigation_run_id = :runId
                order by 1
                """)
            .param("runId", first.id())
            .query(String.class)
            .list();
        assertThat(citationPairs)
            .anyMatch(value -> value.equals("image_acquisition_failure:kubernetes-images"));
        assertThat(citationPairs.stream().map(value -> value.substring(0, value.indexOf(':'))).distinct())
            .contains("scheduling_constraint", "image_acquisition_failure",
                "configuration_or_mount_failure", "runtime_or_health_failure");
        String immutableResult = first.result().toString();

        long invalidKnowledgeContributions = jdbc.sql("""
                select count(*) from hypothesis_evidence he
                join hypotheses h on h.id = he.hypothesis_id
                where h.investigation_run_id = :runId
                  and (he.evidence_id is null or he.observation_id is null)
                """)
            .param("runId", first.id())
            .query(Long.class).single();
        assertThat(invalidKnowledgeContributions).isZero();

        long invalidCitations = jdbc.sql("""
                select count(*)
                from knowledge_citations c
                join investigation_runs ir on ir.id = c.investigation_run_id
                where c.investigation_run_id = :runId
                  and not exists (
                    select 1 from retrieval_hits rh
                    where rh.retrieval_run_id = ir.retrieval_run_id
                      and rh.knowledge_unit_id = c.knowledge_unit_id
                      and rh.selected
                  )
                """)
            .param("runId", first.id())
            .query(Long.class).single();
        assertThat(invalidCitations).isZero();

        addCrashLoopEvidence(eventId);
        evidence.createText(
            eventId,
            new EvidenceModels.TextEvidenceRequest(
                "容器启动后立即退出并反复重启", null, null, null
            ),
            "integration-text-evidence"
        );
        var second = investigations.start(
            eventId,
            "integration-investigation-" + ids.next("key"),
            "integration-test"
        );
        assertThat(second.status()).isEqualTo("COMPLETED");
        assertThat(second.sequenceNo()).isEqualTo(first.sequenceNo() + 1);
        assertThat(second.evidenceSnapshotHash()).isNotEqualTo(first.evidenceSnapshotHash());
        assertThat(investigations.get(first.id()).result().toString()).isEqualTo(immutableResult);
        assertThat(investigations.get(first.id()).stale()).isTrue();
        assertThat(investigations.diff(second.id(), first.id()).evidenceSnapshotChanged()).isTrue();
        assertThat(investigations.knowledgeContext(second.id()).citations()).isNotEmpty();
        assertThat(investigations.nextEvidence(second.id()))
            .allMatch(value -> !value.discriminates().isEmpty());
    }

    @Test
    void importsCanonicalObservationBundleIdempotentlyAndRollsBackEveryItemOnConflict() throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String podLabel = "default/bundle-" + suffix;
        var event = events.create(mapper.readTree("""
            {
              "schema_version":"eventir/0.1",
              "event":{
                "type":"kubernetes_pod_failure",
                "title":"Bundle transaction test",
                "reference_code":"EVT-BUNDLE-%s",
                "domain_pack":"kubernetes-pod-diagnostics/1.0.0"
              },
              "subjects":[{
                "id":"subj_primary",
                "type":"kubernetes_pod",
                "label":"%s",
                "attributes":{"namespace":"default","pod_name":"bundle-%s"}
              }],
              "claims":[],"evidence":[],"observations":[],"hypotheses":[],
              "contradictions":[],"unknowns":[]
            }
            """.formatted(suffix, podLabel, suffix)), "bundle-event-" + suffix);

        String existingExternalId = "z-existing-" + suffix;
        JsonNode original = observationBundle(podLabel, existingExternalId, "image_pull_backoff", true);
        var first = evidence.importObservationBundle(event.id(), original, "bundle-first-" + suffix);
        assertThat(first.duplicate()).isFalse();
        assertThat(first.evidence()).hasSize(1);
        assertThat(first.evidence().getFirst().evidence().reliability()).isEqualTo(0.95);
        assertThat(first.evidence().getFirst().observations())
            .hasSize(1)
            .allMatch(value -> value.verificationStatus().equals("PENDING"));

        JsonNode reordered = mapper.readTree("""
            {
              "evidence_items":[{
                "observations":[{
                  "source_locator":{"field":"status.containerStatuses","kind":"Pod"},
                  "confidence":1.0,
                  "value":true,
                  "predicate":"image_pull_backoff"
                }],
                "captured_at":"2026-08-28T00:00:00Z",
                "source_type":"kubernetes_api",
                "external_id":"%s"
              }],
              "subject":{
                "label":"%s","type":"kubernetes_pod",
                "attributes":{"namespace":"default","pod_name":"bundle-%s"}
              },
              "target_version":"v1.37.0",
              "event_type":"kubernetes_pod_failure",
              "domain_pack":"kubernetes-pod-diagnostics/1.0.0",
              "schema_version":"observation-bundle/1.0"
            }
            """.formatted(existingExternalId, podLabel, suffix));
        var replay = evidence.importObservationBundle(event.id(), reordered, "bundle-replay-" + suffix);
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.bundleHash()).isEqualTo(first.bundleHash());
        assertThat(replay.evidence().getFirst().evidence().id())
            .isEqualTo(first.evidence().getFirst().evidence().id());

        JsonNode conflicting = observationBundle(
            podLabel, "a-new-" + suffix, "container_config_error", true
        );
        ((com.fasterxml.jackson.databind.node.ArrayNode) conflicting.path("evidence_items")).add(
            observationBundle(podLabel, existingExternalId, "image_pull_backoff", false)
                .path("evidence_items").get(0).deepCopy()
        );
        assertThatThrownBy(() -> evidence.importObservationBundle(
            event.id(), conflicting, "bundle-conflict-" + suffix
        )).isInstanceOfSatisfying(ApiException.class,
            value -> assertThat(value.code()).isEqualTo("BUNDLE_EXTERNAL_ID_CONFLICT"));

        long rolledBackEvidence = jdbc.sql("""
                select count(*) from evidence
                where event_id = :eventId and workspace_id = :workspaceId
                  and metadata ->> 'external_id' = :externalId
                """)
            .param("eventId", event.id())
            .param("workspaceId", InstanceScope.ID)
            .param("externalId", "a-new-" + suffix)
            .query(Long.class).single();
        assertThat(rolledBackEvidence).isZero();
        long existingEvidence = jdbc.sql("""
                select count(*) from evidence
                where event_id = :eventId and workspace_id = :workspaceId
                  and metadata ->> 'external_id' = :externalId
                """)
            .param("eventId", event.id())
            .param("workspaceId", InstanceScope.ID)
            .param("externalId", existingExternalId)
            .query(Long.class).single();
        assertThat(existingEvidence).isEqualTo(1);
    }

    @Test
    void completesDomainNeutralEquipmentInvestigationThroughPublicApi() throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);

        mockMvc.perform(get("/api/v1/domain-packs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.key == 'equipment-fault-test')]").exists());
        mockMvc.perform(get(
                "/api/v1/domain-packs/equipment-fault-test/versions/1.0.0/event-types/equipment_fault"
            ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.subject_type").value("equipment_asset"))
            .andExpect(jsonPath("$.data.identity_fields[0]").value("asset_id"))
            .andExpect(jsonPath("$.data.evidence_inputs[?(@.type == 'observation_bundle')].enabled")
                .value(hasItem(true)));

        JsonNode eventResponse = mapper.readTree(mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", "equipment-event-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "event_ir": {
                        "schema_version":"eventir/0.1",
                        "event": {
                          "type":"equipment_fault",
                          "title":"循环泵温度异常",
                          "description":"温度传感器报告超过正常范围",
                          "reference_code":"EVT-EQUIPMENT-%s",
                          "domain_pack":"equipment-fault-test/1.0.0"
                        },
                        "subjects":[{
                          "type":"equipment_asset",
                          "label":"pump-%s",
                          "attributes":{"asset_id":"pump-%s","site":"workshop-a"}
                        }]
                      }
                    }
                    """.formatted(suffix, suffix, suffix)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.event_type").value("equipment_fault"))
            .andReturn().getResponse().getContentAsString());
        String eventId = eventResponse.at("/data/id").asText();
        assertThat(eventId).isNotBlank();

        JsonNode bundleResponse = mapper.readTree(mockMvc.perform(
                post("/api/v1/events/{eventId}/evidence/bundles", eventId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "schema_version":"observation-bundle/1.0",
                          "domain_pack":"equipment-fault-test/1.0.0",
                          "event_type":"equipment_fault",
                          "subject": {
                            "type":"equipment_asset",
                            "label":"pump-%s",
                            "attributes":{"asset_id":"pump-%s"}
                          },
                          "evidence_items":[{
                            "external_id":"sensor-export-%s",
                            "source_type":"sensor_export",
                            "captured_at":"2026-08-28T00:00:00Z",
                            "observations":[{
                              "predicate":"temperature_high",
                              "value":true,
                              "confidence":0.98,
                              "source_locator":{"sensor":"temperature-1"}
                            }]
                          }]
                        }
                        """.formatted(suffix, suffix, suffix))
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.evidence[0].evidence.reliability").value(0.9))
            .andExpect(jsonPath("$.data.evidence[0].observations[0].verification_status")
                .value("PENDING"))
            .andReturn().getResponse().getContentAsString());
        JsonNode observation = bundleResponse.at("/data/evidence/0/observations/0");
        String observationId = observation.path("id").asText();

        mockMvc.perform(patch("/api/v1/observations/{id}", observationId)
                .header("If-Match", observation.path("version").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"verification_status\":\"CONFIRMED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.verification_status").value("CONFIRMED"));

        mockMvc.perform(post("/api/v1/retrieval/debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query":"循环泵温度过高",
                      "event_type":"equipment_fault",
                      "observed_predicates":["temperature_high"],
                      "intent":"CAUSE_CANDIDATES",
                      "domain_pack_key":"equipment-fault-test/1.0.0"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.intents[0].hits[0].selected").value(true));

        JsonNode runResponse = mapper.readTree(mockMvc.perform(
                post("/api/v1/events/{eventId}/investigations", eventId)
                    .header("Idempotency-Key", "equipment-investigation-" + suffix)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.result.hypotheses[0].code").value("overheating"))
            .andReturn().getResponse().getContentAsString());
        String runId = runResponse.at("/data/id").asText();

        mockMvc.perform(get("/api/v1/investigations/{id}/next-evidence", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(get("/api/v1/events/{eventId}/graph", eventId)
                .param("investigation_id", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.investigation_run_id").value(runId));
        mockMvc.perform(get("/api/v1/events/{eventId}/audit", eventId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isNotEmpty());
    }

    @Test
    void completesColdHoldingInvestigationThroughPublicApi() throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);

        mockMvc.perform(get("/api/v1/domain-packs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.key == 'cold-holding-excursion-diagnostics')]").exists());
        mockMvc.perform(get(
                "/api/v1/domain-packs/cold-holding-excursion-diagnostics/versions/1.0.0/event-types/cold_holding_temperature_excursion"
            ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.subject_type").value("cold_holding_unit"))
            .andExpect(jsonPath("$.data.identity_fields[0]").value("site_id"))
            .andExpect(jsonPath("$.data.identity_fields[1]").value("unit_id"))
            .andExpect(jsonPath("$.data.event_requirements.time_range").value("required"))
            .andExpect(jsonPath("$.data.evidence_inputs[?(@.type == 'observation_bundle')].enabled")
                .value(hasItem(true)));

        JsonNode eventResponse = mapper.readTree(mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", "cold-event-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(coldEventRequest(suffix)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.event_type").value("cold_holding_temperature_excursion"))
            .andReturn().getResponse().getContentAsString());
        String eventId = eventResponse.at("/data/id").asText();

        JsonNode bundleResponse = mapper.readTree(mockMvc.perform(
                post("/api/v1/events/{eventId}/evidence/bundles", eventId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(coldObservationBundle(
                        suffix,
                        List.of(
                            "temperature_excursion_detected",
                            "operational_heat_load_detected",
                            "prolonged_door_open_overlaps_excursion",
                            "warm_load_introduced_before_excursion"
                        )
                    ).toString())
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.evidence[0].evidence.reliability").value(0.8))
            .andExpect(jsonPath("$.data.evidence[0].observations[0].verification_status")
                .value("PENDING"))
            .andReturn().getResponse().getContentAsString());
        for (JsonNode observation : bundleResponse.at("/data/evidence/0/observations")) {
            mockMvc.perform(patch("/api/v1/observations/{id}", observation.path("id").asText())
                    .header("If-Match", observation.path("version").asText())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"verification_status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verification_status").value("CONFIRMED"));
        }

        mockMvc.perform(post("/api/v1/retrieval/debug")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query":"冷藏间长时间开门后温度升高",
                      "event_type":"cold_holding_temperature_excursion",
                      "observed_predicates":["operational_heat_load_detected"],
                      "intent":"CAUSE_CANDIDATES",
                      "domain_pack_key":"cold-holding-excursion-diagnostics/1.0.0"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.intents[0].hits[?(@.selected == true)]").isNotEmpty());

        JsonNode runResponse = mapper.readTree(mockMvc.perform(
                post("/api/v1/events/{eventId}/investigations", eventId)
                    .header("Idempotency-Key", "cold-investigation-" + suffix)
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.result.hypotheses[0].code")
                .value("operational_heat_load_or_airflow"))
            .andExpect(jsonPath("$.data.result.hypotheses[0].grounding_status").value("GROUNDED"))
            .andReturn().getResponse().getContentAsString());
        String runId = runResponse.at("/data/id").asText();

        mockMvc.perform(get("/api/v1/investigations/{id}/next-evidence", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(get("/api/v1/events/{eventId}/graph", eventId)
                .param("investigation_id", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.investigation_run_id").value(runId))
            .andExpect(jsonPath("$.data.edges[?(@.type == 'GROUNDED_BY' && @.score_affecting == false)]")
                .isNotEmpty());
        mockMvc.perform(get("/api/v1/events/{eventId}/audit", eventId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isNotEmpty());
    }

    @Test
    void ranksColdHoldingGoldenCausesAndLeavesUnattributedExcursionInconclusive() throws Exception {
        List<GoldenInvestigationScenario> scenarios = List.of(
            new GoldenInvestigationScenario(
                "power-control", List.of(
                    "temperature_excursion_detected", "power_or_control_interruption_detected",
                    "power_interruption_overlaps_excursion"
                ), "power_or_control_interruption", List.of("cold-holding-power-interruption")
            ),
            new GoldenInvestigationScenario(
                "refrigeration-response", List.of(
                    "temperature_excursion_detected", "refrigeration_response_failure_detected",
                    "cooling_command_without_recovery", "compressor_or_fan_alarm_present"
                ), "refrigeration_equipment_response_failure", List.of("cold-holding-equipment-process")
            ),
            new GoldenInvestigationScenario(
                "operational-load", List.of(
                    "temperature_excursion_detected", "operational_heat_load_detected",
                    "prolonged_door_open_overlaps_excursion", "warm_load_introduced_before_excursion"
                ), "operational_heat_load_or_airflow", List.of(
                    "cold-holding-equipment-process", "cold-holding-door-airflow-maintenance"
                )
            ),
            new GoldenInvestigationScenario(
                "measurement-system", List.of(
                    "temperature_excursion_detected", "measurement_system_anomaly_detected",
                    "reference_probe_disagrees", "sensor_calibration_invalid_or_expired"
                ), "measurement_system_issue", List.of(
                    "cold-holding-measurement-verification", "cold-holding-monitoring-records"
                )
            )
        );

        for (GoldenInvestigationScenario scenario : scenarios) {
            var run = runColdHoldingScenario(scenario.id(), scenario.predicates());
            var leading = run.result().hypotheses().getFirst();
            assertThat(leading.code()).as(scenario.id()).isEqualTo(scenario.expectedHypothesis());
            assertThat(leading.score()).as(scenario.id()).isGreaterThanOrEqualTo(55);
            assertThat(leading.band()).as(scenario.id())
                .isIn("LEANING_SUPPORTED", "SUPPORTED");
            assertThat(leading.groundingStatus()).as(scenario.id()).isEqualTo("GROUNDED");
            assertThat(leading.citationIds()).as(scenario.id()).isNotEmpty();

            List<String> citedDocuments = jdbc.sql("""
                    select distinct d.external_id
                    from knowledge_citations c
                    join knowledge_units u on u.id = c.knowledge_unit_id
                    join knowledge_documents d on d.id = u.document_id
                    where c.investigation_run_id = :runId and c.target_id = :hypothesisId
                    order by d.external_id
                    """)
                .param("runId", run.id())
                .param("hypothesisId", leading.id())
                .query(String.class)
                .list();
            assertThat(citedDocuments).as(scenario.id())
                .anyMatch(scenario.expectedDocuments()::contains);
        }

        var insufficient = runColdHoldingScenario(
            "insufficient", List.of("temperature_excursion_detected")
        );
        assertThat(insufficient.result().hypotheses())
            .allMatch(value -> value.score() < 50 && value.band().equals("INCONCLUSIVE"));
        assertThat(insufficient.result().hypotheses())
            .allMatch(value -> value.contributions().stream()
                .noneMatch(contribution -> contribution.value() > 0));
    }

    @Test
    void ranksEveryGoldenKubernetesFailureAsTheLeadingGroundedHypothesis() throws Exception {
        List<GoldenInvestigationScenario> scenarios = List.of(
            new GoldenInvestigationScenario(
                "unschedulable", List.of("pod_unschedulable"),
                "scheduling_constraint", List.of("kubernetes-scheduling-debug", "kubernetes-resources")
            ),
            new GoldenInvestigationScenario(
                "image-pull", List.of("image_pull_backoff", "image_pull_error"),
                "image_acquisition_failure", List.of("kubernetes-images")
            ),
            new GoldenInvestigationScenario(
                "configuration-mount", List.of("container_config_error", "volume_mount_failed"),
                "configuration_or_mount_failure", List.of("kubernetes-scheduling-debug")
            ),
            new GoldenInvestigationScenario(
                "runtime-crash", List.of("crash_loop_backoff", "container_exit_nonzero", "restart_count"),
                "runtime_or_health_failure", List.of("kubernetes-pod-lifecycle", "kubernetes-probes")
            )
        );

        for (GoldenInvestigationScenario scenario : scenarios) {
            String suffix = scenario.id() + "-" + Long.toUnsignedString(System.nanoTime(), 36);
            String podLabel = "default/golden-" + suffix;
            var event = events.create(mapper.readTree("""
                {
                  "schema_version":"eventir/0.1",
                  "event":{
                    "type":"kubernetes_pod_failure",
                    "title":"Golden Investigation %s",
                    "domain_pack":"kubernetes-pod-diagnostics/1.0.0"
                  },
                  "subjects":[{
                    "id":"subj_primary",
                    "type":"kubernetes_pod",
                    "label":"%s",
                    "attributes":{"namespace":"default","pod_name":"golden-%s"}
                  }],
                  "claims":[],"evidence":[],"observations":[],"hypotheses":[],
                  "contradictions":[],"unknowns":[]
                }
                """.formatted(scenario.id(), podLabel, suffix)), "golden-event-" + suffix);

            var imported = evidence.importObservationBundle(
                event.id(), observationBundle(podLabel, "golden-" + suffix, scenario.predicates()),
                "golden-bundle-" + suffix
            );
            assertThat(imported.evidence()).hasSize(1);
            assertThat(imported.evidence().getFirst().observations())
                .hasSize(scenario.predicates().size());
            for (var observation : imported.evidence().getFirst().observations()) {
                var confirmed = evidence.verifyObservation(
                    observation.id(),
                    new EvidenceModels.ObservationVerificationRequest("CONFIRMED", null),
                    observation.version(),
                    "golden-confirm-" + observation.id()
                );
                assertThat(confirmed.verificationStatus()).isEqualTo("CONFIRMED");
            }

            var run = investigations.start(
                event.id(), "golden-investigation-" + suffix, "integration-test"
            );
            assertThat(run.status()).isEqualTo("COMPLETED");
            assertThat(run.evidenceSnapshotSchemaVersion()).isEqualTo(2);
            assertThat(run.result()).isNotNull();
            assertThat(run.result().hypotheses()).isNotEmpty();
            var leading = run.result().hypotheses().getFirst();
            assertThat(leading.code()).as(scenario.id()).isEqualTo(scenario.expectedHypothesis());
            assertThat(leading.groundingStatus()).as(scenario.id()).isEqualTo("GROUNDED");
            assertThat(leading.citationIds()).as(scenario.id()).isNotEmpty();

            List<String> citedDocuments = jdbc.sql("""
                    select distinct d.external_id
                    from knowledge_citations c
                    join knowledge_units u on u.id = c.knowledge_unit_id
                    join knowledge_documents d on d.id = u.document_id
                    where c.investigation_run_id = :runId and c.target_id = :hypothesisId
                    order by d.external_id
                    """)
                .param("runId", run.id())
                .param("hypothesisId", leading.id())
                .query(String.class)
                .list();
            assertThat(citedDocuments).as(scenario.id())
                .anyMatch(scenario.expectedDocuments()::contains);
        }
    }

    @Test
    void buildsRunIsolatedGraphWithStableIdsAndNonScoringKnowledgeEdges() {
        String eventId = "evt_fixture_k8s_pod_001";
        var run = investigations.listForEvent(eventId, null, 100).items().getLast();
        var first = graphs.get(eventId, run.id());
        var replay = graphs.get(eventId, run.id());

        assertThat(first.investigationRunId()).isEqualTo(run.id());
        assertThat(first.nodes()).extracting(value -> value.id()).contains("event:" + eventId);
        assertThat(first.nodes().stream().map(value -> value.id()).collect(java.util.stream.Collectors.toSet()))
            .isEqualTo(replay.nodes().stream().map(value -> value.id()).collect(java.util.stream.Collectors.toSet()));
        assertThat(first.edges()).filteredOn(value -> value.type().equals("GROUNDED_BY"))
            .isNotEmpty().allMatch(value -> !value.scoreAffecting() && value.contribution() == null);
        assertThat(first.edges()).filteredOn(value -> value.scoreAffecting())
            .allMatch(value -> Set.of("SUPPORTS", "CONTRADICTS", "RELATES_TO").contains(value.type()));
    }

    @Test
    void pagesFiltersAndExportsAuditAsJsonLines() throws Exception {
        String eventId = "evt_fixture_k8s_pod_001";
        var firstPage = audit.list(eventId, null, 2, null, null, null);
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.nextCursor()).isNotBlank();
        var secondPage = audit.list(eventId, firstPage.nextCursor(), 2, null, null, null);
        assertThat(secondPage.items()).extracting(value -> value.id())
            .doesNotContainAnyElementsOf(firstPage.items().stream().map(value -> value.id()).toList());

        var completed = audit.list(eventId, null, 50, "local_api", "investigation.completed", null);
        assertThat(completed.items()).isNotEmpty().allMatch(value -> value.action().equals("investigation.completed"));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        audit.writeJsonLines(eventId, null, "investigation.completed", null, output);
        String[] lines = output.toString(StandardCharsets.UTF_8).strip().split("\\R");
        assertThat(lines).isNotEmpty();
        assertThat(lines).allMatch(line -> line.contains("investigation.completed") && line.startsWith("{"));
    }

    @Test
    void streamsMoreThanTwoAuditBatchesWithoutGapsOrDuplicates() throws Exception {
        String eventId = "evt_fixture_k8s_pod_001";
        jdbc.sql("""
                insert into audit_events(
                    id, workspace_id, event_id, actor, action, resource,
                    before_state, after_state, request_id, occurred_at
                )
                select 'aud_stream_' || lpad(gs::text, 5, '0'), :workspaceId, :eventId,
                       '{"type":"system","id":"stream-test"}'::jsonb,
                       'audit.export.stream_test',
                       jsonb_build_object('type', 'stream_test', 'id', gs::text),
                       '{}'::jsonb, jsonb_build_object('sequence', gs),
                       'req_stream_test', now() - gs * interval '1 millisecond'
                from generate_series(1, 1201) gs
                on conflict (id) do nothing
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("eventId", eventId)
            .update();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        audit.writeJsonLines(eventId, null, "audit.export.stream_test", null, output);
        String[] lines = output.toString(StandardCharsets.UTF_8).strip().split("\\R");
        assertThat(lines).hasSize(1201);
        Set<String> ids = new HashSet<>();
        OffsetDateTime previous = null;
        for (String line : lines) {
            JsonNode entry = mapper.readTree(line);
            ids.add(entry.path("id").asText());
            OffsetDateTime current = OffsetDateTime.parse(entry.path("occurred_at").asText());
            if (previous != null) {
                assertThat(previous).isAfterOrEqualTo(current);
            }
            previous = current;
        }
        assertThat(ids).hasSize(1201);
    }

    @Test
    void exposesRealKnowledgeAndDomainPackDetailsWithoutInventingEmbeddingProvenance() {
        var source = knowledge.listSources().stream()
            .filter(value -> value.domainPackKey().equals("kubernetes-pod-diagnostics/1.0.0"))
            .findFirst()
            .orElseThrow();
        var detail = knowledge.getSourceDetail(source.id());
        assertThat(detail.documents()).isNotEmpty().allMatch(document -> document.language().equals("zh-CN"));
        assertThat(detail.embeddingProvenance()).isNotNull();
        assertThat(detail.embeddingProvenance().provider()).isEqualTo("mock");
        assertThat(detail.currentIndexVersion()).isNotBlank();

        var units = knowledge.listUnits(source.id(), null, 1);
        assertThat(units.items()).hasSize(1);
        var unit = knowledge.getUnitDetail(units.items().getFirst().id());
        assertThat(unit.source().id()).isEqualTo(source.id());
        assertThat(unit.document().language()).isEqualTo("zh-CN");
        assertThat(unit.embeddingProvenance()).isNotNull();

        var pack = domainPacks.get("kubernetes-pod-diagnostics", "1.0.0");
        assertThat(pack.summary().name()).isEqualTo("Kubernetes Pod 故障诊断");
        assertThat(pack.summary().knowledgeSourceId()).isEqualTo(source.id());
        assertThat(pack.presentation().path("locale").asText()).isEqualTo("zh-CN");
    }

    @Test
    void rebuildsEveryVectorWhenTheEmbeddingIndexProfileChanges() {
        String sourceId = jdbc.sql("""
                select id from knowledge_sources
                where workspace_id = :workspaceId
                  and domain_pack_key = 'kubernetes-pod-diagnostics/1.0.0'
                """)
            .param("workspaceId", InstanceScope.ID)
            .query(String.class)
            .single();
        jdbc.sql("""
                update knowledge_sources
                set embedding_model_digest = 'outdated-model-digest',
                    index_profile_fingerprint = repeat('0', 64)
                where id = :sourceId
                """)
            .param("sourceId", sourceId)
            .update();
        jdbc.sql("update knowledge_units set embedding = null where knowledge_source_id = :sourceId")
            .param("sourceId", sourceId)
            .update();

        knowledge.importInstalledDomainPacks();

        long unitCount = jdbc.sql("select count(*) from knowledge_units where knowledge_source_id = :sourceId")
            .param("sourceId", sourceId)
            .query(Long.class)
            .single();
        long vectorCount = jdbc.sql("""
                select count(*) from knowledge_units
                where knowledge_source_id = :sourceId and embedding is not null
                """)
            .param("sourceId", sourceId)
            .query(Long.class)
            .single();
        String digest = jdbc.sql("select embedding_model_digest from knowledge_sources where id = :sourceId")
            .param("sourceId", sourceId)
            .query(String.class)
            .single();
        String profile = jdbc.sql("select index_profile_fingerprint from knowledge_sources where id = :sourceId")
            .param("sourceId", sourceId)
            .query(String.class)
            .single();
        assertThat(vectorCount).isEqualTo(unitCount).isPositive();
        assertThat(digest).isEqualTo("test-only");
        assertThat(profile).hasSize(64).isNotEqualTo("0".repeat(64));
    }

    @Test
    void pagesKnowledgeCitationAndRetrievalUsageWithOpaqueStableCursors() {
        String runId = investigations.listForEvent("evt_fixture_k8s_pod_001", null, 100)
            .items().getFirst().id();
        String unitId = jdbc.sql("""
                select rh.knowledge_unit_id
                from investigation_runs ir
                join retrieval_hits rh on rh.retrieval_run_id = ir.retrieval_run_id
                where ir.id = :runId and rh.selected
                order by rh.fusion_rank, rh.knowledge_unit_id
                limit 1
                """)
            .param("runId", runId)
            .query(String.class)
            .single();
        String hypothesisId = jdbc.sql("""
                select id from hypotheses where investigation_run_id = :runId order by id limit 1
                """)
            .param("runId", runId)
            .query(String.class)
            .single();
        jdbc.sql("""
                insert into knowledge_citations(
                    id, investigation_run_id, knowledge_unit_id, target_type, target_id,
                    source_locator, source_version, content_hash, usage_reason, created_at
                )
                select 'cit_page_' || lpad(gs::text, 5, '0'), :runId, :unitId,
                       'HYPOTHESIS', :hypothesisId, '{}'::jsonb, '1.0.0',
                       repeat('a', 64), 'pagination test', now() - gs * interval '1 millisecond'
                from generate_series(1, 45) gs
                on conflict (id) do nothing
                """)
            .param("runId", runId)
            .param("unitId", unitId)
            .param("hypothesisId", hypothesisId)
            .update();
        jdbc.sql("""
                insert into retrieval_runs(
                    id, workspace_id, query_plan, retrieval_config,
                    index_version, embedding_model, created_at
                )
                select 'ret_page_' || lpad(gs::text, 5, '0'), :workspaceId,
                       '{}'::jsonb, '{}'::jsonb, 'page-test', 'mock-embedding-1024',
                       now() - gs * interval '1 millisecond'
                from generate_series(1, 45) gs
                on conflict (id) do nothing
                """)
            .param("workspaceId", InstanceScope.ID)
            .update();
        jdbc.sql("""
                insert into retrieval_hits(
                    retrieval_run_id, knowledge_unit_id, query_intent,
                    fusion_rank, fusion_score, applicability_score, selected, selection_reason
                )
                select 'ret_page_' || lpad(gs::text, 5, '0'), :unitId,
                       'CAUSE_CANDIDATES', gs, 0.01, 1.0, true, 'pagination test'
                from generate_series(1, 45) gs
                on conflict (retrieval_run_id, knowledge_unit_id, query_intent) do nothing
                """)
            .param("unitId", unitId)
            .update();

        var citationIds = new ArrayList<String>();
        String citationCursor = null;
        long citationTotal;
        do {
            var page = knowledge.listCitationUsages(unitId, citationCursor, 20);
            citationTotal = page.total();
            citationIds.addAll(page.items().stream().map(value -> value.citationId()).toList());
            citationCursor = page.nextCursor();
        } while (citationCursor != null);
        assertThat(citationIds).hasSize((int) citationTotal).doesNotHaveDuplicates();
        assertThat(citationIds.size()).isGreaterThanOrEqualTo(45);

        var retrievalIds = new ArrayList<String>();
        String retrievalCursor = null;
        long retrievalTotal;
        do {
            var page = knowledge.listRetrievalUsages(unitId, retrievalCursor, 20);
            retrievalTotal = page.total();
            retrievalIds.addAll(page.items().stream()
                .map(value -> value.retrievalRunId() + ":" + value.queryIntent())
                .toList());
            retrievalCursor = page.nextCursor();
        } while (retrievalCursor != null);
        assertThat(retrievalIds).hasSize((int) retrievalTotal).doesNotHaveDuplicates();
        assertThat(retrievalIds.size()).isGreaterThanOrEqualTo(45);
    }

    @Test
    void pagesCoreListsWithFilterBoundCursorsAndNoDuplicates() {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String eventId = "evt_page_root_" + suffix;
        jdbc.sql("""
                insert into events(
                    id, workspace_id, reference_code, event_type, title, status,
                    domain_pack_key, event_ir, created_at, updated_at
                ) values (
                    :id, :workspaceId, :reference, 'kubernetes_pod_failure', '分页 Pod 事件', 'INVESTIGATING',
                    'kubernetes-pod-diagnostics/1.0.0', cast(:eventIr as jsonb), now(), now()
                )
                """)
            .param("id", eventId)
            .param("workspaceId", InstanceScope.ID)
            .param("reference", "PGROOT-" + suffix)
            .param("eventIr", """
                {"schema_version":"eventir/0.1","event":{"type":"kubernetes_pod_failure","title":"分页 Pod 事件","domain_pack":"kubernetes-pod-diagnostics/1.0.0"},"subjects":[]}
                """)
            .update();
        jdbc.sql("""
                insert into events(
                    id, workspace_id, reference_code, event_type, title, status,
                    domain_pack_key, event_ir, created_at, updated_at
                )
                select 'evt_pg_' || :suffix || '_' || lpad(gs::text, 2, '0'),
                       :workspaceId, 'PGE-' || :suffix || '-' || gs,
                       'kubernetes_pod_failure', :title, 'INVESTIGATING', 'kubernetes-pod-diagnostics/1.0.0',
                       jsonb_build_object('schema_version', 'eventir/0.1', 'event',
                          jsonb_build_object('type', 'kubernetes_pod_failure', 'title', :title,
                            'domain_pack', 'kubernetes-pod-diagnostics/1.0.0'), 'subjects', '[]'::jsonb),
                       now() - gs * interval '1 minute', now() - gs * interval '1 minute'
                from generate_series(1, 25) gs
                """)
            .param("suffix", suffix)
            .param("workspaceId", InstanceScope.ID)
            .param("title", "分页事件 " + suffix)
            .update();

        var firstEvents = events.list("分页事件 " + suffix, "", null, 10);
        assertThat(firstEvents.items()).hasSize(10);
        assertThat(firstEvents.total()).isEqualTo(25);
        assertThatThrownBy(() -> events.list("其他筛选", "", firstEvents.nextCursor(), 10))
            .isInstanceOfSatisfying(ApiException.class,
                value -> assertThat(value.code()).isEqualTo("INVALID_CURSOR"));
        assertThatThrownBy(() -> events.list("分页事件 " + suffix, "", null, 0))
            .isInstanceOfSatisfying(ApiException.class,
                value -> assertThat(value.code()).isEqualTo("INVALID_PAGE_LIMIT"));
        jdbc.sql("""
                insert into events(
                    id, workspace_id, reference_code, event_type, title, status,
                    domain_pack_key, event_ir, created_at, updated_at
                ) values (
                    :id, :workspaceId, :reference, 'kubernetes_pod_failure', :title, 'INVESTIGATING',
                    'kubernetes-pod-diagnostics/1.0.0', cast(:eventIr as jsonb), now() + interval '1 minute', now() + interval '1 minute'
                )
                """)
            .param("id", "evt_pg_new_" + suffix)
            .param("workspaceId", InstanceScope.ID)
            .param("reference", "PGENEW-" + suffix)
            .param("title", "分页事件 " + suffix)
            .param("eventIr", """
                {"schema_version":"eventir/0.1","event":{"type":"kubernetes_pod_failure","title":"并发新增","domain_pack":"kubernetes-pod-diagnostics/1.0.0"},"subjects":[]}
                """)
            .update();
        List<String> eventIds = new ArrayList<>(firstEvents.items().stream().map(value -> value.id()).toList());
        String eventCursor = firstEvents.nextCursor();
        while (eventCursor != null) {
            var page = events.list("分页事件 " + suffix, "", eventCursor, 10);
            eventIds.addAll(page.items().stream().map(value -> value.id()).toList());
            eventCursor = page.nextCursor();
        }
        assertThat(eventIds).hasSize(25).doesNotHaveDuplicates().doesNotContain("evt_pg_new_" + suffix);

        jdbc.sql("""
                insert into evidence(
                    id, event_id, workspace_id, type, source, status,
                    content_type, checksum_sha256, reliability, created_at
                )
                select 'ev_pg_' || :suffix || '_' || lpad(gs::text, 2, '0'),
                       :eventId, :workspaceId, 'TEXT', 'TEST', 'NORMALIZED', 'text/plain',
                       repeat(md5(:suffix || gs::text), 2), 0.8,
                       now() - gs * interval '1 minute'
                from generate_series(1, 25) gs
                """)
            .param("suffix", suffix)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .update();
        List<String> evidenceIds = new ArrayList<>();
        String evidenceCursor = null;
        long evidenceTotal;
        do {
            var page = evidence.list(eventId, evidenceCursor, 10);
            evidenceTotal = page.total();
            evidenceIds.addAll(page.items().stream().map(value -> value.id()).toList());
            evidenceCursor = page.nextCursor();
        } while (evidenceCursor != null);
        assertThat(evidenceTotal).isEqualTo(25);
        assertThat(evidenceIds).hasSize(25).doesNotHaveDuplicates();

        jdbc.sql("""
                insert into investigation_runs(
                    id, event_id, workspace_id, sequence_no, status, event_version,
                    evidence_snapshot_hash, model_policy_version, rule_pack_version,
                    knowledge_index_version, event_ir_snapshot, started_at, completed_at, created_at
                )
                select 'inv_pg_' || :suffix || '_' || lpad(gs::text, 2, '0'),
                       :eventId, :workspaceId, gs, 'COMPLETED', 0, repeat('b', 64),
                       'test', 'test', 'test', e.event_ir,
                       now() - gs * interval '1 minute', now() - gs * interval '1 minute',
                       now() - gs * interval '1 minute'
                from generate_series(1, 25) gs cross join events e
                where e.id = :eventId
                """)
            .param("suffix", suffix)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .update();
        List<String> runIds = new ArrayList<>();
        String runCursor = null;
        long runTotal;
        do {
            var page = investigations.listForEvent(eventId, runCursor, 10);
            runTotal = page.total();
            runIds.addAll(page.items().stream().map(value -> value.id()).toList());
            runCursor = page.nextCursor();
        } while (runCursor != null);
        assertThat(runTotal).isEqualTo(25);
        assertThat(runIds).hasSize(25).doesNotHaveDuplicates();
    }

    @Test
    void rejectsSameVersionDomainPackFingerprintDrift() {
        String sourceId = jdbc.sql("""
                select id from knowledge_sources
                where workspace_id = :workspaceId and domain_pack_key = 'kubernetes-pod-diagnostics/1.0.0'
                """)
            .param("workspaceId", InstanceScope.ID)
            .query(String.class)
            .single();
        String fingerprint = jdbc.sql("select pack_fingerprint from knowledge_sources where id = :id")
            .param("id", sourceId)
            .query(String.class)
            .single();
        try {
            jdbc.sql("update knowledge_sources set pack_fingerprint = repeat('0', 64) where id = :id")
                .param("id", sourceId)
                .update();
            assertThatThrownBy(knowledge::importInstalledDomainPacks)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content changed without a version change");
        } finally {
            jdbc.sql("update knowledge_sources set pack_fingerprint = :fingerprint where id = :id")
                .param("fingerprint", fingerprint)
                .param("id", sourceId)
                .update();
        }
    }

    @Test
    void removesOnlyExpiredIdempotencyRecords() {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        jdbc.sql("""
                insert into idempotency_records(
                    workspace_id, endpoint, idempotency_key, request_hash, state, expires_at
                ) values
                    (:workspaceId, :endpoint, :expiredKey, repeat('a', 64), 'COMPLETED', now() - interval '1 minute'),
                    (:workspaceId, :endpoint, :activeKey, repeat('b', 64), 'COMPLETED', now() + interval '1 hour')
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("endpoint", "/test/idempotency/" + suffix)
            .param("expiredKey", "expired-" + suffix)
            .param("activeKey", "active-" + suffix)
            .update();
        assertThat(idempotencyMaintenance.cleanup()).isGreaterThanOrEqualTo(1);
        List<String> remaining = jdbc.sql("""
                select idempotency_key from idempotency_records
                where workspace_id = :workspaceId and endpoint = :endpoint
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("endpoint", "/test/idempotency/" + suffix)
            .query(String.class)
            .list();
        assertThat(remaining).containsExactly("active-" + suffix);
    }

    @Test
    void exposesSnakeCaseOpenApiAndMapsFrameworkClientErrorsToFourHundreds() throws Exception {
        String openApi = mockMvc.perform(get("/api/v1/openapi"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode specification = mapper.readTree(openApi);
        assertThat(specification.at("/info/title").asText()).isEqualTo("ReasonWeave API");
        assertThat(specification.at("/info/version").asText()).isEqualTo("v1");
        assertThat(specification.at("/components/schemas/GraphEdge/properties/score_affecting").isMissingNode())
            .isFalse();
        assertThat(specification.at("/components/schemas/GraphEdge/properties/scoreAffecting").isMissingNode())
            .isTrue();
        assertThat(specification.at("/components/schemas/CreateEventRequest/properties/event_ir").isMissingNode())
            .isFalse();
        assertThat(specification.at("/components/schemas/CreateEventRequest/required").toString())
            .contains("event_ir");
        assertThat(specification.at("/components/schemas/GraphEdge/required").toString())
            .contains("score_affecting");
        assertThat(specification.at("/components/schemas/EventDetail/required").toString())
            .doesNotContain("description");
        assertThat(specification.at("/paths/~1api~1v1~1session").isMissingNode()).isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1runtime/get").isMissingNode()).isFalse();
        assertThat(specification.at("/components/schemas/EventDetail/properties/workspace_id").isMissingNode())
            .isTrue();
        assertThat(specification.at("/components/schemas/EventTypeView/properties/evidence_inputs/type").asText())
            .isEqualTo("array");
        assertThat(specification.at("/components/schemas/DomainPackDetail/properties/event_definitions/type").asText())
            .isEqualTo("array");
        assertThat(specification.at("/components/schemas/EventTypeView/properties/event_requirements/$ref").asText())
            .isEqualTo("#/components/schemas/EventRequirementsView");
        assertThat(specification.at("/components/schemas/EventRequirementsView/properties/time_range/type").asText())
            .isEqualTo("string");
        assertThat(specification.at("/paths/~1api~1v1~1knowledge~1sources/post").isMissingNode())
            .isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1knowledge~1sources~1{sourceId}~1documents/post").isMissingNode())
            .isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1events~1{eventId}~1weather~1normalize").isMissingNode())
            .isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1events~1{eventId}~1evidence~1bundles/post").isMissingNode())
            .isFalse();

        String runId = investigations.listForEvent("evt_fixture_k8s_pod_001", null, 100)
            .items().getFirst().id();
        JsonNode graphPayload = mapper.readTree(mockMvc.perform(
                get("/api/v1/events/evt_fixture_k8s_pod_001/graph")
                    .param("investigation_id", runId)
            )
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        JsonNode serializedEdge = graphPayload.at("/data/edges/0");
        assertThat(serializedEdge.has("score_affecting")).isTrue();
        assertThat(serializedEdge.has("scoreAffecting")).isFalse();

        String requestId = "req_contract_1234";
        mockMvc.perform(get("/api/v1/not-real").header("X-ReasonWeave-Request-Id", requestId))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-ReasonWeave-Request-Id", requestId))
            .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.meta.request_id").value(requestId));
        mockMvc.perform(get("/api/v1/retrieval/debug"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(post("/api/v1/knowledge/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(get("/api/v1/events/evt_fixture_k8s_pod_001/graph"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("MISSING_REQUEST_VALUE"));
        mockMvc.perform(get("/api/v1/events/evt_fixture_k8s_pod_001/audit").param("limit", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_VALUE"));
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.TEXT_PLAIN).content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_JSON"));
        mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", "reference-code-too-long")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"event_ir":{"schema_version":"eventir/0.1","event":{
                      "type":"kubernetes_pod_failure","title":"长度边界",
                      "reference_code":"EVT-AAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                      "domain_pack":"kubernetes-pod-diagnostics/1.0.0"
                    },"subjects":[{
                      "type":"kubernetes_pod","label":"default/length-boundary",
                      "attributes":{"namespace":"default","pod_name":"length-boundary"}
                    }]}}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        String observationId = jdbc.sql("select id from observations order by id limit 1")
            .query(String.class).single();
        mockMvc.perform(patch("/api/v1/observations/{id}", observationId)
                .header("If-Match", "not-a-version")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"verification_status\":\"CONFIRMED\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_VALUE"));
    }

    @Test
    void isolatesKnowledgeDetailsAndUsagePagesByWorkspace() throws Exception {
        jdbc.sql("insert into workspaces(id, name) values ('ws_other', 'Other') on conflict (id) do nothing").update();
        jdbc.sql("""
                insert into knowledge_sources(
                    id, workspace_id, domain_pack_key, name, source_type, version, status
                ) values ('ks_other', 'ws_other', 'other/1.0.0', 'Other source', 'DOMAIN_PACK', '1.0.0', 'PUBLISHED')
                on conflict (id) do nothing
                """).update();
        jdbc.sql("""
                insert into knowledge_documents(
                    id, knowledge_source_id, workspace_id, title, content_type,
                    checksum_sha256, language, parse_status
                ) values (
                    'kd_other', 'ks_other', 'ws_other', 'Other document', 'text/markdown',
                    repeat('d', 64), 'en', 'PARSED'
                ) on conflict (id) do nothing
                """).update();
        jdbc.sql("""
                insert into knowledge_units(
                    id, workspace_id, knowledge_source_id, document_id, domain_pack_key,
                    title, content, search_text, source_locator, source_version,
                    content_hash, status
                ) values (
                    'ku_other', 'ws_other', 'ks_other', 'kd_other', 'other/1.0.0',
                    'Other unit', 'Other content', 'Other content', '{}'::jsonb, '1.0.0',
                    repeat('u', 64), 'PUBLISHED'
                ) on conflict (id) do nothing
                """).update();

        mockMvc.perform(get("/api/v1/knowledge/sources/ks_other"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/knowledge/units/ku_other"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/knowledge/units/ku_other/citation-usages"))
            .andExpect(status().isNotFound());
    }

    private void assertGoldenQuery(
        String query,
        String expectedExternalId,
        String observedPredicate
    ) {
        KnowledgeModels.RetrievalRunView result = knowledge.debug(
            new KnowledgeModels.RetrievalRequest(
                query,
                "kubernetes_pod_failure",
                List.of(observedPredicate),
                "CAUSE_CANDIDATES",
                "kubernetes-pod-diagnostics/1.0.0"
            )
        );
        List<KnowledgeModels.RetrievalHitView> selectedHits = result.intents().getFirst().hits().stream()
            .filter(KnowledgeModels.RetrievalHitView::selected)
            .toList();
        List<String> selectedUnitIds = selectedHits.stream()
            .map(KnowledgeModels.RetrievalHitView::knowledgeUnitId)
            .toList();
        List<String> externalIds = jdbc.sql("""
                select distinct d.external_id
                from knowledge_units u join knowledge_documents d on d.id = u.document_id
                where u.id in (:ids)
                """)
            .param("ids", selectedUnitIds)
            .query(String.class).list();
        assertThat(externalIds).contains(expectedExternalId);
        assertThat(selectedHits).allMatch(value -> value.fusionScore() >= 0.01);
        assertThat(selectedHits.stream().collect(java.util.stream.Collectors.groupingBy(
            KnowledgeModels.RetrievalHitView::documentId,
            java.util.stream.Collectors.counting()
        )).values()).allMatch(count -> count <= 2);
        Set<String> expectedDocumentIds = new HashSet<>(jdbc.sql("""
                select id from knowledge_documents where external_id = :externalId
                """)
            .param("externalId", expectedExternalId)
            .query(String.class)
            .list());
        assertThat(selectedHits)
            .filteredOn(value -> expectedDocumentIds.contains(value.documentId()))
            .isNotEmpty()
            .allMatch(value -> value.applicabilityScore() == 1.25
                && value.applicabilityReason().equals("EVENT_AND_CONTEXT_MATCH")
                && value.expectedPredicates().contains(observedPredicate));
        assertThat(selectedHits)
            .filteredOn(value -> expectedDocumentIds.contains(value.documentId()))
            .anyMatch(value -> value.keywordRank() != null);
        assertThat(selectedHits)
            .filteredOn(value -> expectedDocumentIds.contains(value.documentId()))
            .anyMatch(value -> value.vectorRank() != null);
    }

    private void addCrashLoopEvidence(String eventId) {
        String evidenceId = ids.next("ev");
        String observationId = ids.next("obs");
        jdbc.sql("""
                insert into evidence(
                    id, event_id, workspace_id, type, source, status,
                    content_text, content_type, checksum_sha256, reliability
                ) values (
                    :id, :eventId, :workspaceId, 'TEXT', 'INTEGRATION_TEST', 'VERIFIED',
                    '容器处于 CrashLoopBackOff', 'text/plain', :checksum, 0.95
                )
                """)
            .param("id", evidenceId)
            .param("eventId", eventId)
            .param("workspaceId", InstanceScope.ID)
            .param("checksum", dev.reasonweave.shared.Hashing.sha256(evidenceId))
            .update();
        jdbc.sql("""
                insert into observations(
                    id, evidence_id, workspace_id, predicate, value, description,
                    model_confidence, verification_status, provenance
                ) values (
                    :id, :evidenceId, :workspaceId, 'crash_loop_backoff',
                    'true'::jsonb, '人工确认容器反复重启', 1.0, 'CONFIRMED',
                    '{"adapter":"integration-test"}'::jsonb
                )
                """)
            .param("id", observationId)
            .param("evidenceId", evidenceId)
            .param("workspaceId", InstanceScope.ID)
            .update();
        jdbc.sql("update events set version = version + 1, updated_at = now() where id = :id")
            .param("id", eventId).update();
    }

    private JsonNode observationBundle(
        String podLabel,
        String externalId,
        String predicate,
        boolean value
    ) throws Exception {
        return mapper.readTree("""
            {
              "schema_version":"observation-bundle/1.0",
              "domain_pack":"kubernetes-pod-diagnostics/1.0.0",
              "event_type":"kubernetes_pod_failure",
              "target_version":"v1.37.0",
              "subject":{
                "type":"kubernetes_pod","label":"%s",
                "attributes":{
                  "namespace":"%s",
                  "pod_name":"%s"
                }
              },
              "evidence_items":[{
                "external_id":"%s",
                "source_type":"kubernetes_api",
                "captured_at":"2026-08-28T00:00:00Z",
                "observations":[{
                  "predicate":"%s",
                  "value":%s,
                  "confidence":1.0,
                  "source_locator":{"kind":"Pod","field":"status.containerStatuses"}
                }]
              }]
            }
            """.formatted(
                podLabel,
                podLabel.substring(0, podLabel.indexOf('/')),
                podLabel.substring(podLabel.indexOf('/') + 1),
                externalId,
                predicate,
                value
            ));
    }

    private JsonNode observationBundle(
        String podLabel,
        String externalId,
        List<String> predicates
    ) {
        var root = mapper.createObjectNode();
        root.put("schema_version", "observation-bundle/1.0");
        root.put("domain_pack", "kubernetes-pod-diagnostics/1.0.0");
        root.put("event_type", "kubernetes_pod_failure");
        root.put("target_version", "v1.37.0");
        var subject = root.putObject("subject");
        subject.put("type", "kubernetes_pod");
        subject.put("label", podLabel);
        var subjectAttributes = subject.putObject("attributes");
        subjectAttributes.put("namespace", podLabel.substring(0, podLabel.indexOf('/')));
        subjectAttributes.put("pod_name", podLabel.substring(podLabel.indexOf('/') + 1));
        var item = root.putArray("evidence_items").addObject();
        item.put("external_id", externalId);
        item.put("source_type", "kubernetes_api");
        item.put("captured_at", "2026-08-28T00:00:00Z");
        var observations = item.putArray("observations");
        for (String predicate : predicates) {
            var observation = observations.addObject();
            observation.put("predicate", predicate);
            if (predicate.equals("restart_count")) {
                observation.put("value", 3);
            } else {
                observation.put("value", true);
            }
            observation.put("confidence", 1.0);
            var sourceLocator = observation.putObject("source_locator");
            sourceLocator.put("kind", "Pod");
            sourceLocator.put("field", "status");
        }
        return root;
    }

    private InvestigationModels.InvestigationRunView runColdHoldingScenario(
        String scenarioId,
        List<String> predicates
    ) throws Exception {
        String suffix = scenarioId + "-" + Long.toUnsignedString(System.nanoTime(), 36);
        var event = events.create(
            mapper.readTree(coldEventRequest(suffix)).path("event_ir"),
            "cold-golden-event-" + suffix
        );
        var imported = evidence.importObservationBundle(
            event.id(), coldObservationBundle(suffix, predicates), "cold-golden-bundle-" + suffix
        );
        for (var observation : imported.evidence().getFirst().observations()) {
            evidence.verifyObservation(
                observation.id(),
                new EvidenceModels.ObservationVerificationRequest("CONFIRMED", null),
                observation.version(),
                "cold-golden-confirm-" + observation.id()
            );
        }
        return investigations.start(
            event.id(), "cold-golden-investigation-" + suffix, "integration-test"
        );
    }

    private String coldEventRequest(String suffix) {
        String referenceSuffix = UUID.nameUUIDFromBytes(suffix.getBytes(StandardCharsets.UTF_8))
            .toString()
            .substring(0, 12);
        return """
            {
              "event_ir": {
                "schema_version":"eventir/0.1",
                "event": {
                  "type":"cold_holding_temperature_excursion",
                  "title":"冷藏单元温度异常 %s",
                  "reference_code":"EVT-COLD-%s",
                  "domain_pack":"cold-holding-excursion-diagnostics/1.0.0",
                  "occurred_at": {
                    "start":"2026-08-28T00:00:00Z",
                    "end":"2026-08-28T02:00:00Z"
                  }
                },
                "subjects":[{
                  "type":"cold_holding_unit",
                  "label":"site-a/unit-%s",
                  "attributes": {
                    "site_id":"site-a",
                    "unit_id":"unit-%s",
                    "unit_type":"walk_in_cooler",
                    "temperature_limit_c":5,
                    "minimum_excursion_minutes":15,
                    "maximum_sample_gap_minutes":10,
                    "sensor_tolerance_c":1,
                    "policy_reference":"现场运行阈值 v1"
                  }
                }]
              }
            }
            """.formatted(suffix, referenceSuffix, suffix, suffix);
    }

    private JsonNode coldObservationBundle(String suffix, List<String> predicates) {
        var root = mapper.createObjectNode();
        root.put("schema_version", "observation-bundle/1.0");
        root.put("domain_pack", "cold-holding-excursion-diagnostics/1.0.0");
        root.put("event_type", "cold_holding_temperature_excursion");
        var subject = root.putObject("subject");
        subject.put("type", "cold_holding_unit");
        subject.put("label", "site-a/unit-" + suffix);
        var subjectAttributes = subject.putObject("attributes");
        subjectAttributes.put("site_id", "site-a");
        subjectAttributes.put("unit_id", "unit-" + suffix);
        var item = root.putArray("evidence_items").addObject();
        item.put("external_id", "cold-collector-" + suffix);
        item.put("source_type", "collector_derived");
        item.put("captured_at", "2026-08-28T02:00:00Z");
        var observations = item.putArray("observations");
        for (String predicate : predicates) {
            var observation = observations.addObject();
            observation.put("predicate", predicate);
            observation.put("value", true);
            observation.put("confidence", 1.0);
            var locator = observation.putObject("source_locator");
            locator.put("kind", "cold_holding_telemetry_summary");
            locator.put("algorithm_version", "cold-holding-collector/1.0.0");
        }
        return root;
    }

    private record GoldenInvestigationScenario(
        String id,
        List<String> predicates,
        String expectedHypothesis,
        List<String> expectedDocuments
    ) {}
}
