package io.github.graphmaquina.maven;

import java.util.List;

import static io.github.graphmaquina.maven.MavenFixture.MAIN_EXPORTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the integration tests assert about the outputs a build leaves in a {@link MavenFixture} project: that a
 * build the generation refused retired nothing of the previous one, and that a build which succeeded published the
 * index the previous one did.
 */
final class BuildAssertions {

    private BuildAssertions() {
    }

    /**
     * Asserts that a build refused before the preparation, in a project that had not been built before, left no
     * pending inventory, no published index and no jar.
     */
    static void assertNothingWasRetiredOrPublished(MavenFixture fixture) {
        assertFalse(fixture.exists("target/graphmaquina/main/owned-outputs.pending.json"));
        assertFalse(fixture.exists(MAIN_EXPORTS));
        assertFalse(fixture.exists("target/fixture-1.0.0.jar"));
    }

    /**
     * Asserts that the main compilation of a previous build is exactly as that build left it: its inventory is
     * sealed, no preparation started another cycle, and its index still publishes {@code published}.
     */
    static void assertNothingWasRetired(MavenFixture fixture, List<String> published) {
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * Asserts that {@code result} is a build the generation refused, with each of {@code messages} in its output,
     * and that it retired nothing of the previous build, whose index was {@code published}.
     */
    static void assertRefusedBeforeAnythingIsRetired(MavenFixture fixture, List<String> published,
            MavenResult result, String... messages) {
        assertNotEquals(0, result.exitCode(), result.output());
        for (String message : messages) {
            assertTrue(result.output().contains(message), result.output());
        }
        assertNothingWasRetired(fixture, published);
    }

    /**
     * Asserts that {@code result} is a successful build that sealed the main inventory and published the index
     * {@code published} again.
     */
    static void assertBuildPublishesTheSameIndex(MavenFixture fixture, MavenResult result, List<String> published) {
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }
}
