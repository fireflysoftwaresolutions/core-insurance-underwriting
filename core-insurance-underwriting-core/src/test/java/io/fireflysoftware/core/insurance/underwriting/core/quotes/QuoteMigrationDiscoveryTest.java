/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.core.quotes;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.ResourceNameParser;
import org.flywaydb.core.internal.scanner.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import static org.junit.jupiter.api.Assertions.*;

/** Real resource resolution only: no DataSource, database or Flyway execution API. */
class QuoteMigrationDiscoveryTest {
    private static final String ORDINARY = "classpath:db/migration";
    private static final String OPTIONAL = "classpath:db/optional/insurance-quote";
    private static final String INSTALLER = "V4__Governed_Quote_Persistence.sql";
    private static final String INSTALLER_SHA = "2b84d4c90b3f3074c8d3c5fb253f999d067f47cfb3f04d4faf4366687881cd5e";
    private static final List<String> ORIGINAL = List.of("V1__Create_Enums.sql", "V2__Create_Tables.sql", "V3__Create_Casts.sql");
    @TempDir Path temporary;

    @Test void ordinaryLocationsResolveOnlyOriginalThreeMigrations() throws Exception {
        Properties properties = applicationProperties();
        assertEquals("true", properties.getProperty("spring.flyway.enabled"));
        assertEquals(ORDINARY, properties.getProperty("spring.flyway.locations"));
        try (var loader = loader(modelResources().toUri().toURL())) {
            assertEquals(ORIGINAL, names(scan(loader, properties.getProperty("spring.flyway.locations"))));
        }
    }

    @Test void explicitOwnerLocationsResolveOriginalV4Identity() throws Exception {
        try (var loader = loader(modelResources().toUri().toURL())) {
            assertInstaller(loader);
        }
    }

    @Test void packagedModelResourcesKeepDefaultAndOwnerModesDistinct() throws Exception {
        Path jar = temporary.resolve("model-sql-resources.jar");
        var directories = new TreeSet<String>();
        try (var output = new JarOutputStream(Files.newOutputStream(jar));
             var resources = Files.walk(modelResources())) {
            for (Path file : resources.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
                String entry = modelResources().relativize(file).toString().replace('\\', '/');
                for (int index = entry.indexOf('/'); index >= 0; index = entry.indexOf('/', index + 1)) {
                    String directory = entry.substring(0, index + 1);
                    if (directories.add(directory)) {
                        output.putNextEntry(new JarEntry(directory)); output.closeEntry();
                    }
                }
                output.putNextEntry(new JarEntry(entry)); output.write(Files.readAllBytes(file)); output.closeEntry();
            }
        }
        try (var loader = loader(jar.toUri().toURL())) {
            assertEquals(ORIGINAL, names(scan(loader, applicationProperties().getProperty("spring.flyway.locations"))));
            assertInstaller(loader);
        }
    }

    private static void assertInstaller(ClassLoader loader) throws Exception {
        var optional = scan(loader, OPTIONAL);
        assertEquals(List.of(INSTALLER), names(optional));
        var all = scan(loader, ORDINARY, OPTIONAL);
        assertEquals(List.of("V1__Create_Enums.sql", "V2__Create_Tables.sql", "V3__Create_Casts.sql", INSTALLER), names(all));
        var configuration = new ClassicConfiguration(loader);
        assertNull(configuration.getDataSource());
        var parsed = new ResourceNameParser(configuration).parse(optional.getFirst().getFilename());
        assertEquals(org.flywaydb.core.api.MigrationVersion.fromVersion("4"), parsed.getVersion());
        assertEquals(2022705259, ChecksumCalculator.calculate(optional.getFirst()));
        try (var bytes = loader.getResourceAsStream("db/optional/insurance-quote/" + INSTALLER)) {
            assertNotNull(bytes);
            assertEquals(INSTALLER_SHA, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));
        }
    }

    private static List<LoadableResource> scan(ClassLoader loader, String... locations) {
        var configuration = new ClassicConfiguration(loader);
        configuration.setLocationsAsStrings(locations);
        assertNull(configuration.getDataSource());
        return new Scanner<>(Object.class, configuration, configuration.getLocations())
                .getResources("V", ".sql").stream().sorted(Comparator.comparing(LoadableResource::getFilename)).toList();
    }
    private static List<String> names(List<LoadableResource> resources) {
        return resources.stream().map(LoadableResource::getFilename).toList();
    }
    private static URLClassLoader loader(URL source) {
        return new URLClassLoader(new URL[]{source}, ClassLoader.getPlatformClassLoader());
    }
    private static Properties applicationProperties() {
        var source = new YamlPropertiesFactoryBean();
        source.setResources(new FileSystemResource(sourceRoot().resolve("core-insurance-underwriting-web/src/main/resources/application.yaml")));
        return java.util.Objects.requireNonNull(source.getObject());
    }
    private static Path modelResources() {
        return sourceRoot().resolve("core-insurance-underwriting-models/src/main/resources");
    }
    private static Path sourceRoot() {
        String explicit = System.getProperty("quote.source.root");
        if (explicit != null) return Path.of(explicit);
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        return Files.isDirectory(basedir.resolve("core-insurance-underwriting-models")) ? basedir : basedir.getParent();
    }
}
