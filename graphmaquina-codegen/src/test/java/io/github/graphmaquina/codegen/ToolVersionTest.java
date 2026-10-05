package io.github.graphmaquina.codegen;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies how the generator reads its own version: a resource the build never stamped is refused instead of
 * being written into every manifest this tool produces.
 */
class ToolVersionTest {

    @TempDir
    Path resources;

    /**
     * A tool run over its unfiltered resources, as an IDE may run it from the source tree, finds the placeholder
     * the build was meant to replace. That is no version at all, so it is refused like a missing one.
     */
    @Test
    void refusesAVersionTheBuildNeverExpanded() throws Exception {
        Path stamp = Files.createDirectories(resources.resolve("io/github/graphmaquina/codegen"))
                .resolve("version.properties");
        Files.writeString(stamp, "version=${version}\n", StandardCharsets.ISO_8859_1);
        URL classes = ToolVersion.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader isolated = new URLClassLoader(new URL[] {resources.toUri().toURL(), classes},
                ClassLoader.getPlatformClassLoader())) {
            Method current = Class.forName(ToolVersion.class.getName(), true, isolated).getMethod("current");

            InvocationTargetException failure =
                    assertThrows(InvocationTargetException.class, () -> current.invoke(null));

            IllegalStateException refused = assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(refused.getMessage().contains("${version}"), refused.getMessage());
        }
    }
}
