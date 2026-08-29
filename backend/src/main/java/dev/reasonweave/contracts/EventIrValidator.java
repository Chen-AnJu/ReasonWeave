package dev.reasonweave.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.reasonweave.shared.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class EventIrValidator {
    private final JsonSchema schema;

    public EventIrValidator() throws IOException {
        ClassPathResource resource = new ClassPathResource("contracts/eventir/eventir-0.1.schema.json");
        try (InputStream input = resource.getInputStream()) {
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(input);
        }
    }

    public void validate(JsonNode eventIr) {
        Set<ValidationMessage> messages = schema.validate(eventIr);
        if (!messages.isEmpty()) {
            List<String> errors = messages.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "EVENT_IR_SCHEMA_INVALID",
                "EventIR 不符合 eventir/0.1 Schema",
                Map.of("errors", errors)
            );
        }
    }
}
