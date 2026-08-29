package dev.reasonweave.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rw")
public record ReasonWeaveProperties(
    @DefaultValue("ReasonWeave") String instanceName,
    @DefaultValue("false") boolean seedFixtures,
    @DefaultValue("./data/blob") String blobRoot,
    @DefaultValue("../domain-packs") String domainPackRoots,
    @DefaultValue("../fixtures") String fixtureRoot,
    @DefaultValue("http://localhost:5173") String corsAllowedOrigins,
    Vision vision,
    Embedding embedding
) {
    public record Vision(
        @DefaultValue("mock") String provider,
        @DefaultValue("https://api.example.invalid/v1") String baseUrl,
        @DefaultValue("/chat/completions") String chatPath,
        @DefaultValue("mock-vision-v1") String model,
        @DefaultValue("") String apiKey
    ) {}

    public record Embedding(
        @DefaultValue("mock") String provider,
        @DefaultValue("http://localhost:11434") String baseUrl,
        @DefaultValue("/api/embed") String path,
        @DefaultValue("qwen3-embedding:0.6b") String model,
        @DefaultValue("1024") int dimension,
        @DefaultValue("unverified") String modelDigest,
        @DefaultValue("为当前领域事件检索权威且可追溯的知识：") String queryInstruction,
        @DefaultValue("") String apiKey
    ) {}

}
