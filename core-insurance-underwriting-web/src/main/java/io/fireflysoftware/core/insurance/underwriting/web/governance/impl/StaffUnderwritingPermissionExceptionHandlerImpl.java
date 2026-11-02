package io.fireflysoftware.core.insurance.underwriting.web.governance.impl;

import io.fireflysoftware.core.insurance.underwriting.web.controllers.impl.GovernedStaffUnderwritingControllerImpl;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Hidden
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=GovernedStaffUnderwritingControllerImpl.class)
@ConditionalOnProperty(prefix="insurance.underwriting.governed",name={"enabled","runtime.enabled"},havingValue="true")
public class StaffUnderwritingPermissionExceptionHandlerImpl{
    public record Failure(String code){}
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Failure> denied(SecurityException error){return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore().cachePrivate()).varyBy("Authorization").body(new Failure("UNDERWRITING_CURRENT_STAFF_DELEGATION_UNAVAILABLE"));}
}
