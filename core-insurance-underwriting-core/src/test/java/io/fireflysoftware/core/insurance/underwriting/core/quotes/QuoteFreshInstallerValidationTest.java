package io.fireflysoftware.core.insurance.underwriting.core.quotes;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.List;
import org.flywaydb.core.api.*;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.api.pattern.ValidatePattern;
import org.flywaydb.core.extensibility.AppliedMigration;
import org.flywaydb.core.internal.database.base.Database;
import org.flywaydb.core.internal.info.*;
import org.flywaydb.core.internal.resolver.*;
import org.flywaydb.core.internal.scanner.Scanner;
import org.flywaydb.core.internal.schemahistory.SchemaHistory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Flyway validation over controlled history metadata, never a database/history repair. */
class QuoteFreshInstallerValidationTest{
    @Test void appliedOriginalV4CannotBeSilentlyValidatedAsCorrectedFreshInstaller()throws Exception{
        Path resources=Path.of("../core-insurance-underwriting-models/src/main/resources").toAbsolutePath();
        try(var loader=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
            var configuration=new ClassicConfiguration(loader);configuration.setLocationsAsStrings("classpath:db/optional/insurance-quote");assertNull(configuration.getDataSource());assertTrue(configuration.isValidateOnMigrate());
            var found=new Scanner<>(Object.class,configuration,configuration.getLocations()).getResources("V",".sql");assertEquals(1,found.size());var resource=found.iterator().next();assertEquals("V4__Governed_Quote_Persistence.sql",resource.getFilename());int fresh=ChecksumCalculator.calculate(resource);assertEquals(-1773760080,fresh);assertNotEquals(2022705259,fresh);
            var resolved=new ResolvedMigrationImpl(MigrationVersion.fromVersion("4"),"Governed Quote Persistence",resource.getFilename(),fresh,null,CoreMigrationType.SQL,resources.resolve("db/optional/insurance-quote/"+resource.getFilename()).toString(),null);var resolver=mock(CompositeMigrationResolver.class);when(resolver.resolveMigrations(configuration)).thenReturn(List.of(resolved));var original=applied("4",CoreMigrationType.SQL,2022705259,1);var baseline=applied("0",CoreMigrationType.BASELINE,0,0);var history=mock(SchemaHistory.class);when(history.allAppliedMigrations()).thenReturn(List.of(baseline,original));
            var service=new MigrationInfoServiceImpl(resolver,history,mock(Database.class),configuration,MigrationVersion.LATEST,false,new ValidatePattern[0],null);service.refresh();var info=(MigrationInfoImpl)java.util.Arrays.stream(service.all()).filter(value->MigrationVersion.fromVersion("4").equals(value.getVersion())).findFirst().orElseThrow();var error=info.validate();assertNotNull(error);assertEquals(CoreErrorCode.CHECKSUM_MISMATCH,error.errorCode);assertEquals(2022705259,original.getChecksum());assertEquals(fresh,resolved.getChecksum());var correctFreshHistory=applied("4",CoreMigrationType.SQL,fresh,1);when(history.allAppliedMigrations()).thenReturn(List.of(baseline,correctFreshHistory));service.refresh();var freshInfo=(MigrationInfoImpl)java.util.Arrays.stream(service.all()).filter(value->MigrationVersion.fromVersion("4").equals(value.getVersion())).findFirst().orElseThrow();assertNull(freshInfo.validate());assertEquals(2022705259,original.getChecksum());verify(history,times(2)).allAppliedMigrations();verifyNoMoreInteractions(history);
        }
    }
    AppliedMigration applied(String version,CoreMigrationType type,int checksum,int rank){var value=mock(AppliedMigration.class);when(value.getVersion()).thenReturn(MigrationVersion.fromVersion(version));when(value.getType()).thenReturn(type);when(value.getChecksum()).thenReturn(checksum);when(value.getInstalledRank()).thenReturn(rank);when(value.isVersioned()).thenReturn(true);when(value.isSuccess()).thenReturn(true);when(value.getState(any(),anyBoolean(),any())).thenReturn(MigrationState.SUCCESS);when(value.getDescription()).thenReturn("Governed Quote Persistence");when(value.getScript()).thenReturn("V4__Governed_Quote_Persistence.sql");return value;}
}
