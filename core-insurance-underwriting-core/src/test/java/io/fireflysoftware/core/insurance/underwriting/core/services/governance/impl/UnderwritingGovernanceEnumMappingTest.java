package io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl;

import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.*;
import io.fireflysoftware.core.insurance.underwriting.models.config.R2dbcConfiguration;
import io.fireflysoftware.core.insurance.underwriting.models.entities.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Update;
import org.springframework.r2dbc.core.binding.BindTarget;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnderwritingGovernanceEnumMappingTest {
    @Test void existingDecisionAndReferralEnumsRetainTheirOwnerNativeTypes(){
        var strategy=new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE,R2dbcConfiguration.governanceEnumConverters());
        var referral=strategy.getOutboundRow(Referral.builder().referralStatus(ReferralStatus.APPROVED).build());
        assertSame(ReferralStatus.APPROVED,referral.get(strategy.getMappingContext().getRequiredPersistentEntity(Referral.class).getRequiredPersistentProperty("referralStatus").getColumnName()).getValue());
        var decision=strategy.getOutboundRow(UnderwritingDecision.builder().decisionOutcome(DecisionOutcome.ACCEPT).build());
        assertSame(DecisionOutcome.ACCEPT,decision.get(strategy.getMappingContext().getRequiredPersistentEntity(UnderwritingDecision.class).getRequiredPersistentProperty("decisionOutcome").getColumnName()).getValue());
        var mapper=strategy.getStatementMapper().forType(Referral.class);
        var update=mapper.getMappedObject(mapper.createUpdate(strategy.getTableName(Referral.class),Update.update("referralStatus",ReferralStatus.APPROVED)).withCriteria(Criteria.where("referralStatus").is(ReferralStatus.OPEN)));
        var values=new ArrayList<Object>();BindTarget target=mock(BindTarget.class);doAnswer(call->{values.add(call.getArgument(1));return null;}).when(target).bind(anyInt(),any());update.bindTo(target);
        assertEquals(2,values.size());assertTrue(values.containsAll(List.of(ReferralStatus.APPROVED,ReferralStatus.OPEN)));assertTrue(values.stream().allMatch(ReferralStatus.class::isInstance));
        assertEquals("RECEIVED",strategy.getOutboundRow(Submission.builder().submissionStatus(SubmissionStatus.RECEIVED).build()).get(strategy.getMappingContext().getRequiredPersistentEntity(Submission.class).getRequiredPersistentProperty("submissionStatus").getColumnName()).getValue());
    }
}
