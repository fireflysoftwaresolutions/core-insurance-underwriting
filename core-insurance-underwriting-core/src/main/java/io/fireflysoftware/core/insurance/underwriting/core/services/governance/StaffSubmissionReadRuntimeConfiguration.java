package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl.*;
import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.impl.GeneratedConfigQuoteTenantBindingResolverImpl;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.postgresql.client.SSLMode;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.fireflyframework.security.adapter.opa.OpaPolicyEvaluationAdapter;
import org.fireflyframework.security.webflux.context.ReactorSecurityContextAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="insurance.underwriting.governed",name={"enabled","runtime.enabled","submission-read.enabled"},havingValue="true")
@EnableConfigurationProperties(StaffSubmissionReadRuntimeProperties.class)
public class StaffSubmissionReadRuntimeConfiguration{
    @Bean(destroyMethod="close")
    Components staffSubmissionReadComponents(StaffSubmissionReadRuntimeProperties database,StaffUnderwritingRuntimeProperties settings,
            R2dbcConverter converter,ApplicationContext context,ReactiveClientRegistrationRepository registrations){
        var security=new ReactorSecurityContextAdapter();var clock=Clock.systemUTC();
        var clients=new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registrations,new InMemoryReactiveOAuth2AuthorizedClientService(registrations));
        var tokens=new WebClientReactiveClientCredentialsTokenResponseClient();tokens.setWebClient(quietTransport());var provider=new ClientCredentialsReactiveOAuth2AuthorizedClientProvider();provider.setAccessTokenResponseClient(tokens);clients.setAuthorizedClientProvider(provider);
        ReactiveOAuth2AuthorizedClientManager currentClients=request->registrations.findByRegistrationId(settings.currentConfigRegistrationId()).switchIfEmpty(Mono.error(invalidRegistration())).flatMap(registration->{
            java.net.URI token;try{token=java.net.URI.create(registration.getProviderDetails().getTokenUri());}catch(RuntimeException invalid){return Mono.error(invalidRegistration());}
            if(!settings.currentConfigRegistrationId().equals(request.getClientRegistrationId())||!AuthorizationGrantType.CLIENT_CREDENTIALS.equals(registration.getAuthorizationGrantType())||!ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(registration.getClientAuthenticationMethod())||registration.getClientId()==null||registration.getClientId().isBlank()||registration.getClientSecret()==null||registration.getClientSecret().isBlank()||!"https".equals(token.getScheme())||token.getHost()==null||token.getUserInfo()!=null||token.getQuery()!=null||token.getFragment()!=null)return Mono.error(invalidRegistration());
            return clients.authorize(request);
        });
        var current=new GeneratedConfigQuoteTenantBindingResolverImpl(security::currentPrincipal,currentClients,quietTransport(),settings.currentConfigEndpoint(),settings.currentConfigRegistrationId(),settings.profile().issuer(),settings.profile().platformTenant(),clock);
        var pdp=new OpaPolicyEvaluationAdapter(quietTransport().mutate().baseUrl(settings.pdpEndpoint().toASCIIString()).build(),settings.pdpDecisionPath());
        var source=settings.profile();var profile=new CurrentStaffSubmissionReadAdmissionImpl.Profile(source.issuer(),source.audience(),source.allowedClientId(),source.platformTenant(),source.resourceTenantId(),source.canonicalStaffRealm());
        var authority=new CurrentStaffSubmissionReadAdmissionImpl(security,pdp,current,profile,clock,System::nanoTime);
        var factory=new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder().host(database.host()).port(database.port()).database(database.database())
            .username(database.username()).password(database.password()).sslMode(SSLMode.valueOf(database.sslMode())).connectTimeout(Duration.ofSeconds(2)).build());
        var pool=new ConnectionPool(ConnectionPoolConfiguration.builder(factory).name("staff-submission-read").initialSize(0).maxSize(2).maxAcquireTime(Duration.ofSeconds(2)).maxCreateConnectionTime(Duration.ofSeconds(2)).maxIdleTime(Duration.ofSeconds(30)).validationQuery("SELECT 1").build());
        try{return new Components(pool,new SpringDataStaffSubmissionReaderImpl(pool,converter,context,authority));}
        catch(RuntimeException error){pool.disposeLater().block(Duration.ofSeconds(5));throw error;}
    }
    @Bean StaffSubmissionReader staffSubmissionReader(Components components){return components.reader;}
    public static final class Components implements AutoCloseable{
        private final ConnectionPool pool;private final StaffSubmissionReader reader;
        private Components(ConnectionPool pool,StaffSubmissionReader reader){this.pool=pool;this.reader=reader;}
        @Override public void close(){pool.disposeLater().block(Duration.ofSeconds(5));}
        @Override public String toString(){return "StaffSubmissionReadComponents[redacted]";}
    }
    private static WebClient quietTransport(){return WebClient.builder().clientConnector(new JdkClientHttpConnector(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build())).codecs(codecs->{codecs.defaultCodecs().maxInMemorySize(16384);codecs.defaultCodecs().enableLoggingRequestDetails(false);}).build();}
    private static IllegalStateException invalidRegistration(){return new IllegalStateException("Explicit registered submission read current-binding machine client required");}
}
