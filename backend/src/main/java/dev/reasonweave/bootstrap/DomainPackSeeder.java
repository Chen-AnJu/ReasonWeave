package dev.reasonweave.bootstrap;

import dev.reasonweave.knowledge.KnowledgeService;
import dev.reasonweave.model.ModelGateway.ModelGatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!openapi-export")
@Order(2)
public class DomainPackSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DomainPackSeeder.class);
    private final KnowledgeService knowledge;

    public DomainPackSeeder(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            knowledge.importInstalledDomainPacks();
        } catch (ModelGatewayException exception) {
            log.warn(
                "Domain Pack knowledge indexing is not ready because the Embedding provider failed: {}",
                exception.getMessage()
            );
        }
    }
}
