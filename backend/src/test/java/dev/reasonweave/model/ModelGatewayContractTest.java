package dev.reasonweave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.reasonweave.config.ReasonWeaveProperties;
import dev.reasonweave.domainpack.DomainPackRegistry;
import dev.reasonweave.domainpack.DomainPackValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelGatewayContractTest {
    @Test
    void mockEmbeddingIsDeterministicAndExactly1024Dimensions() throws Exception {
        ReasonWeaveProperties properties = properties();
        ObjectMapper mapper = new ObjectMapper();
        ModelGateway gateway = new ModelGateway(
            properties,
            mapper,
            HttpClient.newHttpClient(),
            validator(properties, mapper),
            new SimpleMeterRegistry()
        );

        double[] first = gateway.embedding("ImagePullBackOff 镜像拉取失败");
        double[] second = gateway.embedding("ImagePullBackOff 镜像拉取失败");

        assertThat(first).hasSize(1024).containsExactly(second);
        assertThat(java.util.Arrays.stream(first).anyMatch(value -> value != 0)).isTrue();
    }

    @Test
    void rejectsMalformedAndOutOfVocabularyVisionOutputs() throws Exception {
        ReasonWeaveProperties properties = properties();
        ObjectMapper mapper = new ObjectMapper();
        VisionOutputValidator validator = validator(properties, mapper);

        assertThatThrownBy(() -> validator.validate(mapper.readTree("""
            {"limitations": []}
            """), "kubernetes-pod-diagnostics/1.0.0")).isInstanceOf(ModelGateway.ModelOutputValidationException.class);
        assertThatThrownBy(() -> validator.validate(mapper.readTree("""
            {"observations":[{"predicate":"image_pull_error","value":true,"confidence":"high","description":"pull"}],"limitations":[]}
            """), "kubernetes-pod-diagnostics/1.0.0")).isInstanceOf(ModelGateway.ModelOutputValidationException.class);
        assertThatThrownBy(() -> validator.validate(mapper.readTree("""
            {"observations":[{"predicate":"image_pull_error","value":true,"confidence":1.1,"description":"pull"}],"limitations":[]}
            """), "kubernetes-pod-diagnostics/1.0.0")).isInstanceOf(ModelGateway.ModelOutputValidationException.class);
        assertThatThrownBy(() -> validator.validate(mapper.readTree("""
            {"observations":[{"predicate":"unknown_model_predicate","value":true,"confidence":0.8,"description":"unknown"}],"limitations":[]}
            """), "kubernetes-pod-diagnostics/1.0.0")).isInstanceOf(ModelGateway.ModelOutputValidationException.class);
        assertThatThrownBy(() -> validator.validate(mapper.readTree("""
            {"observations":[{"predicate":"image_pull_error","value":true,"confidence":0.8,"description":"pull","extra":true}],"limitations":[]}
            """), "kubernetes-pod-diagnostics/1.0.0")).isInstanceOf(ModelGateway.ModelOutputValidationException.class);

        assertThat(validator.validate(mapper.readTree("""
            {"observations":[{"predicate":"image_pull_error","value":true,"confidence":0.8,"description":"pull"}],"limitations":[]}
        """), "kubernetes-pod-diagnostics/1.0.0")).hasSize(1);
    }

    @Test
    void rejectsWrongDimensionNonNumericAndNonFiniteEmbeddings() throws Exception {
        AtomicReference<String> responseBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            ReasonWeaveProperties properties = providerProperties(
                "http://127.0.0.1:" + server.getAddress().getPort()
            );
            ModelGateway gateway = new ModelGateway(
                properties,
                mapper,
                HttpClient.newHttpClient(),
                validator(properties, mapper),
                new SimpleMeterRegistry()
            );

            responseBody.set("{\"data\":[{\"embedding\":[0.0]}]}");
            assertThatThrownBy(() -> gateway.embedding("dimension"))
                .isInstanceOf(ModelGateway.ModelGatewayException.class)
                .hasMessageContaining("dimension mismatch");

            responseBody.set(embeddingResponse(1024, "\"not-a-number\""));
            assertThatThrownBy(() -> gateway.embedding("type"))
                .isInstanceOf(ModelGateway.ModelGatewayException.class)
                .hasMessageContaining("not numeric");

            responseBody.set(embeddingResponse(1024, "1e309"));
            assertThatThrownBy(() -> gateway.embedding("finite"))
                .isInstanceOf(ModelGateway.ModelGatewayException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validatesOllamaBatchEmbeddingShapeAndDimension() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> responseBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        String modelDigest = "a".repeat(64);
        server.createContext("/api/tags", exchange -> {
            byte[] body = ("{\"models\":[{\"name\":\"qwen3-embedding:0.6b\","
                + "\"model\":\"qwen3-embedding:0.6b\",\"digest\":\""
                + modelDigest + "\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            ReasonWeaveProperties properties = ollamaProperties(
                "http://127.0.0.1:" + server.getAddress().getPort()
            );
            ModelGateway gateway = new ModelGateway(
                properties,
                mapper,
                HttpClient.newHttpClient(),
                validator(properties, mapper),
                new SimpleMeterRegistry()
            );

            assertThat(gateway.embeddingModelDigest()).isEqualTo(modelDigest);
            assertThat(gateway.embeddingProductionReady()).isTrue();

            String vector = vector(1024, "0.0");
            responseBody.set("{\"embeddings\":[[" + vector + "],[" + vector + "]]}");
            assertThat(gateway.embeddingDocuments(List.of("pod", "image")))
                .hasSize(2)
                .allSatisfy(value -> assertThat(value).hasSize(1024));
            assertThat(requestBody.get()).contains("\"input\":[\"pod\",\"image\"]");

            responseBody.set("{\"embeddings\":[[" + vector + "]]}");
            assertThatThrownBy(() -> gateway.embeddingDocuments(List.of("pod", "image")))
                .isInstanceOf(ModelGateway.ModelGatewayException.class)
                .hasMessageContaining("response count mismatch")
                .hasMessageContaining("expected 2, got 1");

            responseBody.set("{\"embeddings\":[[0.0],[0.0]]}");
            assertThatThrownBy(() -> gateway.embeddingDocuments(List.of("pod", "image")))
                .isInstanceOf(ModelGateway.ModelGatewayException.class)
                .hasMessageContaining("dimension mismatch");
        } finally {
            server.stop(0);
        }
    }

    private static VisionOutputValidator validator(
        ReasonWeaveProperties properties,
        ObjectMapper mapper
    ) throws Exception {
        DomainPackRegistry registry = new DomainPackRegistry(properties, new DomainPackValidator(mapper));
        registry.load();
        return new VisionOutputValidator(mapper, registry);
    }

    private static ReasonWeaveProperties properties() {
        return new ReasonWeaveProperties(
            "ReasonWeave Test", true, "./data/blob", "../domain-packs", "../fixtures",
            "http://localhost:5173",
            new ReasonWeaveProperties.Vision(
                "mock", "https://example.invalid/v1", "/chat/completions", "mock-vision", ""
            ),
            new ReasonWeaveProperties.Embedding(
                "mock", "http://localhost:11434", "/api/embed",
                "qwen3-embedding:0.6b", 1024, "test-only", "query:", ""
            )
        );
    }

    private static ReasonWeaveProperties providerProperties(String baseUrl) {
        return new ReasonWeaveProperties(
            "ReasonWeave Test", true, "./data/blob", "../domain-packs", "../fixtures",
            "http://localhost:5173",
            new ReasonWeaveProperties.Vision(
                "mock", "https://example.invalid/v1", "/chat/completions", "mock-vision", ""
            ),
            new ReasonWeaveProperties.Embedding(
                "openai-compatible", baseUrl, "/embeddings", "test-embedding-1024",
                1024, "sha256:test", "query:", "test-key"
            )
        );
    }

    private static ReasonWeaveProperties ollamaProperties(String baseUrl) {
        return new ReasonWeaveProperties(
            "ReasonWeave Test", true, "./data/blob", "../domain-packs", "../fixtures",
            "http://localhost:5173",
            new ReasonWeaveProperties.Vision(
                "mock", "https://example.invalid/v1", "/chat/completions", "mock-vision", ""
            ),
            new ReasonWeaveProperties.Embedding(
                "ollama", baseUrl, "/api/embed", "qwen3-embedding:0.6b",
                1024, "unverified", "", ""
            )
        );
    }

    private static String embeddingResponse(int dimensions, String finalValue) {
        return "{\"data\":[{\"embedding\":[" + vector(dimensions, finalValue) + "]}]}";
    }

    private static String vector(int dimensions, String finalValue) {
        StringBuilder values = new StringBuilder();
        for (int index = 0; index < dimensions; index++) {
            if (index > 0) {
                values.append(',');
            }
            values.append(index == dimensions - 1 ? finalValue : "0.0");
        }
        return values.toString();
    }
}
