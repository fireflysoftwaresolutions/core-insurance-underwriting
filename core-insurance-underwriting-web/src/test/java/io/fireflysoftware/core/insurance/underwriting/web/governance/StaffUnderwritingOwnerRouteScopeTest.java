package io.fireflysoftware.core.insurance.underwriting.web.governance;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class StaffUnderwritingOwnerRouteScopeTest{
    @Test void existingQuoteAndMetadataRoutesKeepTheirOriginalChain(){var filter=new StaffUnderwritingOwnerSecurityConfiguration().staffUnderwritingSupportedRoutes();for(var path:new String[]{"/actuator/health","/v3/api-docs","/api/v1/tenants/one/submissions/two/quote-offers"}){var exchange=MockServerWebExchange.from(MockServerHttpRequest.get(path));var next=new AtomicInteger();filter.filter(exchange,value->{next.incrementAndGet();return Mono.empty();}).block();assertEquals(1,next.get());assertNull(exchange.getResponse().getStatusCode());}}
    @Test void onlyTheThreeDeclaredOwnerMethodsCanEnterTheStaffReceiver(){var filter=new StaffUnderwritingOwnerSecurityConfiguration().staffUnderwritingSupportedRoutes();for(var request:new MockServerHttpRequest[]{MockServerHttpRequest.post("/api/v1/underwriting-governance/intents").build(),MockServerHttpRequest.post("/api/v1/underwriting-governance/actions").build(),MockServerHttpRequest.get("/api/v1/underwriting-governance/actions/original-action").build()}){var exchange=MockServerWebExchange.from(request);var next=new AtomicInteger();filter.filter(exchange,value->{next.incrementAndGet();return Mono.empty();}).block();assertEquals(1,next.get());}}
    @Test void extraPathsAndMethodsInTheStaffPrefixCannotReachAnotherController(){var filter=new StaffUnderwritingOwnerSecurityConfiguration().staffUnderwritingSupportedRoutes();for(var request:new MockServerHttpRequest[]{MockServerHttpRequest.get("/api/v1/underwriting-governance/intents").build(),MockServerHttpRequest.delete("/api/v1/underwriting-governance/actions/one").build(),MockServerHttpRequest.post("/api/v1/underwriting-governance/actions/one/approval").build()}){var exchange=MockServerWebExchange.from(request);var next=new AtomicInteger();filter.filter(exchange,value->{next.incrementAndGet();return Mono.empty();}).block();assertEquals(0,next.get());assertEquals(HttpStatus.NOT_FOUND,exchange.getResponse().getStatusCode());}}
}
