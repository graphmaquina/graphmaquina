package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.CodegenException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
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
 * declares it a peer with {@code @GraphPeers} and binds it as the sub-machine of its state {@code DEPOSIT}, in the set
 * {@code book.Shop}, and {@code cons.Shelf} holds it in a set. The generator reads and checks the indexes the
 * dependencies published before it resolves a peer or emits anything, and resolves a peer it does not generate
 * against those indexes. A peer whose jar carries no index is therefore refused as a type that is no machine, and one
 * whose index is stale reaches the processor, which checks the manifest that carries the indexes as a whole.</p>
 */
class CrossModuleCharacterizationTest {

    private static final String RESOURCES = "/cross-module-characterization";
    private static final UnaryOperator<String> RENAMES_A_STATE =
            (String index) -> index.replace("\"DECLINED\"", "\"REFUSED\"");
    private static final String PAYMENTS_ARE_NO_MACHINE = "the peer machine pay.Payments declared by book.Bookings"
            + " is not a @GenerateGraphApi machine of this compilation or of a dependency export index";

    @TempDir
    Path directory;

    /**
     * A peer whose producer jar carries the index its build published is typed from that index: the booking binds the
     * payments of the jar as the sub-machine of {@code DEPOSIT}, and capturing the deposit confirms the booking.
     */
    @Test
    void aConsumerBindsAPeerFromAJar() throws Exception {
        Path jar = publishedProducer("payments.jar", UnaryOperator.identity());

        assertEquals("CONFIRMED CAPTURED", compileAndRun(tree("consumer"), jar));
    }

    /** The jar that lost its index is refused as a peer that is no machine, in words that say nothing of the index. */
    @Test
    void aPeerFromAJarWithoutIndexIsRefusedToday() throws Exception {
        Path jar = publishedProducer("payments.jar", UnaryOperator.identity());
        PublishedJars.stripIndex(jar);

        assertRefused("SG608", PAYMENTS_ARE_NO_MACHINE, tree("consumer"), List.of(jar));
    }

    /**
     * An index that renames a state of the machine passes the generator, which types the pair from it, and is refused
     * by the processor, and javac reports nothing before it about the vocabulary the generator emitted.
     */
    @Test
    void aPeerFromAJarWithAStaleIndexIsRefusedToday() throws Exception {
        Path jar = publishedProducer("payments.jar", RENAMES_A_STATE);

        Compilation.Prepared prepared = Compilation.prepare(tree("consumer"), List.of(jar));
        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertEquals(List.of("[SG602] the annotation manifest " + prepared.generation().annotationManifest()
                + " gives pay.PaymentStatus the states [PENDING, CAPTURED, REFUSED], but that enum now declares"
                + " [PENDING, CAPTURED, DECLINED]"), result.errors(), result.describe());
    }

    /**
     * A peer that two jars describe is refused by the generator, before it resolves the peer, in words that name both
     * jars in classpath order.
     */
    @Test
    void aPeerListedByTwoJarsIsRefusedNamingBothJars() throws Exception {
        Path first = publishedProducer("a.jar", UnaryOperator.identity());
        Path second = publishedProducer("b.jar", UnaryOperator.identity());

        assertRefused("SG602", "the machine pay.Payments is described by the export indexes of " + first + " and "
                + second + ": keep one artifact of its producer on the classpath", tree("consumer"),
                List.of(first, second));
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

    /**
     * Compiles {@code sources} over {@code jar} through the whole lifecycle and returns what
     * {@code book.Scenario.run()} returns, with the classes it compiled and the jar's loaded together.
     */
    private static Object compileAndRun(Map<String, String> sources, Path jar) throws Exception {
        Compilation.Prepared prepared = Compilation.prepare(sources, List.of(jar));
        Compilation.Result result = Compilation.compilePrepared(prepared);
        assertTrue(result.success(), result.describe());
        URL[] classes = {prepared.request().classesOutput().toUri().toURL(), jar.toUri().toURL()};
        try (URLClassLoader loader =
                new URLClassLoader(classes, CrossModuleCharacterizationTest.class.getClassLoader())) {
            return loader.loadClass("book.Scenario").getMethod("run").invoke(null);
        }
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
