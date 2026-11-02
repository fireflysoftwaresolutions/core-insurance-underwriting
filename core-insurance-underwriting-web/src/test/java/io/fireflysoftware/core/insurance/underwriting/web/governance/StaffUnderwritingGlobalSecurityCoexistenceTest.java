package io.fireflysoftware.core.insurance.underwriting.web.governance;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.GovernedStaffUnderwritingService;
import io.fireflysoftware.core.insurance.underwriting.core.services.governance.StaffUnderwritingRuntimeConfiguration;
import java.util.*;
import org.fireflyframework.security.core.context.PrincipalFactory;
import org.fireflyframework.security.rs.ResourceServerAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class StaffUnderwritingGlobalSecurityCoexistenceTest {
    @Test void enabledStaffFiltersPreserveTheOriginalGlobalChainDecoderAndPrincipalMapping(){
        new ReactiveWebApplicationContextRunner().withConfiguration(AutoConfigurations.of(ResourceServerAutoConfiguration.class))
            .withUserConfiguration(StaffUnderwritingRuntimeConfiguration.class,StaffUnderwritingOwnerSecurityConfiguration.class)
            .withBean(GovernedStaffUnderwritingService.class,()->mock(GovernedStaffUnderwritingService.class))
            .withPropertyValues("insurance.underwriting.governed.enabled=true","insurance.underwriting.governed.runtime.enabled=true",
                "insurance.underwriting.governed.runtime.connection-factory-bean=unusedControlledFactory",
                "insurance.underwriting.governed.runtime.profile.issuer=https://staff.invalid",
                "insurance.underwriting.governed.runtime.profile.audience=staff-underwriting-owner",
                "insurance.underwriting.governed.runtime.profile.allowed-client-id=registered-staff",
                "insurance.underwriting.governed.runtime.profile.platform-tenant=opaque:staff:namespace",
                "insurance.underwriting.governed.runtime.profile.resource-tenant-id=10000000-0000-0000-0000-000000000001",
                "insurance.underwriting.governed.runtime.profile.canonical-staff-realm=canonical:insurer",
                "insurance.underwriting.governed.runtime.profile.authority-level=PROPERTY_REVIEW",
                "insurance.underwriting.governed.runtime.jwk-set-uri=https://staff.invalid/jwks",
                "insurance.underwriting.governed.runtime.pdp-endpoint=https://policy.invalid",
                "insurance.underwriting.governed.runtime.pdp-decision-path=insurance/underwriting",
                "insurance.underwriting.governed.runtime.current-config-endpoint=https://config.invalid",
                "insurance.underwriting.governed.runtime.current-config-registration-id=registered-current",
                "firefly.security.resource-server.issuer=https://existing-customer.invalid",
                "firefly.security.resource-server.jwk-set-uri=https://existing-customer.invalid/jwks",
                "firefly.security.resource-server.audiences=existing-customer-owner",
                "firefly.security.resource-server.role-claim-paths=roles","firefly.security.resource-server.authority-prefix=ROLE_")
            .run(context->{
                assertNull(context.getStartupFailure());
                assertEquals(Set.of("fireflySecurityWebFilterChain"),context.getBeansOfType(SecurityWebFilterChain.class).keySet());
                assertEquals(Set.of("fireflyReactiveJwtDecoder"),context.getBeansOfType(ReactiveJwtDecoder.class).keySet());
                assertEquals(Set.of("fireflyPrincipalFactory"),context.getBeansOfType(PrincipalFactory.class).keySet());
                var principal=context.getBean(PrincipalFactory.class).fromClaims("existing-customer","https://existing-customer.invalid",Map.of("roles",List.of("REVIEWER")));
                assertTrue(principal.authorities().contains("ROLE_REVIEWER"));
                for(String filter:List.of("staffUnderwritingSupportedRoutes","staffUnderwritingExceptionTranslation","staffUnderwritingAuthentication","staffUnderwritingAuthorization"))assertTrue(context.containsBean(filter));
                assertEquals(0,context.getBeanNamesForType(io.r2dbc.spi.ConnectionFactory.class).length);
            });
    }
}
