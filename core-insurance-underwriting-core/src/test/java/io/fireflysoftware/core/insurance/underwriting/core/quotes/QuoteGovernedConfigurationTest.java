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


package io.fireflysoftware.core.insurance.underwriting.core.quotes;

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.*;
import org.fireflyframework.security.spi.*;
import org.fireflyframework.security.api.domain.Decision;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.*;
import java.util.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** Bean/settings controls only; no driver connection, HTTP, JWT, database or native qualification. */
class QuoteGovernedConfigurationTest {
    private Map<String,Object> values() {
        return new HashMap<>(Map.of("insurance.quotes.governed.host","database.example.invalid","insurance.quotes.governed.port","23456",
                "insurance.quotes.governed.database","quote_source_fixture","insurance.quotes.governed.command.password",UUID.randomUUID().toString(),
                "insurance.quotes.governed.read.password",UUID.randomUUID().toString(),"insurance.quotes.governed.issuer","https://issuer.example",
                "insurance.quotes.governed.audience","core-quote","insurance.quotes.governed.tenant-claim","tenant_id",
                "insurance.quotes.governed.delegating-client-claim","azp","insurance.quotes.governed.delegating-client-id","domain-client"));
    }
    private StandardEnvironment environment(Map<String,Object> values) {var env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);env.getPropertySources().addFirst(new MapPropertySource("owned-unit",values));return env;}
    @Test void absentEnableFlagCreatesNoQuoteServiceOrPools() {
        try(var context=new AnnotationConfigApplicationContext()) {context.setEnvironment(environment(Map.of()));context.register(QuoteGovernedConfiguration.class);context.refresh();
            assertEquals(0,context.getBeanNamesForType(QuoteGovernedService.class).length);assertEquals(0,context.getBeanNamesForType(io.r2dbc.pool.ConnectionPool.class).length);}
    }
    @Test void explicitSecureSettingsHaveFixedRolesAndOpaqueDiagnostics() {
        var settings=QuoteGovernedConfiguration.Settings.read(environment(values()));
        assertEquals("quote_command_runtime",settings.commandUsername());assertEquals("quote_read_runtime",settings.readUsername());
        assertEquals("VERIFY_FULL",settings.sslMode().name());assertEquals("QuoteGovernedSettings",settings.toString());
    }
    @Test void absentOrReusedCredentialsDoNotFallBackToLegacyDbProperties() {
        var missing=values();missing.remove("insurance.quotes.governed.command.password");missing.put("DB_PASSWORD",UUID.randomUUID().toString());
        assertThrows(RuntimeException.class,()->QuoteGovernedConfiguration.Settings.read(environment(missing)));
        var reused=values();reused.put("insurance.quotes.governed.read.password",reused.get("insurance.quotes.governed.command.password"));
        assertThrows(RuntimeException.class,()->QuoteGovernedConfiguration.Settings.read(environment(reused)));
    }
    @Test void plaintextTransportIsOnlyAnExplicitOwnedLiteralLoopbackFixture() {
        var values=values();values.put("insurance.quotes.governed.ssl-mode","DISABLE");
        assertThrows(RuntimeException.class,()->QuoteGovernedConfiguration.Settings.read(environment(values)));
        values.put("insurance.quotes.governed.owned-loopback-fixture","true");
        assertThrows(RuntimeException.class,()->QuoteGovernedConfiguration.Settings.read(environment(values)));
        values.put("insurance.quotes.governed.host","127.0.0.1");assertEquals("DISABLE",QuoteGovernedConfiguration.Settings.read(environment(values)).sslMode().name());
    }
    @Test void enabledWithMissingProducerFailsBeforeAnyOwnedPoolExists() {
        try(var context=new AnnotationConfigApplicationContext()) {
            var values=values();values.put("insurance.quotes.governed.enabled","true");values.put("insurance.quotes.governed.sandbox.enabled","true");
            context.setEnvironment(environment(values));context.registerBean(SecurityContextPort.class,()->()->Mono.empty());
            context.registerBean(PolicyDecisionPort.class,()->(p,a,r,c)->Mono.just(Decision.deny("source fixture")));
            context.register(QuoteGovernedConfiguration.class);var error=assertThrows(RuntimeException.class,context::refresh);
            Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
            assertTrue(cause instanceof org.springframework.beans.factory.NoSuchBeanDefinitionException);
            assertTrue(cause.getMessage().contains("QuoteConfigurationPort"));
        }
    }
}
