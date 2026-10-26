package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.CodegenException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * against those indexes, checking the descriptor it takes from one against the class files. A peer whose jar carries
 * no index, or an index that is stale about it, is therefore reported by the generator, naming the jar, while a set
 * that holds a machine whose index is stale reaches the processor, which checks the manifest that carries the indexes
 * as a whole. That manifest carries the index of every dependency on the classpath, so a stale one is refused even
 * when nothing in the consumer, {@code other.Note} alone, uses its machine.</p>
 */
class CrossModuleCharacterizationTest {

    private static final String RESOURCES = "/cross-module-characterization";
    private static final UnaryOperator<String> RENAMES_A_STATE =
            (String index) -> index.replace("\"DECLINED\"", "\"REFUSED\"");
    private static final UnaryOperator<String> NAMES_AN_UNDECLARED_STATE_ENUM = (String index) ->
            index.replaceAll("(\"stateType\"\\s*:\\s*)\"pay\\.PaymentStatus\"", "$1\"pay.Gone\"");

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

    /**
     * The jar that lost its index is reported by the generator as that missing index, at the machine that declares the
     * peer, in words that name the jar.
     */
    @Test
    void aPeerFromAJarWithoutIndexIsReportedAtThatJar() throws Exception {
        Path jar = publishedProducer("payments.jar", UnaryOperator.identity());
        PublishedJars.stripIndex(jar);

        assertRefused(DiagnosticCode.SG602.name(),
                "the peer machine pay.Payments declared by book.Bookings comes from " + jar
                + ", which carries no export index META-INF/graphmaquina/exports.json, so no index this compilation"
                + " reads lists it: package the index the producer's StateGraph build publishes with its classes, or"
                + " rebuild the producer through the complete lifecycle", tree("consumer"), List.of(jar));
    }

    /**
     * An index that renames a state of the machine is refused by the generator, before it types the pair from it, at
     * the machine that declares the peer, in words that name the jar.
     */
    @Test
    void aPeerFromAJarWithAStaleIndexIsReportedAtThatJar() throws Exception {
        Path jar = publishedProducer("payments.jar", RENAMES_A_STATE);

        assertRefused(DiagnosticCode.SG602.name(),
                "the peer machine pay.Payments declared by book.Bookings comes from " + jar
                + ", whose export index META-INF/graphmaquina/exports.json gives pay.PaymentStatus the states"
                + " [PENDING, CAPTURED, REFUSED], but that enum now declares [PENDING, CAPTURED, DECLINED]: package"
                + " the index the producer's StateGraph build publishes with its classes, or rebuild the producer"
                + " through the complete lifecycle", tree("consumer"), List.of(jar));
    }

    /**
     * A peer that two jars describe is refused by the generator, before it resolves the peer, in words that name both
     * jars in classpath order.
     */
    @Test
    void aPeerListedByTwoJarsIsRefusedNamingBothJars() throws Exception {
        Path first = publishedProducer("a.jar", UnaryOperator.identity());
        Path second = publishedProducer("b.jar", UnaryOperator.identity());

        assertRefused(DiagnosticCode.SG602.name(),
                "the machine pay.Payments is described by the export indexes of " + first + " and "
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

        assertRefused(DiagnosticCode.SG602.name(),
                "the machine pay.Payments is described by the export indexes of " + first + " and "
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

        assertEquals(List.of("[" + DiagnosticCode.SG602 + "] the annotation manifest "
                + prepared.generation().annotationManifest()
                + " gives pay.PaymentStatus the states [PENDING, CAPTURED, REFUSED], but that enum now declares"
                + " [PENDING, CAPTURED, DECLINED]"), result.errors(), result.describe());
    }

    /**
     * A jar whose index is stale is refused by the processor even when the consumer uses no machine and no symbol of
     * it: the index renames a state, or names a state enum the classes do not declare. The control is the same consumer
     * over the jar whose index is current.
     */
    @Test
    void aStaleIndexIsRefusedEvenWhenNothingUsesItsMachine() throws Exception {
        assertCompiles(tree("unused"), List.of(publishedProducer("current.jar", UnaryOperator.identity())));

        assertManifestRefused(publishedProducer("renamed.jar", RENAMES_A_STATE), "gives pay.PaymentStatus the states"
                + " [PENDING, CAPTURED, REFUSED], but that enum now declares [PENDING, CAPTURED, DECLINED]");
        assertManifestRefused(publishedProducer("undeclared.jar", NAMES_AN_UNDECLARED_STATE_ENUM), "describes the"
                + " machine pay.Payments over the state enum pay.Gone, which this compilation does not declare");
    }

    /** A peer literal that names no class is a different refusal from a peer that names a machine of a jar. */
    @Test
    void anUnresolvablePeerLiteralToday() throws Exception {
        Path jar = publishedProducer("payments.jar", UnaryOperator.identity());
        Map<String, String> sources = new LinkedHashMap<>(tree("consumer"));
        sources.putAll(tree("unresolvable"));

        assertRefused(DiagnosticCode.SG601.name(),
                "the peer machine nowhere.Missing declared by book.Bookings is unresolved", sources,
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

    /**
     * The consumer that uses nothing of {@code jar} is refused by the processor with the one problem it finds in the
     * annotation manifest, which carries the index of {@code jar}.
     */
    private static void assertManifestRefused(Path jar, String problem) {
        Compilation.Prepared prepared = Compilation.prepare(tree("unused"), List.of(jar));
        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertEquals(List.of("[" + DiagnosticCode.SG602 + "] the annotation manifest "
                + prepared.generation().annotationManifest() + " " + problem), result.errors(), result.describe());
    }

    /** The control of a refusal: the same sources compile over the one jar that carries no fault. */
    private static void assertCompiles(Map<String, String> sources, List<Path> classpath) {
        Compilation.Result result = Compilation.compilePrepared(Compilation.prepare(sources, classpath));

        assertTrue(result.success(), result.describe());
    }

    /** The sources of the fixture tree {@code name}, read from the test resources. */
    private static Map<String, String> tree(String name) {
        return FixtureSources.resource(RESOURCES + "/" + name);
    }
}
