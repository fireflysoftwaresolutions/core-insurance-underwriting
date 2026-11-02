package io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.StaffUnderwritingAdmission;
import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.QuoteTenantBindingResolver;
import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.*;
import io.fireflysoftware.core.insurance.underwriting.models.governance.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.fireflyframework.kernel.audit.AuditActor;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Controlled original-action store branches; physical rollback and process restart are separate gates. */
class StaffUnderwritingRetainedActorTest{
    final UUID tenant=UUID.randomUUID(),submission=UUID.randomUUID(),risk=UUID.randomUUID(),party=UUID.randomUUID(),product=UUID.randomUUID(),action=UUID.randomUUID(),staff=UUID.randomUUID(),referral=UUID.randomUUID(),bindingId=UUID.randomUUID();
    final String issuer="https://issuer.invalid",platform="opaque:original-tenant",digest="a".repeat(64),realm="canonical:staff";final Instant now=Instant.parse("2026-11-01T12:00:00Z");
    final R2dbcEntityTemplate template=mock(R2dbcEntityTemplate.class);final StaffUnderwritingAuditRootImpl root=mock(StaffUnderwritingAuditRootImpl.class);
    final UnderwritingGovernanceRepositories.Referrals referrals=mock(UnderwritingGovernanceRepositories.Referrals.class);final UnderwritingGovernanceRepositories.Actions actions=mock(UnderwritingGovernanceRepositories.Actions.class);final UnderwritingGovernanceRepositories.Native nativeContext=mock(UnderwritingGovernanceRepositories.Native.class);
    final GovernedUnderwritingIntent intent=new GovernedUnderwritingIntent(GovernedUnderwritingIntent.Operation.REFERRAL_CREATE,action,submission,risk,null,0,null,"review");
    AuditActor actor(String client,String tenant,String staffRealm,UUID staffId){return new AuditActor(issuer,"original-subject",client,tenant,staffRealm,staffId);}
    AuditActor original(){return actor("registered-client",platform,realm,staff);}
    StaffUnderwritingAdmission.Permit permit(AuditActor actor,boolean recovery){var binding=new QuoteTenantBindingResolver.Observation(issuer,actor.platformTenantId(),tenant,bindingId,1,digest,1,0,UUID.randomUUID(),0,now,now.plusSeconds(5),now.plusSeconds(60));return new StaffUnderwritingAdmission.Permit(new StaffUnderwritingAdmission.Request(recovery?null:intent,action,intent.intentDigest(tenant),recovery),binding,"PROPERTY_REVIEW",actor,"policy-revision",UUID.randomUUID(),UUID.randomUUID(),now,0,now.plusSeconds(5));}
    GovernedUnderwritingReceipt receipt(){return new GovernedUnderwritingReceipt(action,intent.intentDigest(tenant),intent.operation(),referral,0,null,digest);}
    UnderwritingGovernanceRows.Action retained(){var result=receipt();String json="{\"actionId\":\""+action+"\",\"intentDigest\":\""+result.intentDigest()+"\",\"operation\":\"REFERRAL_CREATE\",\"referralId\":\""+referral+"\",\"referralVersion\":0,\"decisionId\":null,\"receiptDigest\":\""+digest+"\"}";return new UnderwritingGovernanceRows.Action(UUID.randomUUID(),tenant,issuer,"original-subject","registered-client",platform,realm,staff,action,"REFERRAL_CREATE",result.intentDigest(),"{}",submission,party,product,risk,"{}",digest,"PROPERTY_REVIEW",referral,null,json,digest,UUID.randomUUID(),digest,digest,bindingId,1,digest,"policy-revision","authorize-id","policy-revision","admit-id",digest,UUID.randomUUID(),UUID.randomUUID());}
    @SuppressWarnings("unchecked") SpringDataGovernedUnderwritingStoreImpl store(){
        when(nativeContext.protocol()).thenReturn(Mono.just(true));when(nativeContext.read(any(),any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyLong())).thenReturn(Mono.just(true));when(nativeContext.receipt(any())).thenReturn(Mono.just(true));
        var source=new UnderwritingGovernanceRows.Source(tenant,submission,party,product,risk,party,"PROPERTY","QUOTED","PROPERTY","USD",UUID.randomUUID(),1,new java.math.BigDecimal("100000.00"),"USD",now.atOffset(ZoneOffset.UTC),now.atOffset(ZoneOffset.UTC));
        when(nativeContext.open(any(),any(),any(),any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyLong())).thenReturn(Mono.just(source));when(root.observeLockedSource(any(),any())).thenReturn(Mono.empty());
        when(actions.findByTenantIdAndOwnerIssuerAndOwnerSubjectAndActionId(any(),anyString(),anyString(),any())).thenReturn(Mono.just(retained()));
        doAnswer(call->((Supplier<Mono<GovernedUnderwritingRecovery>>)call.getArgument(1)).get()).when(root).<GovernedUnderwritingRecovery>read(any(),any());
        doAnswer(call->((Supplier<Mono<Optional<GovernedUnderwritingCommandResponse>>>)call.getArgument(6)).get().map(value->{assertTrue(value.isPresent());return value.orElseThrow();})).when(root).<GovernedUnderwritingCommandResponse>execute(any(),any(),any(),anyString(),any(),any(),any(),any());
        return new SpringDataGovernedUnderwritingStoreImpl(template,root,UUID::randomUUID,referrals,actions,nativeContext);
    }
    void unchanged(){verifyNoInteractions(template,referrals);verify(nativeContext,never()).receipt(any());}
    @Test void changedCanonicalStaffIdCannotReplayAnOriginalCommittedAction(){var store=store();assertThrows(SecurityException.class,()->store.execute(permit(actor("registered-client",platform,realm,UUID.randomUUID()),false)).block());unchanged();}
    @Test void changedCanonicalStaffIdCannotRecoverAnOriginalCommittedAction(){var store=store();assertThrows(SecurityException.class,()->store.recover(permit(actor("registered-client",platform,realm,UUID.randomUUID()),true)).block());unchanged();}
    @Test void sameStaffUuidInAnotherCanonicalRealmCannotRecover(){var store=store();assertThrows(SecurityException.class,()->store.recover(permit(actor("registered-client",platform,"foreign:realm",staff),true)).block());unchanged();}
    @Test void changedRegisteredClientOrOpaquePlatformNamespaceCannotRecover(){var store=store();for(var actor:List.of(actor("foreign-client",platform,realm,staff),actor("registered-client","opaque:foreign-tenant",realm,staff)))assertThrows(SecurityException.class,()->store.recover(permit(actor,true)).block());unchanged();}
    @Test void unchangedOriginalActorCanRecoverThroughANewStoreInstanceWithoutMutation(){var first=store();var second=new SpringDataGovernedUnderwritingStoreImpl(template,root,UUID::randomUUID,referrals,actions,nativeContext);assertNotSame(first,second);var result=second.recover(permit(original(),true)).block();assertNotNull(result);assertEquals(GovernedUnderwritingRecovery.State.FOUND,result.state());assertEquals(receipt(),result.receipt());verifyNoInteractions(template,referrals);verify(nativeContext).receipt(any());}
}
