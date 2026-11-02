package io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.StaffSubmissionReader;

import io.fireflysoftware.core.insurance.underwriting.models.entities.Submission;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.fireflyframework.core.transaction.*;
import org.fireflyframework.core.transaction.impl.DefaultBoundedRootTransaction;
import org.springframework.context.ApplicationContext;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;

/** One current-authority read through a separately provisioned read-only business connection. */
public final class SpringDataStaffSubmissionReaderImpl implements StaffSubmissionReader {
    private final CurrentStaffSubmissionReadAdmissionImpl authority;
    private final R2dbcEntityTemplate operations;
    private final BoundedRootTransaction transaction;
    private final DefaultTransactionDefinition definition;

    public SpringDataStaffSubmissionReaderImpl(ConnectionFactory readFactory,R2dbcConverter converter,
            ApplicationContext context,CurrentStaffSubmissionReadAdmissionImpl authority){
        this.authority=Objects.requireNonNull(authority);
        var owned=new OwnedLeaseConnectionFactory(Objects.requireNonNull(readFactory));
        operations=new R2dbcEntityTemplate(DatabaseClient.create(owned),PostgresDialect.INSTANCE,Objects.requireNonNull(converter));
        operations.setBeanFactory(Objects.requireNonNull(context));operations.setApplicationContext(context);
        var manager=new R2dbcTransactionManager(owned);manager.setEnforceReadOnly(true);
        transaction=new DefaultBoundedRootTransaction(BoundedR2dbcResources.verify(owned,operations.getDatabaseClient(),operations,manager),
            new CleanupPolicy(Duration.ofSeconds(2),Duration.ofSeconds(2)));
        definition=new DefaultTransactionDefinition();definition.setName("staff-submission-read");definition.setReadOnly(true);
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);definition.setTimeout(5);
    }
    @Override public Mono<Submission> read(UUID submissionId){
        return authority.read(submissionId,permit->{
            var workError=new AtomicReference<Throwable>();
            return transaction.execute(permit.operationBudget(),definition,scope->authority.revalidate(permit)
                .then(Mono.defer(()->{
                    scope.narrow(permit.operationBudget());
                    return operations.selectOne(Query.query(Criteria.where("tenantId").is(permit.resourceTenantId()).and("submissionId").is(permit.submissionId())),Submission.class)
                        .map(row->{if(!permit.resourceTenantId().equals(row.getTenantId())||!permit.submissionId().equals(row.getSubmissionId()))throw denied();return Optional.of(row);})
                        .defaultIfEmpty(Optional.empty());
                })).flatMap(value->authority.revalidate(permit).thenReturn(value)).doOnError(workError::set))
                .map(RootCompletion::value).onErrorMap(RootTransactionException.class,error->{
                    var facts=error.transactionFacts();
                    if(workError.get()!=null&&facts.rollbackAcknowledged()&&!facts.commitAcknowledged()&&!facts.boundaryCompleted()&&error.cleanupFact()==CleanupFact.COMPLETE)return workError.get();
                    return new IllegalStateException("STAFF_SUBMISSION_READ_COMPLETION_UNCERTAIN");
                });
        }).flatMap(value->value.map(Mono::just).orElseGet(Mono::empty));
    }
    private static SecurityException denied(){return new SecurityException("Exact current tenant submission read required");}
    @Override public String toString(){return "SpringDataStaffSubmissionReaderImpl";}
}
