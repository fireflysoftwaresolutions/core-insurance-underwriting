package io.fireflysoftware.core.insurance.underwriting.core.quotes;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuoteOriginalRuntimeCaseExpressionTest{
    @Test void callerRoleCaseStaysAnExpressionAndRetainsEveryOriginalPrivilegePredicate()throws Exception{
        String script=Files.readString(Path.of("../core-insurance-underwriting-models/src/main/resources/db/optional/insurance-quote/V4__Governed_Quote_Persistence.sql"));int start=script.indexOf("CREATE FUNCTION insurance_quote_private.require_runtime(");assertTrue(start>=0);String body=script.substring(start,script.indexOf("END $$;",start));
        assertTrue(body.contains("OR session_user IS DISTINCT FROM (CASE WHEN expected_group='quote_command' THEN 'quote_command_runtime' ELSE 'quote_read_runtime' END)"));assertFalse(body.contains("OR session_user IS DISTINCT FROM CASE "));
        for(String predicate:new String[]{"current_user<>'quote_guard'","current_setting('role') NOT IN('none',session_user::text)","attributes.rolsuper OR attributes.rolbypassrls OR attributes.rolcreaterole OR attributes.rolcreatedb OR attributes.rolreplication OR NOT attributes.rolcanlogin","has_database_privilege(session_user,current_database(),'TEMP')","has_schema_privilege(session_user,'public','CREATE')","has_schema_privilege(session_user,'insurance_quote','CREATE')","has_schema_privilege(session_user,'insurance_quote_private','CREATE')","(SELECT count(*) FROM pg_auth_members WHERE member=attributes.oid)<>1","r.rolname=expected_group AND m.inherit_option AND NOT m.set_option AND NOT m.admin_option"})assertTrue(body.contains(predicate),predicate);
    }
}
