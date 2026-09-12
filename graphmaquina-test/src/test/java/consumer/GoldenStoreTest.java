package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.internal.GoldenStore;
import io.github.graphmaquina.test.snapshot.GoldenOptions;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Contract of the golden store and of its configuration. Reading resolves the resource through the class
 * loader and never writes; update resolves the file through the source root and never reads the class path,
 * so a stale build output cannot make a matching source be rewritten. Names that could steer a write out of
 * the root — absolute, relative, backslashed, or through a symbolic link — are refused before any file is
 * touched, and the failure of a comparison carries the rendered difference, not both documents.
 */
class GoldenStoreTest {

    private static final BiFunction<String, String, String> DIFF =
            (expected, actual) -> "<" + expected + "|" + actual + ">";
    private static final String REGENERATE = "\nrun with -D" + GoldenOptions.UPDATE_PROPERTY + "=true -D"
            + GoldenOptions.ROOT_PROPERTY + "=<source root> to regenerate it";

    // --- the premise of this task: read, fail with a diff, and update --------------------------------

    @Test
    void aMatchingResourcePasses(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("golden"));
        Files.writeString(root.resolve("golden/x.mmd"), "a\nb\n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = loader(root)) {
            GoldenStore.assertMatches("golden/x.mmd", "a\nb\n", GoldenOptions.readOnly(loader), DIFF);
        }
    }

    @Test
    void aDifferingResourceFailsWithTheRenderedDifference(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("golden"));
        Files.writeString(root.resolve("golden/x.mmd"), "a\nb\n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = loader(root)) {
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("golden/x.mmd", "a\nc\n",
                            GoldenOptions.readOnly(loader), DIFF));
            assertEquals("golden/x.mmd", error.context());
            assertEquals("content", error.property());
            assertEquals("matching golden", error.expected());
            assertEquals("<a\nb\n|a\nc\n>" + REGENERATE, error.actual());
        }
    }

    @Test
    void aMissingResourceExplainsHowToTurnOnUpdate(@TempDir Path root) throws Exception {
        try (URLClassLoader loader = loader(root)) {
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("golden/absent.mmd", "a\n",
                            GoldenOptions.readOnly(loader), DIFF));
            assertEquals("golden/absent.mmd", error.context());
            assertEquals("content", error.property());
            assertEquals("an existing golden resource", error.expected());
            assertTrue(String.valueOf(error.actual()).contains("-D" + GoldenOptions.UPDATE_PROPERTY + "=true"),
                    String.valueOf(error.actual()));
            assertTrue(String.valueOf(error.actual()).contains("-D" + GoldenOptions.ROOT_PROPERTY + "="),
                    String.valueOf(error.actual()));
        }
    }

    // --- what counts as equal -----------------------------------------------------------------------

    @Test
    void lineTerminatorsAreNormalizedOnBothSides(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("x.mmd"), "a\r\nb\r\n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = loader(root)) {
            GoldenOptions options = GoldenOptions.readOnly(loader);
            GoldenStore.assertMatches("x.mmd", "a\nb\n", options, DIFF);
            GoldenStore.assertMatches("x.mmd", "a\rb\r", options, DIFF);
        }
    }

    @Test
    void blanksInsideALineAreCompared(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("x.mmd"), "a \n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = loader(root)) {
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("x.mmd", "a\n", GoldenOptions.readOnly(loader), DIFF));
            assertEquals("<a \n|a\n>" + REGENERATE, error.actual());
        }
    }

    @Test
    void unicodeSurvivesAWriteAndTheReadThatFollows(@TempDir Path root) throws Exception {
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenStore.assertMatches("golden/x.mmd", "é ☃ \uD83D\uDE80\n",
                    GoldenOptions.updating(loader, root, written::add), DIFF);
            assertEquals(List.of(root.resolve("golden/x.mmd")), written);
            assertEquals("é ☃ \uD83D\uDE80\n",
                    Files.readString(root.resolve("golden/x.mmd"), StandardCharsets.UTF_8));
            GoldenStore.assertMatches("golden/x.mmd", "é ☃ \uD83D\uDE80\n",
                    GoldenOptions.readOnly(loader), DIFF);
        }
    }

    @Test
    void aResourceThatIsNotUtf8NamesTheResourceAndKeepsTheCause(@TempDir Path root) throws Exception {
        Files.write(root.resolve("x.mmd"), new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0x0A});
        try (URLClassLoader loader = loader(root)) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> GoldenStore.assertMatches("x.mmd", "a\n", GoldenOptions.readOnly(loader), DIFF));
            assertTrue(error.getMessage().contains("<x.mmd>"), error.getMessage());
            assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
            assertInstanceOf(CharacterCodingException.class, error.getCause());
        }
    }

    // --- update mode --------------------------------------------------------------------------------

    @Test
    void updateCreatesTheMissingDirectoriesAndReportsOnce(@TempDir Path root) throws Exception {
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenOptions options = GoldenOptions.updating(loader, root, written::add);
            GoldenStore.assertMatches("deep/nested/x.mmd", "a\n", options, DIFF);
            assertEquals(List.of(root.resolve("deep/nested/x.mmd")), written);
            assertEquals("a\n", Files.readString(root.resolve("deep/nested/x.mmd"), StandardCharsets.UTF_8));
            GoldenStore.assertMatches("deep/nested/x.mmd", "a\n", options, DIFF);
            assertEquals(1, written.size());
            try (Stream<Path> leftovers = Files.list(root.resolve("deep/nested"))) {
                assertEquals(List.of(root.resolve("deep/nested/x.mmd")), leftovers.toList());
            }
        }
    }

    @Test
    void updateRewritesADifferingSourceAndReportsEveryWrite(@TempDir Path root) throws Exception {
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenOptions options = GoldenOptions.updating(loader, root, written::add);
            GoldenStore.assertMatches("x.mmd", "a\n", options, DIFF);
            GoldenStore.assertMatches("x.mmd", "b\n", options, DIFF);
            assertEquals(List.of(root.resolve("x.mmd"), root.resolve("x.mmd")), written);
            assertEquals("b\n", Files.readString(root.resolve("x.mmd"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void updateComparesTheSourceFileAndNotTheClassPath(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("x.mmd"), "a\n", StandardCharsets.UTF_8);
        List<Path> written = new ArrayList<>();
        try (URLClassLoader stale = new URLClassLoader(new URL[0], null)) {
            GoldenStore.assertMatches("x.mmd", "a\n", GoldenOptions.updating(stale, root, written::add), DIFF);
            assertEquals(List.of(), written);
            assertEquals("a\n", Files.readString(root.resolve("x.mmd"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void updateWritesUnderARootWithSpacesAndNeverBesideTheWorkingDirectory(@TempDir Path root)
            throws Exception {
        Path spaced = Files.createDirectories(root.resolve("a b"));
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenStore.assertMatches("golden/x.mmd", "a\n",
                    GoldenOptions.updating(loader, spaced, written::add), DIFF);
            assertEquals(List.of(spaced.resolve("golden/x.mmd")), written);
            assertEquals("a\n", Files.readString(spaced.resolve("golden/x.mmd"), StandardCharsets.UTF_8));
            assertFalse(Files.exists(Path.of("golden/x.mmd")));
        }
    }

    @Test
    void aResourceInsideAJarIsReadLikeAnyOther(@TempDir Path root) throws Exception {
        Path archive = root.resolve("goldens.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive))) {
            jar.putNextEntry(new JarEntry("golden/x.mmd"));
            jar.write("a\nb\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {archive.toUri().toURL()}, null)) {
            GoldenOptions options = GoldenOptions.readOnly(loader);
            GoldenStore.assertMatches("golden/x.mmd", "a\nb\n", options, DIFF);
            assertEquals("<a\nb\n|a\nc\n>" + REGENERATE, assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("golden/x.mmd", "a\nc\n", options, DIFF)).actual());
        }
    }

    @Test
    void aSourceRootThatIsNotADirectoryIsRefused(@TempDir Path root) throws Exception {
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> GoldenStore.assertMatches("x.mmd", "a\n",
                            GoldenOptions.updating(loader, root.resolve("absent"), written::add), DIFF));
            assertTrue(error.getMessage().contains("is not a directory"), error.getMessage());
        }
        assertEquals(List.of(), written);
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void readModeWritesNothingAtAll(@TempDir Path root) {
        String previousUpdate = System.getProperty(GoldenOptions.UPDATE_PROPERTY);
        String previousRoot = System.getProperty(GoldenOptions.ROOT_PROPERTY);
        try {
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "false");
            System.setProperty(GoldenOptions.ROOT_PROPERTY, root.toString());
            GoldenOptions options = GoldenOptions.defaults();
            assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("golden/never-written.mmd", "a\n", options, DIFF));
            try (Stream<Path> entries = Files.list(root)) {
                assertEquals(List.of(), entries.toList());
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        } finally {
            restore(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
            restore(GoldenOptions.ROOT_PROPERTY, previousRoot);
        }
    }

    /**
     * A golden is a source file the build rewrites, so it must end up with the permissions a newly written
     * file gets — not the owner-only ones a temporary file is created with and a move would preserve.
     */
    @Test
    void aWrittenGoldenCarriesThePermissionsOfANewlyWrittenFile(@TempDir Path root) throws Exception {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null,
                "the file system carries no POSIX permissions");
        Files.writeString(root.resolve("reference.mmd"), "a\n", StandardCharsets.UTF_8);
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenStore.assertMatches("golden/x.mmd", "a\n",
                    GoldenOptions.updating(loader, root, written::add), DIFF);
        }
        assertEquals(List.of(root.resolve("golden/x.mmd")), written);
        assertEquals(Files.getPosixFilePermissions(root.resolve("reference.mmd")),
                Files.getPosixFilePermissions(root.resolve("golden/x.mmd")));
    }

    // --- names that must never reach the file system ------------------------------------------------

    @Test
    void aNameThatCouldLeaveTheRootIsRejected() {
        for (String resource : List.of("", "/golden/x.mmd", "golden\\x.mmd", "golden//x.mmd",
                "golden/./x.mmd", "golden/../../x.mmd", "C:/golden/x.mmd", "golden/", "..",
                "golden/" + (char) 0 + "x.mmd")) {
            assertThrows(IllegalArgumentException.class,
                    () -> GoldenStore.assertMatches(resource, "a\n",
                            GoldenOptions.readOnly(GoldenStoreTest.class.getClassLoader()), DIFF),
                    "<" + resource + "> was accepted");
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> GoldenStore.assertMatches("golden/../../x.mmd", "a\n",
                        GoldenOptions.readOnly(GoldenStoreTest.class.getClassLoader()), DIFF))
                .getMessage().contains("relative segment"));
    }

    @Test
    void aSymbolicLinkOutOfTheRootIsRejected(@TempDir Path root, @TempDir Path outside) throws Exception {
        Files.createSymbolicLink(root.resolve("away"), outside);
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> GoldenStore.assertMatches("away/x.mmd", "a\n",
                            GoldenOptions.updating(loader, root, written::add), DIFF));
            assertTrue(error.getMessage().contains("outside the source root"), error.getMessage());
        }
        assertEquals(List.of(), written);
        try (Stream<Path> entries = Files.list(outside)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        GoldenOptions options = GoldenOptions.readOnly(GoldenStoreTest.class.getClassLoader());
        assertEquals("resource", assertThrows(NullPointerException.class,
                () -> GoldenStore.assertMatches(null, "a\n", options, DIFF)).getMessage());
        assertEquals("actual", assertThrows(NullPointerException.class,
                () -> GoldenStore.assertMatches("x.mmd", null, options, DIFF)).getMessage());
        assertEquals("options", assertThrows(NullPointerException.class,
                () -> GoldenStore.assertMatches("x.mmd", "a\n", null, DIFF)).getMessage());
        assertEquals("diff", assertThrows(NullPointerException.class,
                () -> GoldenStore.assertMatches("x.mmd", "a\n", options, null)).getMessage());
        assertEquals("loader", assertThrows(NullPointerException.class,
                () -> GoldenOptions.readOnly(null)).getMessage());
        assertEquals("sourceRoot", assertThrows(NullPointerException.class,
                () -> GoldenOptions.updating(GoldenStoreTest.class.getClassLoader(), null, path -> { }))
                .getMessage());
        assertEquals("reporter", assertThrows(NullPointerException.class,
                () -> GoldenOptions.updating(GoldenStoreTest.class.getClassLoader(), Path.of("/tmp"), null))
                .getMessage());
    }

    // --- the configuration the properties describe --------------------------------------------------

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void defaultsUseTheContextClassLoaderAndReadEveryCallAnew(@TempDir Path root) {
        String previousUpdate = System.getProperty(GoldenOptions.UPDATE_PROPERTY);
        String previousRoot = System.getProperty(GoldenOptions.ROOT_PROPERTY);
        try {
            System.clearProperty(GoldenOptions.UPDATE_PROPERTY);
            System.clearProperty(GoldenOptions.ROOT_PROPERTY);
            GoldenOptions read = GoldenOptions.defaults();
            assertSame(Thread.currentThread().getContextClassLoader(), read.loader());
            assertFalse(read.update());
            assertEquals(Optional.empty(), read.sourceRoot());
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "true");
            System.setProperty(GoldenOptions.ROOT_PROPERTY, root.toString());
            GoldenOptions update = GoldenOptions.defaults();
            assertTrue(update.update());
            assertEquals(Optional.of(root), update.sourceRoot());
            assertFalse(read.update(), "options already built must not follow the property");
        } finally {
            restore(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
            restore(GoldenOptions.ROOT_PROPERTY, previousRoot);
        }
    }

    @Test
    @ResourceLock("contextClassLoader")
    void defaultsFallBackToTheLoaderOfThisModule() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);
            assertSame(GoldenOptions.class.getClassLoader(), GoldenOptions.defaults().loader());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void theUpdatePropertyAcceptsOnlyTrueAndFalse(@TempDir Path root) {
        String previousUpdate = System.getProperty(GoldenOptions.UPDATE_PROPERTY);
        String previousRoot = System.getProperty(GoldenOptions.ROOT_PROPERTY);
        try {
            System.setProperty(GoldenOptions.ROOT_PROPERTY, root.toString());
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "false");
            assertFalse(GoldenOptions.defaults().update());
            for (String value : List.of("TRUE", "True", "yes", "1", "")) {
                System.setProperty(GoldenOptions.UPDATE_PROPERTY, value);
                IllegalArgumentException error =
                        assertThrows(IllegalArgumentException.class, GoldenOptions::defaults);
                assertTrue(error.getMessage().contains(GoldenOptions.UPDATE_PROPERTY), error.getMessage());
            }
        } finally {
            restore(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
            restore(GoldenOptions.ROOT_PROPERTY, previousRoot);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void updateModeWithoutARootIsRefused() {
        String previousUpdate = System.getProperty(GoldenOptions.UPDATE_PROPERTY);
        String previousRoot = System.getProperty(GoldenOptions.ROOT_PROPERTY);
        try {
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "true");
            System.clearProperty(GoldenOptions.ROOT_PROPERTY);
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, GoldenOptions::defaults);
            assertTrue(error.getMessage().contains(GoldenOptions.ROOT_PROPERTY), error.getMessage());
        } finally {
            restore(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
            restore(GoldenOptions.ROOT_PROPERTY, previousRoot);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void aRelativeRootResolvesAgainstTheWorkingDirectory() {
        String previousRoot = System.getProperty(GoldenOptions.ROOT_PROPERTY);
        try {
            System.setProperty(GoldenOptions.ROOT_PROPERTY, "src/./test/resources");
            assertEquals(Optional.of(Path.of("src/test/resources").toAbsolutePath().normalize()),
                    GoldenOptions.defaults().sourceRoot());
        } finally {
            restore(GoldenOptions.ROOT_PROPERTY, previousRoot);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    @ResourceLock(Resources.SYSTEM_OUT)
    void theDefaultReporterPrintsTheAbsolutePathOfEveryWrite(@TempDir Path root) {
        String previousUpdate = System.getProperty(GoldenOptions.UPDATE_PROPERTY);
        String previousRoot = System.getProperty(GoldenOptions.ROOT_PROPERTY);
        PrintStream previousOut = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "true");
            System.setProperty(GoldenOptions.ROOT_PROPERTY, root.toString());
            GoldenOptions options = GoldenOptions.defaults();
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            GoldenStore.assertMatches("golden/x.mmd", "a\n", options, DIFF);
            GoldenStore.assertMatches("golden/x.mmd", "a\n", options, DIFF);
        } finally {
            System.setOut(previousOut);
            restore(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
            restore(GoldenOptions.ROOT_PROPERTY, previousRoot);
        }
        assertEquals("UPDATED " + root.resolve("golden/x.mmd") + System.lineSeparator(),
                buffer.toString(StandardCharsets.UTF_8));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void explicitOptionsReadNoProperty(@TempDir Path root, @TempDir Path other) {
        String previousUpdate = System.getProperty(GoldenOptions.UPDATE_PROPERTY);
        String previousRoot = System.getProperty(GoldenOptions.ROOT_PROPERTY);
        try {
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "true");
            System.setProperty(GoldenOptions.ROOT_PROPERTY, root.toString());
            ClassLoader loader = GoldenStoreTest.class.getClassLoader();
            GoldenOptions read = GoldenOptions.readOnly(loader);
            assertFalse(read.update());
            assertEquals(Optional.empty(), read.sourceRoot());
            assertSame(loader, read.loader());
            GoldenOptions update = GoldenOptions.updating(loader, other, path -> { });
            assertTrue(update.update());
            assertEquals(Optional.of(other), update.sourceRoot());
        } finally {
            restore(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
            restore(GoldenOptions.ROOT_PROPERTY, previousRoot);
        }
    }

    private static URLClassLoader loader(Path root) throws Exception {
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
