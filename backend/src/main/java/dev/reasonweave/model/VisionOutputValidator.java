package dev.reasonweave.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.reasonweave.domainpack.DomainPackDefinition;
import dev.reasonweave.domainpack.DomainPackRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class VisionOutputValidator {
    public static final String SCHEMA_VERSION = "vision-observations/1";
    private static final String RESOURCE = "contracts/model/vision-observations-1.schema.json";
    private final JsonSchema schema;
    private final JsonNode schemaDefinition;
    private final DomainPackRegistry domainPacks;

    public VisionOutputValidator(ObjectMapper mapper, DomainPackRegistry domainPacks) throws IOException {
        this.domainPacks = domainPacks;
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            this.schemaDefinition = mapper.readTree(input);
        }
        this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(schemaDefinition);
    }

    public JsonNode schemaDefinition() {
        return schemaDefinition.deepCopy();
    }

    public List<ModelGateway.ObservationDraft> validate(JsonNode payload, String domainPackKey) {
        Set<ValidationMessage> messages = schema.validate(payload);
        if (!messages.isEmpty()) {
            List<String> errors = messages.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .limit(20)
                .toList();
            throw new ModelGateway.ModelOutputValidationException(
                "Vision output schema validation failed: " + String.join("; ", errors)
            );
        }

        DomainPackDefinition definition = domainPacks.require(domainPackKey);
        Set<String> allowedPredicates = vocabularyPredicates(definition);
        List<ModelGateway.ObservationDraft> drafts = new ArrayList<>();
        for (JsonNode observation : payload.path("observations")) {
            String predicate = observation.path("predicate").asText();
            if (!allowedPredicates.contains(predicate)) {
                throw new ModelGateway.ModelOutputValidationException(
                    "Vision output predicate is not in the active Domain Pack vocabulary: " + predicate
                );
            }
            drafts.add(new ModelGateway.ObservationDraft(
                predicate,
                observation.path("value").deepCopy(),
                observation.path("description").asText(),
                observation.path("confidence").asDouble()
            ));
        }
        return List.copyOf(drafts);
    }

    private static Set<String> vocabularyPredicates(DomainPackDefinition definition) {
        Set<String> values = new LinkedHashSet<>();
        definition.content().vocabulary().path("predicates").fieldNames().forEachRemaining(values::add);
        return Set.copyOf(values);
    }
}
