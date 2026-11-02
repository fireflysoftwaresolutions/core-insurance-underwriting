package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.UnderwritingGovernanceValues;
import org.fireflyframework.security.api.domain.PolicyEvaluationProvenance;
import java.util.Objects;

public record StaffUnderwritingDecisionEvidence(PolicyEvaluationProvenance authorization,PolicyEvaluationProvenance admission) {
    public StaffUnderwritingDecisionEvidence {Objects.requireNonNull(authorization);Objects.requireNonNull(admission);for(var value:java.util.List.of(authorization,admission)){UnderwritingGovernanceValues.text(value.policyRevision(),128);UnderwritingGovernanceValues.text(value.decisionId(),1024);}}
    public String digest(){return UnderwritingGovernanceValues.hash("CORE_UNDERWRITING_PDP_DECISIONS_V1",authorization.policyRevision(),authorization.decisionId(),admission.policyRevision(),admission.decisionId());}
    @Override public String toString(){return "StaffUnderwritingDecisionEvidence[redacted]";}
}
