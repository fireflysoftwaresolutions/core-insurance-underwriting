package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.*;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface GovernedStaffUnderwritingService {
    Mono<GovernedUnderwritingPreparedIntent> prepare(GovernedUnderwritingIntent intent);
    Mono<GovernedUnderwritingCommandResponse> execute(GovernedUnderwritingCommandRequest request);
    Mono<GovernedUnderwritingRecovery> recover(UUID actionId,String digest);
}
