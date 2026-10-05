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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * the root — absolute, backslashed, drive-prefixed, with an empty or relative segment, or reaching outside
 * through a symbolic link — are refused before any file is touched, and the failure of a comparison carries
 * the rendered difference, not both documents.
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
        Files.writeString(root.resolve("golden/x.dot"), "a\nb\n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = loader(root)) {
            GoldenStore.assertMatches("golden/x.dot", "a\nb\n", GoldenOptions.readOnly(loader), DIFF);
        }
    }

    @Test
    void aDifferingResourceFailsWithTheRenderedDifference(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("golden"));
        Files.writeString(root.resolve("golden/x.dot"), "a\nb\n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = loader(root)) {
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("golden/x.dot", "a\nc\n",
                            GoldenOptions.readOnly(loader), DIFF));
            assertEquals("golden/x.dot", error.context());
            assertEquals("content", error.property());
            assertEquals("matching golden", error.expected());
            assertEquals("<a\nb\n|a\nc\n>", error.actual());
            assertEquals("golden/x.dot: content expected <matching golden> but was <<a\nb\n|a\nc\n>>"
                    + REGENERATE, error.getMessage());
        }
    }

    @Test
    void aMissingResourceExplainsHowToTurnOnUpdate(@TempDir Path root) throws Exception {
        try (URLClassLoader loader = loader(root)) {
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("golden/absent.dot", "a\n",
                            GoldenOptions.readOnly(loader), DIFF));
            assertEquals("golden/absent.dot", error.context());
            assertEquals("content", error.property());
            assertEquals("an existing golden resource", error.expected());
            assertTrue(String.valueOf(error.actual()).contains("-D" + GoldenOptions.UPDATE_PROPERTY + "=true"),
                    String.valueOf(error.actual()));
            assertTrue(String.valueOf(error.actual()).contains("-D" + GoldenOptions.ROOT_PROPERTY + "="),
                    String.valueOf(error.actual()));
        }
    }

    /**
     * A class-path directory answers a stream of its own entry names, which the comparison would otherwise
     * take for the golden and report as a content difference.
     */
    @Test
    void aResourceThatNamesADirectoryIsNotAGolden(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("golden/sub"));
        Files.writeString(root.resolve("golden/a.dot"), "digraph {}\n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = loader(root)) {
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("golden", "digraph {}\n",
                            GoldenOptions.readOnly(loader), DIFF));
            assertEquals("golden", error.context());
            assertEquals("content", error.property());
            assertEquals("an existing golden resource", error.expected());
            assertEquals("a directory", error.actual());
            assertEquals("golden: content expected <an existing golden resource> but was <a directory>",
                    error.getMessage());
        }
    }

    // --- what counts as equal -----------------------------------------------------------------------

    @Test
    void lineTerminatorsAreNormalizedOnBothSides(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("x.dot"), "a\r\nb\r\n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = loader(root)) {
            GoldenOptions options = GoldenOptions.readOnly(loader);
            GoldenStore.assertMatches("x.dot", "a\nb\n", options, DIFF);
            GoldenStore.assertMatches("x.dot", "a\rb\r", options, DIFF);
        }
    }

    /** An editor may save a UTF-8 golden with a byte order mark; the mark is no part of the text it holds. */
    @Test
    void aByteOrderMarkIsNotPartOfTheGolden(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("x.dot"), "\uFEFFa\nb\n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = loader(root)) {
            GoldenOptions options = GoldenOptions.readOnly(loader);
            GoldenStore.assertMatches("x.dot", "a\nb\n", options, DIFF);
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("x.dot", "a\nc\n", options, DIFF));
            assertEquals("<a\nb\n|a\nc\n>", error.actual());
        }
    }

    @Test
    void blanksInsideALineAreCompared(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("x.dot"), "a \n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = loader(root)) {
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("x.dot", "a\n", GoldenOptions.readOnly(loader), DIFF));
            assertEquals("<a \n|a\n>", error.actual());
        }
    }

    @Test
    void unicodeSurvivesAWriteAndTheReadThatFollows(@TempDir Path root) throws Exception {
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenStore.assertMatches("golden/x.dot", "é ☃ \uD83D\uDE80\n",
                    GoldenOptions.updating(loader, root, written::add), DIFF);
            assertEquals(List.of(root.resolve("golden/x.dot")), written);
            assertEquals("é ☃ \uD83D\uDE80\n",
                    Files.readString(root.resolve("golden/x.dot"), StandardCharsets.UTF_8));
            GoldenStore.assertMatches("golden/x.dot", "é ☃ \uD83D\uDE80\n",
                    GoldenOptions.readOnly(loader), DIFF);
        }
    }

    @Test
    void aResourceThatIsNotUtf8NamesTheResourceAndKeepsTheCause(@TempDir Path root) throws Exception {
        Files.write(root.resolve("x.dot"), new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0x0A});
        try (URLClassLoader loader = loader(root)) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> GoldenStore.assertMatches("x.dot", "a\n", GoldenOptions.readOnly(loader), DIFF));
            assertTrue(error.getMessage().contains("<x.dot>"), error.getMessage());
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
            GoldenStore.assertMatches("deep/nested/x.dot", "a\n", options, DIFF);
            assertEquals(List.of(root.resolve("deep/nested/x.dot")), written);
            assertEquals("a\n", Files.readString(root.resolve("deep/nested/x.dot"), StandardCharsets.UTF_8));
            GoldenStore.assertMatches("deep/nested/x.dot", "a\n", options, DIFF);
            assertEquals(1, written.size());
            try (Stream<Path> leftovers = Files.list(root.resolve("deep/nested"))) {
                assertEquals(List.of(root.resolve("deep/nested/x.dot")), leftovers.toList());
            }
        }
    }

    @Test
    void updateRewritesADifferingSourceAndReportsEveryWrite(@TempDir Path root) throws Exception {
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenOptions options = GoldenOptions.updating(loader, root, written::add);
            GoldenStore.assertMatches("x.dot", "a\n", options, DIFF);
            GoldenStore.assertMatches("x.dot", "b\n", options, DIFF);
            assertEquals(List.of(root.resolve("x.dot"), root.resolve("x.dot")), written);
            assertEquals("b\n", Files.readString(root.resolve("x.dot"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void updateComparesTheSourceFileAndNotTheClassPath(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("x.dot"), "a\n", StandardCharsets.UTF_8);
        List<Path> written = new ArrayList<>();
        try (URLClassLoader stale = new URLClassLoader(new URL[0], null)) {
            GoldenStore.assertMatches("x.dot", "a\n", GoldenOptions.updating(stale, root, written::add), DIFF);
            assertEquals(List.of(), written);
            assertEquals("a\n", Files.readString(root.resolve("x.dot"), StandardCharsets.UTF_8));
        }
    }

    /**
     * Update mode is the one path meant to repair a golden, so a source file that cannot be decoded — saved as
     * Latin-1, say — is replaced like any other differing one. Only a read refuses it.
     */
    @Test
    void updateReplacesASourceThatIsNotUtf8(@TempDir Path root) throws Exception {
        Files.write(root.resolve("x.dot"), "caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenStore.assertMatches("x.dot", "a\n", GoldenOptions.updating(loader, root, written::add), DIFF);
            assertEquals(List.of(root.resolve("x.dot")), written);
            assertEquals("a\n", Files.readString(root.resolve("x.dot"), StandardCharsets.UTF_8));
            GoldenStore.assertMatches("x.dot", "a\n", GoldenOptions.readOnly(loader), DIFF);
        }
    }

    @Test
    void updateWritesUnderARootWithSpacesAndNeverBesideTheWorkingDirectory(@TempDir Path root)
            throws Exception {
        Path spaced = Files.createDirectories(root.resolve("a b"));
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenStore.assertMatches("golden/x.dot", "a\n",
                    GoldenOptions.updating(loader, spaced, written::add), DIFF);
            assertEquals(List.of(spaced.resolve("golden/x.dot")), written);
            assertEquals("a\n", Files.readString(spaced.resolve("golden/x.dot"), StandardCharsets.UTF_8));
            assertFalse(Files.exists(Path.of("golden/x.dot")));
        }
    }

    @Test
    void aResourceInsideAJarIsReadLikeAnyOther(@TempDir Path root) throws Exception {
        Path archive = root.resolve("goldens.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive))) {
            jar.putNextEntry(new JarEntry("golden/x.dot"));
            jar.write("a\nb\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {archive.toUri().toURL()}, null)) {
            GoldenOptions options = GoldenOptions.readOnly(loader);
            GoldenStore.assertMatches("golden/x.dot", "a\nb\n", options, DIFF);
            assertEquals("<a\nb\n|a\nc\n>", assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("golden/x.dot", "a\nc\n", options, DIFF)).actual());
        }
    }

    @Test
    void aSourceRootThatIsNotADirectoryIsRefused(@TempDir Path root) throws Exception {
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> GoldenStore.assertMatches("x.dot", "a\n",
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
                    () -> GoldenStore.assertMatches("golden/never-written.dot", "a\n", options, DIFF));
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
        Files.writeString(root.resolve("reference.dot"), "a\n", StandardCharsets.UTF_8);
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenStore.assertMatches("golden/x.dot", "a\n",
                    GoldenOptions.updating(loader, root, written::add), DIFF);
        }
        assertEquals(List.of(root.resolve("golden/x.dot")), written);
        assertEquals(Files.getPosixFilePermissions(root.resolve("reference.dot")),
                Files.getPosixFilePermissions(root.resolve("golden/x.dot")));
    }

    // --- names that must never reach the file system ------------------------------------------------

    @Test
    void aNameThatCouldLeaveTheRootIsRejected() {
        for (String resource : List.of("", "/golden/x.dot", "golden\\x.dot", "golden//x.dot",
                "golden/./x.dot", "golden/../../x.dot", "C:/golden/x.dot", "golden/", "..",
                "golden/" + (char) 0 + "x.dot")) {
            assertThrows(IllegalArgumentException.class,
                    () -> GoldenStore.assertMatches(resource, "a\n",
                            GoldenOptions.readOnly(GoldenStoreTest.class.getClassLoader()), DIFF),
                    "<" + resource + "> was accepted");
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> GoldenStore.assertMatches("golden/../../x.dot", "a\n",
                        GoldenOptions.readOnly(GoldenStoreTest.class.getClassLoader()), DIFF))
                .getMessage().contains("relative segment"));
    }

    @Test
    void aSymbolicLinkOutOfTheRootIsRejected(@TempDir Path root, @TempDir Path outside) throws Exception {
        Files.createSymbolicLink(root.resolve("away"), outside);
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            for (String resource : List.of("away/x.dot", "away/missing/nested/x.dot")) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> GoldenStore.assertMatches(resource, "a\n",
                                GoldenOptions.updating(loader, root, written::add), DIFF));
                assertTrue(error.getMessage().contains("outside the source root"), error.getMessage());
            }
        }
        assertEquals(List.of(), written);
        try (Stream<Path> entries = Files.list(outside)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    @Test
    void anEscapingParentIsRejectedEvenWhenItsTargetReturnsInsideAndMatches(@TempDir Path temp)
            throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path inside = Files.writeString(root.resolve("inside.dot"), "old\n");
        Files.createSymbolicLink(root.resolve("away"), outside);
        Path external = Files.createSymbolicLink(outside.resolve("x.dot"), inside);
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            assertThrows(IllegalArgumentException.class,
                    () -> GoldenStore.assertMatches("away/x.dot", "old\n",
                            GoldenOptions.updating(loader, root, written::add), DIFF));
        }
        assertTrue(Files.isSymbolicLink(external));
        assertEquals(inside, Files.readSymbolicLink(external));
        assertEquals("old\n", Files.readString(inside));
        assertEquals(List.of(), written);
        try (Stream<Path> entries = Files.list(outside)) {
            assertEquals(List.of(external), entries.toList());
        }
    }

    @Test
    void aSymbolicLinkThatStaysInsideAllowsNestedUpdates(@TempDir Path root) throws Exception {
        Path inside = Files.createDirectory(root.resolve("inside"));
        Path alias = Files.createSymbolicLink(root.resolve("alias"), inside);
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenOptions update = GoldenOptions.updating(loader, root, written::add);
            GoldenStore.assertMatches("alias/nested/x.dot", "new\n", update, DIFF);
            GoldenStore.assertMatches("alias/nested/x.dot", "new\n", update, DIFF);
            GoldenStore.assertMatches("alias/nested/x.dot", "new\n", GoldenOptions.readOnly(loader), DIFF);
        }
        assertTrue(Files.isSymbolicLink(alias));
        assertEquals("new\n", Files.readString(inside.resolve("nested/x.dot")));
        assertEquals(List.of(root.resolve("alias/nested/x.dot")), written);
        try (Stream<Path> entries = Files.list(inside.resolve("nested"))) {
            assertEquals(List.of(inside.resolve("nested/x.dot")), entries.toList());
        }
    }

    /**
     * The store accepts a link that stays inside the root, so an update has to write through a link that is
     * the golden itself: replacing it would leave the shared file stale and the golden a copy of its own.
     */
    @Test
    void aSymbolicLinkToAFileInsideTheRootIsWrittenThroughAndKept(@TempDir Path root) throws Exception {
        Path shared = Files.createDirectory(root.resolve("shared"));
        Path module = Files.createDirectory(root.resolve("module"));
        Path target = Files.writeString(shared.resolve("x.dot"), "old\n");
        Path link = Files.createSymbolicLink(module.resolve("x.dot"), Path.of("../shared/x.dot"));
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenStore.assertMatches("module/x.dot", "new\n",
                    GoldenOptions.updating(loader, root, written::add), DIFF);
            GoldenStore.assertMatches("module/x.dot", "new\n",
                    GoldenOptions.updating(loader, root, written::add), DIFF);
            GoldenStore.assertMatches("module/x.dot", "new\n", GoldenOptions.readOnly(loader), DIFF);
        }
        assertTrue(Files.isSymbolicLink(link), "the link must survive the update");
        assertEquals(Path.of("../shared/x.dot"), Files.readSymbolicLink(link));
        assertEquals("new\n", Files.readString(target));
        assertEquals(List.of(link), written, "the path that was named is the one reported, once");
        try (Stream<Path> entries = Files.list(shared)) {
            assertEquals(List.of(target), entries.toList());
        }
        try (Stream<Path> entries = Files.list(module)) {
            assertEquals(List.of(link), entries.toList());
        }
    }

    @Test
    void concurrentExplicitOptionsKeepTheirLoadersRootsAndReportersIsolated(@TempDir Path temp)
            throws Exception {
        Path firstRoot = Files.createDirectory(temp.resolve("first"));
        Path secondRoot = Files.createDirectory(temp.resolve("second"));
        Files.writeString(firstRoot.resolve("x.dot"), "first old\n");
        Files.writeString(secondRoot.resolve("x.dot"), "second old\n");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<List<Path>> first = workers.submit(
                    () -> updateAndReadOwnGolden(firstRoot, "first new\n", ready, start));
            Future<List<Path>> second = workers.submit(
                    () -> updateAndReadOwnGolden(secondRoot, "second new\n", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "both workers must reach the start gate");
            start.countDown();
            assertEquals(List.of(firstRoot.resolve("x.dot")), first.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(secondRoot.resolve("x.dot")), second.get(5, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "golden workers must terminate");
        }
        assertEquals("first new\n", Files.readString(firstRoot.resolve("x.dot")));
        assertEquals("second new\n", Files.readString(secondRoot.resolve("x.dot")));
        for (Path root : List.of(firstRoot, secondRoot)) {
            try (Stream<Path> entries = Files.list(root)) {
                assertEquals(List.of(root.resolve("x.dot")), entries.toList());
            }
        }
    }

    private static List<Path> updateAndReadOwnGolden(Path root, String content,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        List<Path> written = new ArrayList<>();
        try (URLClassLoader loader = loader(root)) {
            GoldenOptions update = GoldenOptions.updating(loader, root, written::add);
            GoldenOptions read = GoldenOptions.readOnly(loader);
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS), "start gate must open");
            GoldenStore.assertMatches("x.dot", content, update, DIFF);
            GoldenStore.assertMatches("x.dot", content, read, DIFF);
            assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("x.dot", "must not overwrite\n", read, DIFF));
            assertThrows(GraphAssertionError.class,
                    () -> GoldenStore.assertMatches("absent.dot", "must not create\n", read, DIFF));
        }
        return written;
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        GoldenOptions options = GoldenOptions.readOnly(GoldenStoreTest.class.getClassLoader());
        assertEquals("resource", assertThrows(NullPointerException.class,
                () -> GoldenStore.assertMatches(null, "a\n", options, DIFF)).getMessage());
        assertEquals("actual", assertThrows(NullPointerException.class,
                () -> GoldenStore.assertMatches("x.dot", null, options, DIFF)).getMessage());
        assertEquals("options", assertThrows(NullPointerException.class,
                () -> GoldenStore.assertMatches("x.dot", "a\n", null, DIFF)).getMessage());
        assertEquals("diff", assertThrows(NullPointerException.class,
                () -> GoldenStore.assertMatches("x.dot", "a\n", options, null)).getMessage());
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

    /**
     * Read mode never writes, so it never uses the root: a value that is not a path cannot fail it, and the
     * options it builds carry no root at all, however the property is set. Update mode needs the root and
     * refuses the same value when the options are built.
     */
    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void theRootIsResolvedOnlyWhenUpdateModeNeedsIt(@TempDir Path root) throws Exception {
        String previousUpdate = System.getProperty(GoldenOptions.UPDATE_PROPERTY);
        String previousRoot = System.getProperty(GoldenOptions.ROOT_PROPERTY);
        String checkedIn = Files.readString(Path.of(
                GoldenStoreTest.class.getClassLoader().getResource("golden/simple.dot").toURI()));
        String notAPath = "bad" + (char) 0 + "root";
        try {
            System.setProperty(GoldenOptions.ROOT_PROPERTY, notAPath);
            System.clearProperty(GoldenOptions.UPDATE_PROPERTY);
            assertEquals(Optional.empty(), GoldenOptions.defaults().sourceRoot());
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "false");
            GoldenOptions read = GoldenOptions.defaults();
            assertFalse(read.update());
            assertEquals(Optional.empty(), read.sourceRoot());
            GoldenStore.assertMatches("golden/simple.dot", checkedIn, read, DIFF);
            System.setProperty(GoldenOptions.ROOT_PROPERTY, root.toString());
            assertEquals(Optional.empty(), GoldenOptions.defaults().sourceRoot());
            System.setProperty(GoldenOptions.ROOT_PROPERTY, notAPath);
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "true");
            assertThrows(IllegalArgumentException.class, GoldenOptions::defaults);
        } finally {
            restore(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
            restore(GoldenOptions.ROOT_PROPERTY, previousRoot);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void aRelativeRootResolvesAgainstTheWorkingDirectory() {
        String previousUpdate = System.getProperty(GoldenOptions.UPDATE_PROPERTY);
        String previousRoot = System.getProperty(GoldenOptions.ROOT_PROPERTY);
        try {
            System.setProperty(GoldenOptions.UPDATE_PROPERTY, "true");
            System.setProperty(GoldenOptions.ROOT_PROPERTY, "src/./test/resources");
            assertEquals(Optional.of(Path.of("src/test/resources").toAbsolutePath().normalize()),
                    GoldenOptions.defaults().sourceRoot());
        } finally {
            restore(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
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
            GoldenStore.assertMatches("golden/x.dot", "a\n", options, DIFF);
            // The file already holds this text, so nothing is written and nothing is printed.
            GoldenStore.assertMatches("golden/x.dot", "a\n", options, DIFF);
            GoldenStore.assertMatches("golden/y.dot", "b\n", options, DIFF);
        } finally {
            System.setOut(previousOut);
            restore(GoldenOptions.UPDATE_PROPERTY, previousUpdate);
            restore(GoldenOptions.ROOT_PROPERTY, previousRoot);
        }
        assertEquals("UPDATED " + root.resolve("golden/x.dot") + System.lineSeparator()
                        + "UPDATED " + root.resolve("golden/y.dot") + System.lineSeparator(),
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
