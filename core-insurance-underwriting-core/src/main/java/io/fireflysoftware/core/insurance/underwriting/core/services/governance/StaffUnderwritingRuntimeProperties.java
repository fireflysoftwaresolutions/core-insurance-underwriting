package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl.CurrentStaffUnderwritingAdmissionImpl;
import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.UnderwritingGovernanceValues;
import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit owner deployment selection; no request identity or inferred resource tenant. */
@ConfigurationProperties("insurance.underwriting.governed.runtime")
public record StaffUnderwritingRuntimeProperties(String connectionFactoryBean,
        CurrentStaffUnderwritingAdmissionImpl.Profile profile,URI jwkSetUri,URI pdpEndpoint,String pdpDecisionPath,
        URI currentConfigEndpoint,String currentConfigRegistrationId){
    public StaffUnderwritingRuntimeProperties{
        connectionFactoryBean=UnderwritingGovernanceValues.text(connectionFactoryBean,128);
        if(!connectionFactoryBean.matches("[A-Za-z][A-Za-z0-9._-]{0,127}"))throw invalid();
        Objects.requireNonNull(profile);https(URI.create(profile.issuer()));https(jwkSetUri);https(pdpEndpoint);https(currentConfigEndpoint);
        pdpDecisionPath=UnderwritingGovernanceValues.text(pdpDecisionPath,255);
        if(!pdpDecisionPath.matches("[A-Za-z0-9_]+(/[A-Za-z0-9_]+)*"))throw invalid();
        currentConfigRegistrationId=UnderwritingGovernanceValues.text(currentConfigRegistrationId,100);
        if(!currentConfigRegistrationId.matches("[A-Za-z0-9._-]{1,100}"))throw invalid();
    }
    private static void https(URI value){
        if(value==null || !"https".equals(value.getScheme()) || value.getHost()==null || value.getUserInfo()!=null || value.getQuery()!=null || value.getFragment()!=null || !value.normalize().equals(value) || value.getRawPath().contains("%") || value.getPort()==0 || value.getPort()>65535)throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Explicit governed STAFF owner runtime configuration required");}
    @Override public String toString(){return "StaffUnderwritingRuntimeProperties[redacted]";}
}
