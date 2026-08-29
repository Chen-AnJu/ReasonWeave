package dev.reasonweave.domainpack;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reasonweave.knowledge.KnowledgeService;
import dev.reasonweave.runtime.InstanceScope;
import dev.reasonweave.shared.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class DomainPackService {
    private final JdbcClient jdbc;
    private final DomainPackRegistry registry;
    private final KnowledgeService knowledge;

    public DomainPackService(
        JdbcClient jdbc,
        DomainPackRegistry registry,
        KnowledgeService knowledge
    ) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.knowledge = knowledge;
    }

    public List<DomainPackModels.DomainPackSummary> list() {
        return registry.all().stream().map(this::summary).toList();
    }

    public DomainPackModels.DomainPackDetail get(String key, String version) {
        DomainPackDefinition definition = registry.require(key, version);
        DomainPackCatalog catalog = definition.content();
        JsonNode manifest = catalog.manifest();
        List<String> warnings = new ArrayList<>();
        if (manifest.path("fixture_only").asBoolean(false)) {
            warnings.add("此领域包仅供开发与测试，不可作为行业标准或生产判断依据。");
        }
        if (!definition.productionAllowed()) {
            warnings.add("此领域包未声明为可用于生产调查。");
        }
        return new DomainPackModels.DomainPackDetail(
            summary(definition), manifest,
            definition.eventTypes().stream().sorted().map(eventType -> eventTypeView(definition, eventType)).toList(),
            catalog.vocabulary(), catalog.presentation(), catalog.hypotheses(),
            catalog.rules(), catalog.nextEvidence(), catalog.retrievalConfig(),
            catalog.knowledgeMetadata(), List.copyOf(warnings)
        );
    }

    public DomainPackModels.EventTypeView getEventType(String key, String version, String eventType) {
        DomainPackDefinition definition = registry.require(key, version);
        if (!definition.supportsEventType(eventType)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "领域包事件类型不存在");
        }
        return eventTypeView(definition, eventType);
    }

    private DomainPackModels.EventTypeView eventTypeView(
        DomainPackDefinition definition,
        String eventType
    ) {
        DomainPackDefinition.EventTypeDefinition event = definition.requireEventTypeDefinition(eventType);
        JsonNode manifest = definition.content().manifest();
        JsonNode eventPresentation = definition.content().presentation().path("event_types").path(eventType);
        List<String> requiredFields = new ArrayList<>();
        event.attributesSchema().path("required").forEach(value -> requiredFields.add(value.asText()));
        List<DomainPackModels.FieldPresentationView> fields = new ArrayList<>();
        eventPresentation.path("fields").fields().forEachRemaining(entry -> {
            List<DomainPackModels.FieldOptionView> options = new ArrayList<>();
            entry.getValue().path("options").forEach(option -> {
                if (option.isTextual()) {
                    options.add(new DomainPackModels.FieldOptionView(option.asText(), option.asText()));
                }
                else if (option.isObject() && option.path("value").isTextual()) {
                    options.add(new DomainPackModels.FieldOptionView(
                        option.path("value").asText(),
                        option.path("label").asText(option.path("value").asText())
                    ));
                }
            });
            fields.add(new DomainPackModels.FieldPresentationView(
                entry.getKey(),
                entry.getValue().path("label").asText(entry.getKey()),
                entry.getValue().path("control").asText("text"),
                optionalText(entry.getValue(), "placeholder"),
                requiredFields.contains(entry.getKey()),
                List.copyOf(options)
            ));
        });

        List<DomainPackModels.EvidenceInputView> evidenceInputs = new ArrayList<>();
        event.evidenceInputs().fields().forEachRemaining(entry -> {
            JsonNode configuration = entry.getValue();
            JsonNode inputPresentation = eventPresentation.path("evidence_inputs").path(entry.getKey());
            String sourceProfile = optionalText(configuration, "source_profile");
            Double sourceReliability = sourceProfile == null
                ? null
                : definition.sourceProfiles().get(sourceProfile).reliability();
            String verificationStatus = optionalText(configuration, "verification_status");
            List<String> contentTypes = new ArrayList<>();
            configuration.path("content_types").forEach(value -> contentTypes.add(value.asText()));
            evidenceInputs.add(new DomainPackModels.EvidenceInputView(
                entry.getKey(),
                configuration.path("enabled").asBoolean(false),
                sourceProfile,
                sourceReliability,
                optionalText(configuration, "predicate"),
                verificationStatus,
                "PENDING".equals(verificationStatus),
                List.copyOf(contentTypes),
                optionalText(inputPresentation, "label"),
                optionalText(inputPresentation, "help"),
                optionalText(inputPresentation, "collector_command")
            ));
        });
        return new DomainPackModels.EventTypeView(
            definition.scopedKey(),
            event.eventType(),
            event.subjectType(),
            event.identityFields(),
            event.labelTemplate(),
            event.attributesSchema(),
            new DomainPackModels.EventRequirementsView(event.timeRangeRequirement()),
            List.copyOf(evidenceInputs),
            targetVersion(manifest.path("supported_target_versions")),
            new DomainPackModels.EventPresentationView(
                eventPresentation.path("label").asText(event.eventType()),
                eventPresentation.path("subject_label").asText(event.subjectType()),
                List.copyOf(fields)
            )
        );
    }

    private DomainPackModels.TargetVersionView targetVersion(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return null;
        }
        return new DomainPackModels.TargetVersionView(
            node.path("scheme").asText("semver"),
            optionalText(node, "minimum"),
            optionalText(node, "maximum_exclusive")
        );
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private DomainPackModels.DomainPackSummary summary(DomainPackDefinition definition) {
        DomainPackCatalog catalog = definition.content();
        JsonNode manifest = catalog.manifest();
        JsonNode presentation = catalog.presentation();
        String key = manifest.path("key").asText();
        String version = manifest.path("version").asText();
        String scopedKey = key + "/" + version;
        Map<String, Object> index = jdbc.sql("""
                select s.id, s.status,
                       (select count(*) from knowledge_documents d where d.knowledge_source_id = s.id) document_count,
                       (select count(*) from knowledge_units u where u.knowledge_source_id = s.id) unit_count
                from knowledge_sources s
                where s.workspace_id = :workspaceId and s.domain_pack_key = :domainPackKey
                  and s.version = :version
                order by s.created_at desc
                limit 1
                """)
            .param("workspaceId", InstanceScope.ID)
            .param("domainPackKey", scopedKey)
            .param("version", version)
            .query((rs, rowNum) -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", rs.getString("id"));
                value.put("status", rs.getString("status"));
                value.put("documents", rs.getInt("document_count"));
                value.put("units", rs.getInt("unit_count"));
                return value;
            })
            .optional()
            .orElse(Map.of());
        int unitCount = (Integer) index.getOrDefault("units", 0);
        List<String> readinessReasons = new ArrayList<>();
        readinessReasons.addAll(knowledge.indexReadinessReasons(definition, false));
        if (!definition.productionAllowed()) readinessReasons.add("PRODUCTION_NOT_ALLOWED");
        return new DomainPackModels.DomainPackSummary(
            key,
            version,
            presentation.path("name").asText(manifest.path("name").asText()),
            presentation.path("description").asText(""),
            index.isEmpty() ? "NOT_INDEXED" : index.get("status").toString(),
            manifest.path("fixture_only").asBoolean(true),
            manifest.path("production_allowed").asBoolean(false),
            manifest.path("compatible_eventir").asText(),
            catalog.hypotheses().path("hypotheses").size(),
            catalog.rules().path("rules").size(),
            (Integer) index.getOrDefault("documents", 0),
            unitCount,
            (String) index.get("id"),
            index.isEmpty() ? null : knowledge.currentIndexVersion(scopedKey),
            presentation.path("locale").asText("zh-CN"),
            definition.fingerprint(),
            definition.eventTypes().stream().sorted().toList(),
            definition.vectorPolicy(),
            manifest.path("observation_bundle"),
            targetVersion(manifest.path("supported_target_versions")),
            manifest.path("source_profiles"),
            catalog.yaml("LICENSES.yaml"),
            readinessReasons.isEmpty(),
            List.copyOf(readinessReasons)
        );
    }
}
