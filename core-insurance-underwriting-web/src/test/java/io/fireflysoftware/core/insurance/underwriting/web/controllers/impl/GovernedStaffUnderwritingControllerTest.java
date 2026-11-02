package io.fireflysoftware.core.insurance.underwriting.web.controllers.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.GovernedStaffUnderwritingService;
import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.*;
import com.fasterxml.jackson.databind.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class GovernedStaffUnderwritingControllerTest {
    GovernedStaffUnderwritingControllerImpl controller(){return new GovernedStaffUnderwritingControllerImpl(new StaticListableBeanFactory().getBeanProvider(GovernedStaffUnderwritingService.class));}
    GovernedUnderwritingIntent intent(){return new GovernedUnderwritingIntent(GovernedUnderwritingIntent.Operation.REFERRAL_CREATE,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,0,null,"Delegated property review");}
    @Test void missingTrustedOwnerCannotPrepareAnyMutation(){assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,()->controller().prepare(intent()).block()).getStatusCode());}
    @Test void missingTrustedOwnerCannotWriteDecisionOrReferral(){assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,()->controller().execute(new GovernedUnderwritingCommandRequest(intent(),"a".repeat(64))).block()).getStatusCode());}
    @Test void missingTrustedOwnerCannotTurnRecoveryIntoAbsence(){assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,()->controller().recover(UUID.randomUUID(),"a".repeat(64)).block()).getStatusCode());}
    @Test void unknownBodyAuthorityIsRefusedEvenByAnOtherwiseLenientMapper()throws Exception{var mapper=new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);var body=mapper.valueToTree(intent());for(String name:new String[]{"resourceTenantId","staffRealm","staffActorId","authorityReference","authorityLevel","approved"}){var supplied=body.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)supplied).put(name,"caller-supplied");assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->mapper.treeToValue(supplied,GovernedUnderwritingIntent.class));}}
}
