package io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.QuoteTenantBindingResolver;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.PolicyEvaluationPort;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled current SPI and policy observations only; no signed HTTP or persisted read is claimed. */
class CurrentStaffSubmissionReadAdmissionTest {
    final Instant now=Instant.parse("2026-11-02T12:00:00Z");
    final String issuer="https://controlled-staff.invalid",namespace="opaque:insurer:tenant",digest="a".repeat(64);
    final UUID resource=UUID.randomUUID(),binding=UUID.randomUUID(),status=UUID.randomUUID(),staff=UUID.randomUUID(),submission=UUID.randomUUID();
    final AtomicLong nanos=new AtomicLong();
    final AtomicReference<Instant> wall=new AtomicReference<>(now);
    final AtomicInteger lookups=new AtomicInteger(),evaluations=new AtomicInteger(),reads=new AtomicInteger();
    final AtomicReference<SecurityPrincipal> current=new AtomicReference<>(principal("STAFF","domain-staff",List.of("core-underwriting"),namespace,Set.of("insurance:underwriting:submissions:read")));
    final List<Map<String,Object>> contexts=new ArrayList<>();
    boolean differentBinding,changeDuringPolicy,omitProvenance,deny,principalUnavailable;
    SecurityPrincipal principal(String kind,String client,List<String> audience,String tenant,Set<String> scopes){
        return SecurityPrincipal.builder().issuer(issuer).subject("current-staff-subject").tenantId(tenant).scopes(scopes)
            .claims(Map.of("principal_kind",kind,"staff_actor_id",staff.toString(),"tenant_id",tenant,"client_id",client,"aud",audience,"exp",now.plusSeconds(60),"nbf",now.minusSeconds(1))).build();
    }
    CurrentStaffSubmissionReadAdmissionImpl authority(){
        QuoteTenantBindingResolver resolver=(actor,expected,budget,until,remaining)->{
            lookups.incrementAndGet();assertSame(current.get(),actor);assertEquals(resource,expected);
            return Mono.just(new QuoteTenantBindingResolver.Observation(issuer,namespace,resource,binding,differentBinding?2:1,digest,1,0,status,0,now,now.plusSeconds(5),now.plusSeconds(60)));
        };
        PolicyEvaluationPort pdp=(actor,action,path,facts)->{
            evaluations.incrementAndGet();contexts.add(facts);assertEquals("insurance.underwriting.submission.read",action);
            assertEquals("urn:firefly:insurance:underwriting:tenant:"+resource+":submission:"+submission,path);
            if(changeDuringPolicy)current.set(principal("HUMAN","domain-staff",List.of("core-underwriting"),namespace,Set.of("insurance:underwriting:submissions:read")));
            return Mono.just(new PolicyEvaluation(deny?Decision.deny("controlled refusal"):Decision.permit(),omitProvenance?Optional.empty():Optional.of(new PolicyEvaluationProvenance("read-policy-1","read-decision-"+evaluations.get()))));
        };
        return new CurrentStaffSubmissionReadAdmissionImpl(()->principalUnavailable?Mono.never():Mono.just(current.get()),pdp,resolver,
            new CurrentStaffSubmissionReadAdmissionImpl.Profile(issuer,"core-underwriting","domain-staff",namespace,resource,"canonical-staff-realm"),new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId ignored){return this;}public Instant instant(){return wall.get();}},nanos::get);
    }
    @Test void readPurposeUsesExactCurrentSubmissionAndTenantWithoutActionOrIntent(){
        var a=authority();String result=a.read(submission,permit->a.revalidate(permit).then(Mono.fromSupplier(()->{
            reads.incrementAndGet();assertEquals("READ_SUBMISSION",permit.purpose().name());assertEquals(submission,permit.submissionId());assertEquals(resource,permit.resourceTenantId());return "controlled-owner-read";
        }))).block();assertEquals("controlled-owner-read",result);assertEquals(1,reads.get());assertEquals(2,evaluations.get());
        for(var facts:contexts){assertEquals("READ_SUBMISSION",facts.get("purpose"));assertEquals(resource,facts.get("resourceTenantId"));assertEquals(submission,facts.get("submissionId"));assertFalse(facts.containsKey("actionId"));assertFalse(facts.containsKey("intentDigest"));}
        assertEquals(List.of("authorize","response"),contexts.stream().map(v->v.get("role")).toList());
    }
    @Test void wrongKindClientAudienceNamespaceAndBroaderScopeNeverReachRead(){
        for(var caller:List.of(principal("HUMAN","domain-staff",List.of("core-underwriting"),namespace,Set.of("insurance:underwriting:submissions:read")),
            principal("STAFF","wrong-client",List.of("core-underwriting"),namespace,Set.of("insurance:underwriting:submissions:read")),
            principal("STAFF","domain-staff",List.of("core-underwriting","extra"),namespace,Set.of("insurance:underwriting:submissions:read")),
            principal("STAFF","domain-staff",List.of("core-underwriting"),resource.toString(),Set.of("insurance:underwriting:submissions:read")),
            principal("STAFF","domain-staff",List.of("core-underwriting"),namespace,Set.of("insurance:underwriting:submissions:read","insurance:underwriting:referrals:create")))){
            current.set(caller);assertThrows(SecurityException.class,()->authority().read(submission,p->{reads.incrementAndGet();return Mono.just("unexpected");}).block());
        }
        assertEquals(0,lookups.get());assertEquals(0,evaluations.get());assertEquals(0,reads.get());
    }
    @Test void currentPrincipalChangedByPolicyRefusesBeforeRead(){changeDuringPolicy=true;assertThrows(SecurityException.class,()->authority().read(submission,p->{reads.incrementAndGet();return Mono.just("unexpected");}).block());assertEquals(0,reads.get());}
    @Test void currentPrincipalChangedAfterReadWithholdsResponse(){assertThrows(SecurityException.class,()->authority().read(submission,p->{reads.incrementAndGet();current.set(principal("HUMAN","domain-staff",List.of("core-underwriting"),namespace,Set.of("insurance:underwriting:submissions:read")));return Mono.just("controlled-read-not-returned");}).block());assertEquals(1,reads.get());}
    @Test void changedBindingAfterReadWithholdsResponse(){assertThrows(SecurityException.class,()->authority().read(submission,p->{reads.incrementAndGet();differentBinding=true;return Mono.just("controlled-read-not-returned");}).block());assertEquals(1,reads.get());}
    @Test void deniedOrUnprovenancedPolicyNeverReachesRead(){for(boolean missing:List.of(false,true)){omitProvenance=missing;deny=!missing;assertThrows(SecurityException.class,()->authority().read(submission,p->{reads.incrementAndGet();return Mono.just("unexpected");}).block());}assertEquals(0,reads.get());}
    @Test void monotonicOriginalBudgetCannotRenewAfterRead(){assertThrows(SecurityException.class,()->authority().read(submission,p->{reads.incrementAndGet();nanos.addAndGet(Duration.ofSeconds(2).toNanos());assertEquals(Duration.ofSeconds(3),p.operationBudget().remaining());nanos.addAndGet(Duration.ofSeconds(4).toNanos());return Mono.just("controlled-read-not-returned");}).block());assertEquals(1,reads.get());}
    @Test void retainedPermitIsInvalidAfterCompletionAndDuringAnotherRead(){var a=authority();var retained=new AtomicReference<CurrentStaffSubmissionReadAdmissionImpl.Permit>();assertEquals("read",a.read(submission,p->{retained.set(p);return Mono.just("read");}).block());assertThrows(SecurityException.class,()->a.revalidate(retained.get()).block());assertThrows(SecurityException.class,()->a.read(submission,p->a.revalidate(retained.get()).thenReturn("unexpected")).block());}
    @Test void nestedReadCannotCreateAnotherAdmission(){var a=authority();assertThrows(SecurityException.class,()->a.read(submission,p->{int prior=evaluations.get();return a.read(submission,nested->{reads.incrementAndGet();return Mono.just("unexpected");}).doFinally(signal->assertEquals(prior,evaluations.get()));}).block());assertEquals(0,reads.get());}
    @Test void wallClockRollbackCannotExtendOriginalReadObservation(){assertThrows(SecurityException.class,()->authority().read(submission,p->{wall.set(now.minusSeconds(1));return Mono.just("controlled-read-not-returned");}).block());}
    @Test void unavailableCurrentPrincipalCannotHoldAnUnboundedRead(){principalUnavailable=true;reactor.test.StepVerifier.withVirtualTime(()->authority().read(submission,p->{reads.incrementAndGet();return Mono.just("unexpected");})).thenAwait(Duration.ofSeconds(6)).expectError(SecurityException.class).verify(Duration.ofSeconds(1));assertEquals(0,reads.get());}
    @Test void missingSubmissionIsRejectedBeforeCurrentAuthorityLookup(){assertThrows(IllegalArgumentException.class,()->authority().read(new UUID(0,0),p->Mono.just("unexpected")).block());assertEquals(0,lookups.get());}
}
