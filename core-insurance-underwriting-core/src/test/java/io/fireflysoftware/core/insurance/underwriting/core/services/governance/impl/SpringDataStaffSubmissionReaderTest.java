package io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl;

import io.r2dbc.spi.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Spring Data mapping and bounded root over a controlled SPI, not PostgreSQL or HTTP proof. */
class SpringDataStaffSubmissionReaderTest {
    static final class Driver implements ConnectionFactory {
        final List<String> statements=new ArrayList<>();final Map<Object,Object> bindings=new LinkedHashMap<>();
        int creates,begins,commits,rollbacks,closes;Runnable selected=()->{};Map<String,Object> row;
        final Connection connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
            case "isAutoCommit" -> true;
            case "beginTransaction" -> Mono.fromRunnable(()->begins++);
            case "commitTransaction" -> Mono.fromRunnable(()->commits++);
            case "rollbackTransaction" -> Mono.fromRunnable(()->rollbacks++);
            case "close" -> Mono.fromRunnable(()->closes++);
            case "createStatement" -> statement((String)a[0]);
            case "toString" -> "ControlledSubmissionReadConnection";
            default -> Mono.empty();
        });
        Statement statement(String sql){statements.add(sql);return(Statement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(p,m,a)->{
            if(m.getName().equals("bind")){bindings.put(a[0],a[1]);return p;}
            if(m.getName().equals("execute")){if(sql.startsWith("SELECT"))selected.run();return Mono.just(result());}
            if(m.getReturnType()==Statement.class)return p;throw new UnsupportedOperationException(m.getName());
        });}
        Result result(){return(Result)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Result.class},(p,m,a)->switch(m.getName()){
            case "getRowsUpdated" -> Mono.just(0L);
            case "map" -> row==null?Flux.empty():Flux.just(a[0]instanceof BiFunction f?f.apply(row(),metadata()):((java.util.function.Function)a[0]).apply(row()));
            case "flatMap" -> Flux.empty();
            case "filter" -> p;
            default -> throw new UnsupportedOperationException(m.getName());
        });}
        Row row(){return(Row)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Row.class},(p,m,a)->switch(m.getName()){
            case "get" -> row.get(a[0]instanceof Integer i?new ArrayList<>(row.keySet()).get(i):a[0]);
            case "getMetadata" -> metadata();
            default -> throw new UnsupportedOperationException(m.getName());
        });}
        RowMetadata metadata(){return(RowMetadata)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{RowMetadata.class},(p,m,a)->switch(m.getName()){
            case "getColumnMetadatas" -> row.keySet().stream().map(this::column).toList();
            case "getColumnMetadata" -> column(a[0]instanceof Integer i?new ArrayList<>(row.keySet()).get(i):(String)a[0]);
            case "contains" -> row.containsKey(a[0]);
            default -> throw new UnsupportedOperationException(m.getName());
        });}
        ColumnMetadata column(String name){return(ColumnMetadata)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ColumnMetadata.class},(p,m,a)->switch(m.getName()){
            case "getName" -> name;
            case "getJavaType" -> row.get(name).getClass();
            case "getType" -> R2dbcType.VARCHAR;
            case "getNullability" -> Nullability.UNKNOWN;
            default -> null;
        });}
        public Publisher<? extends Connection> create(){return Mono.fromSupplier(()->{creates++;return connection;});}
        public ConnectionFactoryMetadata getMetadata(){return()->"PostgreSQL";}
    }
    @Test void exactMappedTenantAndResourceReadHasNoMutationAndClosesBeforeAbsence(){
        var authority=new CurrentStaffSubmissionReadAdmissionTest();var d=new Driver();
        try(var context=new GenericApplicationContext()){context.refresh();var reader=new SpringDataStaffSubmissionReaderImpl(d,new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context,authority.authority());
            assertNull(reader.read(authority.submission).doOnSuccess(value->assertEquals(1,d.closes)).block());}
        assertEquals(1,d.creates);assertEquals(1,d.begins);assertEquals(1,d.commits);assertEquals(0,d.rollbacks);assertEquals(1,d.closes);
        assertTrue(d.statements.contains("SET TRANSACTION READ ONLY"),d.statements.toString());
        var select=d.statements.stream().filter(s->s.startsWith("SELECT")).toList();assertEquals(1,select.size());
        assertTrue(select.getFirst().contains("\"submission\".\"tenant_id\" = $1"),select.getFirst());assertTrue(select.getFirst().contains("\"submission\".\"submission_id\" = $2"),select.getFirst());
        assertEquals(Set.of(authority.resource,authority.submission),new HashSet<>(d.bindings.values()));assertEquals(2,authority.evaluations.get());
        assertTrue(d.statements.stream().allMatch(s->s.startsWith("SELECT")||s.equals("SET TRANSACTION READ ONLY")));
    }
    @Test void deniedReadNeverAcquiresBusinessConnection(){
        var authority=new CurrentStaffSubmissionReadAdmissionTest();authority.deny=true;var d=new Driver();
        try(var context=new GenericApplicationContext()){context.refresh();var reader=new SpringDataStaffSubmissionReaderImpl(d,new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context,authority.authority());assertThrows(SecurityException.class,()->reader.read(authority.submission).block());}
        assertEquals(0,d.creates);assertEquals(0,d.statements.size());
    }
    @Test void changedCurrentBindingAfterSelectRollsBackAndWithholdsEvenAbsence(){
        var authority=new CurrentStaffSubmissionReadAdmissionTest();var d=new Driver();d.selected=()->authority.differentBinding=true;
        try(var context=new GenericApplicationContext()){context.refresh();var reader=new SpringDataStaffSubmissionReaderImpl(d,new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context,authority.authority());assertThrows(SecurityException.class,()->reader.read(authority.submission).block());}
        assertEquals(0,d.commits);assertEquals(1,d.rollbacks);assertEquals(1,d.closes);
    }
    @Test void mappedOwnerRecordIsReturnedOnlyAfterCurrentResponseAuthorityAndClose(){
        var authority=new CurrentStaffSubmissionReadAdmissionTest();var d=new Driver();d.row=Map.of("submission_id",authority.submission,"tenant_id",authority.resource,"submission_number","SUB-CONTROLLED-1");
        try(var context=new GenericApplicationContext()){context.refresh();var reader=new SpringDataStaffSubmissionReaderImpl(d,new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context,authority.authority());
            var result=reader.read(authority.submission).doOnNext(value->{assertEquals(1,d.closes);assertEquals(2,authority.evaluations.get());}).block();
            assertNotNull(result);assertEquals(authority.submission,result.getSubmissionId());assertEquals(authority.resource,result.getTenantId());assertEquals("SUB-CONTROLLED-1",result.getSubmissionNumber());}
    }
    @Test void incorrectlyScopedDriverRowCannotEscapeExactOwnerRead(){
        var authority=new CurrentStaffSubmissionReadAdmissionTest();var d=new Driver();d.row=Map.of("submission_id",authority.submission,"tenant_id",UUID.randomUUID());
        try(var context=new GenericApplicationContext()){context.refresh();var reader=new SpringDataStaffSubmissionReaderImpl(d,new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context,authority.authority());assertThrows(SecurityException.class,()->reader.read(authority.submission).block());}
        assertEquals(0,d.commits);assertEquals(1,d.rollbacks);assertEquals(1,d.closes);
    }

}
