package io.github.graphmaquina.gradle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The plugin defaults the generator version to its own, which it reads from a resource the build filters
 * rather than from the jar manifest: TestKit loads the plugin from a classes directory, where the manifest
 * is absent but the resource is present. This pins the packaged version to the one the build resolved.
 */
class StateGraphPluginTest {

    @Test
    void reportsTheVersionTheBuildResolved() {
        assertEquals(System.getProperty("graphmaquina.version"), StateGraphPlugin.pluginVersion());
    }
}
