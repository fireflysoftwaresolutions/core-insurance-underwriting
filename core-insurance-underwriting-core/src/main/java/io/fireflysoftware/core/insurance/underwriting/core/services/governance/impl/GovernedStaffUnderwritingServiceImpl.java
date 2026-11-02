package io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.GovernedStaffUnderwritingService;
import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.*;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Each command invokes one owner transaction; restart recovery uses durable owner actions, never an in-memory saga. */
public final class GovernedStaffUnderwritingServiceImpl implements GovernedStaffUnderwritingService {
    private final CurrentStaffUnderwritingAdmissionImpl authority;private final SpringDataGovernedUnderwritingStoreImpl store;
    public GovernedStaffUnderwritingServiceImpl(CurrentStaffUnderwritingAdmissionImpl authority,SpringDataGovernedUnderwritingStoreImpl store){this.authority=Objects.requireNonNull(authority);this.store=Objects.requireNonNull(store);}
    @Override public Mono<GovernedUnderwritingPreparedIntent> prepare(GovernedUnderwritingIntent intent){return Mono.defer(()->authority.prepare(intent));}
    @Override public Mono<GovernedUnderwritingCommandResponse> execute(GovernedUnderwritingCommandRequest request){return Mono.defer(()->{Objects.requireNonNull(request);return authority.execute(request.intent(),request.originalIntentDigest(),store::execute);});}
    @Override public Mono<GovernedUnderwritingRecovery> recover(UUID actionId,String digest){return Mono.defer(()->authority.recover(actionId,digest,permit->authority.revalidate(permit).then(store.recover(permit)).flatMap(value->authority.revalidate(permit).thenReturn(value))));}
}
