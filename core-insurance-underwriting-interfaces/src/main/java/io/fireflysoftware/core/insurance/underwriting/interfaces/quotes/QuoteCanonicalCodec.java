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


package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Versioned bounded data encoding; decoding verifies integrity, never authenticity or admission. */
public final class QuoteCanonicalCodec {
    private static final String TERMS = "FIREFLY_INSURANCE_QUOTE_TERMS_1";
    private static final String INTENT = "FIREFLY_INSURANCE_QUOTE_ACCEPT_INTENT_1";
    private static final String RECEIPT = "FIREFLY_INSURANCE_QUOTE_ACCEPT_RECEIPT_1";
    private static final String ACTION = "FIREFLY_INSURANCE_QUOTE_ACTION_1";
    private static final String RESULT = "FIREFLY_INSURANCE_QUOTE_RESULT_1";
    private static final int MAX_BYTES = 65536;
    private static final int MAX_FIELDS = 128;
    private static final int MAX_FIELD_BYTES = 32768;

    private QuoteCanonicalCodec() {}

    // Mutable control state/generation deliberately are not part of the existing terms digest.
    public record Terms(QuoteOfferScope scope, UUID offerId, long offerVersion, Instant issuedAt,
            Instant expiresAt, QuoteOfferTerms terms) {
        public Terms {
            new QuoteOfferVersion(scope, offerId, offerVersion, 0, QuoteOfferVersion.State.OFFERED,
                    terms, issuedAt, expiresAt);
        }
    }

