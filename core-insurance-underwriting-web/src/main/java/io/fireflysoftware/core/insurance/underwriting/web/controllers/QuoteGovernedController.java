/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.web.controllers;

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.QuoteGovernedService;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.http.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.*;
import org.fireflyframework.web.error.models.ErrorResponse;
import org.fireflyframework.web.error.models.ProblemDetail;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.fireflyframework.web.error.exceptions.InvalidRequestException;
import org.fireflyframework.web.error.exceptions.ServiceUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@SecurityScheme(name="governedQuoteBearer",type=SecuritySchemeType.HTTP,scheme="bearer",bearerFormat="JWT")
@SecurityRequirement(name="governedQuoteBearer")
@ApiResponses({
    @ApiResponse(responseCode="401",description="Bearer authentication is required. Security-filter failures may have an empty body; framework-encoded failures use the shared schema.",content={
        @Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=ErrorResponse.class)),
        @Content(mediaType=MediaType.APPLICATION_PROBLEM_JSON_VALUE,schema=@Schema(implementation=ProblemDetail.class))}),
    @ApiResponse(responseCode="403",description="Current authorization is required. Security-filter failures may have an empty body; framework-encoded failures use the shared schema.",content={
        @Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=ErrorResponse.class)),
        @Content(mediaType=MediaType.APPLICATION_PROBLEM_JSON_VALUE,schema=@Schema(implementation=ProblemDetail.class))})})
@Validated
@RequestMapping("/api/v1/tenants/{tenantId}/submissions/{submissionId}/parties/{partyId}/products/{productId}/quote-offers")
@Tag(name="GovernedQuoteOffers",description="Default-off governed quote observations and action recovery; acceptance is not policy binding or payment.")
public class QuoteGovernedController {
    private final ObjectProvider<QuoteGovernedService> services;
    public QuoteGovernedController(ObjectProvider<QuoteGovernedService> services) {this.services=Objects.requireNonNull(services);}

