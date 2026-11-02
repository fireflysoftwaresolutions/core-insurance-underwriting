package io.fireflysoftware.core.insurance.underwriting.interfaces.governance;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GovernedUnderwritingIntentTest {
    final UUID tenant=UUID.randomUUID(),submission=UUID.randomUUID(),risk=UUID.randomUUID(),action=UUID.randomUUID(),referral=UUID.randomUUID();
    final String digest="a".repeat(64);
    GovernedUnderwritingIntent create(){return new GovernedUnderwritingIntent(GovernedUnderwritingIntent.Operation.REFERRAL_CREATE,action,submission,risk,null,0,null,"Property referral requires delegated review");}
    @Test void originalActionAndServerResourceMappingBindTheSameOwnerDigest(){var r=create();assertEquals(r.intentDigest(tenant),r.intentDigest(tenant));assertNotEquals(r.intentDigest(tenant),r.intentDigest(UUID.randomUUID()));assertNotEquals(r.intentDigest(tenant),new GovernedUnderwritingIntent(r.operation(),UUID.randomUUID(),submission,risk,null,0,null,r.reason()).intentDigest(tenant));}
    @Test void referralCreationCannotBorrowAReceiptOrSelectAnExistingReferral(){assertThrows(IllegalArgumentException.class,()->new GovernedUnderwritingIntent(GovernedUnderwritingIntent.Operation.REFERRAL_CREATE,action,submission,risk,referral,0,null,"reason"));assertThrows(IllegalArgumentException.class,()->new GovernedUnderwritingIntent(GovernedUnderwritingIntent.Operation.REFERRAL_CREATE,action,submission,risk,null,0,digest,"reason"));}
    @Test void approvalRequiresTheExactCreatedReferralVersionAndReceipt(){assertNotNull(new GovernedUnderwritingIntent(GovernedUnderwritingIntent.Operation.REFERRAL_APPROVE,action,submission,risk,referral,0,digest,null));for(long version:new long[]{-1,1,Long.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->new GovernedUnderwritingIntent(GovernedUnderwritingIntent.Operation.REFERRAL_APPROVE,action,submission,risk,referral,version,digest,null));}
    @Test void decisionRequiresTheExactGovernedApprovalVersionAndReceipt(){assertNotNull(new GovernedUnderwritingIntent(GovernedUnderwritingIntent.Operation.DECISION_RECORD,action,submission,risk,referral,1,digest,null));assertThrows(IllegalArgumentException.class,()->new GovernedUnderwritingIntent(GovernedUnderwritingIntent.Operation.DECISION_RECORD,action,submission,risk,referral,0,digest,null));}
    @Test void existingApprovalAndDecisionRequestsCannotSupplyAuthorityOrFreeReasons(){for(var op:new GovernedUnderwritingIntent.Operation[]{GovernedUnderwritingIntent.Operation.REFERRAL_APPROVE,GovernedUnderwritingIntent.Operation.DECISION_RECORD})assertThrows(IllegalArgumentException.class,()->new GovernedUnderwritingIntent(op,action,submission,risk,referral,op==GovernedUnderwritingIntent.Operation.DECISION_RECORD?1:0,digest,"customer supplied authority"));}
    @Test void diagnosticsDoNotExposeOriginalSelectorsOrReason(){var r=create();assertFalse(r.toString().contains(submission.toString()));assertFalse(r.toString().contains(r.reason()));}
}
