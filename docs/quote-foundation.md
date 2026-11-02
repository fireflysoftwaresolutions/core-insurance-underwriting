# Quote foundation

The quote foundation provides immutable Java contracts and local rules for preparing terms and recording the meaning of an acceptance. It is a source foundation for a future governed quote journey. Existing submission and generic quote APIs keep their behavior.

## Terms and acceptance

`QuoteOfferScope` binds tenant, submission, party, and product identifiers. `QuoteOfferTerms` holds currency, premium, tax, fees, ordered coverage terms, product revision and digest, risk digest, and provider/rule versions. Money uses exact currency precision and bounded decimal input; total premium is validated separately.

`QuoteOfferVersion` adds the offer identifier, version, control generation, state, issue time, and expiry. Local acceptance rules require the expected scope, version, generation, terms digest, and a live supplied decision horizon. The immutable receipt links the exact intent to its acceptance time and one control-generation advance.

These local checks operate on supplied facts. They do not authenticate the caller, fetch current product or risk evidence, authorize an operation, reserve an action, or commit a receipt. A supplied clock or digest is not owner authority. A `QuoteAcceptanceReceipt` constructed in memory is not evidence of a database commit.

## Illustrative property pricing

`SandboxPropertyQuoteRating` has an explicit implementation in `services.quotes.impl`. It has no Spring registration or route. It supports only the sample USD property shape, an insured limit greater than zero and at most $1,000,000, a deductible from zero through that limit, and a period of one to 366 days.

The sample base premium is 1% of the insured limit, with a $25 minimum. It adds a 5% illustrative tax and a $10 fee, using explicit cent rounding. For a $100,000 limit, those amounts are $1,000, $50, and $10. The period and deductible are validated and included in the terms; the sample does not prorate or adjust the price for them.

The returned wording and exclusions are illustrative sandbox data. This calculator is not an insurer integration, actuarial model, approved rate, tax determination, or underwriting decision. Acceptance does not bind coverage, issue a policy, collect a premium, transfer money, or create an accounting entry. A production provider and coherent current product/configuration authority require separate integration.

## Canonical data

`QuoteCanonicalCodec` uses versioned, length-prefixed UTF-8 fields. The existing terms and acceptance-intent digests use the same canonical bytes. Typed decoders enforce framing, size, field, currency, amount, coverage, and time rules and reject trailing or noncanonical encodings.

Terms bytes describe immutable offered facts. Mutable control state and generation are deliberately outside the terms digest. The acceptance-intent bytes bind the expected generation and action. Receipt, owner action, and result envelopes have distinct version headers.

`QuoteActionEnvelope` binds the stable original issuer and subject, full scope, operation, offer/version, expected generation, action, and intent digest. `QuoteResultEnvelope` links that action to result/version/acceptance identifiers, decision time, and resulting generation. Issuer and subject retain their exact Unicode and case. Raw tokens and ephemeral authorization evidence are not envelope fields.

Encoding or decoding these envelopes proves data consistency only. It does not prove the issuer, a database timestamp, permission, a provider outcome, or a durable result. OFFER request assembly and the current risk snapshot producer remain prerequisites for the future owner service.

## Durable recovery remains separate

The local rules distinguish an identical retained intent from a changed intent. A future service must load the retained owner result under fresh current authority, including when an earlier credential or offer has expired. An absent row means only that no result was observed at that read; it cannot establish final absence of an effect while an earlier commit may still complete.

Persistence, scoped roles and transactions, current caller/PDP checks, native human delegation, strict HTTP inputs, original OpenAPI production, and generated SDK integration are not provided by this foundation. There are no new routes or enabled beans. Source-level unit tests do not establish those integrations or a complete insurance journey.
