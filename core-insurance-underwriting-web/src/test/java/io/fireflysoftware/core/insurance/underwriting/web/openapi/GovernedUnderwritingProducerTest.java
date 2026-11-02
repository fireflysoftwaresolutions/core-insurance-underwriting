package io.fireflysoftware.core.insurance.underwriting.web.openapi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fireflysoftware.core.insurance.underwriting.core.services.governance.GovernedStaffUnderwritingService;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("openapi-gen")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes=OpenApiGenApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={"spring.main.web-application-type=reactive","springdoc.api-docs.enabled=true","springdoc.api-docs.version=OPENAPI_3_0","insurance.underwriting.governed.enabled=true"})
class GovernedUnderwritingProducerTest {
    @Autowired ApplicationContext context;
    record Operation(String path,String verb,String id){}
    Set<Operation> operations(JsonNode document){var result=new HashSet<Operation>();document.path("paths").fields().forEachRemaining(path->path.getValue().fields().forEachRemaining(method->{if(Set.of("get","post","put","patch","delete","head","options","trace").contains(method.getKey()))assertTrue(result.add(new Operation(path.getKey(),method.getKey(),method.getValue().path("operationId").asText())));}));return result;}
    Set<Operation> staffOperations(){return Set.of(new Operation("/api/v1/underwriting-governance/intents","post","prepareGovernedUnderwritingIntent"),new Operation("/api/v1/underwriting-governance/actions","post","executeGovernedUnderwritingAction"),new Operation("/api/v1/underwriting-governance/actions/{actionId}","get","recoverGovernedUnderwritingAction"));}
    Set<Operation> originalSixtyFive(JsonNode document){var declared=operations(document);var baseline=new HashSet<>(declared);baseline.removeAll(staffOperations());assertEquals(65,baseline.size());var extended=new HashSet<>(baseline);extended.addAll(staffOperations());assertTrue(declared.equals(baseline)||declared.equals(extended));return baseline;}
    JsonNode resolve(JsonNode doc,JsonNode value){if(!value.has("$ref"))return value;var reference=value.path("$ref").asText();assertTrue(reference.startsWith("#/components/schemas/"));return doc.at(reference.substring(1));}
    void schema(JsonNode doc){
        var carriers=new TreeSet<String>();doc.at("/components/securitySchemes").fieldNames().forEachRemaining(carriers::add);assertEquals(Set.of("governedQuoteBearer"),carriers);assertEquals("bearer",doc.at("/components/securitySchemes/governedQuoteBearer/scheme").asText());
        for(var row:List.of(new Operation("/api/v1/underwriting-governance/intents","post","prepareGovernedUnderwritingIntent"),new Operation("/api/v1/underwriting-governance/actions","post","executeGovernedUnderwritingAction"),new Operation("/api/v1/underwriting-governance/actions/{actionId}","get","recoverGovernedUnderwritingAction"))){var operation=doc.path("paths").path(row.path()).path(row.verb());assertEquals(row.id(),operation.path("operationId").asText());assertTrue(operation.path("security").toString().contains("governedQuoteBearer"));for(var parameter:operation.path("parameters"))assertNotEquals("X-Idempotency-Key",parameter.path("name").asText());}
        var intent=doc.at("/components/schemas/GovernedUnderwritingIntent");var fields=new TreeSet<String>();intent.path("properties").fieldNames().forEachRemaining(fields::add);assertEquals(Set.of("operation","actionId","submissionId","riskId","referralId","expectedReferralVersion","expectedReceiptDigest","reason"),fields);assertFalse(intent.path("additionalProperties").asBoolean(true));
        var operation=doc.path("paths").path("/api/v1/underwriting-governance/actions").path("post");var response=resolve(doc,operation.path("responses").path("200").path("content").path("application/json").path("schema"));var statuses=new TreeSet<String>();resolve(doc,response.path("properties").path("status")).path("enum").forEach(value->statuses.add(value.asText()));assertEquals(Set.of("COMMITTED","COMMITTED_RESPONSE_UNAVAILABLE","OUTCOME_UNKNOWN","REFUSED"),statuses);
    }
    @Test @Order(1) void originalFullProducerRetainsAllSixtyFiveOperationsAndAddsOnlyThreeTypedStaffRoutes()throws Exception{
        var client=WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(3)).build();byte[] json=client.get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();byte[] yaml=client.get().uri("/v3/api-docs.yaml").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();assertNotNull(json);assertNotNull(yaml);assertTrue(json.length>0 && json.length<=4*1024*1024 && yaml.length>0 && yaml.length<=4*1024*1024);var actual=new ObjectMapper().readTree(json);assertEquals(actual,new ObjectMapper(new YAMLFactory()).readTree(yaml));Files.createDirectories(Path.of("target"));Files.write(Path.of("target/underwriting-staff-openapi.json"),json);Files.write(Path.of("target/underwriting-staff-openapi.yml"),yaml);
        var original=new ObjectMapper(new YAMLFactory()).readTree(Files.readAllBytes(Path.of("../core-insurance-underwriting-sdk/src/main/resources/openapi.yml")));var expected=new HashSet<>(originalSixtyFive(original));expected.addAll(staffOperations());assertEquals(68,expected.size());assertEquals(expected,operations(actual));schema(actual);
    }
    @Test @Order(2) void documentationProducerCreatesNoAuthorityStoreOrBusinessConnectionFactory(){assertEquals(0,context.getBeanNamesForType(GovernedStaffUnderwritingService.class,false,false).length);assertEquals(0,context.getBeanNamesForType(io.r2dbc.spi.ConnectionFactory.class,false,false).length);}
    @Test @Order(3) void ownedBoundaryCopiesOnlyTheSameSemanticallyVerifiedFullProducer()throws Exception{
        if(!Boolean.getBoolean("owned.insurance.underwriting-sdk-boundary.enabled"))return;
        assertEquals("validated-hosted-manual-main-v1",System.getProperty("owned.insurance.underwriting-sdk-boundary.hosted"));var root=Path.of(System.getProperty("owned.insurance.underwriting-sdk-boundary.root")).normalize();assertTrue(root.isAbsolute());assertEquals(root,root.toRealPath());assertEquals(Path.of("..").toRealPath(),root);assertEquals(Path.of(System.getProperty("owned.insurance.underwriting-sdk-boundary.workspace"),"qualification","underwriting-staff-source-chain","core-insurance-underwriting").toRealPath(),root);
        var canonical=root.resolve("core-insurance-underwriting-sdk/src/main/resources/openapi.yml");assertTrue(Files.isRegularFile(canonical,LinkOption.NOFOLLOW_LINKS));assertEquals(canonical,canonical.toRealPath());assertTrue(Files.size(canonical)<=4*1024*1024);var preimage=Files.readAllBytes(canonical);String expected=System.getProperty("owned.insurance.underwriting-sdk-boundary.preimage-sha256");assertNotNull(expected);assertEquals(expected,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(preimage)));
        var json=Files.readAllBytes(Path.of("target/underwriting-staff-openapi.json"));var yaml=Files.readAllBytes(Path.of("target/underwriting-staff-openapi.yml"));assertTrue(json.length>0 && json.length<=4*1024*1024 && yaml.length>0 && yaml.length<=4*1024*1024);var actual=new ObjectMapper().readTree(json);assertEquals(actual,new ObjectMapper(new YAMLFactory()).readTree(yaml));var previous=originalSixtyFive(new ObjectMapper(new YAMLFactory()).readTree(preimage));assertTrue(operations(actual).containsAll(previous));assertEquals(68,operations(actual).size());schema(actual);Files.write(Path.of("target/underwriting-staff-sdk-preimage.yml"),preimage,StandardOpenOption.CREATE_NEW);Files.write(canonical,yaml,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);assertArrayEquals(yaml,Files.readAllBytes(canonical));
    }
}
