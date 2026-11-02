package io.fireflysoftware.core.insurance.underwriting.core.quotes;

import java.net.JarURLConnection;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks only the selected fresh module's compilation or its byte-identical standard source JAR. */
final class FreshSourceSdkFixture {
    private FreshSourceSdkFixture() {}
    static void require(Class<?> type,Path selectedClasses)throws Exception {
        Path classes=selectedClasses.toRealPath();
        assertEquals("classes",classes.getFileName().toString());assertEquals("target",classes.getParent().getFileName().toString());
        Path module=classes.getParent().getParent();String artifact=module.getFileName().toString();assertTrue(artifact.endsWith("-sdk"));
        Path expectedJar=module.resolve("target/"+artifact+"-26.10.6.jar");
        Path origin=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        boolean directory=origin.equals(classes);
        if(!directory){assertFalse(Files.isSymbolicLink(expectedJar));assertEquals(expectedJar.toRealPath(),origin);assertTrue(Files.isRegularFile(expectedJar,LinkOption.NOFOLLOW_LINKS));assertTrue(Files.size(expectedJar)<=32*1024*1024);}
        var compiled=new TreeMap<String,byte[]>();long total=0;
        try(var files=Files.walk(classes)){
            for(Path path:files.sorted().toList()){
                assertFalse(Files.isSymbolicLink(path));if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;
                assertTrue(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS));assertTrue(Files.size(path)<=8*1024*1024);
                String name=classes.relativize(path).toString().replace('\\','/');byte[] bytes=Files.readAllBytes(path);total+=bytes.length;
                assertTrue(total<=32*1024*1024 && compiled.size()<8192);assertNull(compiled.put(name,bytes));
            }
        }
        assertTrue(compiled.keySet().stream().anyMatch(name->name.endsWith(".class")));
        byte[] canonical=Files.readAllBytes(module.resolve("src/main/resources/openapi.yml"));assertArrayEquals(canonical,compiled.get("openapi.yml"));
        if(!directory){
            try(var archive=new JarFile(expectedJar.toFile(),true)){
                var entries=archive.stream().toList();var names=new HashSet<String>();for(var entry:entries)assertTrue(names.add(entry.getName()));
                var metadata=Set.of("META-INF/MANIFEST.MF","META-INF/maven/io.fireflysoftware/"+artifact+"/pom.xml","META-INF/maven/io.fireflysoftware/"+artifact+"/pom.properties");
                var expected=new HashSet<>(compiled.keySet());expected.addAll(metadata);var observed=new HashSet<String>();for(var entry:entries)if(!entry.isDirectory())observed.add(entry.getName());assertEquals(expected,observed);
                for(var entry:compiled.entrySet()){
                    var packaged=archive.getJarEntry(entry.getKey());assertNotNull(packaged);assertEquals(entry.getValue().length,packaged.getSize());
                    try(var input=archive.getInputStream(packaged)){assertArrayEquals(entry.getValue(),input.readNBytes(entry.getValue().length+1));}
                }
                try(var input=archive.getInputStream(archive.getJarEntry("META-INF/maven/io.fireflysoftware/"+artifact+"/pom.xml"))){byte[] pom=Files.readAllBytes(module.resolve("pom.xml"));assertArrayEquals(pom,input.readNBytes(pom.length+1));}
                var properties=new Properties();try(var input=archive.getInputStream(archive.getJarEntry("META-INF/maven/io.fireflysoftware/"+artifact+"/pom.properties"))){assertTrue(archive.getJarEntry("META-INF/maven/io.fireflysoftware/"+artifact+"/pom.properties").getSize()<=8192);properties.load(input);}
                assertEquals(Set.of("groupId","artifactId","version"),properties.stringPropertyNames());assertEquals("io.fireflysoftware",properties.getProperty("groupId"));assertEquals(artifact,properties.getProperty("artifactId"));assertEquals("26.10.6",properties.getProperty("version"));
                assertTrue(archive.getJarEntry("META-INF/MANIFEST.MF").getSize()<=8192);var manifest=archive.getManifest();assertNotNull(manifest);assertTrue(manifest.getEntries().isEmpty());var attributes=manifest.getMainAttributes();
                assertEquals("1.0",attributes.getValue("Manifest-Version"));assertEquals("Maven JAR Plugin 3.5.0",attributes.getValue("Created-By"));assertNull(attributes.getValue("Class-Path"));assertNull(attributes.getValue("Main-Class"));
                var allowed=Set.of("Manifest-Version","Created-By","Java-Version","Build-Jdk-Spec","Build-Tool-Jdk-Spec","Implementation-Title","Implementation-Version","Implementation-Vendor","Specification-Title","Specification-Version","Specification-Vendor","Automatic-Module-Name");
                for(var key:attributes.keySet())assertTrue(allowed.contains(key.toString()));
            }
        }
        for(String name:compiled.keySet()){
            if(!name.endsWith(".class") || name.equals("module-info.class"))continue;
            var resources=Collections.list(type.getClassLoader().getResources(name));assertEquals(1,resources.size(),"One selected source SDK classpath provider required: "+name);var resource=resources.getFirst();
            if(directory){assertEquals("file",resource.getProtocol());assertEquals(classes.resolve(name).toRealPath(),Path.of(resource.toURI()).toRealPath());}
            else {assertEquals("jar",resource.getProtocol());assertEquals(expectedJar.toRealPath(),Path.of(((JarURLConnection)resource.openConnection()).getJarFileURL().toURI()).toRealPath());}
        }
    }
}
