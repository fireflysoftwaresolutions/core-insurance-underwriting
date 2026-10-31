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


package io.fireflysoftware.core.insurance.underwriting.models.quotes;

import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Verified historical data only; every response still requires a current native window and caller/PDP. */
public record QuoteRetainedGraph(QuoteOfferVersion offer, QuoteResultEnvelope offerResult,
        Optional<QuoteAcceptanceReceipt> receipt, Optional<QuoteResultEnvelope> acceptanceResult, int canonicalBytes) {
    public static final class IntentConflict extends IllegalArgumentException {
        public IntentConflict() {super("QUOTE_INTENT_CONFLICT");}
    }
    public static QuoteRetainedGraph verify(QuoteAdmission admission, QuoteRows.Control c, QuoteRows.Version v,
            List<QuoteRows.Action> actions, QuoteRows.Acceptance acceptance) {
        var offer=version(admission,c,v);
        require(actions!=null && actions.size()==(offer.state()==QuoteOfferVersion.State.OFFERED?1:2));
        var creator=actions.stream().filter(x->Objects.equals(x.actionId(),c.createdActionId())).findFirst().orElseThrow(QuoteRetainedGraph::invalid);
        var offered=action(admission,v,creator);
        require(offered.action().operation()==QuoteActionEnvelope.Operation.OFFER && offered.recordedAt().equals(offer.issuedAt())
                && c.createdAt().toInstant().equals(offer.issuedAt()) && offered.acceptanceId()==null && offered.resultingControlGeneration()==0);
        var request=QuoteCanonicalCodec.readOfferRequest(creator.requestCanonical());
        require(offered.action().matchesOffer(request) && request.expectedProductRevisionId().equals(offer.terms().productRevisionId())
                && request.expectedProductDigest().equals(offer.terms().productDigest())
                && request.coverageStart().toString().equals(offer.terms().coverageTerms().get("coverageStart"))
                && request.coverageEnd().toString().equals(offer.terms().coverageTerms().get("coverageEnd")));
        int size=v.canonicalTerms().length+creator.resultCanonical().length;
        if (offer.state()==QuoteOfferVersion.State.OFFERED) {
            require(acceptance==null && c.updatedAt().toInstant().equals(c.createdAt().toInstant()));
            return new QuoteRetainedGraph(offer,offered,Optional.empty(),Optional.empty(),size);
        }
        require(acceptance!=null);scoped(admission,acceptance,c.offerId());
        var acceptedAction=actions.stream().filter(x->!Objects.equals(x.actionId(),creator.actionId())).findFirst().orElseThrow(QuoteRetainedGraph::invalid);
        var accepted=action(admission,v,acceptedAction);var intent=QuoteCanonicalCodec.readIntent(acceptedAction.requestCanonical());
        require(accepted.action().operation()==QuoteActionEnvelope.Operation.ACCEPT && accepted.action().matches(intent)
                && accepted.resultingControlGeneration()==1 && intent.expectedControlGeneration()==0 && intent.offerVersion()==1
                && intent.termsDigest().equals(v.termsDigest()) && Objects.equals(accepted.acceptanceId(),c.acceptanceId())
                && Objects.equals(accepted.acceptanceId(),acceptance.acceptanceId()) && Objects.equals(acceptance.actionId(),intent.actionId())
                && Objects.equals(acceptance.offerVersionId(),v.offerVersionId()) && acceptance.offerVersion()==1
                && acceptance.expectedControlGeneration()==0 && acceptance.resultingControlGeneration()==1
                && Objects.equals(acceptance.termsDigest(),intent.termsDigest()) && Objects.equals(acceptance.intentDigest(),intent.intentDigest())
                && Objects.equals(acceptance.actorIssuer(),admission.issuer()) && Objects.equals(acceptance.actorSubject(),admission.subject())
                && Objects.equals(acceptance.authorityEvidenceDigest(),acceptedAction.authorityEvidenceDigest())
                && acceptance.acceptedAt().toInstant().equals(accepted.recordedAt()) && c.updatedAt().toInstant().equals(accepted.recordedAt())
                && Arrays.equals(acceptance.resultCanonical(),acceptedAction.resultCanonical())
                && Objects.equals(acceptance.resultDigest(),acceptedAction.resultDigest()));
        size+=acceptedAction.resultCanonical().length;
        return new QuoteRetainedGraph(offer,offered,Optional.of(new QuoteAcceptanceReceipt(intent,accepted.recordedAt(),1)),Optional.of(accepted),size);
    }

    static QuoteOfferVersion version(QuoteAdmission a, QuoteRows.Control c, QuoteRows.Version v) {
        Objects.requireNonNull(a);Objects.requireNonNull(c);Objects.requireNonNull(v);
        scoped(a,c,c.offerId());scoped(a,v,c.offerId());
        require(c.currentOfferVersion()==1 && v.offerVersion()==1 && (c.state().equals("OFFERED") && c.controlGeneration()==0 && c.acceptanceId()==null
                || c.state().equals("ACCEPTED") && c.controlGeneration()==1 && c.acceptanceId()!=null));
        var terms=QuoteCanonicalCodec.readTerms(v.canonicalTerms());var risk=QuoteCanonicalCodec.readRisk(v.riskSnapshot());
        var t=terms.terms();
        require(terms.scope().equals(a.scope()) && terms.offerId().equals(c.offerId()) && terms.offerVersion()==1
                && terms.issuedAt().equals(v.issuedAt().toInstant()) && terms.expiresAt().equals(v.expiresAt().toInstant())
                && Objects.equals(v.canonicalFormat(),"FIREFLY_INSURANCE_QUOTE_TERMS_1")
                && Objects.equals(v.termsDigest(),QuoteCanonicalCodec.digest(v.canonicalTerms()))
                && risk.scope().equals(a.scope()) && Objects.equals(v.riskDigest(),risk.riskDigest()) && Objects.equals(t.riskDigest(),v.riskDigest())
                && Objects.equals(t.productRevisionId(),v.productRevisionId()) && Objects.equals(t.productDigest(),v.productDigest())
                && Objects.equals(t.providerVersion(),v.providerVersion()) && Objects.equals(t.ruleSetVersion(),v.ruleSetVersion())
                && Objects.equals(t.currency(),v.currency()) && t.basePremium().compareTo(v.basePremium())==0
                && t.tax().compareTo(v.tax())==0 && t.fees().compareTo(v.fees())==0);
        return new QuoteOfferVersion(a.scope(),c.offerId(),1,c.controlGeneration(),QuoteOfferVersion.State.valueOf(c.state()),t,terms.issuedAt(),terms.expiresAt());
    }

    private static QuoteResultEnvelope action(QuoteAdmission a, QuoteRows.Version v, QuoteRows.Action row) {
        scoped(a,row,v.offerId());
        var result=QuoteCanonicalCodec.readResult(row.resultCanonical());var action=result.action();
        require(action.scope().equals(a.scope()) && action.offerId().equals(v.offerId()) && action.offerVersion()==1
                && Objects.equals(action.ownerIssuer(),a.issuer()) && Objects.equals(action.ownerSubject(),a.subject())
                && Objects.equals(row.actorIssuer(),a.issuer()) && Objects.equals(row.actorSubject(),a.subject())
                && Objects.equals(row.offerVersionId(),v.offerVersionId()) && row.offerVersion()==1
                && action.operation().name().equals(row.operation()) && Objects.equals(action.actionId(),row.actionId())
                && Objects.equals(action.intentDigest(),row.requestDigest()) && Objects.equals(row.requestDigest(),QuoteCanonicalCodec.digest(row.requestCanonical()))
                && Objects.equals(row.resultDigest(),QuoteCanonicalCodec.digest(row.resultCanonical()))
                && Objects.equals(result.resultId(),row.actionResultId()) && Objects.equals(result.offerVersionId(),v.offerVersionId())
                && Objects.equals(result.acceptanceId(),row.acceptanceId()) && result.recordedAt().equals(row.recordedAt().toInstant()));
        return result;
    }

    static void scoped(QuoteAdmission a, QuoteRows.Scoped row, UUID offerId) {
        require(Objects.equals(row.tenantId(),a.tenantId()) && Objects.equals(row.partyId(),a.partyId())
                && Objects.equals(row.submissionId(),a.submissionId()) && Objects.equals(row.productId(),a.productId())
                && Objects.equals(row.ownerIssuer(),a.issuer()) && Objects.equals(row.ownerSubject(),a.subject())
                && Objects.equals(row.offerId(),offerId) && (a.offerId()==null || Objects.equals(a.offerId(),offerId)));
    }

    public Optional<QuoteResultEnvelope> resultFor(UUID actionId, String expectedIntentDigest) {
        Objects.requireNonNull(actionId);
        if (expectedIntentDigest==null || !expectedIntentDigest.matches("[0-9a-f]{64}")) throw invalid();
        var result=offerResult.action().actionId().equals(actionId)?Optional.of(offerResult):acceptanceResult.filter(x->x.action().actionId().equals(actionId));
        if (result.isPresent() && !result.orElseThrow().action().intentDigest().equals(expectedIntentDigest)) throw new IntentConflict();
        return result;
    }
    private static void require(boolean condition) {if (!condition) throw invalid();}
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("QUOTE_RETAINED_GRAPH_INVALID");}
    @Override public String toString() {return "QuoteRetainedGraph";}
}
