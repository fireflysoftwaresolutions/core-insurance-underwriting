package io.fireflysoftware.core.insurance.underwriting.web.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.core.insurance.underwriting.web.governance.impl.StaffUnderwritingPermissionExceptionHandlerImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

class StaffUnderwritingPermissionResponseTest{
    @Test void currentPermissionDenialCannotDiscloseTheInternalAuthorityMessage()throws Exception{var result=new StaffUnderwritingPermissionExceptionHandlerImpl().denied(new SecurityException("SYNTHETIC-PRIVATE-DETAIL"));assertEquals(HttpStatus.FORBIDDEN,result.getStatusCode());var body=new ObjectMapper().writeValueAsString(result.getBody());assertEquals("{\"code\":\"UNDERWRITING_CURRENT_STAFF_DELEGATION_UNAVAILABLE\"}",body);assertFalse(body.contains("SYNTHETIC-PRIVATE-DETAIL"));assertTrue(result.getHeaders().getCacheControl().contains("no-store"));assertTrue(result.getHeaders().getVary().contains("Authorization"));}
}
