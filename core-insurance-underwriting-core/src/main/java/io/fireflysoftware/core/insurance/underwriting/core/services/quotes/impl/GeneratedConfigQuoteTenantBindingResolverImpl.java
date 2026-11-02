/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.core.services.quotes.impl;

import io.fireflysoftware.common.config.sdk.api.TenantResourceBindingCurrentApi;
import io.fireflysoftware.common.config.sdk.invoker.ApiClient;
import io.fireflysoftware.common.config.sdk.model.TenantResourceBindingCurrent;
import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.QuoteTenantBindingResolver;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteAuthorityBudget;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

/** Genuine CURRENT and own configured machine client; the response is not a human attestation. */
public final class GeneratedConfigQuoteTenantBindingResolverImpl implements QuoteTenantBindingResolver {
    private record Lookup(SecurityPrincipal principal,UUID resource,QuoteAuthorityBudget original,Instant until,Supplier<Duration> remaining) {
        void requireLive() {remaining.get();original.remaining(until);remaining.get();}
    }
    private final SecurityContextPort principals;
    private final TenantResourceBindingCurrentApi current;
    private final String issuer,platformTenant;
    private final Clock clock;
    private final Object lookupKey=new Object();

    public GeneratedConfigQuoteTenantBindingResolverImpl(SecurityContextPort principals,ReactiveOAuth2AuthorizedClientManager manager,
            WebClient client,URI endpoint,String registrationId,String issuer,String platformTenant,Clock clock) {
        this.principals=Objects.requireNonNull(principals);Objects.requireNonNull(manager);Objects.requireNonNull(client);
        this.clock=Objects.requireNonNull(clock);this.issuer=namespace(issuer,500);this.platformTenant=namespace(platformTenant,1024);
        if(registrationId==null || !registrationId.matches("[A-Za-z0-9._-]{1,100}") || endpoint==null
                || !"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getUserInfo()!=null
                || endpoint.getQuery()!=null || endpoint.getFragment()!=null || !endpoint.normalize().equals(endpoint)
                || endpoint.getRawPath().contains("%") || endpoint.getPort()==0 || endpoint.getPort()>65535)throw invalid();
        String base=endpoint.toASCIIString().replaceFirst("/$","");
        URI exact=URI.create(base+"/api/v1/tenant-resource-bindings/current");
        var machine=UsernamePasswordAuthenticationToken.authenticated("insurance-quote-current-client",null,List.of());
        WebClient authorized=client.mutate().filter((request,next)->Mono.deferContextual(context->{
            Lookup lookup=context.getOrDefault(lookupKey,null);
            if(lookup==null)return Mono.error(denied());lookup.requireLive();
            if(request.method()!=HttpMethod.GET || !exact.equals(request.url()) || request.headers().containsHeader(HttpHeaders.AUTHORIZATION))return Mono.error(denied());
            return Mono.defer(()->manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(registrationId).principal(machine).build()))
                .switchIfEmpty(Mono.error(denied())).timeout(lookup.remaining.get()).flatMap(authorization->{
                    lookup.requireLive();var token=authorization.getAccessToken();
                    if(!registrationId.equals(authorization.getClientRegistration().getRegistrationId())
                            || !AuthorizationGrantType.CLIENT_CREDENTIALS.equals(authorization.getClientRegistration().getAuthorizationGrantType())
                            || token==null || !OAuth2AccessToken.TokenType.BEARER.equals(token.getTokenType()) || token.getTokenValue().isBlank()
                            || token.getExpiresAt()==null || !clock.instant().isBefore(token.getExpiresAt()))return Mono.error(denied());
                    lookup.requireLive();
                    return next.exchange(ClientRequest.from(request).headers(headers->headers.setBearerAuth(token.getTokenValue())).build())
                            .timeout(lookup.remaining.get());
                });
        })).build();
        current=new TenantResourceBindingCurrentApi(new ApiClient(authorized).setBasePath(base));
    }
    @Override public Mono<Observation> resolve(SecurityPrincipal expected,UUID expectedResourceTenant,QuoteAuthorityBudget originalBudget,
            Instant originalNotAfter,Supplier<Duration> remaining) {
        return Mono.defer(()->{
            Objects.requireNonNull(expected);Objects.requireNonNull(expectedResourceTenant);Objects.requireNonNull(originalBudget);
            Objects.requireNonNull(originalNotAfter);Objects.requireNonNull(remaining);
            var lookup=new Lookup(expected,expectedResourceTenant,originalBudget,originalNotAfter,remaining);lookup.requireLive();
            Instant started=clock.instant();
            return Mono.defer(principals::currentPrincipal).switchIfEmpty(Mono.error(denied())).flatMap(principal->{
                lookup.requireLive();Instant expiry=principal.claim("exp",Instant.class);
                if(!expected.equals(principal) || !issuer.equals(principal.issuer()) || !platformTenant.equals(principal.tenantId())
                        || !platformTenant.equals(principal.claim("tenant_id",String.class)) || expiry==null || !clock.instant().isBefore(expiry))return Mono.error(denied());
                return Mono.defer(current::observeCurrentTenantResourceBinding).switchIfEmpty(Mono.error(denied()))
                    .map(value->snapshot(value,lookup,started))
                    .flatMap(observation->Mono.defer(principals::currentPrincipal).switchIfEmpty(Mono.error(denied())).map(latest->{
                        lookup.requireLive();if(!expected.equals(latest))throw denied();return observation;
                    })).timeout(remaining.get()).contextWrite(context->context.put(lookupKey,lookup));
            }).timeout(remaining.get());
        }).onErrorMap(error->denied());
    }
    private Observation snapshot(TenantResourceBindingCurrent value,Lookup lookup,Instant started) {
        lookup.requireLive();
        var reference=value.getReference();var definition=value.getDefinition();
        if(reference==null || definition==null)throw denied();
        var namespace=definition.getNamespace();var resource=definition.getResourceTenant();var eligibility=definition.getEligibility();
        if(namespace==null || resource==null || eligibility==null)throw denied();
        UUID bindingId=reference.getBindingId(),definedBinding=definition.getBindingId(),tenant=resource.getValue(),statusId=eligibility.getStatusId();
        Long version=reference.getVersion(),definedVersion=definition.getVersion(),control=value.getControlVersion();
        Long tenantGeneration=eligibility.getTenantGeneration(),statusGeneration=eligibility.getStatusGeneration();
        String digest=reference.getDefinitionDigest(),observedIssuer=namespace.getIssuer(),observedPlatform=namespace.getPlatformTenant();
        Long observed=value.getObservedAtEpochMillis(),valid=value.getValidUntilEpochMillis(),business=definition.getNotAfterEpochMillis();
        Instant now=clock.instant();
        if(bindingId==null || !bindingId.equals(definedBinding) || version==null || !version.equals(definedVersion)
                || tenant==null || !tenant.equals(lookup.resource) || !issuer.equals(observedIssuer) || !platformTenant.equals(observedPlatform)
                || observed==null || valid==null || business==null || control==null || tenantGeneration==null || statusGeneration==null
                || business<1 || business>253402300799999L || observed<started.toEpochMilli() || observed>now.toEpochMilli()
                || valid<=now.toEpochMilli() || business<=now.toEpochMilli())throw denied();
        var snapshot=new Observation(observedIssuer,observedPlatform,tenant,bindingId,version,digest,control,tenantGeneration,statusId,statusGeneration,
                Instant.ofEpochMilli(observed),Instant.ofEpochMilli(valid),Instant.ofEpochMilli(business));
        lookup.requireLive();lookup.original.remaining(snapshot.validUntil());lookup.requireLive();return snapshot;
    }
    private static String namespace(String value,int max) {
        if(value==null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length>max
                || value.codePoints().anyMatch(c->Character.isISOControl(c) || c>=0xD800 && c<=0xDFFF))throw invalid();return value;
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Explicit quote namespace, HTTPS endpoint and machine registration required");}
    private static SecurityException denied() {return new SecurityException("Current trusted quote tenant binding unavailable");}
}
