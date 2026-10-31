/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.fireflysoftware.core.insurance.underwriting.core.services.quotes;

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.impl.QuoteGovernedServiceImpl;
import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.impl.SandboxPropertyQuoteRatingImpl;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.QuoteMappedStore;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.QuotePersistence;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.postgresql.client.SSLMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="insurance.quotes.governed",name="enabled",havingValue="true",matchIfMissing=false)
public class QuoteGovernedConfiguration {
    private static final String PREFIX="insurance.quotes.governed.";

    @Bean(destroyMethod="close")
    public Components quoteGovernedComponents(QuoteConfigurationPort configurations,SecurityContextPort security,
            PolicyDecisionPort pdp,R2dbcConverter converter,ApplicationContext context,Environment environment) {
        if (!environment.getProperty(PREFIX+"sandbox.enabled",Boolean.class,false)) throw new IllegalStateException("Explicit sandbox rating opt-in required");
        var settings=Settings.read(environment);var clock=Clock.systemUTC();
        var commands=pool(settings,true);ConnectionPool reads=null;
        try {
            reads=pool(settings,false);
            var persistence=new QuotePersistence(commands,reads,converter,context);
            var store=new QuoteMappedStore(persistence,clock,System::nanoTime,UUID::randomUUID);
            var authority=new QuoteAuthorityGate(security,pdp,settings.profile(),clock,System::nanoTime);
            var service=new QuoteGovernedServiceImpl(authority,store,configurations,new SandboxPropertyQuoteRatingImpl(),clock,System::nanoTime);
            return new Components(service,commands,reads);
        } catch (RuntimeException error) {
            try {close(commands,reads);} catch (RuntimeException cleanup) {error.addSuppressed(cleanup);}
            throw error;
        }
    }
    @Bean public QuoteGovernedService quoteGovernedService(Components components) {return components.service;}

    private static ConnectionPool pool(Settings s,boolean command) {
        var configuration=PostgresqlConnectionConfiguration.builder().host(s.host).port(s.port).database(s.database)
                .username(command?s.commandUsername():s.readUsername()).password(command?s.commandPassword:s.readPassword)
                .sslMode(s.sslMode).connectTimeout(Duration.ofSeconds(2)).build();
        return new ConnectionPool(ConnectionPoolConfiguration.builder(new PostgresqlConnectionFactory(configuration))
                .name(command?"insurance-quote-command":"insurance-quote-retained-read").initialSize(0).maxSize(2)
                .maxAcquireTime(Duration.ofSeconds(2)).maxCreateConnectionTime(Duration.ofSeconds(2)).maxIdleTime(Duration.ofSeconds(30))
                .validationQuery("SELECT 1").build());
    }
    private static void close(ConnectionPool commands,ConnectionPool reads) {
        Mono.whenDelayError(commands==null?Mono.empty():commands.disposeLater(),reads==null?Mono.empty():reads.disposeLater()).block(Duration.ofSeconds(10));
    }
    public static final class Components implements AutoCloseable {
        private final QuoteGovernedService service;
        private final ConnectionPool commands;
        private final ConnectionPool reads;
        private Components(QuoteGovernedService service,ConnectionPool commands,ConnectionPool reads) {this.service=service;this.commands=commands;this.reads=reads;}
        @Override public void close() {QuoteGovernedConfiguration.close(commands,reads);}
        @Override public String toString() {return "QuoteGovernedComponents";}
    }
    public static final class Settings {
        private final String host;
        private final int port;
        private final String database;
        private final SSLMode sslMode;
        private final String commandPassword;
        private final String readPassword;
        private final QuoteAuthorityGate.Profile profile;
        private Settings(String host,int port,String database,SSLMode sslMode,String commandPassword,String readPassword,QuoteAuthorityGate.Profile profile) {
            this.host=host;this.port=port;this.database=database;this.sslMode=sslMode;this.commandPassword=commandPassword;this.readPassword=readPassword;this.profile=profile;
        }
        public static Settings read(Environment env) {
            String host=required(env,"host"),database=required(env,"database");
            Integer port=env.getRequiredProperty(PREFIX+"port",Integer.class);
            if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || !database.matches("[a-z][a-z0-9_]{0,62}") || port<1 || port>65535) throw invalid();
            boolean owned=env.getProperty(PREFIX+"owned-loopback-fixture",Boolean.class,false);
            SSLMode mode=SSLMode.valueOf(env.getProperty(PREFIX+"ssl-mode","VERIFY_FULL").replace('-','_').toUpperCase(Locale.ROOT));
            if (owned && (!host.equals("127.0.0.1") || port==18080 || port==9001)
                    || mode!=SSLMode.VERIFY_FULL && !(owned && host.equals("127.0.0.1") && mode==SSLMode.DISABLE)) throw invalid();
            String commands=required(env,"command.password"),reads=required(env,"read.password");
            if (!password(commands) || !password(reads) || commands.equals(reads)) throw invalid();
            var profile=new QuoteAuthorityGate.Profile(required(env,"issuer"),required(env,"audience"),required(env,"tenant-claim"),
                    required(env,"delegating-client-claim"),required(env,"delegating-client-id"));
            return new Settings(host,port,database,mode,commands,reads,profile);
        }
        public String commandUsername() {return "quote_command_runtime";}
        public String readUsername() {return "quote_read_runtime";}
        public SSLMode sslMode() {return sslMode;}
        public QuoteAuthorityGate.Profile profile() {return profile;}
        private static boolean password(String value) {int length=value.getBytes(StandardCharsets.UTF_8).length;return length>=32 && length<=512 && value.indexOf(0)<0;}
        private static String required(Environment env,String key) {String value=env.getRequiredProperty(PREFIX+key);if (value.isBlank()) throw invalid();return value;}
        private static IllegalStateException invalid() {return new IllegalStateException("Explicit qualified quote settings required");}
        @Override public String toString() {return "QuoteGovernedSettings";}
    }
}
