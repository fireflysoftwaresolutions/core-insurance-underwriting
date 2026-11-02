package io.fireflysoftware.core.insurance.underwriting.web.governance;

import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.UnderwritingGovernanceValues;
import io.fireflysoftware.core.insurance.underwriting.core.services.governance.StaffUnderwritingRuntimeConfiguration;
import io.fireflysoftware.core.insurance.underwriting.core.services.governance.StaffUnderwritingRuntimeProperties;
import org.fireflyframework.security.core.authority.AuthorityMappingProperties;
import org.fireflyframework.security.core.authority.ConfigurableAuthorityMapper;
import org.fireflyframework.security.core.context.PrincipalFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthenticatedReactiveAuthorizationManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.authorization.AuthorizationWebFilter;
import org.springframework.security.web.server.authorization.ExceptionTranslationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.*;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/** Scoped native receiver filters preserve the owner's other routes and existing global chain. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="insurance.underwriting.governed",name={"enabled","runtime.enabled"},havingValue="true")
public class StaffUnderwritingOwnerSecurityConfiguration{
    static final ServerWebExchangeMatcher PREFIX=ServerWebExchangeMatchers.pathMatchers("/api/v1/underwriting-governance/**");
    static final ServerWebExchangeMatcher SUPPORTED=new OrServerWebExchangeMatcher(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST,"/api/v1/underwriting-governance/intents","/api/v1/underwriting-governance/actions"),ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET,"/api/v1/underwriting-governance/actions/*"));
    @Bean @Order(-60)
    WebFilter staffUnderwritingSupportedRoutes(){return(exchange,chain)->PREFIX.matches(exchange).flatMap(prefix->{if(!prefix.isMatch())return chain.filter(exchange);return SUPPORTED.matches(exchange).flatMap(supported->{if(supported.isMatch())return chain.filter(exchange);exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);return exchange.getResponse().setComplete();});});}
    @Bean @Order(-55)
    WebFilter staffUnderwritingExceptionTranslation(){var delegate=new ExceptionTranslationWebFilter();delegate.setAuthenticationEntryPoint(new BearerTokenServerAuthenticationEntryPoint());return scoped(delegate);}
    @Bean @Order(-50)
    WebFilter staffUnderwritingAuthentication(StaffUnderwritingRuntimeProperties settings){
        var decoder=StaffUnderwritingRuntimeConfiguration.receiver(settings);
        var principals=new PrincipalFactory(new ConfigurableAuthorityMapper(new AuthorityMappingProperties(java.util.List.of("roles"),java.util.List.of("scope"),"")));
        var manager=new JwtReactiveAuthenticationManager(decoder);manager.setJwtAuthenticationConverter(jwt->Mono.fromSupplier(()->{
            Object raw=jwt.getClaims().get("tenant_id");if(!(raw instanceof String tenant))throw invalid();try{UnderwritingGovernanceValues.text(tenant,1024);}catch(RuntimeException malformed){throw invalid();}
            var principal=principals.fromClaims(jwt.getSubject(),jwt.getIssuer().toString(),jwt.getClaims()).toBuilder().tenantId(tenant).build();return new JwtAuthenticationToken(jwt,principal,principal.authorities().stream().map(SimpleGrantedAuthority::new).toList());
        }));var delegate=new AuthenticationWebFilter(manager);delegate.setRequiresAuthenticationMatcher(SUPPORTED);delegate.setServerAuthenticationConverter(new ServerBearerTokenAuthenticationConverter());delegate.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());delegate.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(new BearerTokenServerAuthenticationEntryPoint()));return scoped(delegate);
    }
    @Bean @Order(-40)
    WebFilter staffUnderwritingAuthorization(){return scoped(new AuthorizationWebFilter(AuthenticatedReactiveAuthorizationManager.authenticated()));}
    private static WebFilter scoped(WebFilter delegate){return(exchange,chain)->SUPPORTED.matches(exchange).flatMap(result->result.isMatch()?delegate.filter(exchange,chain):chain.filter(exchange));}
    private static org.springframework.security.oauth2.core.OAuth2AuthenticationException invalid(){return new org.springframework.security.oauth2.core.OAuth2AuthenticationException(new org.springframework.security.oauth2.core.OAuth2Error("invalid_token","Exact signed opaque tenant claim required",null));}
}
