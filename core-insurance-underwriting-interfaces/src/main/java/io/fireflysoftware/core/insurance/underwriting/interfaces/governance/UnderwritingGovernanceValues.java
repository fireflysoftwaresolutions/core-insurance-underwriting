package io.fireflysoftware.core.insurance.underwriting.interfaces.governance;

import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteCanonicalCodec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public final class UnderwritingGovernanceValues {
    private UnderwritingGovernanceValues(){}
    public static UUID id(UUID value){if(value==null || value.equals(new UUID(0,0)))throw invalid();return value;}
    public static String digest(String value){if(value==null || !value.matches("[0-9a-f]{64}"))throw invalid();return value;}
    public static String text(String value,int max){if(value==null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl) || !StandardCharsets.UTF_8.newEncoder().canEncode(value) || value.getBytes(StandardCharsets.UTF_8).length>max)throw invalid();return value;}
    public static String hash(String...values){return QuoteCanonicalCodec.digest(QuoteCanonicalCodec.encodeFields(Arrays.asList(values)));}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Exact bounded owner underwriting value required");}
}
