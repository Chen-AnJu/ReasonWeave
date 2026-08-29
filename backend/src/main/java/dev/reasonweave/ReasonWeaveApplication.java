package dev.reasonweave;

import dev.reasonweave.config.ReasonWeaveProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(ReasonWeaveProperties.class)
public class ReasonWeaveApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReasonWeaveApplication.class, args);
    }
}
