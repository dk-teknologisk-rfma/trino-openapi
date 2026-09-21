/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.net.was;

import io.airlift.configuration.InvalidConfigurationException;
import io.airlift.http.client.Request;
import io.airlift.http.client.testing.TestingHttpClient;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.trino.spi.TrinoException;
import org.junit.jupiter.api.Test;
import pl.net.was.authentication.Authentication;
import pl.net.was.authentication.AuthenticationType;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for Part 2 of docs/adr/0001-unsatisfiable-security-requirements.md:
 * a request whose only applicable security requirements are all unsatisfiable
 * must fail loudly (TrinoException) rather than silently going out without
 * credentials, while a spec that authenticates cleanly - either via a
 * properly declared scheme, or via no security requirements at all, falling
 * through to the configured default authentication.type - must keep working
 * exactly as before.
 */
class AuthenticationTest {
    private static final URI BASE_URI = URI.create("https://example.com/api");

    @Test
    void unsatisfiableRootRequirementThrowsTrinoExceptionNamingTheScheme() {
        // root security references "apiKeyAuth", but components.securitySchemes
        // never declares it - unsatisfiable.
        OpenAPI openApi = new OpenAPI()
                .paths(pathsWithJsonGet("/pets"))
                .security(java.util.List.of(new SecurityRequirement().addList("apiKeyAuth")));
        OpenApiSpec spec = new OpenApiSpec(openApi);

        OpenApiConfig config = new OpenApiConfig().setBaseUri(BASE_URI);
        Authentication authentication = new Authentication(config, spec, unusedHttpClient());

        Request request = getRequest("/pets");

        assertThatThrownBy(() -> authentication.filterRequest(request))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("apiKeyAuth");
    }

    @Test
    void rootSecurityWithUndeclaredSchemeStillBuildsCatalog() {
        // catalog/spec construction must succeed - we deliberately don't reject
        // the catalog just because one security requirement is unsatisfiable -
        // and the tables are still there.
        OpenAPI openApi = new OpenAPI()
                .paths(pathsWithJsonGet("/pets"))
                .security(java.util.List.of(new SecurityRequirement().addList("apiKeyAuth")));

        OpenApiSpec spec = new OpenApiSpec(openApi);
        assertThat(spec.getTables()).containsKey("pets");
        assertThat(spec.getSecurityRequirements()).hasSize(1);
        assertThat(spec.getSecuritySchemas()).isEmpty();
    }

    @Test
    void regressionDeclaredSchemeStillAuthenticates() throws InvalidConfigurationException {
        // regression guard: a spec with a properly declared scheme still
        // authenticates the same way it did before the unsatisfiable-requirement
        // handling was added.
        SecurityScheme apiKeyScheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-Api-Key");
        OpenAPI openApi = new OpenAPI()
                .paths(pathsWithJsonGet("/pets"))
                .components(new Components().securitySchemes(Map.of("apiKeyAuth", apiKeyScheme)))
                .security(java.util.List.of(new SecurityRequirement().addList("apiKeyAuth")));
        OpenApiSpec spec = new OpenApiSpec(openApi);

        OpenApiConfig config = new OpenApiConfig()
                .setBaseUri(BASE_URI)
                .setApiKeyValue("secret-value");
        Authentication authentication = new Authentication(config, spec, unusedHttpClient());

        Request result = authentication.filterRequest(getRequest("/pets"));
        assertThat(result.getHeader("X-Api-Key")).isEqualTo("secret-value");
    }

    @Test
    void regressionNoSecurityRequirementsFallsThroughToConfiguredDefault() throws InvalidConfigurationException {
        // regression guard: this is the path all three DMI catalogs actually
        // use today - they declare no securitySchemes and no root security at
        // all, so authentication comes entirely from the configured
        // authentication.type default.
        OpenAPI openApi = new OpenAPI().paths(pathsWithJsonGet("/pets"));
        OpenApiSpec spec = new OpenApiSpec(openApi);
        assertThat(spec.getSecurityRequirements()).isEmpty();
        assertThat(spec.getSecuritySchemas()).isEmpty();

        OpenApiConfig config = new OpenApiConfig()
                .setBaseUri(BASE_URI)
                .setAuthenticationType(AuthenticationType.API_KEY)
                .setApiKeyName("X-Api-Key")
                .setApiKeyValue("default-secret");
        Authentication authentication = new Authentication(config, spec, unusedHttpClient());

        Request result = authentication.filterRequest(getRequest("/pets"));
        assertThat(result.getHeader("X-Api-Key")).isEqualTo("default-secret");
    }

    private static Request getRequest(String path) {
        return new Request.Builder()
                .setMethod("GET")
                .setUri(BASE_URI.resolve(path))
                .addHeader("X-Trino-OpenAPI-Path", path)
                .build();
    }

    private static TestingHttpClient unusedHttpClient() {
        // none of these tests exercise the OAuth token-fetch path, so the
        // client should never actually be called.
        return new TestingHttpClient(request -> {
            throw new AssertionError("unexpected HTTP call to " + request.getUri());
        });
    }

    private static Paths pathsWithJsonGet(String path) {
        Schema<?> schema = new Schema<>();
        schema.setType("object");
        schema.setProperties(new LinkedHashMap<>(Map.of("name", newSchema("string"))));

        Operation get = new Operation().responses(new ApiResponses().addApiResponse(
                "200",
                new ApiResponse()
                        .description("ok")
                        .content(new Content().addMediaType("application/json", new MediaType().schema(schema)))));
        return new Paths().addPathItem(path, new PathItem().get(get));
    }

    private static Schema<?> newSchema(String type) {
        Schema<?> schema = new Schema<>();
        schema.setType(type);
        return schema;
    }
}
