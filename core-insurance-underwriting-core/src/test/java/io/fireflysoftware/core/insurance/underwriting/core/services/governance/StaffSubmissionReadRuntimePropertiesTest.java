package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaffSubmissionReadRuntimePropertiesTest{
    @Test void secureReaderUsesOnlyItsExplicitDedicatedRole(){var value=new StaffSubmissionReadRuntimeProperties(true,"db.example",5432,"insurance","underwriting_submission_read","r".repeat(32),null,false);assertEquals("VERIFY_FULL",value.sslMode());assertFalse(value.toString().contains("r".repeat(32)));}
    @Test void existingWriterOrLegacyCredentialsCannotBecomeTheReader(){for(String username:new String[]{"firefly_audit_writer","quote_command_runtime","postgres",""})assertThrows(IllegalArgumentException.class,()->new StaffSubmissionReadRuntimeProperties(true,"db.example",5432,"insurance",username,"r".repeat(32),"VERIFY_FULL",false));}
    @Test void plaintextRequiresExplicitOwnedLiteralLoopback(){assertThrows(IllegalArgumentException.class,()->new StaffSubmissionReadRuntimeProperties(true,"127.0.0.1",5432,"insurance","underwriting_submission_read","r".repeat(32),"DISABLE",false));assertThrows(IllegalArgumentException.class,()->new StaffSubmissionReadRuntimeProperties(true,"localhost",5432,"insurance","underwriting_submission_read","r".repeat(32),"DISABLE",true));assertEquals("DISABLE",new StaffSubmissionReadRuntimeProperties(true,"127.0.0.1",5432,"insurance","underwriting_submission_read","r".repeat(32),"DISABLE",true).sslMode());}
    @Test void noCredentialOrDatabaseDefaultIsInferred(){assertThrows(IllegalArgumentException.class,()->new StaffSubmissionReadRuntimeProperties(true,null,0,null,null,null,null,false));assertThrows(IllegalArgumentException.class,()->new StaffSubmissionReadRuntimeProperties(true,"db.example",5432,"insurance","underwriting_submission_read","short","VERIFY_FULL",false));}
}
