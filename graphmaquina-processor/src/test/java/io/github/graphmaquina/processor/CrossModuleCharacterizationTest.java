package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.CodegenException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a consumer that takes machines from a dependency jar is treated today, pinned as it is so that a change to it
 * is a decision a test shows.
 *
 * <p>The producer is the machine {@code pay.Payments}. A consumer reaches it in two ways: {@code book.Bookings}
 * declares it a peer with {@code @GraphPeers}, and {@code cons.Shelf} holds it in a set. The generator resolves a
 * peer among the machines the compilation itself generates and reads the indexes the dependencies published only
 * after it has emitted, so a peer from a jar is refused whatever its index says, or lacks. A set takes its machines
 * from those indexes, and the processor checks the manifest that carries them as a whole.</p>
 */
class CrossModuleCharacterizationTest {

    private static final String RESOURCES = "/cross-module-characterization";
    private static final UnaryOperator<String> RENAMES_A_STATE =
            (String index) -> index.replace("\"DECLINED\"", "\"REFUSED\"");
    private static final String PAYMENTS_ARE_NOT_LOCAL = "the peer machine pay.Payments declared by book.Bookings"
            + " is not a @GenerateGraphApi machine of this compilation";

    @TempDir
    Path directory;

    /** A peer whose producer jar carries the index its build published is refused as not generated here. */
    @Test
    void aPeerFromAJarIsRefusedToday() throws Exception {
        Path jar = publishedProducer("payments.jar", UnaryOperator.identity());

        assertRefused("SG608", PAYMENTS_ARE_NOT_LOCAL, tree("consumer"), List.of(jar));
    }

    /** The jar that lost its index is refused in the same words, which say nothing of the missing index. */
    @Test
    void aPeerFromAJarWithoutIndexIsRefusedToday() throws Exception {
        Path jar = publishedProducer("payments.jar", UnaryOperator.identity());
        PublishedJars.stripIndex(jar);

        assertRefused("SG608", PAYMENTS_ARE_NOT_LOCAL, tree("consumer"), List.of(jar));
    }

    /** An index that renames a state of the machine is refused in the same words, before any index is read. */
    @Test
    void aPeerFromAJarWithAStaleIndexIsRefusedToday() throws Exception {
        Path jar = publishedProducer("payments.jar", RENAMES_A_STATE);

        assertRefused("SG608", PAYMENTS_ARE_NOT_LOCAL, tree("consumer"), List.of(jar));
    }

    /** Two jars that describe the machine are refused in the same words, which do not name either of them. */
    @Test
    void aPeerListedByTwoJarsIsRefusedToday() throws Exception {
        Path first = publishedProducer("a.jar", UnaryOperator.identity());
        Path second = publishedProducer("b.jar", UnaryOperator.identity());

        assertRefused("SG608", PAYMENTS_ARE_NOT_LOCAL, tree("consumer"), List.of(first, second));
    }

    /**
     * A set that holds a machine two jars describe is refused by the generator, before it publishes anything, in words
     * that name both jars in classpath order. The control is the same sources over the first jar alone.
     */
    @Test
    void aSetHoldingAMachineListedByTwoJarsIsRefusedNamingBothJars() throws Exception {
        Path first = publishedProducer("a.jar", UnaryOperator.identity());
        Path second = publishedProducer("b.jar", UnaryOperator.identity());
        assertCompiles(tree("shelf"), List.of(first));

        assertRefused("SG602", "the machine pay.Payments is described by the export indexes of " + first + " and "
                + second + ": keep one artifact of its producer on the classpath", tree("shelf"),
                List.of(first, second));
    }

    /**
     * A set that holds a machine whose index lists a state the enum no longer declares is refused by the
     * processor, and javac reports nothing before it about the vocabulary the generator emitted.
     */
    @Test
    void aSetHoldingAMachineWhoseIndexIsStaleIsRefusedToday() throws Exception {
        Path current = publishedProducer("current.jar", UnaryOperator.identity());
        Path stale = publishedProducer("stale.jar", RENAMES_A_STATE);
        assertCompiles(tree("shelf"), List.of(current));

        Compilation.Prepared prepared = Compilation.prepare(tree("shelf"), List.of(stale));
        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertEquals(List.of("[SG602] the annotation manifest " + prepared.generation().annotationManifest()
                + " gives pay.PaymentStatus the states [PENDING, CAPTURED, REFUSED], but that enum now declares"
                + " [PENDING, CAPTURED, DECLINED]"), result.errors(), result.describe());
    }

    /** A peer literal that names no class is a different refusal from a peer that names a machine of a jar. */
    @Test
    void anUnresolvablePeerLiteralToday() throws Exception {
        Path jar = publishedProducer("payments.jar", UnaryOperator.identity());
        Map<String, String> sources = new LinkedHashMap<>(tree("consumer"));
        sources.putAll(tree("unresolvable"));

        assertRefused("SG601", "the peer machine nowhere.Missing declared by book.Bookings is unresolved", sources,
                List.of(jar));
    }

    /** The producer compiled on its own into {@code name}, packaged with the index {@code index} rewrites. */
    private Path publishedProducer(String name, UnaryOperator<String> index) throws Exception {
        return PublishedJars.publish(tree("producer"), directory.resolve(name), index);
    }

    /** The generator stops on {@code sources} over {@code classpath} with {@code code} and {@code message}. */
    private static void assertRefused(String code, String message, Map<String, String> sources,
            List<Path> classpath) {
        CodegenException failure = assertThrows(CodegenException.class,
                () -> Compilation.prepare(sources, classpath));

        assertEquals(code, failure.code());
        assertEquals(message, failure.getMessage());
    }

    /** The control of a refusal: the same sources compile over the one jar that carries no fault. */
    private static void assertCompiles(Map<String, String> sources, List<Path> classpath) {
        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(sources, classpath));

        assertTrue(result.success(), result.describe());
    }

    /** The sources of the fixture tree {@code name}, read from the test resources. */
    private static Map<String, String> tree(String name) throws URISyntaxException {
        URL root = Objects.requireNonNull(CrossModuleCharacterizationTest.class.getResource(RESOURCES),
                RESOURCES + " is not on the test classpath");
        return FixtureSources.read(Path.of(root.toURI()).resolve(name));
    }
}
