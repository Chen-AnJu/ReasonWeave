package dev.reasonweave.bootstrap;

import dev.reasonweave.runtime.InstanceScope;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!openapi-export")
@Order(1)
public class BootstrapSeeder implements ApplicationRunner {
    private final JdbcClient jdbc;

    public BootstrapSeeder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.sql("""
                insert into workspaces(id, name)
                values (:id, :name)
                on conflict (id) do nothing
                """)
            .param("id", InstanceScope.ID)
            .param("name", "本地实例")
            .update();
    }
}