    @DisableIdempotency
    @PostMapping
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=QuoteCommandResponse.class)))
    @Operation(summary="Request a governed quote offer",description="Returns the original action and intent with one of four explicit outcomes. Unknown is not admission, commit, refusal or permission to retry a new action.")
    public Mono<ResponseEntity<QuoteCommandResponse>> offer(@PathVariable UUID tenantId,@PathVariable UUID submissionId,
            @PathVariable UUID partyId,@PathVariable UUID productId,@Valid @RequestBody QuoteOfferRequest request) {
        return Mono.defer(()->{
            var scope=new QuoteOfferScope(tenantId,submissionId,partyId,productId);
            var intent=capture(()->Objects.requireNonNull(request).toIntent(scope));var owner=owner();
            return write(()->owner.offer(intent),intent.actionId(),intent.intentDigest(),action->action.matchesOffer(intent));
        });
    }
    @DisableIdempotency
    @PostMapping("/{offerId}/acceptances")
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=QuoteCommandResponse.class)))
    @Operation(summary="Accept exact governed quote terms",description="Requires the same action identity and exact offer/version/control/terms expectations. No policy bind or payment is performed.")
    public Mono<ResponseEntity<QuoteCommandResponse>> accept(@PathVariable UUID tenantId,@PathVariable UUID submissionId,
            @PathVariable UUID partyId,@PathVariable UUID productId,@PathVariable UUID offerId,@Valid @RequestBody QuoteAcceptanceRequest request) {
        return Mono.defer(()->{
            var scope=new QuoteOfferScope(tenantId,submissionId,partyId,productId);
            var intent=capture(()->Objects.requireNonNull(request).toIntent(scope,offerId));var owner=owner();
            return write(()->owner.accept(intent),intent.actionId(),intent.intentDigest(),action->action.matches(intent));
        });
    }
    @DisableIdempotency
    @GetMapping("/{offerId}")
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=QuoteReadResponse.class)))
    @Operation(summary="Observe the current authorized quote",description="An authorized empty observation is not proof that the offer never existed or cannot later be visible.")
    public Mono<ResponseEntity<QuoteReadResponse>> current(@PathVariable UUID tenantId,@PathVariable UUID submissionId,
            @PathVariable UUID partyId,@PathVariable UUID productId,@PathVariable UUID offerId) {
        return Mono.defer(()->{
            var scope=new QuoteOfferScope(tenantId,submissionId,partyId,productId);var owner=owner();
            return Mono.defer(()->owner.current(scope,offerId)).map(value->value
                    .map(found->new QuoteReadResponse(QuoteReadResponse.State.FOUND,entry(found,scope,offerId)))
                    .orElseGet(()->new QuoteReadResponse(QuoteReadResponse.State.NOT_OBSERVED_AT_THIS_READ,null)))
                    .switchIfEmpty(Mono.error(readUnavailable())).onErrorMap(error->readUnavailable()).map(QuoteGovernedController::ok);
        });
    }
    @DisableIdempotency
    @GetMapping
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=QuoteHistoryResponse.class)))
    @Operation(summary="Observe authorized governed quote history",description="Preserves the bounded owner's result order; no legacy CRUD fallback.")
    public Mono<ResponseEntity<QuoteHistoryResponse>> history(@PathVariable UUID tenantId,@PathVariable UUID submissionId,
            @PathVariable UUID partyId,@PathVariable UUID productId) {
        return Mono.defer(()->{
            var scope=new QuoteOfferScope(tenantId,submissionId,partyId,productId);var owner=owner();
            return Mono.defer(()->owner.history(scope)).map(values->new QuoteHistoryResponse(values.stream().map(value->entry(value,scope,null)).toList()))
                    .switchIfEmpty(Mono.error(readUnavailable())).onErrorMap(error->readUnavailable()).map(QuoteGovernedController::ok);
        });
    }
    @DisableIdempotency
    @GetMapping("/actions/{actionId}/recovery")
    @ApiResponse(responseCode="200",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=QuoteRecoveryResponse.class)))
    @Operation(summary="Recover the exact existing quote action",description="Never executes another offer or acceptance. Not observed and intent conflict are not success or permission for a new mutation.")
    public Mono<ResponseEntity<QuoteRecoveryResponse>> recover(@PathVariable UUID tenantId,@PathVariable UUID submissionId,
            @PathVariable UUID partyId,@PathVariable UUID productId,@PathVariable UUID actionId,
            @RequestParam @Pattern(regexp="[0-9a-f]{64}") String expectedIntentDigest) {
        return Mono.defer(()->{
            var scope=new QuoteOfferScope(tenantId,submissionId,partyId,productId);var owner=owner();
            return Mono.defer(()->owner.recover(scope,actionId,expectedIntentDigest)).map(value->{
                if(!actionId.equals(value.actionId()))throw readUnavailable();
                var result=value.result().orElse(null);
                if(result!=null && (!scope.equals(result.action().scope()) || !expectedIntentDigest.equals(result.action().intentDigest())))throw readUnavailable();
                return new QuoteRecoveryResponse(QuoteRecoveryResponse.State.valueOf(value.state().name()),actionId,expectedIntentDigest,result);
            }).switchIfEmpty(Mono.error(readUnavailable())).onErrorMap(error->readUnavailable()).map(QuoteGovernedController::ok);
        });
    }
    private static Mono<ResponseEntity<QuoteCommandResponse>> write(Supplier<Mono<QuoteGovernedService.WriteOutcome>> invocation,
            UUID actionId,String intentDigest,Predicate<QuoteActionEnvelope> matches) {
        var unknown=new QuoteCommandResponse(QuoteCommandResponse.Status.OUTCOME_UNKNOWN,actionId,intentDigest,null);
        return Mono.defer(invocation).map(value->{
            if(!actionId.equals(value.actionId()))throw new IllegalStateException("Quote response action mismatch");
            var result=value.result().orElse(null);
            if(result!=null && !matches.test(result.action()))throw new IllegalStateException("Quote response intent mismatch");
            return new QuoteCommandResponse(QuoteCommandResponse.Status.valueOf(value.status().name()),actionId,intentDigest,result);
        }).switchIfEmpty(Mono.just(unknown)).onErrorReturn(unknown).map(QuoteGovernedController::ok);
    }
    private static QuoteReadResponse.Entry entry(QuoteGovernedService.ReadView view,QuoteOfferScope scope,UUID expectedOfferId) {
        var offer=view.offer();
        if(!scope.equals(offer.scope()) || expectedOfferId!=null && !expectedOfferId.equals(offer.offerId()))throw readUnavailable();
        var receipt=view.receipt().orElse(null);
        if(receipt!=null && (!scope.equals(receipt.intent().scope()) || !offer.offerId().equals(receipt.intent().offerId())
                || offer.offerVersion()!=receipt.intent().offerVersion() || !offer.termsDigest().equals(receipt.intent().termsDigest())))throw readUnavailable();
        return new QuoteReadResponse.Entry(offer,offer.termsDigest(),receipt);
    }
    private QuoteGovernedService owner() {
        var owner=services.getIfAvailable();
        if(owner==null)throw new ServiceUnavailableException("QUOTE_OWNER_UNAVAILABLE","Trusted governed quote service required","insurance-governed-quotes",null,null);
        return owner;
    }
    private static <T> T capture(Supplier<T> capture) {
        try{return capture.get();}
        catch(IllegalArgumentException|NullPointerException invalid) {throw new InvalidRequestException("QUOTE_INPUT_INVALID","Exact supported quote request required");}
    }
    private static ResponseStatusException readUnavailable() {return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"QUOTE_READ_UNAVAILABLE");}
    private static <T> ResponseEntity<T> ok(T value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
