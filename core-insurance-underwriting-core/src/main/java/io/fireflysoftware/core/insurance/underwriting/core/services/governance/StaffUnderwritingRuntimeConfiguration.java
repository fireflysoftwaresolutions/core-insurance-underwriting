package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl.*;
import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.impl.GeneratedConfigQuoteTenantBindingResolverImpl;
import io.r2dbc.spi.ConnectionFactory;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.fireflyframework.core.transaction.*;
import org.fireflyframework.security.adapter.opa.OpaPolicyEvaluationAdapter;
import org.fireflyframework.security.webflux.context.ReactorSecurityContextAdapter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.reactive.function.client.WebClient;

/** Real owner composition is explicit and installs no schema, seed approval or authority grant. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="insurance.underwriting.governed",name={"enabled","runtime.enabled"},havingValue="true")
@EnableConfigurationProperties(StaffUnderwritingRuntimeProperties.class)
public class StaffUnderwritingRuntimeConfiguration{
    public static ReactiveJwtDecoder receiver(StaffUnderwritingRuntimeProperties settings){
        var decoder=NimbusReactiveJwtDecoder.withJwkSetUri(settings.jwkSetUri().toASCIIString()).webClient(quietTransport()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(settings.profile().issuer()),new JwtTimestampValidator(Duration.ZERO),
            jwt->jwt.getAudience().size()==1 && settings.profile().audience().equals(jwt.getAudience().getFirst())?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Exact STAFF owner audience required",null))));return decoder;
    }
    @Bean
    @ConditionalOnMissingBean(GovernedStaffUnderwritingService.class)
    GovernedStaffUnderwritingService governedStaffUnderwritingService(StaffUnderwritingRuntimeProperties settings,BeanFactory beans,
            R2dbcConverter converter,ApplicationContext context,ReactiveClientRegistrationRepository registrations){
        var security=new ReactorSecurityContextAdapter();var clock=Clock.systemUTC();
        var clients=new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registrations,new InMemoryReactiveOAuth2AuthorizedClientService(registrations));
        var tokens=new WebClientReactiveClientCredentialsTokenResponseClient();tokens.setWebClient(quietTransport());var provider=new ClientCredentialsReactiveOAuth2AuthorizedClientProvider();provider.setAccessTokenResponseClient(tokens);clients.setAuthorizedClientProvider(provider);
        ReactiveOAuth2AuthorizedClientManager currentClients=request->registrations.findByRegistrationId(settings.currentConfigRegistrationId()).switchIfEmpty(reactor.core.publisher.Mono.error(invalidRegistration())).flatMap(registration->{
            java.net.URI token;try{token=java.net.URI.create(registration.getProviderDetails().getTokenUri());}catch(RuntimeException invalid){return reactor.core.publisher.Mono.error(invalidRegistration());}
            if(!settings.currentConfigRegistrationId().equals(request.getClientRegistrationId()) || !AuthorizationGrantType.CLIENT_CREDENTIALS.equals(registration.getAuthorizationGrantType()) || !ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(registration.getClientAuthenticationMethod()) || registration.getClientId()==null || registration.getClientId().isBlank() || registration.getClientSecret()==null || registration.getClientSecret().isBlank() || !"https".equals(token.getScheme()) || token.getHost()==null || token.getUserInfo()!=null || token.getQuery()!=null || token.getFragment()!=null)return reactor.core.publisher.Mono.error(invalidRegistration());
            return clients.authorize(request);
        });
        var current=new GeneratedConfigQuoteTenantBindingResolverImpl(security::currentPrincipal,currentClients,quietTransport(),settings.currentConfigEndpoint(),settings.currentConfigRegistrationId(),settings.profile().issuer(),settings.profile().platformTenant(),clock);
        var delegate=beans.getBean(settings.connectionFactoryBean(),ConnectionFactory.class);
        var factory=delegate instanceof OwnedLeaseConnectionFactory held?held:new OwnedLeaseConnectionFactory(delegate);
        var client=DatabaseClient.create(factory);var template=new R2dbcEntityTemplate(client,PostgresDialect.INSTANCE,converter);template.setBeanFactory(context);template.setApplicationContext(context);
        var manager=new R2dbcTransactionManager(factory);var resources=BoundedR2dbcResources.verify(factory,client,template,manager);
        var pdp=new OpaPolicyEvaluationAdapter(quietTransport().mutate().baseUrl(settings.pdpEndpoint().toASCIIString()).build(),settings.pdpDecisionPath());
        var authority=new CurrentStaffUnderwritingAdmissionImpl(security,pdp,current,settings.profile(),clock,System::nanoTime,UUID::randomUUID);
        var root=new StaffUnderwritingAuditRootImpl(resources,client,template,manager,new CleanupPolicy(Duration.ofSeconds(1),Duration.ofSeconds(1)),authority,authority,clock,System::nanoTime);
        return new GovernedStaffUnderwritingServiceImpl(authority,new SpringDataGovernedUnderwritingStoreImpl(template,root,UUID::randomUUID));
    }
    private static WebClient quietTransport(){return WebClient.builder().clientConnector(new JdkClientHttpConnector(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build())).codecs(codecs->{codecs.defaultCodecs().maxInMemorySize(16384);codecs.defaultCodecs().enableLoggingRequestDetails(false);}).build();}
    private static IllegalStateException invalidRegistration(){return new IllegalStateException("Explicit registered STAFF current-binding machine client required");}
}
