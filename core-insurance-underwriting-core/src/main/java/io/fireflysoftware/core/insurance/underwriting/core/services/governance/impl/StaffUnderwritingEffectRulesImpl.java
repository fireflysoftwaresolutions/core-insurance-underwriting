package io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.StaffUnderwritingAdmission;
import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.GovernedUnderwritingIntent;
import io.fireflysoftware.core.insurance.underwriting.models.governance.UnderwritingGovernanceRows;
import java.util.Objects;

public final class StaffUnderwritingEffectRulesImpl {
    private StaffUnderwritingEffectRulesImpl(){}
    public static void source(StaffUnderwritingAdmission.Permit permit,UnderwritingGovernanceRows.Source source){
        var intent=permit.request().intent();
        if(intent==null || source==null || !permit.resourceTenantId().equals(source.tenantId()) || !intent.submissionId().equals(source.submissionId()) || !intent.riskId().equals(source.riskId())
                || source.partyId()==null || !source.partyId().equals(source.riskPartyId()) || source.productId()==null || !"PROPERTY".equals(source.objectType()) || !"PROPERTY".equals(source.lineOfBusiness()) || !"USD".equals(source.currency()) || source.valueId()==null || source.declaredValue()==null || source.declaredValue().signum()<=0 || !"USD".equals(source.valueCurrency()) || source.riskUpdatedAt()==null || source.valueUpdatedAt()==null || !java.util.Set.of("RECEIVED","QUOTED","REFERRED").contains(source.status()))throw refused();
    }
    public static String digest(UnderwritingGovernanceRows.Source source){
        java.math.BigDecimal amount;try{amount=source.declaredValue().setScale(2,java.math.RoundingMode.UNNECESSARY);if(amount.precision()>19)throw refused();}catch(ArithmeticException invalid){throw refused();}
        return io.fireflysoftware.core.insurance.underwriting.interfaces.governance.UnderwritingGovernanceValues.hash("CORE_UNDERWRITING_SOURCE_V1",source.tenantId().toString(),source.submissionId().toString(),source.partyId().toString(),source.productId().toString(),source.riskId().toString(),source.riskPartyId().toString(),source.objectType(),source.status(),source.lineOfBusiness(),source.currency(),source.valueId().toString(),Integer.toString(source.lineNumber()),amount.toPlainString(),source.valueCurrency(),source.riskUpdatedAt().toInstant().toString(),source.valueUpdatedAt().toInstant().toString());
    }
    public static void control(StaffUnderwritingAdmission.Permit permit,UnderwritingGovernanceRows.Source source,UnderwritingGovernanceRows.ReferralControl control){
        source(permit,source);var intent=permit.request().intent();
        if(control==null || control.creatorStaffRealm()==null || control.creatorStaffId()==null || control.currentUpdatedAt()==null || !Objects.equals(control.referralId(),intent.referralId()) || !source.tenantId().equals(control.tenantId()) || !source.submissionId().equals(control.submissionId()) || !source.partyId().equals(control.partyId()) || !source.productId().equals(control.productId()) || !source.riskId().equals(control.riskId())
                || control.version()!=intent.expectedReferralVersion() || !intent.expectedReceiptDigest().equals(control.currentReceiptDigest()) || !permit.authorityLevel().equals(control.authorityLevel()))throw refused();
        if(intent.operation()==GovernedUnderwritingIntent.Operation.REFERRAL_APPROVE){
            if(!"OPEN".equals(control.state()) || permit.actor().staffRealm().equals(control.creatorStaffRealm()) && permit.actor().staffId().equals(control.creatorStaffId()) || control.approverIssuer()!=null || control.approverSubject()!=null || control.approverStaffRealm()!=null || control.approverStaffId()!=null)throw refused();
        }else if(intent.operation()==GovernedUnderwritingIntent.Operation.DECISION_RECORD){
            if(!Objects.equals(digest(source),control.approvedSourceDigest()) || !"APPROVED".equals(control.state()) || control.approverIssuer()==null || control.approverSubject()==null || control.approverStaffRealm()==null || control.approverStaffId()==null || Objects.equals(control.creatorStaffRealm(),control.approverStaffRealm()) && Objects.equals(control.creatorStaffId(),control.approverStaffId()))throw refused();
        }else throw refused();
    }
    private static SecurityException refused(){return new SecurityException("Exact current governed underwriting effect prerequisites required");}
}
