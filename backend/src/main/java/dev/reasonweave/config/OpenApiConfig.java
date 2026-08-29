package dev.reasonweave.config;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI reasonWeaveOpenApi() {
        return new OpenAPI().info(new Info()
            .title("ReasonWeave API")
            .version("v1")
            .description("Self-hosted, Domain Pack driven evidence reasoning API."));
    }

    @Bean
    ModelConverter snakeCaseSchemaPropertyConverter(ObjectMapper applicationObjectMapper) {
        return new JacksonPropertyNamingConverter(applicationObjectMapper);
    }

    private static final class JacksonPropertyNamingConverter implements ModelConverter {
        private final ObjectMapper applicationObjectMapper;

        private JacksonPropertyNamingConverter(ObjectMapper applicationObjectMapper) {
            this.applicationObjectMapper = applicationObjectMapper;
        }

        @Override
        public Schema<?> resolve(
            AnnotatedType type,
            ModelConverterContext context,
            Iterator<ModelConverter> chain
        ) {
            JavaType requestedType = applicationObjectMapper.constructType(type.getType());
            if (requestedType != null && JsonNode.class.isAssignableFrom(requestedType.getRawClass())) {
                return new Schema<>();
            }
            if (!chain.hasNext()) {
                return null;
            }
            Schema<?> resolved = chain.next().resolve(type, context, chain);
            if (resolved == null) {
                return null;
            }

            Schema<?> target = resolved;
            if (resolved.get$ref() != null && resolved.get$ref().startsWith(Components.COMPONENTS_SCHEMAS_REF)) {
                String name = resolved.get$ref().substring(Components.COMPONENTS_SCHEMAS_REF.length());
                target = context.getDefinedModels().get(name);
            }
            if (target == null || target.getProperties() == null) {
                return resolved;
            }

            JavaType javaType = requestedType;
            if (javaType == null || javaType.getRawClass().getPackageName().startsWith("java.")) {
                return resolved;
            }
            Map<String, String> renames = renameProperties(target, javaType);
            markRequiredRecordProperties(target, javaType, renames);
            return resolved;
        }

        private Map<String, String> renameProperties(Schema<?> schema, JavaType javaType) {
            BeanDescription description;
            try {
                description = applicationObjectMapper.getSerializationConfig().introspect(javaType);
            }
            catch (RuntimeException exception) {
                return Map.of();
            }

            Map<String, String> renames = new LinkedHashMap<>();
            for (BeanPropertyDefinition property : description.findProperties()) {
                String externalName = property.getName();
                String internalName = property.getInternalName();
                if (externalName != null
                    && internalName != null
                    && !externalName.equals(internalName)
                    && schema.getProperties().containsKey(internalName)
                    && !schema.getProperties().containsKey(externalName)) {
                    renames.put(internalName, externalName);
                }
            }
            if (renames.isEmpty()) {
                return Map.of();
            }

            Map<String, Schema> renamed = new LinkedHashMap<>();
            schema.getProperties().forEach((name, value) -> renamed.put(renames.getOrDefault(name, name), value));
            schema.setProperties(renamed);
            List<String> required = schema.getRequired();
            if (required != null) {
                required.replaceAll(name -> renames.getOrDefault(name, name));
            }
            return Map.copyOf(renames);
        }

        private void markRequiredRecordProperties(
            Schema<?> schema,
            JavaType javaType,
            Map<String, String> renames
        ) {
            Class<?> raw = javaType.getRawClass();
            if (!raw.isRecord()) {
                return;
            }
            List<String> required = new ArrayList<>();
            for (java.lang.reflect.RecordComponent component : raw.getRecordComponents()) {
                if (component.isAnnotationPresent(ApiOptional.class)) {
                    continue;
                }
                String name = renames.getOrDefault(component.getName(), component.getName());
                if (schema.getProperties().containsKey(name)) {
                    required.add(name);
                }
            }
            schema.setRequired(required);
        }
    }
}
