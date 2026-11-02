package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("insurance.underwriting.governed.submission-read")
public record StaffSubmissionReadRuntimeProperties(boolean enabled,String host,int port,String database,
        String username,String password,String sslMode,boolean ownedLoopbackFixture){
    public StaffSubmissionReadRuntimeProperties{
        if(enabled){
            if(host==null||!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")||port<1||port>65535||database==null||!database.matches("[a-z][a-z0-9_]{0,62}")
                ||!"underwriting_submission_read".equals(username)||password==null||password.getBytes(StandardCharsets.UTF_8).length<32||password.getBytes(StandardCharsets.UTF_8).length>512||password.indexOf(0)>=0)throw invalid();
            sslMode=sslMode==null?"VERIFY_FULL":sslMode.replace('-','_').toUpperCase(Locale.ROOT);
            if(ownedLoopbackFixture&&!"127.0.0.1".equals(host)||!"VERIFY_FULL".equals(sslMode)&&!(ownedLoopbackFixture&&"127.0.0.1".equals(host)&&"DISABLE".equals(sslMode)))throw invalid();
        }
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Explicit dedicated submission read database settings required");}
    @Override public String toString(){return "StaffSubmissionReadRuntimeProperties[redacted]";}
}
