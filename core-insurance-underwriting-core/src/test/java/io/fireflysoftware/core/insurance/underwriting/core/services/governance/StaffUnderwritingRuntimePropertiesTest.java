package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl.CurrentStaffUnderwritingAdmissionImpl;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class StaffUnderwritingRuntimePropertiesTest{
    CurrentStaffUnderwritingAdmissionImpl.Profile profile(){return new CurrentStaffUnderwritingAdmissionImpl.Profile("https://issuer.invalid","underwriting-owner","registered-staff","opaque:insurer:tenant",UUID.randomUUID(),"canonical:insurer-staff","PROPERTY_REVIEW");}
    StaffUnderwritingRuntimeProperties properties(URI current){return new StaffUnderwritingRuntimeProperties("qualifiedStaffWriter",profile(),URI.create("https://issuer.invalid/jwks"),URI.create("https://pdp.invalid"),"insurance/underwriting",current,"registered-current-client");}
    @Test void configuredOpaqueNamespaceAndServerSelectedTenantAreRetainedWithoutInference(){var value=properties(URI.create("https://config.invalid"));assertEquals("opaque:insurer:tenant",value.profile().platformTenant());assertNotEquals(value.profile().resourceTenantId().toString(),value.profile().platformTenant());assertEquals("canonical:insurer-staff",value.profile().canonicalStaffRealm());assertEquals("StaffUnderwritingRuntimeProperties[redacted]",value.toString());}
    @Test void plaintextForeignAuthorityOrAmbiguousEndpointCannotBeAdopted(){for(var value:new String[]{"http://127.0.0.1:1234","https://user@config.invalid","https://config.invalid?foreign=true","https://config.invalid/#foreign","https://config.invalid/a/../b","https://config.invalid/%2f"})assertThrows(IllegalArgumentException.class,()->properties(URI.create(value)));}
    @Test void runtimeCompositionIsOffUnlessBothOwnerAndRuntimeFlagsAreExplicit(){for(var flags:java.util.List.of(java.util.Map.<String,Object>of(),java.util.Map.<String,Object>of("insurance.underwriting.governed.enabled","true"),java.util.Map.<String,Object>of("insurance.underwriting.governed.runtime.enabled","true"))){try(var context=new AnnotationConfigApplicationContext()){context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("controlled-optin",flags));context.register(StaffUnderwritingRuntimeConfiguration.class);context.refresh();assertEquals(0,context.getBeanNamesForType(GovernedStaffUnderwritingService.class).length);assertEquals(0,context.getBeanNamesForType(io.r2dbc.spi.ConnectionFactory.class).length);}}}
}
