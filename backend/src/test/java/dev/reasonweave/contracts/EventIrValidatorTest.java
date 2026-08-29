package dev.reasonweave.contracts;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reasonweave.shared.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EventIrValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventIrValidator validator = createValidator();

    @Test
    void acceptsBundledEventIrFixture() throws Exception {
        var fixture = mapper.readTree(Files.readString(
            Path.of("../fixtures/eventir/kubernetes-pod-image-pull.json")
        ));
        assertThatCode(() -> validator.validate(fixture)).doesNotThrowAnyException();
    }

    @Test
    void rejectsPayloadWithoutRequiredSubjects() throws Exception {
        var invalid = mapper.readTree("""
            {"schema_version":"eventir/0.1","event":{"type":"kubernetes_pod_failure","title":"x"}}
            """);
        assertThatThrownBy(() -> validator.validate(invalid))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("EventIR");
    }

    private static EventIrValidator createValidator() {
        try {
            return new EventIrValidator();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
