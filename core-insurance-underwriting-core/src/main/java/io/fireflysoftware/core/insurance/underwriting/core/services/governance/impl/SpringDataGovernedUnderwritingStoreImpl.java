package io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.*;
import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.*;
import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.*;
import io.fireflysoftware.core.insurance.underwriting.models.entities.*;
import io.fireflysoftware.core.insurance.underwriting.models.governance.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.core.StatementMapper;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.data.relational.core.query.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import reactor.core.publisher.Mono;

/** Real existing Referral/Decision rows and durable action receipts share the actual canonical audit root. */
public final class SpringDataGovernedUnderwritingStoreImpl {
    private final R2dbcEntityTemplate template;private final StaffUnderwritingAuditRootImpl root;
    private final UnderwritingGovernanceRepositories.Referrals referrals;private final UnderwritingGovernanceRepositories.Actions actions;private final UnderwritingGovernanceRepositories.Native nativeContext;private final Supplier<UUID> ids;
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10).maxStringLength(4096).build()).build()).registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public SpringDataGovernedUnderwritingStoreImpl(R2dbcEntityTemplate template,StaffUnderwritingAuditRootImpl root,Supplier<UUID> ids){this(template,root,ids,new R2dbcRepositoryFactory(template));}
    private SpringDataGovernedUnderwritingStoreImpl(R2dbcEntityTemplate template,StaffUnderwritingAuditRootImpl root,Supplier<UUID> ids,R2dbcRepositoryFactory factory){this(template,root,ids,factory.getRepository(UnderwritingGovernanceRepositories.Referrals.class),factory.getRepository(UnderwritingGovernanceRepositories.Actions.class),factory.getRepository(UnderwritingGovernanceRepositories.Native.class));}
    SpringDataGovernedUnderwritingStoreImpl(R2dbcEntityTemplate template,StaffUnderwritingAuditRootImpl root,Supplier<UUID> ids,UnderwritingGovernanceRepositories.Referrals referrals,UnderwritingGovernanceRepositories.Actions actions,UnderwritingGovernanceRepositories.Native nativeContext){this.template=Objects.requireNonNull(template);this.root=Objects.requireNonNull(root);root.requireTemplate(template);this.ids=Objects.requireNonNull(ids);this.referrals=Objects.requireNonNull(referrals);this.actions=Objects.requireNonNull(actions);this.nativeContext=Objects.requireNonNull(nativeContext);}
    public Mono<GovernedUnderwritingCommandResponse> execute(StaffUnderwritingAdmission.Permit permit){return Mono.defer(()->{
        var request=Objects.requireNonNull(permit.request().intent());String digest=request.intentDigest(permit.resourceTenantId());if(!digest.equals(permit.request().intentDigest()))throw refused();
        var source=new AtomicReference<UnderwritingGovernanceRows.Source>();var selected=new AtomicReference<UnderwritingGovernanceRows.ReferralControl>();UUID event=id();
        return root.execute(permit,request.submissionId(),event,action(request.operation()),
            ()->UnderwritingGovernanceValues.hash("CORE_UNDERWRITING_BUSINESS_EFFECT_V1",digest,sourceDigest(source.get()),permit.authorityLevel(),selected.get()==null?"":selected.get().currentReceiptDigest()),
            ()->selected.get()==null?null:selected.get().currentEventId(),
            ()->valid(nativeContext.protocol()).then(nativeContext.open(permit.resourceTenantId(),request.submissionId(),request.riskId(),request.actionId(),request.operation().name(),digest,permit.actor().issuer(),permit.actor().subject(),permit.actor().clientId(),permit.actor().platformTenantId(),permit.actor().staffRealm(),permit.actor().staffId(),permit.notAfter().toEpochMilli()))
                .switchIfEmpty(Mono.error(refused())).flatMap(scope->{StaffUnderwritingEffectRulesImpl.source(permit,scope);source.set(scope);return root.observeLockedSource(permit,scope).then(retained(permit)).flatMap(existing->{
                    if(existing.isPresent() || request.operation()==GovernedUnderwritingIntent.Operation.REFERRAL_CREATE)return Mono.just(existing);
                    return referrals.findByTenantIdAndSubmissionIdAndRiskIdAndReferralId(permit.resourceTenantId(),request.submissionId(),request.riskId(),request.referralId()).switchIfEmpty(Mono.error(refused())).map(control->{StaffUnderwritingEffectRulesImpl.control(permit,scope,control);selected.set(control);return Optional.<GovernedUnderwritingCommandResponse>empty();});
                });}),effect->{
                var scope=source.get();var previous=selected.get();var record=effect.record();var time=LocalDateTime.ofInstant(record.recordedAt(),ZoneOffset.UTC);var offset=OffsetDateTime.ofInstant(record.recordedAt(),ZoneOffset.UTC);
                UUID referral=request.operation()==GovernedUnderwritingIntent.Operation.REFERRAL_CREATE?id():request.referralId();UUID decision=request.operation()==GovernedUnderwritingIntent.Operation.DECISION_RECORD?id():null;
                long version=request.operation()==GovernedUnderwritingIntent.Operation.REFERRAL_CREATE?0:1;
                String receiptDigest=UnderwritingGovernanceValues.hash("CORE_UNDERWRITING_RECEIPT_V1",permit.resourceTenantId().toString(),permit.actor().issuer(),permit.actor().subject(),request.actionId().toString(),digest,request.operation().name(),referral.toString(),Long.toString(version),decision==null?"":decision.toString(),record.recordHash());
                var receipt=new GovernedUnderwritingReceipt(request.actionId(),digest,request.operation(),referral,version,decision,receiptDigest);
                Mono<Void> effectRows=switch(request.operation()){
                    case REFERRAL_CREATE -> insert(Referral.builder().referralId(referral).submissionId(request.submissionId()).reason(request.reason()).authorityLevel(permit.authorityLevel()).referralStatus(ReferralStatus.OPEN).referredAt(time).createdAt(time).updatedAt(time).build())
                        .then(insert(new UnderwritingGovernanceRows.ReferralControl(referral,permit.resourceTenantId(),request.submissionId(),scope.partyId(),scope.productId(),request.riskId(),0,"OPEN",permit.authorityLevel(),permit.actor().issuer(),permit.actor().subject(),permit.actor().staffRealm(),permit.actor().staffId(),null,null,null,null,request.actionId(),request.actionId(),event,receiptDigest,null,offset)));
                    case REFERRAL_APPROVE -> one(template.update(Referral.class).matching(Query.query(Criteria.where("referralId").is(referral).and("submissionId").is(request.submissionId()).and("referralStatus").is(ReferralStatus.OPEN).and("authorityLevel").is(permit.authorityLevel()).and("updatedAt").is(previous.currentUpdatedAt().toLocalDateTime()))).apply(Update.update("referralStatus",ReferralStatus.APPROVED).set("updatedAt",time)))
                        .then(one(template.update(UnderwritingGovernanceRows.ReferralControl.class).matching(Query.query(Criteria.where("referralId").is(referral).and("tenantId").is(permit.resourceTenantId()).and("submissionId").is(request.submissionId()).and("riskId").is(request.riskId()).and("version").is(0L).and("state").is("OPEN").and("currentReceiptDigest").is(request.expectedReceiptDigest())))
                            .apply(Update.update("version",1L).set("state","APPROVED").set("approvedSourceDigest",sourceDigest(scope)).set("approverIssuer",permit.actor().issuer()).set("approverSubject",permit.actor().subject()).set("approverStaffRealm",permit.actor().staffRealm()).set("approverStaffId",permit.actor().staffId()).set("currentActionId",request.actionId()).set("currentEventId",event).set("currentReceiptDigest",receiptDigest).set("currentUpdatedAt",offset))));
                    case DECISION_RECORD -> insert(UnderwritingDecision.builder().underwritingDecisionId(decision).submissionId(request.submissionId()).decisionOutcome(DecisionOutcome.ACCEPT).conditions(null)
                            .authorityReference("governed:"+previous.currentActionId()+":"+previous.currentReceiptDigest()).decidedAt(time).boundPolicyId(null).createdAt(time).updatedAt(time).build())
                        .then(insert(new UnderwritingGovernanceRows.DecisionControl(decision,permit.resourceTenantId(),request.submissionId(),scope.partyId(),scope.productId(),request.riskId(),referral,previous.currentActionId(),previous.currentReceiptDigest(),request.actionId(),event,receiptDigest)));
                };
                return effectRows.then(retain(permit,scope,receipt,effect)).thenReturn(new GovernedUnderwritingCommandResponse(GovernedUnderwritingCommandResponse.Status.COMMITTED,request.actionId(),digest,receipt));
            });
    });}
    public Mono<GovernedUnderwritingRecovery> recover(StaffUnderwritingAdmission.Permit permit){return Mono.defer(()->{
        if(!permit.request().recovery())throw refused();
        return root.read(permit,()->valid(nativeContext.protocol()).then(valid(nativeContext.read(permit.resourceTenantId(),permit.request().actionId(),permit.request().intentDigest(),permit.actor().issuer(),permit.actor().subject(),permit.actor().clientId(),permit.actor().platformTenantId(),permit.actor().staffRealm(),permit.actor().staffId(),permit.notAfter().toEpochMilli()))).then(retained(permit)).map(value->{
            if(value.isEmpty())return new GovernedUnderwritingRecovery(GovernedUnderwritingRecovery.State.NOT_OBSERVED_AT_THIS_READ,permit.request().actionId(),permit.request().intentDigest(),null);
            var command=value.orElseThrow();return command.status()==GovernedUnderwritingCommandResponse.Status.COMMITTED?new GovernedUnderwritingRecovery(GovernedUnderwritingRecovery.State.FOUND,command.actionId(),command.intentDigest(),command.receipt()):new GovernedUnderwritingRecovery(GovernedUnderwritingRecovery.State.INTENT_CONFLICT,permit.request().actionId(),permit.request().intentDigest(),null);
        }));
    });}
    private Mono<Optional<GovernedUnderwritingCommandResponse>> retained(StaffUnderwritingAdmission.Permit permit){return actions.findByTenantIdAndOwnerIssuerAndOwnerSubjectAndActionId(permit.resourceTenantId(),permit.actor().issuer(),permit.actor().subject(),permit.request().actionId()).flatMap(row->{
        var actor=permit.actor();if(!permit.resourceTenantId().equals(row.tenantId()) || !permit.request().actionId().equals(row.actionId()) || !Objects.equals(actor.issuer(),row.ownerIssuer()) || !Objects.equals(actor.subject(),row.ownerSubject()) || !Objects.equals(actor.clientId(),row.clientId()) || !Objects.equals(actor.platformTenantId(),row.platformTenant()) || !Objects.equals(actor.staffRealm(),row.staffRealm()) || !Objects.equals(actor.staffId(),row.staffId()))throw refused();
        if(!permit.request().intentDigest().equals(row.intentDigest()))return Mono.just(Optional.of(new GovernedUnderwritingCommandResponse(GovernedUnderwritingCommandResponse.Status.REFUSED,permit.request().actionId(),permit.request().intentDigest(),null)));
        return valid(nativeContext.receipt(row.operationId())).then(Mono.fromSupplier(()->{
            var receipt=decode(row.resultJson());if(!row.actionId().equals(receipt.actionId()) || !row.intentDigest().equals(receipt.intentDigest()) || !row.operation().equals(receipt.operation().name()) || !row.receiptDigest().equals(receipt.receiptDigest()) || !row.referralId().equals(receipt.referralId()) || !Objects.equals(row.decisionId(),receipt.decisionId()))throw refused();
            return Optional.of(new GovernedUnderwritingCommandResponse(GovernedUnderwritingCommandResponse.Status.COMMITTED,row.actionId(),row.intentDigest(),receipt));
        }));
    }).defaultIfEmpty(Optional.empty());}
    private Mono<Void> retain(StaffUnderwritingAdmission.Permit permit,UnderwritingGovernanceRows.Source source,GovernedUnderwritingReceipt receipt,StaffUnderwritingAuditRootImpl.Effect effect){var record=effect.record();var decisions=effect.decisions();var request=permit.request().intent();
        var row=new UnderwritingGovernanceRows.Action(id(),permit.resourceTenantId(),permit.actor().issuer(),permit.actor().subject(),permit.actor().clientId(),permit.actor().platformTenantId(),permit.actor().staffRealm(),permit.actor().staffId(),request.actionId(),request.operation().name(),permit.request().intentDigest(),encode(request),request.submissionId(),source.partyId(),source.productId(),request.riskId(),encode(source),sourceDigest(source),permit.authorityLevel(),receipt.referralId(),receipt.decisionId(),encode(receipt),receipt.receiptDigest(),record.event().eventId(),record.recordHash(),record.event().evidenceDigest(),permit.bindingId(),permit.bindingVersion(),permit.bindingDigest(),decisions.authorization().policyRevision(),decisions.authorization().decisionId(),decisions.admission().policyRevision(),decisions.admission().decisionId(),decisions.digest(),permit.processId(),permit.correlationId());
        return insert(row).then(valid(nativeContext.receipt(row.operationId()))).then(actions.findByTenantIdAndOwnerIssuerAndOwnerSubjectAndActionId(permit.resourceTenantId(),permit.actor().issuer(),permit.actor().subject(),request.actionId()).filter(row::equals).switchIfEmpty(Mono.error(refused())).then());
    }
    private Mono<Void> insert(Object row){var strategy=template.getDataAccessStrategy();var mapper=strategy.getStatementMapper();StatementMapper.InsertSpec insert=mapper.createInsert(strategy.getTableName(row.getClass()));var outbound=strategy.getOutboundRow(row);for(var column:outbound.keySet())insert=insert.withColumn(column,outbound.get(column));return one(template.getDatabaseClient().sql(mapper.getMappedObject(insert)).fetch().rowsUpdated());}
    private static Mono<Void> one(Mono<Long> value){return value.filter(count->count==1L).switchIfEmpty(Mono.error(refused())).then();}
    private static Mono<Void> valid(Mono<Boolean> value){return value.filter(Boolean.TRUE::equals).switchIfEmpty(Mono.error(refused())).then();}
    private UUID id(){return UnderwritingGovernanceValues.id(ids.get());}
    private String encode(Object value){try{String result=json.writeValueAsString(value);if(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw refused();return result;}catch(com.fasterxml.jackson.core.JsonProcessingException error){throw refused();}}
    private GovernedUnderwritingReceipt decode(String value){try{if(value==null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw refused();return json.readValue(value,GovernedUnderwritingReceipt.class);}catch(com.fasterxml.jackson.core.JsonProcessingException error){throw refused();}}
    private static String sourceDigest(UnderwritingGovernanceRows.Source source){return StaffUnderwritingEffectRulesImpl.digest(source);}
    private static String action(GovernedUnderwritingIntent.Operation operation){return switch(operation){case REFERRAL_CREATE->"insurance.underwriting.referral.create";case REFERRAL_APPROVE->"insurance.underwriting.referral.approve";case DECISION_RECORD->"insurance.underwriting.decision.record";};}
    private static SecurityException refused(){return new SecurityException("Exact governed owner underwriting effect required");}
}
