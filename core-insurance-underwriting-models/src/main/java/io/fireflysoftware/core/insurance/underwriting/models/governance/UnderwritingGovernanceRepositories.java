package io.fireflysoftware.core.insurance.underwriting.models.governance;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

/** Dedicated native helper calls and scoped Spring Data reads; no generic application CRUD grant. */
public final class UnderwritingGovernanceRepositories {
    private UnderwritingGovernanceRepositories(){}
    public interface Referrals extends Repository<UnderwritingGovernanceRows.ReferralControl,UUID>{Mono<UnderwritingGovernanceRows.ReferralControl> findByTenantIdAndSubmissionIdAndRiskIdAndReferralId(UUID tenantId,UUID submissionId,UUID riskId,UUID referralId);}
    public interface Actions extends Repository<UnderwritingGovernanceRows.Action,UUID>{Mono<UnderwritingGovernanceRows.Action> findByTenantIdAndOwnerIssuerAndOwnerSubjectAndActionId(UUID tenantId,String ownerIssuer,String ownerSubject,UUID actionId);}
    public interface Native extends Repository<UnderwritingGovernanceRows.Action,UUID>{
        @Query("SELECT insurance_quote.underwriting_protocol() AS valid") Mono<Boolean> protocol();
        @Query("SELECT * FROM insurance_quote.underwriting_open(:tenant,:submission,:risk,:action,:operation,:digest,:issuer,:subject,:client,:platform,:staffRealm,:staffId,:until)")
        Mono<UnderwritingGovernanceRows.Source> open(@Param("tenant") UUID tenant,@Param("submission") UUID submission,@Param("risk") UUID risk,@Param("action") UUID action,@Param("operation") String operation,@Param("digest") String digest,@Param("issuer") String issuer,@Param("subject") String subject,@Param("client") String client,@Param("platform") String platform,@Param("staffRealm") String staffRealm,@Param("staffId") UUID staffId,@Param("until") long until);
        @Query("SELECT insurance_quote.underwriting_read(:tenant,:action,:digest,:issuer,:subject,:client,:platform,:staffRealm,:staffId,:until) AS valid")
        Mono<Boolean> read(@Param("tenant") UUID tenant,@Param("action") UUID action,@Param("digest") String digest,@Param("issuer") String issuer,@Param("subject") String subject,@Param("client") String client,@Param("platform") String platform,@Param("staffRealm") String staffRealm,@Param("staffId") UUID staffId,@Param("until") long until);
        @Query("SELECT insurance_quote.underwriting_receipt(:operation) AS valid") Mono<Boolean> receipt(@Param("operation") UUID operation);
    }
}
