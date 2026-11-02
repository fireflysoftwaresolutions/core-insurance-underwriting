/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.core.quotes;

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.*;
import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.impl.GeneratedConfigQuoteTenantBindingResolverImpl;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteAuthorityBudget;
import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine OAuth manager and generated SDK over controlled transports; no actual credentials or network. */
class GeneratedConfigQuoteTenantBindingResolverTest {
    private static final String ISSUER="https://synthetic.identity.example",PLATFORM="synthetic:opaque:tenant";
    private static final UUID RESOURCE=new UUID(0,1),BINDING=new UUID(0,2),STATUS=new UUID(0,3);
    private static final class MovingClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){if(!zone.equals(ZoneOffset.UTC))throw new IllegalArgumentException();return this;}
        @Override public Instant instant(){return now.get();}
    }
    private static final class Fixture {
        final MovingClock clock=new MovingClock();final Instant start=clock.instant();final AtomicLong nanos=new AtomicLong();
        final AtomicInteger tokens=new AtomicInteger(),sdkCalls=new AtomicInteger();
        final AtomicReference<Runnable> atToken=new AtomicReference<>(()->{}),atSdk=new AtomicReference<>(()->{});
        final AtomicReference<java.util.function.UnaryOperator<String>> response=new AtomicReference<>(value->value);
        final AtomicReference<SecurityPrincipal> principal=new AtomicReference<>(new SecurityPrincipal("opaque:human",ISSUER,PLATFORM,Set.of(),Set.of(),
                Map.of("tenant_id",PLATFORM,"exp",start.plusSeconds(60)),start,null,Set.of(),Map.of()));
        final QuoteAuthorityBudget original=QuoteAuthorityBudget.observe(start,start.plusSeconds(5),0,clock,nanos::get);
        final Instant until=start.plusSeconds(2);final Supplier<Duration> remaining=()->{
            Duration value=original.remaining(until);long elapsed=nanos.get();if(elapsed<0 || elapsed>=2_000_000_000L)throw new IllegalStateException("Synthetic original authorization expired");
            Duration cap=Duration.ofNanos(2_000_000_000L-elapsed);return value.compareTo(cap)<0?value:cap;
        };
        final ReactiveOAuth2AuthorizedClientManager manager;final WebClient client;
        Fixture() {
            var registration=ClientRegistration.withRegistrationId("quote-current").clientId("synthetic-lookup-client")
                    .clientSecret("SYNTHETIC-NOT-A-CREDENTIAL").clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).tokenUri("https://synthetic.identity.example/token").build();
            var registrations=new InMemoryReactiveClientRegistrationRepository(registration);
            var tokenClient=new WebClientReactiveClientCredentialsTokenResponseClient();
            tokenClient.setWebClient(WebClient.builder().exchangeFunction(request->{
                tokens.incrementAndGet();atToken.get().run();assertEquals(HttpMethod.POST,request.method());assertEquals(URI.create("https://synthetic.identity.example/token"),request.url());
                assertTrue(request.headers().getFirst(HttpHeaders.AUTHORIZATION).startsWith("Basic "));
                return Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE,"application/json")
                        .body("{\"access_token\":\"synthetic-machine-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}").build());
            }).build());
            var provider=new ClientCredentialsReactiveOAuth2AuthorizedClientProvider();provider.setAccessTokenResponseClient(tokenClient);
            var actual=new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registrations,new InMemoryReactiveOAuth2AuthorizedClientService(registrations));
            actual.setAuthorizedClientProvider(provider);manager=actual;
            client=io.fireflysoftware.common.config.sdk.invoker.ApiClient.buildWebClientBuilder().exchangeFunction(request->{
                sdkCalls.incrementAndGet();atSdk.get().run();assertEquals(HttpMethod.GET,request.method());
                assertEquals(URI.create("https://synthetic.config.example/api/v1/tenant-resource-bindings/current"),request.url());
                assertEquals("Bearer synthetic-machine-token",request.headers().getFirst(HttpHeaders.AUTHORIZATION));assertNull(request.url().getQuery());
                assertNull(request.headers().getFirst("X-Tenant-Id"));
                assertFalse(request.headers().headerNames().stream().anyMatch(key->key.toLowerCase(Locale.ROOT).contains("proof")||key.toLowerCase(Locale.ROOT).contains("subject")));
                return Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE,"application/json").body(response.get().apply(current())).build());
            }).build();
        }
        String current() {
            long now=clock.millis();
            return "{\"reference\":{\"bindingId\":\""+BINDING+"\",\"version\":7,\"definitionDigest\":\""+"a".repeat(64)+"\"},"
                    +"\"definition\":{\"bindingId\":\""+BINDING+"\",\"version\":7,\"namespace\":{\"issuer\":\""+ISSUER+"\",\"platformTenant\":\""+PLATFORM+"\"},"
                    +"\"resourceTenant\":{\"value\":\""+RESOURCE+"\"},\"eligibility\":{\"tenantGeneration\":4,\"statusId\":\""+STATUS+"\",\"statusGeneration\":3},\"notAfterEpochMillis\":"+start.plusSeconds(60).toEpochMilli()+"},"
                    +"\"controlVersion\":9,\"observedAtEpochMillis\":"+now+",\"validUntilEpochMillis\":"+(now+5000)+"}";
        }
        GeneratedConfigQuoteTenantBindingResolverImpl resolver() {return new GeneratedConfigQuoteTenantBindingResolverImpl(()->Mono.justOrEmpty(principal.get()),manager,client,
                URI.create("https://synthetic.config.example"),"quote-current",ISSUER,PLATFORM,clock);}
        QuoteTenantBindingResolver.Observation resolve() {return resolver().resolve(principal.get(),RESOURCE,original,until,remaining).block(Duration.ofSeconds(3));}
    }
    @Test void actualClientCredentialsAndGeneratedCurrentReturnOnlyTheAuthoritativeUuid() {
        var f=new Fixture();var value=f.resolve();assertNotNull(value);assertEquals(RESOURCE,value.resourceTenantId());assertEquals(PLATFORM,value.platformTenant());
        assertEquals(BINDING,value.bindingId());assertEquals(7,value.version());assertEquals(9,value.controlVersion());assertEquals(4,value.tenantGeneration());
        assertEquals(STATUS,value.statusId());assertEquals(3,value.statusGeneration());assertEquals(1,f.tokens.get());assertEquals(1,f.sdkCalls.get());
    }
    @Test void originalMonotonicDeadlineIncludesTokenAcquisitionAndPreventsTheSdkCall() {
        var f=new Fixture();f.atToken.set(()->f.nanos.set(2_000_000_000L));
        assertThrows(SecurityException.class,f::resolve);assertEquals(1,f.tokens.get());assertEquals(0,f.sdkCalls.get());
    }
    @Test void lateSdkAndChangingPrincipalNeverCreateAnObservation() {
        var late=new Fixture();late.atSdk.set(()->late.nanos.set(2_000_000_000L));assertThrows(SecurityException.class,late::resolve);
        var changed=new Fixture();changed.atSdk.set(()->changed.principal.set(new SecurityPrincipal("opaque:other",ISSUER,PLATFORM,Set.of(),Set.of(),
                Map.of("tenant_id",PLATFORM,"exp",changed.start.plusSeconds(60)),changed.start,null,Set.of(),Map.of())));
        assertThrows(SecurityException.class,changed::resolve);
    }
    @ParameterizedTest @ValueSource(strings={"namespace","resource","binding","version","digest","control","eligibility","expired","overlong"})
    void malformedOrForeignCurrentCannotBecomeAValidBinding(String kind) {
        var f=new Fixture();f.response.set(value->switch(kind) {
            case "namespace"->value.replace(PLATFORM,"synthetic:foreign");case "resource"->value.replace(RESOURCE.toString(),new UUID(0,99).toString());
            case "binding"->value.replaceFirst(BINDING.toString(),new UUID(0,99).toString());case "version"->value.replaceFirst("\"version\":7","\"version\":8");
            case "digest"->value.replace("a".repeat(64),"invalid");case "control"->value.replace("\"controlVersion\":9","\"controlVersion\":0");
            case "eligibility"->value.replace("\"tenantGeneration\":4","\"tenantGeneration\":-1");
            case "expired"->value.replace("\"validUntilEpochMillis\":"+(f.clock.millis()+5000),"\"validUntilEpochMillis\":"+f.clock.millis());
            case "overlong"->value.replace("\"validUntilEpochMillis\":"+(f.clock.millis()+5000),"\"validUntilEpochMillis\":"+(f.clock.millis()+6000));
            default->throw new IllegalArgumentException();
        });
        var error=assertThrows(SecurityException.class,f::resolve);assertEquals("Current trusted quote tenant binding unavailable",error.getMessage());assertNull(error.getCause());
    }
    @Test void wrongCallerNamespaceRefusesBeforeAnyTokenOrSdkAccess() {
        var f=new Fixture();var p=f.principal.get();f.principal.set(new SecurityPrincipal(p.subject(),p.issuer(),"synthetic:foreign",p.authorities(),p.scopes(),
                Map.of("tenant_id","synthetic:foreign","exp",f.start.plusSeconds(60)),p.authTime(),p.acr(),p.amr(),p.attributes()));
        assertThrows(SecurityException.class,f::resolve);assertEquals(0,f.tokens.get());assertEquals(0,f.sdkCalls.get());
    }
}
