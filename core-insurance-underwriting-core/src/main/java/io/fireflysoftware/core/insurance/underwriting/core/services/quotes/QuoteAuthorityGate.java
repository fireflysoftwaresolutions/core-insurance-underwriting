/*
 * Copyright 2026 Firefly Software Foundation
 *
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


package io.fireflysoftware.core.insurance.underwriting.core.services.quotes;

import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteCanonicalCodec;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.QuoteAdmission;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.QuoteNativeWindow;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteAuthorityBudget;
import org.fireflyframework.security.api.domain.Decision;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** Current shared-API adapter. Signature validation belongs to the configured inbound security binding. */
public final class QuoteAuthorityGate {
    public enum Phase { PREPARE, COMMIT, READ, RESPONSE }
    public record Profile(String issuer,String audience,String tenantClaim,String delegatingClientClaim,String delegatingClientId) {
        public Profile {
            text(issuer,512);text(audience,255);text(delegatingClientId,255);
            if (tenantClaim==null || delegatingClientClaim==null || !tenantClaim.matches("[A-Za-z][A-Za-z0-9_:.]{0,63}")
                    || !delegatingClientClaim.matches("[A-Za-z][A-Za-z0-9_:.]{0,63}") || tenantClaim.equals(delegatingClientClaim)
                    || Set.of("party_id","principal_kind","exp","nbf","aud","sub","iss").contains(tenantClaim)
                    || Set.of("party_id","principal_kind","exp","nbf","aud","sub","iss").contains(delegatingClientClaim)) throw unavailable();
        }
    }
    public record Request(QuoteAdmission.Mode mode,Phase phase,UUID tenantId,UUID submissionId,UUID partyId,UUID productId,
            UUID offerId,UUID actionId,String intentDigest,Map<String,String> details) {
        public Request {
            Objects.requireNonNull(mode);Objects.requireNonNull(phase);Objects.requireNonNull(tenantId);Objects.requireNonNull(submissionId);Objects.requireNonNull(partyId);
            if (phase!=Phase.RESPONSE && switch(mode) {
                case OFFER_PREPARE -> phase!=Phase.PREPARE;case OFFER -> phase!=Phase.COMMIT;
                case ACCEPT -> phase!=Phase.PREPARE && phase!=Phase.COMMIT;case READ,HISTORY,RECOVER -> phase!=Phase.READ;
            }) throw unavailable();
            if (productId==null && mode!=QuoteAdmission.Mode.OFFER_PREPARE) throw unavailable();
            if (mode==QuoteAdmission.Mode.OFFER_PREPARE && offerId!=null) throw unavailable();
            if ((mode==QuoteAdmission.Mode.READ || mode==QuoteAdmission.Mode.ACCEPT || mode==QuoteAdmission.Mode.OFFER) && offerId==null) throw unavailable();
            if ((mode==QuoteAdmission.Mode.OFFER_PREPARE || mode==QuoteAdmission.Mode.OFFER || mode==QuoteAdmission.Mode.ACCEPT || mode==QuoteAdmission.Mode.RECOVER)
                    && (actionId==null || intentDigest==null || !intentDigest.matches("[0-9a-f]{64}"))) throw unavailable();
            details=Map.copyOf(details);
            if (details.size()>7 || !Set.of("expectedProductRevisionId","expectedProductDigest","coverageStart","coverageEnd","expectedOfferVersion","expectedControlGeneration","termsDigest").containsAll(details.keySet())) throw unavailable();
            details.values().forEach(value->text(value,512));
        }
        Request response() {return new Request(mode,Phase.RESPONSE,tenantId,submissionId,partyId,productId,offerId,actionId,intentDigest,details);}
        String resource() {return "urn:firefly:insurance:quote:tenant:"+tenantId+":submission:"+submissionId+(offerId==null?"":":offer:"+offerId)+(actionId==null?"":":action:"+actionId);}
        @Override public String toString() {return "QuoteAuthorityRequest";}
    }
    public record Actor(String issuer,String subject,UUID tenantId,UUID partyId,String audience,String delegatingClient,
            Instant expiresAt,Instant notBefore,Set<String> scopes) {
        public Actor {scopes=Set.copyOf(scopes);}
        @Override public String toString() {return "QuoteActor";}
    }
    public final class Permit {
        private final Actor actor;
        private final SecurityPrincipal principal;
        private final Request request;
        private final Instant until;
        private final String evidenceDigest;
        private final long beforeNanos;
        private final QuoteAuthorityBudget originalBudget;
        private Permit(Actor actor,SecurityPrincipal principal,Request request,Instant until,String evidenceDigest,long beforeNanos,Instant observedAt) {
            this.actor=actor;this.principal=principal;this.request=request;this.until=until;this.evidenceDigest=evidenceDigest;this.beforeNanos=beforeNanos;
            this.originalBudget=QuoteAuthorityBudget.observe(observedAt,until,beforeNanos,clock,nanos);
        }
        public Actor actor() {return actor;}
        public Request request() {return request;}
        public Instant until() {return until;}
        public String evidenceDigest() {return evidenceDigest;}
        public void requireLive() {
            long elapsed=nanos.getAsLong()-beforeNanos;
            if (!clock.instant().isBefore(until) || elapsed<0 || elapsed>=5_000_000_000L) throw unavailable();
        }
        public QuoteAdmission admission(Instant configUntil) {
            if (request.phase()==Phase.RESPONSE || request.mode()==QuoteAdmission.Mode.ACCEPT && request.phase()!=Phase.COMMIT) throw unavailable();
            return admission(request.mode(),request.offerId(),configUntil);
        }
        public QuoteAdmission acceptanceMetadataAdmission() {
            if (request.mode()!=QuoteAdmission.Mode.ACCEPT || request.phase()!=Phase.PREPARE) throw unavailable();
            return admission(QuoteAdmission.Mode.READ,request.offerId(),until);
        }
        private QuoteAdmission admission(QuoteAdmission.Mode mode,UUID offer,Instant configUntil) {
            requireLive();
            return new QuoteAdmission(UUID.randomUUID(),mode,request.tenantId(),request.submissionId(),request.partyId(),request.productId(),offer,request.actionId(),
                    actor.issuer(),actor.subject(),actor.expiresAt(),until,configUntil,evidenceDigest,originalBudget);
        }
        @Override public String toString() {return "QuoteAuthorityPermit";}
    }
    private final SecurityContextPort security;
    private final PolicyDecisionPort pdp;
    private final Profile profile;
    private final Clock clock;
    private final LongSupplier nanos;
    public QuoteAuthorityGate(SecurityContextPort security,PolicyDecisionPort pdp,Profile profile,Clock clock,LongSupplier nanos) {
        this.security=Objects.requireNonNull(security);this.pdp=Objects.requireNonNull(pdp);this.profile=Objects.requireNonNull(profile);
        this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
    }

