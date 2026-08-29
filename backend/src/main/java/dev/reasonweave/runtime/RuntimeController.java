package dev.reasonweave.runtime;

import dev.reasonweave.config.ReasonWeaveProperties;
import dev.reasonweave.shared.ApiEnvelope;
import dev.reasonweave.shared.RequestIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runtime")
@Tag(name = "运行实例", description = "自托管实例与当前能力边界")
public class RuntimeController {
    private final ReasonWeaveProperties properties;

    public RuntimeController(ReasonWeaveProperties properties) {
        this.properties = properties;
    }

    public record RuntimeView(
        String apiVersion,
        String deploymentMode,
        String instanceName,
        Map<String, Boolean> capabilities
    ) {}

    @GetMapping
    @Operation(summary = "读取本地实例能力")
    ApiEnvelope<RuntimeView> runtime(HttpServletRequest request) {
        return ApiEnvelope.of(
            new RuntimeView(
                "v1",
                "self_hosted",
                properties.instanceName(),
                Map.of(
                    "authentication", false,
                    "multi_tenancy", false,
                    "synchronous_investigation", true,
                    "domain_pack_registry", true
                )
            ),
            RequestIds.current(request)
        );
    }
}