    public static byte[] encodeFields(List<String> fields) {
        Objects.requireNonNull(fields, "fields");
        if (fields.isEmpty() || fields.size() > MAX_FIELDS) throw invalid();
        try {
            var buffer = new ByteArrayOutputStream();
            var output = new DataOutputStream(buffer);
            output.writeInt(fields.size());
            for (String field : fields) {
                Objects.requireNonNull(field, "field");
                if (field.length() > MAX_FIELD_BYTES) throw invalid();
                var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(field));
                int size = encoded.remaining();
                if (size > MAX_FIELD_BYTES || buffer.size() + 4 + size > MAX_BYTES) throw invalid();
                output.writeInt(size);
                byte[] bytes = new byte[size]; encoded.get(bytes); output.write(bytes);
            }
            return buffer.toByteArray();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Invalid bounded quote encoding", error);
        }
    }

    public static List<String> decodeFields(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes.length > MAX_BYTES) throw invalid();
        try {
            var input = ByteBuffer.wrap(bytes);
            int count = input.getInt();
            if (count <= 0 || count > MAX_FIELDS || count > input.remaining() / 4) throw invalid();
            var result = new ArrayList<String>(count);
            for (int i = 0; i < count; i++) {
                if (input.remaining() < 4) throw invalid();
                int size = input.getInt();
                if (size < 0 || size > MAX_FIELD_BYTES || size > input.remaining()) throw invalid();
                var field = input.slice(); field.limit(size);
                result.add(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(field).toString());
                input.position(input.position() + size);
            }
            if (input.hasRemaining()) throw invalid();
            return List.copyOf(result);
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid quote UTF-8", error);
        }
    }

    public static String digest(byte[] canonical) {
        Objects.requireNonNull(canonical, "canonical");
        if (canonical.length > MAX_BYTES) throw invalid();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    public static byte[] terms(QuoteOfferVersion value) {
        Objects.requireNonNull(value, "value");
        return terms(new Terms(value.scope(), value.offerId(), value.offerVersion(), value.issuedAt(), value.expiresAt(), value.terms()));
    }

    public static byte[] terms(Terms value) {
        Objects.requireNonNull(value, "value");
        var fields = scoped(TERMS, value.scope()); var t = value.terms();
        fields.addAll(List.of(value.offerId().toString(), Long.toString(value.offerVersion()), value.issuedAt().toString(),
                value.expiresAt().toString(), t.currency(), t.basePremium().toPlainString(), t.tax().toPlainString(),
                t.fees().toPlainString(), t.productRevisionId().toString(), t.productDigest(), t.riskDigest(),
                t.providerVersion(), t.ruleSetVersion(), Integer.toString(t.coverageTerms().size())));
        t.coverageTerms().forEach((key, text) -> { fields.add(key); fields.add(text); });
        return encodeFields(fields);
    }

    public static Terms readTerms(byte[] bytes) {
        var f = domain(bytes, TERMS, -1);
        if (f.size() < 21) throw invalid();
        int count = Integer.parseInt(f.get(18));
        if (count < 1 || count > 32 || f.size() != 19 + count * 2) throw invalid();
        var coverage = new TreeMap<String, String>();
        for (int i = 19; i < f.size(); i += 2) if (coverage.put(f.get(i), f.get(i + 1)) != null) throw invalid();
        var terms = new QuoteOfferTerms(f.get(9), money(f.get(10)), money(f.get(11)), money(f.get(12)), coverage,
                UUID.fromString(f.get(13)), f.get(14), f.get(15), f.get(16), f.get(17));
        var value = new Terms(scope(f, 1), UUID.fromString(f.get(5)), Long.parseLong(f.get(6)),
                Instant.parse(f.get(7)), Instant.parse(f.get(8)), terms);
        exact(bytes, terms(value)); return value;
    }

    public static byte[] intent(QuoteAcceptanceIntent value) {
        Objects.requireNonNull(value, "value");
        var f = scoped(INTENT, value.scope());
        f.addAll(List.of(value.offerId().toString(), Long.toString(value.offerVersion()),
                Long.toString(value.expectedControlGeneration()), value.termsDigest(), value.actionId().toString()));
        return encodeFields(f);
    }

    public static QuoteAcceptanceIntent readIntent(byte[] bytes) {
        var f = domain(bytes, INTENT, 10);
        var v = new QuoteAcceptanceIntent(scope(f, 1), UUID.fromString(f.get(5)), Long.parseLong(f.get(6)),
                Long.parseLong(f.get(7)), f.get(8), UUID.fromString(f.get(9)));
        exact(bytes, intent(v)); return v;
    }

    public static byte[] receipt(QuoteAcceptanceReceipt value) {
        Objects.requireNonNull(value, "value");
        var f = new ArrayList<String>(List.of(RECEIPT)); f.addAll(decodeFields(intent(value.intent())));
        f.addAll(List.of(value.acceptedAt().toString(), Long.toString(value.resultingControlGeneration())));
        return encodeFields(f);
    }

    public static QuoteAcceptanceReceipt readReceipt(byte[] bytes) {
        var f = domain(bytes, RECEIPT, 13);
        var v = new QuoteAcceptanceReceipt(readIntent(encodeFields(f.subList(1, 11))), Instant.parse(f.get(11)), Long.parseLong(f.get(12)));
        exact(bytes, receipt(v)); return v;
    }

    public static byte[] action(QuoteActionEnvelope value) {
        Objects.requireNonNull(value, "value");
        var f = scoped(ACTION, value.scope());
        f.addAll(List.of(value.ownerIssuer(), value.ownerSubject(), value.operation().name(), value.offerId().toString(),
                Long.toString(value.offerVersion()), Long.toString(value.expectedControlGeneration()),
                value.actionId().toString(), value.intentDigest())); return encodeFields(f);
    }

    public static QuoteActionEnvelope readAction(byte[] bytes) {
        var f = domain(bytes, ACTION, 13);
        var v = new QuoteActionEnvelope(scope(f, 1), f.get(5), f.get(6), QuoteActionEnvelope.Operation.valueOf(f.get(7)),
                UUID.fromString(f.get(8)), Long.parseLong(f.get(9)), Long.parseLong(f.get(10)), UUID.fromString(f.get(11)), f.get(12));
        exact(bytes, action(v)); return v;
    }

    public static byte[] result(QuoteResultEnvelope value) {
        Objects.requireNonNull(value, "value");
        var f = new ArrayList<String>(List.of(RESULT)); f.addAll(decodeFields(action(value.action())));
        f.addAll(List.of(value.resultId().toString(), value.offerVersionId().toString(), value.acceptanceId() == null ? "" : value.acceptanceId().toString(),
                value.recordedAt().toString(), Long.toString(value.resultingControlGeneration()))); return encodeFields(f);
    }

    public static QuoteResultEnvelope readResult(byte[] bytes) {
        var f = domain(bytes, RESULT, 19);
        var v = new QuoteResultEnvelope(readAction(encodeFields(f.subList(1, 14))), UUID.fromString(f.get(14)),
                UUID.fromString(f.get(15)), f.get(16).isEmpty() ? null : UUID.fromString(f.get(16)), Instant.parse(f.get(17)), Long.parseLong(f.get(18)));
        exact(bytes, result(v)); return v;
    }

    private static ArrayList<String> scoped(String domain, QuoteOfferScope scope) {
        var result = new ArrayList<String>(List.of(domain)); QuoteDigests.scope(result, scope); return result;
    }
    private static QuoteOfferScope scope(List<String> f, int offset) {
        return new QuoteOfferScope(UUID.fromString(f.get(offset)), UUID.fromString(f.get(offset + 1)),
                UUID.fromString(f.get(offset + 2)), UUID.fromString(f.get(offset + 3)));
    }
    private static List<String> domain(byte[] bytes, String header, int count) {
        var f = decodeFields(bytes);
        if (!f.getFirst().equals(header) || count >= 0 && f.size() != count) throw invalid(); return f;
    }
    private static BigDecimal money(String field) {
        if (field.length() > 64) throw invalid(); return new BigDecimal(field);
    }
    private static void exact(byte[] original, byte[] canonical) {
        if (!Arrays.equals(original, canonical)) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid canonical quote bytes"); }
}