    public Mono<Permit> authorize(Request request) {
        return Mono.defer(()->{
            Instant before=clock.instant();long start=nanos.getAsLong();
            return security.currentPrincipal().switchIfEmpty(Mono.error(unavailable())).flatMap(principal->{
                var actor=actor(principal,request,before);var context=context(request,actor);
                return decide(principal,request,context).then(Mono.defer(security::currentPrincipal).switchIfEmpty(Mono.error(unavailable())))
                    .map(current->{
                        if (!principal.equals(current) || !actor.equals(actor(current,request,clock.instant()))) throw unavailable();
                        var until=actor.expiresAt().isBefore(before.plusSeconds(5))?actor.expiresAt():before.plusSeconds(5);
                        var facts=new ArrayList<String>(List.of("QUOTE_LOCAL_PDP_OBSERVATION_1",actor.issuer(),actor.subject(),actor.audience(),actor.delegatingClient(),actor.expiresAt().toString(),actor.notBefore().toString(),request.resource(),request.mode().name(),request.phase().name(),before.toString(),until.toString(),"PERMIT"));
                        principal.scopes().stream().sorted().forEach(scope->{facts.add("scope");facts.add(scope);});
                        principal.authorities().stream().sorted().forEach(authority->{facts.add("authority");facts.add(authority);});
                        context.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{facts.add(entry.getKey());facts.add((String)entry.getValue());});
                        var permit=new Permit(actor,principal,request,until,QuoteCanonicalCodec.digest(QuoteCanonicalCodec.encodeFields(facts)),start,before);
                        permit.requireLive();return permit;
                    });
            });
        }).timeout(Duration.ofSeconds(2)).onErrorMap(error->unavailable());
    }

    public void requireSameActor(Permit original,Permit current) {
        original.requireLive();current.requireLive();if (!original.actor().equals(current.actor()) || !original.principal.equals(current.principal)) throw unavailable();
    }
    public <T> Mono<T> project(Permit original,QuoteNativeWindow window,Supplier<T> boundedProjection) {
        return Mono.defer(()->{
            original.requireLive();window.requireLive(clock.instant(),nanos.getAsLong());
            return authorize(original.request().response()).timeout(window.remaining(clock.instant(),nanos.getAsLong()))
                .map(current->{
                    requireSameActor(original,current);window.requireLive(clock.instant(),nanos.getAsLong());
                    T value=Objects.requireNonNull(boundedProjection.get());
                    original.requireLive();window.requireLive(clock.instant(),nanos.getAsLong());return value;
                });
        }).onErrorMap(error->unavailable());
    }
    private Actor actor(SecurityPrincipal p,Request request,Instant now) {
        text(p.issuer(),512);text(p.subject(),255);
        if (!profile.issuer().equals(p.issuer()) || !"HUMAN".equals(p.claim("principal_kind",String.class))) throw unavailable();
        var tenant=uuid(p.claim(profile.tenantClaim(),String.class));var party=uuid(p.claim("party_id",String.class));
        if (!tenant.toString().equals(p.tenantId()) || !tenant.equals(request.tenantId()) || !party.equals(request.partyId())) throw unavailable();
        var expiry=p.claim("exp",Instant.class);var nbf=p.claim("nbf",Instant.class);var audience=p.claim("aud",List.class);
        if (expiry==null || nbf==null || !now.isBefore(expiry) || now.isBefore(nbf) || !nbf.isBefore(expiry)
                || audience==null || audience.size()!=1 || !profile.audience().equals(audience.getFirst())
                || !profile.delegatingClientId().equals(p.claim(profile.delegatingClientClaim(),String.class))) throw unavailable();
        if (!p.hasScope(scope(request.mode())) || request.mode()==QuoteAdmission.Mode.RECOVER && !p.hasScope("insurance:quotes:read")) throw unavailable();
        return new Actor(p.issuer(),p.subject(),tenant,party,profile.audience(),profile.delegatingClientId(),expiry,nbf,p.scopes());
    }
    private Mono<Void> decide(SecurityPrincipal principal,Request request,Map<String,Object> context) {
        String action=action(request.mode());
        var decision=call(principal,action,request.resource(),context);
        return request.mode()==QuoteAdmission.Mode.RECOVER?call(principal,"insurance.quote.read",request.resource(),context).then(decision):decision;
    }
    private Mono<Void> call(SecurityPrincipal p,String action,String resource,Map<String,Object> context) {
        return Mono.defer(()->pdp.authorize(p,action,resource,context)).filter(d->d.effect()==Decision.Effect.PERMIT && d.obligations().isEmpty())
                .switchIfEmpty(Mono.error(unavailable())).then();
    }
    private static Map<String,Object> context(Request r,Actor a) {
        var result=new LinkedHashMap<String,Object>();result.put("profile","insurance-quote-human-self-party-1");result.put("phase",r.phase().name());
        result.put("tenantId",r.tenantId().toString());result.put("submissionId",r.submissionId().toString());result.put("partyId",r.partyId().toString());
        if (r.productId()!=null) result.put("productId",r.productId().toString());
        if (r.offerId()!=null) result.put("offerId",r.offerId().toString());if (r.actionId()!=null) result.put("actionId",r.actionId().toString());
        if (r.intentDigest()!=null) result.put("intentDigest",r.intentDigest());
        result.put("issuer",a.issuer());result.put("subject",a.subject());result.putAll(r.details());return Map.copyOf(result);
    }
    private static String scope(QuoteAdmission.Mode m) {return switch(m) {
        case OFFER_PREPARE,OFFER -> "insurance:quotes:offer";case ACCEPT -> "insurance:quotes:accept";case READ -> "insurance:quotes:read";
        case HISTORY -> "insurance:quotes:history:read";case RECOVER -> "insurance:quotes:recover";
    };}
    private static String action(QuoteAdmission.Mode m) {return switch(m) {
        case OFFER_PREPARE,OFFER -> "insurance.quote.offer";case ACCEPT -> "insurance.quote.accept";case READ -> "insurance.quote.read";
        case HISTORY -> "insurance.quote.history.read";case RECOVER -> "insurance.quote.recover";
    };}
    private static UUID uuid(String value) {
        if (value==null) throw unavailable();var id=UUID.fromString(value);if (!id.toString().equals(value)) throw unavailable();return id;
    }
    private static void text(String value,int max) {
        if (value==null || value.isBlank() || value.indexOf(0)>=0 || value.length()>max || QuoteCanonicalCodec.encodeFields(List.of(value)).length-8>max) throw unavailable();
    }
    private static IllegalStateException unavailable() {return new IllegalStateException("QUOTE_AUTHORITY_UNAVAILABLE");}
    @Override public String toString() {return "QuoteAuthorityGate";}
}
