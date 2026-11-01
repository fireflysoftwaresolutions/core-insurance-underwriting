# Governed quote HTTP contract

These typed routes delegate to the existing governed quote owner. They do not make legacy quote CRUD an authority for pricing, bind an insurance policy, collect payment, install the optional schema, or activate a provider. The existing `insurance.quotes.governed.enabled` capability remains default-off. A genuinely absent service produces the shared code `QUOTE_OWNER_UNAVAILABLE`/503 before an owner invocation. Bean-creation or owner failures are not silently treated as absence.

The independent route family is:

`/api/v1/tenants/{tenantId}/submissions/{submissionId}/parties/{partyId}/products/{productId}/quote-offers`

| Operation | Inputs | Successful typed response |
| --- | --- | --- |
| POST base | Stable caller actionId, coverage dates, expectedProductRevisionId and expectedProductDigest | Command outcome below |
| POST base/{offerId}/acceptances | Stable caller actionId, offerVersion, explicit expectedControlGeneration, termsDigest | Command outcome below |
| GET base/{offerId} | Expected resource scope and offer | FOUND entry or NOT_OBSERVED_AT_THIS_READ |
| GET base | Expected resource scope | Owner-ordered bounded history entries |
| GET base/actions/{actionId}/recovery | Required expectedIntentDigest query | FOUND, NOT_OBSERVED_AT_THIS_READ or INTENT_CONFLICT |

Every response containing protected owner data uses `Cache-Control: no-store`. All five methods opt out of the framework's HTTP idempotency cache using `DisableIdempotency`. The owner's stable action/canonical-intent journal remains the business replay control. No response cache becomes authorization or mutation truth.

Request DTOs carry expectations, never issuer, subject, principal, actor/PDP proof, price, provider or authority expiry. The expected resource scope is constructed from the path, not body fields. Unknown JSON body fields are ignored and cannot replace that scope or create authority. The actual owner must independently validate the current authenticated principal, tenant binding, party, delegation, PDP and finite native context. Existing deterministic request-construction failures use the shared fixed `QUOTE_INPUT_INVALID`/400. Ordinary bean-validation failures retain the shared400 contract. An omitted control generation is invalid; it never silently becomes zero.

## Command outcome

A completed HTTP200 command has required `status`, original `actionId` and captured canonical `intentDigest`. It contains `result` only for COMMITTED. The controller verifies the owner result's operation/scope/action/intent and acceptance expectations before projecting it.

| Status | Meaning |
| --- | --- |
| COMMITTED | The owner confirmed the exact action result and admitted its protected projection. |
| COMMITTED_RESPONSE_UNAVAILABLE | The owner reports a known commit but cannot return the protected result; recover the same action. |
| OUTCOME_UNKNOWN | No exact usable owner completion is available. This does not assert admission, work start, commit or refusal. |
| REFUSED | The existing owner emitted its explicit refusal completion. |

After invocation only, an unclassified owner error, empty completion or mismatched result maps conservatively to OUTCOME_UNKNOWN with the same captured action/digest and no protected value. It is never relabeled as no-write, success or retryable503. Pre-controller bearer401/403 remains unchanged; known input400 and genuinely missing-owner503 occur separately before invocation. No raw exception message/cause is put in the envelope. No automatic retry, replacement action ID or Retry-After promise is made.

Read/recovery errors, empty publishers and mismatched owner data produce a fixed shared500 boundary, never a fabricated missing observation or generic503. An authorized explicit empty Optional is the only current-read path to NOT_OBSERVED_AT_THIS_READ. Recovery never calls offer or accept. NOT_OBSERVED_AT_THIS_READ is not proof that an action cannot later become visible; INTENT_CONFLICT is not permission to repair or overwrite it.

Read/history entries include the exact canonical `termsDigest` explicitly, because the existing `QuoteOfferVersion.termsDigest()` computation is not a record component. Acceptance must use that exact digest and the observed version/control expectations. This reuses the owner's canonical calculation; it does not introduce a pricing rule or change stored formats.

Transport loss, encoding failure or a client timeout is not a command outcome. Recover with the original action and expected intent rather than interpreting missing HTTP data as no mutation. The typed controller does not extend an owner deadline, reauthorize a cached result, or establish a physical HTTP-commit/native transaction proof by itself.

## Deployment requirements

Keep the owner service, current authority checks, repository transaction and optional schema installation aligned with the deployed release. Resolve the signed opaque platform tenant through the Configuration owner before using a resource UUID. Validate issuer, audience, expiration and any signed not-before claim through the security adapter. Never infer a tenant UUID from an opaque namespace or invent a missing not-before value.

Use the existing Product purpose owner and explicit Configuration lookup credentials. A fixed-premium TERM_PC product requires its own supported managed program; it cannot be converted implicitly to the illustrative sandbox deductible, rating rules or offer lifetime. Enable the governed owner only after its product adapter, trusted inbound identity, current PDP decisions, schema roles and transaction behavior are configured and verified. Validate the complete experience → domain → core operation, including rollback, replay and concurrent requests. Policy binding also requires a real accepted quote and recoverable policy and document prerequisites.

The maintained controller, shared-handler and original-producer tests cover this HTTP boundary. Their scope is distinct from deployment authorization, durable business operations and provider integration.

## Producer-owned bearer metadata

The governed controller declares the HTTP bearer/JWT scheme and requires it for all five operations. Shared JSON ErrorResponse and ProblemDetail schemas document401/403 when the framework encodes those responses; a security filter may instead return an empty body. The maintained original OpenApiGenApplication test reads the actual /v3/api-docs resource in a mock reactive web context, asserts the bearer component and each operation's requirement/responses, and checks that no business ConnectionFactory bean was created. It does not fabricate an OpenAPI document or edit a generated spec/SDK.

Configure and verify the actual bearer filter, signature and JWKS validation, issuer and audience, current PDP decisions and delegated authority in the deployment. API metadata describes the contract; runtime authorization still requires those controls.

## OpenAPI and generated clients

The original producer declares `application/json` and a concrete response schema for each successful operation. Generate clients through the repository's official OpenAPI generation flow using the matching producer output. Configure the client with the trusted service endpoint for the deployment; a documentation capture or generated client default is not an endpoint-discovery mechanism. Verify the generated models, bearer support and packaged specification before adopting a new SDK release.
