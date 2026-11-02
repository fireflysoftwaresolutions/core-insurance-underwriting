package io.fireflysoftware.core.insurance.underwriting.web.controllers.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.GovernedStaffUnderwritingService;
import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/underwriting-governance")
@ConditionalOnProperty(prefix="insurance.underwriting.governed",name="enabled",havingValue="true")
@Tag(name="GovernedUnderwriting",description="Explicit bounded STAFF referral and unconditional ACCEPT owner records; no insurance cover or full lifecycle admission")
@SecurityRequirement(name="governedQuoteBearer")
public final class GovernedStaffUnderwritingControllerImpl {
    private final ObjectProvider<GovernedStaffUnderwritingService> services;
    public GovernedStaffUnderwritingControllerImpl(ObjectProvider<GovernedStaffUnderwritingService> services){this.services=services;}
    @DisableIdempotency
    @PostMapping(value="/intents",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="prepareGovernedUnderwritingIntent",summary="Read-only original STAFF underwriting intent preparation",description="Requires the current verified registered STAFF caller, current namespace mapping and delegated PDP decision. Grants no authority, reserves nothing and creates no business record.")
    public Mono<ResponseEntity<GovernedUnderwritingPreparedIntent>> prepare(@RequestBody GovernedUnderwritingIntent request){return Mono.defer(()->service().prepare(request)).map(this::response);}
    @DisableIdempotency
    @PostMapping(value="/actions",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="executeGovernedUnderwritingAction",summary="Create referral, approve by a distinct delegated staff actor, or record exact ACCEPT decision",description="Requires the immutable originally prepared action and digest plus fresh current STAFF delegation. Exact native owner effects, audit and durable original-action receipt share one transaction; no implicit retry or invented compensation.")
    public Mono<ResponseEntity<GovernedUnderwritingCommandResponse>> execute(@RequestBody GovernedUnderwritingCommandRequest request){return Mono.defer(()->service().execute(request)).map(this::response);}
    @DisableIdempotency
    @GetMapping(value="/actions/{actionId}",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="recoverGovernedUnderwritingAction",summary="Read the durable original STAFF underwriting action",description="Requires current owner recovery/read permission and the exact original digest. NOT_OBSERVED_AT_THIS_READ proves only that read, and never starts or authorizes another mutation.")
    public Mono<ResponseEntity<GovernedUnderwritingRecovery>> recover(@PathVariable UUID actionId,@RequestParam String intentDigest){return Mono.defer(()->service().recover(actionId,intentDigest)).map(this::response);}
    private GovernedStaffUnderwritingService service(){var service=services.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configured governed STAFF underwriting owner required");return service;}
    private <T> ResponseEntity<T> response(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).varyBy("Authorization").body(value);}
}
