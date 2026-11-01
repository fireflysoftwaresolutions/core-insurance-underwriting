/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.web.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.context.ActiveProfiles("openapi-gen")
@SpringBootTest(classes=OpenApiGenApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={
        "spring.main.web-application-type=reactive","insurance.quotes.governed.enabled=false",
        "springdoc.api-docs.enabled=true","springdoc.api-docs.version=OPENAPI_3_0"})
class QuoteGovernedOpenApiSecurityContractTest {
    private static final String BASE="/api/v1/tenants/{tenantId}/submissions/{submissionId}/parties/{partyId}/products/{productId}/quote-offers";
    @Autowired ApplicationContext context;
    private static final java.util.concurrent.atomic.AtomicInteger captures=new java.util.concurrent.atomic.AtomicInteger();
    private JsonNode producerDocument() throws Exception {
        byte[] bytes=WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(3)).build()
                .get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        assertThat(bytes).isNotNull();
        assertThat(bytes.length).isLessThanOrEqualTo(4*1024*1024);
        String supplied=System.getProperty("quote.source.output");
        var output=supplied==null?java.nio.file.Path.of("target","quote-producer").toAbsolutePath():java.nio.file.Path.of(supplied);
        if(supplied==null)java.nio.file.Files.createDirectories(output);
        var options=supplied==null
                ?new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING}
                :new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE_NEW};
        assertThat(output.isAbsolute()).isTrue();
        var capture=output.resolve("producer-openapi-"+captures.incrementAndGet()+".json");
        java.nio.file.Files.write(capture,bytes,options);
        java.nio.file.Files.setPosixFilePermissions(capture,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var checksum=java.nio.file.Path.of(capture+".sha256");
        java.nio.file.Files.writeString(checksum,digest+"\n",options);
        java.nio.file.Files.setPosixFilePermissions(checksum,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        return new ObjectMapper().readTree(bytes);
    }
    @Test void originalProducerPublishesBearerSchemeAndAllFiveOperationRequirements() throws Exception {
        var doc=producerDocument();var scheme=doc.path("components").path("securitySchemes").path("governedQuoteBearer");
        assertThat(scheme.path("type").asText()).isEqualTo("http");assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").asText()).isEqualTo("JWT");
        for(var operation:List.of(new String[]{BASE,"post"},new String[]{BASE,"get"},new String[]{BASE+"/{offerId}","get"},
                new String[]{BASE+"/{offerId}/acceptances","post"},new String[]{BASE+"/actions/{actionId}/recovery","get"})) {
            var value=doc.path("paths").path(operation[0]).path(operation[1]);assertThat(value.isMissingNode()).isFalse();
            boolean required=false;
            for(var requirement:value.path("security"))if(requirement.has("governedQuoteBearer")) {
                assertThat(requirement.path("governedQuoteBearer").isArray()).isTrue();required=true;
            }
            assertThat(required).as("Bearer requirement on %s %s",operation[1],operation[0]).isTrue();
            for(String code:List.of("401","403")) {
                var responses=value.path("responses").path(code);assertThat(responses.isMissingNode()).isFalse();
                var ordinary=resolve(doc,responses.path("content").path("application/json").path("schema"));
                assertThat(ordinary.path("properties").has("status")).isTrue();assertThat(ordinary.path("properties").has("code")).isTrue();
                var problem=resolve(doc,responses.path("content").path("application/problem+json").path("schema"));
                assertThat(problem.path("properties").has("status")).isTrue();assertThat(problem.path("properties").has("title")).isTrue();
            }
        }
    }
    @Test void originalProducerRetainsTheRequiredFourStateCommandEnvelope() throws Exception {
        var doc=producerDocument();
        for(String path:List.of(BASE,BASE+"/{offerId}/acceptances")) {
            var schema=resolve(doc,doc.path("paths").path(path).path("post").path("responses").path("200")
                    .path("content").path("application/json").path("schema"));
            var required=new java.util.HashSet<String>();schema.path("required").forEach(value->required.add(value.asText()));
            assertThat(required).contains("status","actionId","intentDigest");
            var statuses=new java.util.HashSet<String>();resolve(doc,schema.path("properties").path("status")).path("enum").forEach(value->statuses.add(value.asText()));
            assertThat(statuses).containsExactlyInAnyOrder("COMMITTED","COMMITTED_RESPONSE_UNAVAILABLE","OUTCOME_UNKNOWN","REFUSED");
        }
    }
    @Test void originalProducerContractContextDoesNotCreateBusinessConnectionFactories() throws Exception {
        producerDocument();assertThat(context.getBeanNamesForType(io.r2dbc.spi.ConnectionFactory.class,false,false)).isEmpty();
    }
    private static JsonNode resolve(JsonNode root,JsonNode schema) {
        if(schema.has("$ref")) {
            String ref=schema.path("$ref").asText();assertThat(ref).startsWith("#/components/schemas/");return root.at(ref.substring(1));
        }
        return schema;
    }
}
