package io.fireflysoftware.core.insurance.underwriting.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fireflysoftware.core.insurance.underwriting.sdk.api.GovernedQuoteOffersApi;
import io.fireflysoftware.core.insurance.underwriting.sdk.invoker.ApiClient;
import io.fireflysoftware.core.insurance.underwriting.sdk.invoker.auth.HttpBearerAuth;
import io.fireflysoftware.core.insurance.underwriting.sdk.model.QuoteOfferRequest;
import io.fireflysoftware.core.insurance.underwriting.sdk.model.QuoteAcceptanceRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedGovernedQuoteContractTest {
    private static final UUID TENANT=new UUID(0,1), SUBMISSION=new UUID(0,2), PARTY=new UUID(0,3), PRODUCT=new UUID(0,4), OFFER=new UUID(0,5), ACTION=new UUID(0,6);
    private static final String DIGEST="a".repeat(64);
    private static final String PATH="/api/v1/tenants/"+TENANT+"/submissions/"+SUBMISSION+"/parties/"+PARTY+"/products/"+PRODUCT+"/quote-offers";
    private static final Duration LIMIT=Duration.ofSeconds(5);
    private static final ObjectMapper MAPPER=ApiClient.createDefaultMapper(null);

    @Test void officialInputMatchesOriginalProducerContract() throws Exception {
        String supplied=System.getProperty("quote.source.output");
        var producer=supplied==null?Path.of("..","core-insurance-underwriting-web","target","quote-producer").toAbsolutePath():Path.of(supplied);
        var original=new ObjectMapper().readTree(producer.resolve("producer-openapi-1.json").toFile());
        var selected=new ObjectMapper(new YAMLFactory()).readTree(Path.of("src/main/resources/openapi.yml").toFile());
        if(Boolean.getBoolean("owned.insurance.underwriting-sdk-boundary.enabled")||selected.path("paths").has("/api/v1/underwriting-governance/actions")){
            var extended=new ObjectMapper().readTree(Path.of("..","core-insurance-underwriting-web","target","underwriting-staff-openapi.json").toFile());
            original.path("paths").fields().forEachRemaining(entry->assertEquals(entry.getValue(),extended.path("paths").path(entry.getKey())));
            original.path("components").fields().forEachRemaining(section->section.getValue().fields().forEachRemaining(entry->assertEquals(entry.getValue(),extended.path("components").path(section.getKey()).path(entry.getKey()))));
            original=extended;
        }
        assertEquals(original.path("paths"),selected.path("paths"));
        assertEquals(original.path("components"),selected.path("components"));
        assertEquals("bearer",selected.at("/components/securitySchemes/governedQuoteBearer/scheme").asText());
        var base="/api/v1/tenants/{tenantId}/submissions/{submissionId}/parties/{partyId}/products/{productId}/quote-offers";
        for(var operation:List.of(new String[]{base,"post"},new String[]{base,"get"},new String[]{base+"/{offerId}","get"},
                new String[]{base+"/{offerId}/acceptances","post"},new String[]{base+"/actions/{actionId}/recovery","get"})) {
            var node=selected.path("paths").path(operation[0]).path(operation[1]);
            assertEquals("application/json",node.path("responses").path("200").path("content").fieldNames().next());
            assertTrue(node.path("security").toString().contains("governedQuoteBearer"));
        }
    }

    @Test void generatedClientsHaveFreshOriginExplicitEndpointAndPerInstanceBearer() throws Exception {
        var expected=Path.of("target/classes").toRealPath();
        Map<String,Object> origins=new LinkedHashMap<>();
        for(var type:List.of(ApiClient.class,GovernedQuoteOffersApi.class,HttpBearerAuth.class,QuoteOfferRequest.class,QuoteAcceptanceRequest.class)) {
            var location=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            assertEquals(expected,location);
            var resource=type.getName().replace('.','/')+".class";
            assertEquals(1,Collections.list(type.getClassLoader().getResources(resource)).size());
            try(var stream=type.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(stream);origins.put(type.getName(),Map.of("codeSource",location.toString(),"classSha256",
                        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()))));
            }
        }
        java.nio.file.Files.writeString(Path.of("target/generated-sdk-origins.json"),MAPPER.writeValueAsString(origins));
        var first=new ApiClient().setBasePath("https://configured-one.invalid");
        var second=new ApiClient().setBasePath("https://configured-two.invalid");
        assertEquals("https://configured-one.invalid",first.getBasePath());
        assertEquals("https://configured-two.invalid",second.getBasePath());
        var firstAuth=assertInstanceOf(HttpBearerAuth.class,first.getAuthentication("governedQuoteBearer"));
        var secondAuth=assertInstanceOf(HttpBearerAuth.class,second.getAuthentication("governedQuoteBearer"));
        assertNotSame(firstAuth,secondAuth);first.setBearerToken("synthetic-one");assertNull(secondAuth.getBearerToken());
    }

    @Test void allFiveGeneratedOperationsUseTypedWireContract() throws Exception {
        List<String> observed=new ArrayList<>();
        var nextStatus=new java.util.concurrent.atomic.AtomicReference<>("REFUSED");
        var strategies=ExchangeStrategies.builder().codecs(codecs->{
            codecs.defaultCodecs().jackson2JsonEncoder(new org.springframework.http.codec.json.Jackson2JsonEncoder(MAPPER,MediaType.APPLICATION_JSON));
            codecs.defaultCodecs().jackson2JsonDecoder(new org.springframework.http.codec.json.Jackson2JsonDecoder(MAPPER,MediaType.APPLICATION_JSON));
        }).build();
        var transport=ApiClient.buildWebClientBuilder().exchangeFunction(request->{
            assertEquals("configured-quote.invalid",request.url().getHost());
            assertEquals(List.of("Bearer synthetic-quote"),request.headers().getOrEmpty(HttpHeaders.AUTHORIZATION));
            observed.add(request.method()+" "+request.url().getRawPath());
            if(request.method()==HttpMethod.POST) {
                var captured=new MockClientHttpRequest(request.method(),request.url());
                return request.writeTo(captured,strategies).then(Mono.defer(captured::getBodyAsString)).flatMap(body->{
                    try {
                        JsonNode json=MAPPER.readTree(body);assertEquals(ACTION.toString(),json.path("actionId").asText());
                        if(request.url().getPath().endsWith("/acceptances")) {
                            assertEquals(1,json.path("offerVersion").asLong());assertEquals(0,json.path("expectedControlGeneration").asLong());
                            assertEquals(DIGEST,json.path("termsDigest").asText());
                        } else {
                            assertEquals("2027-01-01",json.path("coverageStart").asText());assertEquals("2028-01-01",json.path("coverageEnd").asText());
                            assertEquals(PRODUCT.toString(),json.path("expectedProductRevisionId").asText());assertEquals(DIGEST,json.path("expectedProductDigest").asText());
                        }
                    } catch(java.io.IOException invalid){return Mono.error(invalid);}
                    return Mono.just(ClientResponse.create(HttpStatus.OK,strategies).header("Content-Type","application/json")
                            .body("{\"status\":\""+nextStatus.get()+"\",\"actionId\":\""+ACTION+"\",\"intentDigest\":\""+DIGEST+"\"}").build());
                });
            }
            String body;
            if(request.url().getPath().endsWith("/recovery")) {
                assertEquals("expectedIntentDigest="+DIGEST,request.url().getRawQuery());
                body="{\"state\":\"NOT_OBSERVED_AT_THIS_READ\",\"actionId\":\""+ACTION+"\",\"intentDigest\":\""+DIGEST+"\"}";
            } else if(request.url().getPath().endsWith("/"+OFFER))body="{\"state\":\"NOT_OBSERVED_AT_THIS_READ\"}";
            else body="{\"entries\":[]}";
            return Mono.just(ClientResponse.create(HttpStatus.OK,strategies).header("Content-Type","application/json").body(body).build());
        }).build();
        var client=new ApiClient(transport).setBasePath("https://configured-quote.invalid");client.setBearerToken("synthetic-quote");
        var api=new GovernedQuoteOffersApi(client);
        var request=new QuoteOfferRequest().actionId(ACTION).coverageStart(LocalDate.of(2027,1,1)).coverageEnd(LocalDate.of(2028,1,1))
                .expectedProductRevisionId(PRODUCT).expectedProductDigest(DIGEST);
        for(String status:List.of("COMMITTED","COMMITTED_RESPONSE_UNAVAILABLE","OUTCOME_UNKNOWN","REFUSED")) {
            nextStatus.set(status);var response=api.offerWithHttpInfo(TENANT,SUBMISSION,PARTY,PRODUCT,request).block(LIMIT);
            assertNotNull(response);assertEquals(200,response.getStatusCode().value());assertEquals(status,response.getBody().getStatus().toString());
            assertEquals(ACTION,response.getBody().getActionId());assertEquals(DIGEST,response.getBody().getIntentDigest());
        }
        var acceptance=new QuoteAcceptanceRequest().actionId(ACTION).offerVersion(1L).expectedControlGeneration(0L).termsDigest(DIGEST);
        assertEquals("REFUSED",api.acceptWithHttpInfo(TENANT,SUBMISSION,PARTY,PRODUCT,OFFER,acceptance).block(LIMIT).getBody().getStatus().toString());
        assertEquals("NOT_OBSERVED_AT_THIS_READ",api.currentWithHttpInfo(TENANT,SUBMISSION,PARTY,PRODUCT,OFFER).block(LIMIT).getBody().getState().toString());
        assertTrue(api.historyWithHttpInfo(TENANT,SUBMISSION,PARTY,PRODUCT).block(LIMIT).getBody().getEntries().isEmpty());
        assertEquals("NOT_OBSERVED_AT_THIS_READ",api.recoverWithHttpInfo(TENANT,SUBMISSION,PARTY,PRODUCT,ACTION,DIGEST).block(LIMIT).getBody().getState().toString());
        assertEquals(8,observed.size());assertTrue(observed.contains("POST "+PATH));assertTrue(observed.contains("POST "+PATH+"/"+OFFER+"/acceptances"));
        assertTrue(observed.contains("GET "+PATH));assertTrue(observed.contains("GET "+PATH+"/"+OFFER));assertTrue(observed.contains("GET "+PATH+"/actions/"+ACTION+"/recovery"));
    }

    @Test void overlappingGeneratedCallersRetainBearerAndHttpFailures() {
        Map<String,List<String>> headers=new ConcurrentHashMap<>();var arrivals=new AtomicInteger();Sinks.Empty<Void> both=Sinks.empty();
        var transport=ApiClient.buildWebClientBuilder().exchangeFunction(request->Mono.defer(()->{
            assertNull(headers.putIfAbsent(request.url().getHost(),List.copyOf(request.headers().getOrEmpty(HttpHeaders.AUTHORIZATION))));
            if(arrivals.incrementAndGet()==2)both.tryEmitEmpty();
            return both.asMono().thenReturn(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body("{\"entries\":[]}").build());
        })).build();
        var one=new ApiClient(transport).setBasePath("https://one.invalid");one.setBearerToken("synthetic-one");
        var two=new ApiClient(transport).setBasePath("https://two.invalid");two.setBearerToken("synthetic-two");
        Mono.when(new GovernedQuoteOffersApi(one).history(TENANT,SUBMISSION,PARTY,PRODUCT).then(),
                new GovernedQuoteOffersApi(two).history(TENANT,SUBMISSION,PARTY,PRODUCT).then()).block(LIMIT);
        assertEquals(Map.of("one.invalid",List.of("Bearer synthetic-one"),"two.invalid",List.of("Bearer synthetic-two")),headers);
        for(var status:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN)) {
            var denied=ApiClient.buildWebClientBuilder().exchangeFunction(request->Mono.just(ClientResponse.create(status).build())).build();
            var api=new GovernedQuoteOffersApi(new ApiClient(denied).setBasePath("https://denied.invalid"));
            var error=assertThrows(WebClientResponseException.class,()->api.history(TENANT,SUBMISSION,PARTY,PRODUCT).block(LIMIT));
            assertEquals(status.value(),error.getStatusCode().value());
        }
    }
}
