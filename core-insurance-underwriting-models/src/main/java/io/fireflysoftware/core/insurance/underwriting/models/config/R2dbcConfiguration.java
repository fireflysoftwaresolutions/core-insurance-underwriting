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


package io.fireflysoftware.core.insurance.underwriting.models.config;

import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.ReferralStatus;
import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.DecisionOutcome;
import io.fireflysoftware.core.insurance.underwriting.models.config.impl.ReferralStatusWriteConverterImpl;
import io.fireflysoftware.core.insurance.underwriting.models.config.impl.DecisionOutcomeWriteConverterImpl;
import io.r2dbc.postgresql.codec.EnumCodec;
import io.r2dbc.postgresql.extension.CodecRegistrar;
import java.util.List;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.postgresql.client.SSLMode;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;

/**
 * PostgreSQL connection factory for this service.
 */
@Configuration
public class R2dbcConfiguration extends AbstractR2dbcConfiguration {

    @Value("${DB_HOST:localhost}")
    private String host;

    @Value("${DB_PORT:5432}")
    private int port;

    @Value("${DB_NAME:postgres}")
    private String database;

    @Value("${DB_USERNAME:postgres}")
    private String username;

    @Value("${DB_PASSWORD:postgres}")
    private String password;

    @Value("${DB_SSL_MODE:disable}")
    private String sslMode;

    @Bean
    @Primary
    @Override
    public ConnectionFactory connectionFactory() {
        return new PostgresqlConnectionFactory(
                PostgresqlConnectionConfiguration.builder()
                        .host(host)
                        .port(port)
                        .username(username)
                        .password(password)
                        .database(database)
                        .sslMode(SSLMode.valueOf(sslMode.toUpperCase()))
                        .codecRegistrar(governanceEnumCodecs())
                        .build()
        );
    }
    public static CodecRegistrar governanceEnumCodecs(){return EnumCodec.builder().withEnum("referral_status_enum",ReferralStatus.class).withEnum("decision_outcome_enum",DecisionOutcome.class).build();}
    public static List<Object> governanceEnumConverters(){return List.of(new ReferralStatusWriteConverterImpl(),new DecisionOutcomeWriteConverterImpl());}
    @Override protected List<Object> getCustomConverters(){return governanceEnumConverters();}

}
