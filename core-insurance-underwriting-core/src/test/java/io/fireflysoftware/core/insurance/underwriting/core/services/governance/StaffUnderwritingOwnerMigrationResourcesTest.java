package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import io.fireflysoftware.core.insurance.underwriting.models.entities.Submission;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.internal.scanner.Scanner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual model classpath resources only; never executes an installer or creates history. */
class StaffUnderwritingOwnerMigrationResourcesTest {
    static final String STAFF="db/optional/insurance-underwriting-governance/";
    static final Map<String,String> SCRIPTS=Map.of("V5__Recorded_TERM_PC_Underwriting_Facts.sql","d013e847264556034e4e5db39e62b39620a255d2d5a2707de2cee8b78de53bf3","V6__Governed_STAFF_Underwriting_Actions.sql","d0f052bc4754bcb5b01ca119419ed3f97832644346e61113dbe65477ad1f4db0");
    @Test void actualOwningModelArtifactPackagesOnlyTheExactNativeCandidateResources()throws Exception{
        try(var loader=owner()){
            for(var script:SCRIPTS.entrySet()){var path=STAFF+script.getKey();assertEquals(1,Collections.list(loader.getResources(path)).size());try(var input=loader.getResourceAsStream(path)){assertNotNull(input);var body=input.readAllBytes();assertTrue(body.length>0&&body.length<=262144);assertEquals(script.getValue(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));}}
            assertNull(loader.getResource("db/optional/insurance-quote/V5__Recorded_TERM_PC_Underwriting_Facts.sql"));assertNull(loader.getResource("db/optional/insurance-quote/V6__Governed_STAFF_Underwriting_Actions.sql"));
        }
    }
    @Test void ordinaryAndExistingV4OwnerDiscoveryNeverAdoptManualStaffVersions()throws Exception{
        try(var loader=owner()){
            assertEquals(List.of("V1__Create_Enums.sql","V2__Create_Tables.sql","V3__Create_Casts.sql"),names(loader,"classpath:db/migration"));assertEquals(List.of("V4__Governed_Quote_Persistence.sql"),names(loader,"classpath:db/optional/insurance-quote"));assertEquals(List.of("V1__Create_Enums.sql","V2__Create_Tables.sql","V3__Create_Casts.sql","V4__Governed_Quote_Persistence.sql"),names(loader,"classpath:db/migration","classpath:db/optional/insurance-quote"));assertEquals(SCRIPTS.keySet(),new HashSet<>(names(loader,"classpath:"+STAFF)));
        }
    }
    static URLClassLoader owner()throws Exception{var source=Submission.class.getProtectionDomain().getCodeSource().getLocation();var location=Path.of(source.toURI()).toRealPath();Path basedir=Path.of(System.getProperty("basedir",".")).toAbsolutePath();Path root=Files.isDirectory(basedir.resolve("core-insurance-underwriting-models"))?basedir:basedir.getParent();assertTrue(location.startsWith(root.resolve("core-insurance-underwriting-models/target").toRealPath()));return new URLClassLoader(new java.net.URL[]{source},ClassLoader.getPlatformClassLoader());}
    static List<String> names(ClassLoader loader,String... locations){var configuration=new ClassicConfiguration(loader);configuration.setLocationsAsStrings(locations);assertNull(configuration.getDataSource());return new Scanner<>(Object.class,configuration,configuration.getLocations()).getResources("V",".sql").stream().map(org.flywaydb.core.api.resource.LoadableResource::getFilename).sorted().toList();}
}
