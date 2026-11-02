/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.web.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.QuoteGovernedService;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.fireflyframework.web.error.config.ErrorHandlingProperties;
import org.fireflyframework.web.error.converter.*;
import org.fireflyframework.web.error.handler.GlobalExceptionHandler;
import org.fireflyframework.web.error.service.ErrorResponseNegotiator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.DelegatingWebFluxConfiguration;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuoteGovernedControllerTest {
    private static UUID id(long value) { return new UUID(0,value); }
    private static final QuoteOfferScope SCOPE=new QuoteOfferScope(id(1),id(2),id(3),id(4));
    private static final UUID ACTION=id(5),OFFER=id(6);
    private static final String BASE="/api/v1/tenants/"+SCOPE.tenantId()+"/submissions/"+SCOPE.submissionId()
            +"/parties/"+SCOPE.partyId()+"/products/"+SCOPE.productId()+"/quote-offers";
    private static final Instant NOW=Instant.parse("2026-10-31T12:00:00Z");
    private static QuoteOfferRequestIntent intent() {
        return new QuoteOfferRequestIntent(SCOPE,ACTION,LocalDate.of(2026,11,1),LocalDate.of(2027,11,1),id(7),"a".repeat(64));
    }
    private static Map<String,Object> request() {
        return new LinkedHashMap<>(Map.of("actionId",ACTION.toString(),"coverageStart","2026-11-01","coverageEnd","2027-11-01",
                "expectedProductRevisionId",id(7).toString(),"expectedProductDigest","a".repeat(64)));
    }
    private static QuoteResultEnvelope result(QuoteOfferRequestIntent request) {
        return new QuoteResultEnvelope(QuoteActionEnvelope.offer("https://synthetic.example.test","opaque-synthetic-subject",OFFER,request),id(8),id(9),null,NOW,0);
    }
    private static QuoteGovernedService.ReadView readView(UUID offer) {
        var terms=new QuoteOfferTerms("USD",new BigDecimal("10.00"),new BigDecimal("1.00"),new BigDecimal("0.00"),
                Map.of("coverage","synthetic-only"),id(7),"a".repeat(64),"b".repeat(64),"synthetic-provider-1","synthetic-rule-1");
        return new QuoteGovernedService.ReadView(new QuoteOfferVersion(SCOPE,offer,1,0,QuoteOfferVersion.State.OFFERED,terms,NOW,NOW.plusSeconds(60)),Optional.empty());
    }
    private static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final QuoteGovernedService owner=mock(QuoteGovernedService.class);
        final WebTestClient client;
        Fixture(boolean configured) {
            if(configured)context.registerBean(QuoteGovernedService.class,()->owner);
            context.registerBean(QuoteGovernedController.class,()->new QuoteGovernedController(context.getBeanProvider(QuoteGovernedService.class)));
            context.register(DelegatingWebFluxConfiguration.class);
            context.registerBean(org.springframework.validation.beanvalidation.MethodValidationPostProcessor.class,org.springframework.validation.beanvalidation.MethodValidationPostProcessor::new);
            var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
            context.registerBean(GlobalExceptionHandler.class,()->new GlobalExceptionHandler(
                    new ExceptionConverterService(List.of(new ValidationExceptionConverter(),new WebFluxExceptionConverter())),
                    Optional.empty(),new ErrorHandlingProperties(),Optional.empty(),Optional.empty(),new MockEnvironment(),mapper,
                    new ErrorResponseNegotiator(mapper),Optional.empty()));
            context.refresh();
            client=WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(3)).build();
            assertThat(context.getBeanNamesForType(io.r2dbc.spi.ConnectionFactory.class,false,false)).isEmpty();
        }
        @Override public void close() {context.close();}
    }
    @Test void genuineMissingOwnerReturnsShared503WithoutBusinessResources() {
        try(var f=new Fixture(false)) {
            f.client.post().uri(BASE).bodyValue(request()).exchange().expectStatus().isEqualTo(503)
                    .expectHeader().doesNotExist("Retry-After").expectBody().jsonPath("$.code").isEqualTo("QUOTE_OWNER_UNAVAILABLE");
            verifyNoInteractions(f.owner);
        }
    }
    @Test void genuineMissingOwnerUsesActualProblemJsonNegotiation() {
        try(var f=new Fixture(false)) {
            f.client.get().uri(BASE+"/"+OFFER).accept(MediaType.APPLICATION_PROBLEM_JSON).exchange().expectStatus().isEqualTo(503)
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON).expectBody().jsonPath("$.extensions.code").isEqualTo("QUOTE_OWNER_UNAVAILABLE");
        }
    }
    @Test void deterministicIntentFailureIsTyped400BeforeInvocation() {
        try(var f=new Fixture(true)) {
            var body=request();body.put("coverageEnd","2026-10-31");
            f.client.post().uri(BASE).bodyValue(body).exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("QUOTE_INPUT_INVALID");
            verifyNoInteractions(f.owner);
        }
    }
    @Test void actualBeanValidationRemainsTyped400() {
        try(var f=new Fixture(true)) {
            var body=request();body.remove("actionId");
            f.client.post().uri(BASE).bodyValue(body).exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
            verifyNoInteractions(f.owner);
        }
    }
    @Test void missingControlExpectationDoesNotSilentlyDefaultToZero() {
        try(var f=new Fixture(true)) {
            f.client.post().uri(BASE+"/"+OFFER+"/acceptances").bodyValue(Map.of("actionId",ACTION.toString(),"offerVersion",1,"termsDigest","b".repeat(64)))
                    .exchange().expectStatus().isBadRequest();verifyNoInteractions(f.owner);
        }
    }
    @Test void protectedEndpointsOptOutOfTransportIdempotencyCache() {
        var methods=Arrays.stream(QuoteGovernedController.class.getDeclaredMethods()).filter(method->Set.of("offer","accept","current","history","recover").contains(method.getName())).toList();
        assertThat(methods).hasSize(5);
        for(var method:methods)assertThat(method.isAnnotationPresent(org.fireflyframework.web.idempotency.annotation.DisableIdempotency.class)).isTrue();
    }
    @ParameterizedTest @EnumSource(QuoteGovernedService.Status.class)
    void allFourOwnerStatusesRemain200WithOriginalIdentity(QuoteGovernedService.Status status) {
        try(var f=new Fixture(true)) {
            when(f.owner.offer(any())).thenReturn(Mono.just(new QuoteGovernedService.WriteOutcome(status,ACTION,
                    status==QuoteGovernedService.Status.COMMITTED?Optional.of(result(intent())):Optional.empty())));
            var body=f.client.post().uri(BASE).bodyValue(request()).exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("Cache-Control","no-store").expectBody()
                    .jsonPath("$.status").isEqualTo(status.name()).jsonPath("$.actionId").isEqualTo(ACTION.toString())
                    .jsonPath("$.intentDigest").isEqualTo(intent().intentDigest());
            if(status==QuoteGovernedService.Status.COMMITTED)body.jsonPath("$.result.resultId").isEqualTo(id(8).toString());
            else body.jsonPath("$.result").doesNotExist();
            verify(f.owner).offer(intent());verifyNoMoreInteractions(f.owner);
        }
    }
    @Test void opaqueOwnerFailureIsUnknownWithoutRawDetailOrRetryAdvice() {
        try(var f=new Fixture(true)) {
            when(f.owner.offer(any())).thenReturn(Mono.error(new SecurityException("SYNTHETIC-DO-NOT-DISCLOSE")));
            f.client.post().uri(BASE).bodyValue(request()).exchange().expectStatus().isOk().expectHeader().doesNotExist("Retry-After")
                    .expectBody().jsonPath("$.status").isEqualTo("OUTCOME_UNKNOWN").jsonPath("$.actionId").isEqualTo(ACTION.toString())
                    .jsonPath("$.intentDigest").isEqualTo(intent().intentDigest()).jsonPath("$.result").doesNotExist()
                    .consumeWith(value->assertThat(new String(value.getResponseBody(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("DO-NOT-DISCLOSE"));
        }
    }
    @Test void emptyOwnerWriteIsUnknownRatherThanEmptySuccess() {
        try(var f=new Fixture(true)) {
            when(f.owner.offer(any())).thenReturn(Mono.empty());
            f.client.post().uri(BASE).bodyValue(request()).exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("OUTCOME_UNKNOWN");
        }
    }
    @Test void mismatchedOwnerResultCannotExposeForeignProtectedData() {
        try(var f=new Fixture(true)) {
            var foreign=new QuoteOfferRequestIntent(new QuoteOfferScope(id(100),SCOPE.submissionId(),SCOPE.partyId(),SCOPE.productId()),ACTION,
                    intent().coverageStart(),intent().coverageEnd(),intent().expectedProductRevisionId(),intent().expectedProductDigest());
            when(f.owner.offer(any())).thenReturn(Mono.just(new QuoteGovernedService.WriteOutcome(QuoteGovernedService.Status.COMMITTED,ACTION,Optional.of(result(foreign)))));
            f.client.post().uri(BASE).bodyValue(request()).exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.status").isEqualTo("OUTCOME_UNKNOWN").jsonPath("$.result").doesNotExist();
        }
    }
    @Test void bodyClaimsCannotReplacePathScopeOrSupplyAuthority() {
        try(var f=new Fixture(true)) {
            when(f.owner.offer(any())).thenReturn(Mono.just(new QuoteGovernedService.WriteOutcome(QuoteGovernedService.Status.REFUSED,ACTION,Optional.empty())));
            var body=request();body.put("tenantId",id(100).toString());body.put("issuer","SYNTHETIC-UNTRUSTED");body.put("principal_kind","HUMAN");
            f.client.post().uri(BASE).bodyValue(body).exchange().expectStatus().isOk();verify(f.owner).offer(intent());verifyNoMoreInteractions(f.owner);
        }
    }
    @Test void acceptanceBindsTheOriginalCapturedIntent() {
        try(var f=new Fixture(true)) {
            var accepted=new QuoteAcceptanceIntent(SCOPE,OFFER,1,0,"b".repeat(64),ACTION);
            when(f.owner.accept(any())).thenReturn(Mono.just(new QuoteGovernedService.WriteOutcome(QuoteGovernedService.Status.REFUSED,ACTION,Optional.empty())));
            f.client.post().uri(BASE+"/"+OFFER+"/acceptances").bodyValue(Map.of("actionId",ACTION.toString(),"offerVersion",1,
                    "expectedControlGeneration",0,"termsDigest","b".repeat(64))).exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.actionId").isEqualTo(ACTION.toString()).jsonPath("$.intentDigest").isEqualTo(accepted.intentDigest());
            verify(f.owner).accept(accepted);verifyNoMoreInteractions(f.owner);
        }
    }
    @Test void authorizedEmptyReadIsOnlyNotObservedAtThisRead() {
        try(var f=new Fixture(true)) {
            when(f.owner.current(SCOPE,OFFER)).thenReturn(Mono.just(Optional.empty()));
            f.client.get().uri(BASE+"/"+OFFER).exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store")
                    .expectBody().jsonPath("$.state").isEqualTo("NOT_OBSERVED_AT_THIS_READ").jsonPath("$.entry").doesNotExist();
            verify(f.owner).current(SCOPE,OFFER);verifyNoMoreInteractions(f.owner);
        }
    }
    @Test void currentAndHistoryKeepProtectedScopeAndOwnerOrder() {
        try(var f=new Fixture(true)) {
            when(f.owner.current(SCOPE,OFFER)).thenReturn(Mono.just(Optional.of(readView(OFFER))));
            when(f.owner.history(SCOPE)).thenReturn(Mono.just(List.of(readView(OFFER),readView(id(10)))));
            f.client.get().uri(BASE+"/"+OFFER).exchange().expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("FOUND")
                    .jsonPath("$.entry.offer.offerId").isEqualTo(OFFER.toString()).jsonPath("$.entry.termsDigest").isEqualTo(readView(OFFER).offer().termsDigest());
            f.client.get().uri(BASE).exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store").expectBody()
                    .jsonPath("$.entries[0].offer.offerId").isEqualTo(OFFER.toString()).jsonPath("$.entries[1].offer.offerId").isEqualTo(id(10).toString());
            verify(f.owner).current(SCOPE,OFFER);verify(f.owner).history(SCOPE);verifyNoMoreInteractions(f.owner);
        }
    }
    @Test void readFailureIsNotFabricatedAbsenceOr503() {
        try(var f=new Fixture(true)) {
            when(f.owner.current(SCOPE,OFFER)).thenReturn(Mono.error(new IllegalStateException("SYNTHETIC-DO-NOT-DISCLOSE")));
            f.client.get().uri(BASE+"/"+OFFER).exchange().expectStatus().isEqualTo(500).expectBody()
                    .jsonPath("$.state").doesNotExist().consumeWith(value->assertThat(new String(value.getResponseBody(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("DO-NOT-DISCLOSE"));
        }
    }
    @ParameterizedTest @EnumSource(QuoteGovernedService.RecoveryState.class)
    void recoveryStatesNeverBecomeAnotherWrite(QuoteGovernedService.RecoveryState state) {
        try(var f=new Fixture(true)) {
            when(f.owner.recover(SCOPE,ACTION,intent().intentDigest())).thenReturn(Mono.just(new QuoteGovernedService.Recovery(state,ACTION,
                    state==QuoteGovernedService.RecoveryState.FOUND?Optional.of(result(intent())):Optional.empty())));
            f.client.get().uri(BASE+"/actions/"+ACTION+"/recovery?expectedIntentDigest="+intent().intentDigest()).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo(state.name()).jsonPath("$.actionId").isEqualTo(ACTION.toString());
            verify(f.owner).recover(SCOPE,ACTION,intent().intentDigest());verifyNoMoreInteractions(f.owner);
        }
    }
    @Test void cancellingTheBoundaryCancelsTheOriginalOwnerSubscription() {
        try(var f=new Fixture(true)) {
            var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
            when(f.owner.offer(any())).thenReturn(Mono.<QuoteGovernedService.WriteOutcome>never().doOnCancel(()->cancelled.set(true)));
            var controller=f.context.getBean(QuoteGovernedController.class);
            var request=new io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.http.QuoteOfferRequest(ACTION,
                    intent().coverageStart(),intent().coverageEnd(),intent().expectedProductRevisionId(),intent().expectedProductDigest());
            reactor.test.StepVerifier.create(controller.offer(SCOPE.tenantId(),SCOPE.submissionId(),SCOPE.partyId(),SCOPE.productId(),request))
                    .thenCancel().verify();
            assertThat(cancelled).isTrue();verify(f.owner).offer(intent());verifyNoMoreInteractions(f.owner);
        }
    }
    @Test void emptyRecoveryAndReadCannotBecomeNotObserved() {
        try(var f=new Fixture(true)) {
            when(f.owner.recover(any(),any(),anyString())).thenReturn(Mono.empty());when(f.owner.current(any(),any())).thenReturn(Mono.empty());
            f.client.get().uri(BASE+"/actions/"+ACTION+"/recovery?expectedIntentDigest="+intent().intentDigest()).exchange().expectStatus().isEqualTo(500);
            f.client.get().uri(BASE+"/"+OFFER).exchange().expectStatus().isEqualTo(500);verify(f.owner).recover(SCOPE,ACTION,intent().intentDigest());verify(f.owner).current(SCOPE,OFFER);verifyNoMoreInteractions(f.owner);
        }
    }
}
