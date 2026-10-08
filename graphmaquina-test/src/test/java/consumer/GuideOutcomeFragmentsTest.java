package consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles the Java fragments of the guide's "Handle transition outcomes" section, its failed-call handler
 * included, plus its async completion callback and observer examples, against the published core jar. A second
 * test compiles every fragment of the guide's "Recover by failure kind and exception type" section the same way,
 * as a compilation unit of its own. This test runs no generator, so that section's annotated operation compiles
 * against stand-ins shaped like the {@code Transition} and {@code Recovery} annotations the generator emits. The
 * fragments are cut out of {@code docs/guide.md} verbatim and wrapped only by stand-in types and methods, so a
 * renamed accessor in the guide breaks this test the way it would break a reader. A heading or block that
 * cannot be found fails the test instead of compiling nothing. The guide, the repository and the release come
 * from the {@code readmePath}, {@code artifactRepository} and {@code artifactVersion} system properties that
 * {@code publishedArtifactTest} sets.
 */
class GuideOutcomeFragmentsTest {

    private static final String SECTION_HEADING = "## Handle transition outcomes";
    private static final String RECOVERY_HEADING = "### Recover by failure kind and exception type";
    private static final String FUTURE_PREFIX =
            "CompletableFuture<Outcome<Ticket, TicketState, TicketTransition>> pending =";
    private static final String OBSERVER_PREFIX =
            "TicketMachines machines = TicketMachinesRuntime.builder(new TicketActions())";

    private static final String HEADER = """
            import io.github.graphmaquina.api.CallFailure;
            import io.github.graphmaquina.api.MachineCall;
            import io.github.graphmaquina.api.MachineObserver;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.OutcomeCode;
            import io.github.graphmaquina.api.OutcomeStatus;
            import io.github.graphmaquina.error.CallFailedException;
            import java.time.Duration;
            import java.util.Optional;
            import java.util.concurrent.CompletableFuture;

            /** Every outcome fragment of docs/guide.md, extracted verbatim, compiled against core. */
            public final class GuideFragments {
                private GuideFragments() { }

                enum TicketState { OPEN, REVIEW, DONE, FAILED }
                enum TicketTransition { SUBMIT, APPROVE, DECLINE, RETOUCH, REJECT, AUTO_APPROVE }
                static final class Ticket { }
                static final class TicketActions { }
                interface TicketMachines { }
                static final class TicketMachinesRuntime {
                    static Builder builder(TicketActions actions) { return new Builder(); }
                    static final class Builder {
                        Builder observer(MachineObserver observer) { return this; }
                        TicketMachines build() { return new TicketMachines() { }; }
                    }
                }

                // --- "Handle transition outcomes", block 1 (describe)
            """;

    private static final String STUBS = """
                interface StubTickets {
                    Outcome<Ticket, TicketState, TicketTransition> approve(Ticket ticket);
                    <R> CompletableFuture<R> async(java.util.function.Supplier<R> call);
                }
            """;

    private static final String RECOVERY_HEADER = """
            import io.github.graphmaquina.annotation.Timeout;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.FailureKind;
            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.MachineKey;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.StateMachine;
            import io.github.graphmaquina.api.TransitionContext;
            import io.github.graphmaquina.dsl.StateGraph;
            import io.github.graphmaquina.dsl.TransitionBuilder;
            import io.github.graphmaquina.symbol.ActionSymbol;
            import io.github.graphmaquina.symbol.GuardSymbol;
            import io.github.graphmaquina.symbol.HookSymbol;
            import java.io.IOException;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import java.net.SocketTimeoutException;
            import java.util.Optional;

            /** Every recovery fragment of docs/guide.md, extracted verbatim, compiled against core. */
            public final class GuideRecoveryFragments {
                private GuideRecoveryFragments() { }

                /** Publication entity stand-in. */
                static final class Publication { }
                /** Adapter that returns the action result. */
                interface PublicationAdapter {
                    ActionOutcome publish(Publication publication);
                }
                /** Provider availability failure. */
                static final class ServiceUnavailableException extends RuntimeException {
                    ServiceUnavailableException(String message) { super(message); }
                }
                /** Provider rejection failure. */
                static final class ProviderRejectedException extends RuntimeException {
                    ProviderRejectedException(String message) { super(message); }
                }
                /** Invalid provider response failure. */
                static final class InvalidResponseException extends RuntimeException {
                    InvalidResponseException(String message) { super(message); }
                }
                /** Action symbol names used by the annotation example. */
                static final class PublicationActionsRefs {
                    private PublicationActionsRefs() { }
                    /** Symbol for the publish action. */
                    static final class Publish implements ActionSymbol {
                        private Publish() { }
                    }
                }
                // Shaped like the vocabulary the generator emits for a facade named Publications.
                /** Vocabulary stand-in for the generated publication graph. */
                static final class PublicationsGraph {
                    private PublicationsGraph() { }
                    /** A generated recovery annotation stand-in. */
                    @Target({})
                    @Retention(RetentionPolicy.CLASS)
                    @interface Recovery {
                        Class<? extends FailureKind> kind();
                        PublicationChange transition();
                        Class<? extends Throwable>[] failsOn() default {};
                    }
                    /** A generated transition annotation stand-in. */
                    @Target({ElementType.TYPE, ElementType.METHOD})
                    @Retention(RetentionPolicy.CLASS)
                    @interface Transition {
                        PublicationChange id();
                        PublicationState[] from();
                        PublicationState to();
                        Class<? extends ActionSymbol>[] action() default {};
                        Class<? extends GuardSymbol>[] guards() default {};
                        PublicationChange[] onFailure() default {};
                        Class<? extends Throwable>[] failsOn() default {};
                        Recovery[] recoveries() default {};
                        Timeout[] timeout() default {};
                        Class<? extends HookSymbol>[] afterCommit() default {};
                    }
                }

                // --- "Recover by failure kind and exception type", block 1 (states, identifiers, failure kinds)
            """;

    @Test
    void everyOutcomeFragmentOfTheGuideCompilesAgainstThePublishedCore(@TempDir Path workDir) throws IOException {
        List<String> guide = Files.readAllLines(Path.of(property("readmePath")), UTF_8);
        Path coreJar = publishedCoreJar();

        assertJavaBlockCount(guide, SECTION_HEADING, 5);

        StringBuilder source = new StringBuilder(HEADER);
        source.append(blockAfter(guide, SECTION_HEADING, 1)).append('\n');
        source.append("    // --- block 2 (TransitionResponse and httpStatus)\n");
        source.append(blockAfter(guide, SECTION_HEADING, 2)).append('\n');
        source.append("    // --- block 3 (asX), wrapped in a method\n");
        source.append("    static void narrow(Outcome<Ticket, TicketState, TicketTransition> outcome) {\n");
        source.append(blockAfter(guide, SECTION_HEADING, 3)).append("    }\n\n");
        source.append("    // --- block 4 (detail switch)\n");
        source.append(blockAfter(guide, SECTION_HEADING, 4)).append('\n');
        source.append("    // --- block 5 (handle a failed call)\n");
        source.append(blockAfter(guide, SECTION_HEADING, 5)).append('\n');
        source.append("    // --- the async completion callback block, wrapped in a method\n").append(STUBS);
        source.append("    static void handle(StubTickets tickets, Ticket ticket) {\n");
        source.append(blockStarting(guide, FUTURE_PREFIX)).append("    }\n\n");
        source.append("    // --- the observer example, wrapped in a method\n");
        source.append("    static void observeTheSet() {\n");
        source.append(blockStarting(guide, OBSERVER_PREFIX)).append("    }\n}\n");

        List<String> diagnostics = compile(workDir, "GuideFragments", source.toString(), coreJar);
        assertTrue(diagnostics.isEmpty(), "the guide's outcome fragments must compile against the published core:\n"
                + String.join("\n", diagnostics) + "\n--- generated GuideFragments.java ---\n" + source);
    }

    /** Compiles the recovery section's five Java blocks against the staged core jar. */
    @Test
    void everyRecoveryFragmentOfTheGuideCompilesAgainstThePublishedCore(@TempDir Path workDir) throws IOException {
        List<String> guide = Files.readAllLines(Path.of(property("readmePath")), UTF_8);
        Path coreJar = publishedCoreJar();

        assertJavaBlockCount(guide, RECOVERY_HEADING, 5);

        StringBuilder source = new StringBuilder(RECOVERY_HEADER);
        source.append(blockAfter(guide, RECOVERY_HEADING, 1)).append('\n');
        source.append("    // --- block 2 (the DSL declaration), wrapped in a method\n");
        source.append("    static void declare(MachineKey<Publication, PublicationState, PublicationChange> key,\n");
        source.append("            PublicationAdapter adapter) {\n");
        source.append(blockAfter(guide, RECOVERY_HEADING, 2)).append("    }\n\n");
        source.append("    // --- block 3 (the annotated operation), on a facade\n");
        source.append("    /** Publication facade stand-in for the annotation sample. */\n");
        source.append("    interface Publications {\n");
        source.append(blockAfter(guide, RECOVERY_HEADING, 3)).append("    }\n\n");
        source.append("    // --- block 4 (the most specific type), applied to an edge\n");
        source.append("    static void mostSpecific(\n");
        source.append("            TransitionBuilder<Publication, PublicationState, PublicationChange> edge) {\n");
        source.append("        edge\n").append(blockAfter(guide, RECOVERY_HEADING, 4)).append("        ;\n    }\n\n");
        source.append("    // --- block 5 (reading the kind), wrapped in a method\n");
        source.append("    static void read(StateMachine<Publication, PublicationState, PublicationChange> machine,\n");
        source.append("            Publication publication) {\n");
        source.append(blockAfter(guide, RECOVERY_HEADING, 5)).append("    }\n}\n");

        List<String> diagnostics = compile(workDir, "GuideRecoveryFragments", source.toString(), coreJar);
        assertTrue(diagnostics.isEmpty(), "the guide's recovery fragments must compile against the published core:\n"
                + String.join("\n", diagnostics) + "\n--- generated GuideRecoveryFragments.java ---\n" + source);
    }

    /**
     * Fails unless {@code heading} has {@code expected} java blocks after it and before the next level-two
     * heading. Headings are matched outside code fences only.
     */
    private static void assertJavaBlockCount(List<String> guide, String heading, int expected) {
        boolean inside = false;
        MarkdownFence fence = null;
        int count = 0;
        for (String line : guide) {
            if (fence != null) {
                fence = fence.closes(line) ? null : fence;
                continue;
            }
            fence = MarkdownFence.opening(line).orElse(null);
            if (fence != null) {
                if (inside && fence.java()) {
                    count++;
                }
            } else if (!inside) {
                inside = line.equals(heading);
            } else if (line.startsWith("## ")) {
                break;
            }
        }
        assertEquals(expected, count, "the guide's section '" + heading + "' has " + count
                + " java blocks; this test covers exactly " + expected + ": update the test to match the section");
    }

    /**
     * Returns the {@code n}th java block after {@code heading} and before the next level-two heading. Headings
     * are matched outside code fences only. Fails when the block is missing or empty.
     */
    private static String blockAfter(List<String> guide, String heading, int n) {
        boolean inside = false;
        boolean taking = false;
        MarkdownFence fence = null;
        int count = 0;
        StringBuilder block = new StringBuilder();
        for (String line : guide) {
            if (fence != null) {
                if (fence.closes(line)) {
                    if (taking) {
                        break;
                    }
                    fence = null;
                } else if (taking) {
                    block.append(line).append('\n');
                }
                continue;
            }
            fence = MarkdownFence.opening(line).orElse(null);
            if (fence != null) {
                if (inside && fence.java()) {
                    count++;
                    taking = count == n;
                }
            } else if (!inside) {
                inside = line.equals(heading);
            } else if (line.startsWith("## ")) {
                break;
            }
        }
        assertFalse(block.isEmpty(), "no non-empty java block number " + n + " after the heading '"
                + heading + "' of the guide");
        return block.toString();
    }

    /** Returns the first java block whose first line starts with the prefix. Fails when there is none. */
    private static String blockStarting(List<String> guide, String prefix) {
        StringBuilder block = new StringBuilder();
        MarkdownFence fence = null;
        boolean first = false;
        boolean taking = false;
        for (String line : guide) {
            if (fence == null) {
                fence = MarkdownFence.opening(line).orElse(null);
                first = fence != null && fence.java();
            } else if (fence.closes(line)) {
                if (taking) {
                    break;
                }
                fence = null;
            } else if (fence.java()) {
                if (first) {
                    first = false;
                    taking = line.startsWith(prefix);
                }
                if (taking) {
                    block.append(line).append('\n');
                }
            }
        }
        assertFalse(block.isEmpty(), "no non-empty java block starting with '" + prefix + "' in the guide");
        return block.toString();
    }

    private static List<String> compile(Path workDir, String className, String content, Path coreJar)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "this test needs a JDK compiler, not a JRE");
        Path source = Files.writeString(workDir.resolve(className + ".java"), content);
        Path classes = Files.createDirectories(workDir.resolve("classes"));
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(collector, null, UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(List.of(source));
            List<String> options = List.of("--release", "21", "-proc:none", "-Xlint:all,-serial", "-Werror",
                    "-classpath", coreJar.toString(), "-d", classes.toString());
            boolean succeeded = compiler.getTask(null, manager, collector, options, null, units).call();
            List<String> reported = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
                reported.add(diagnostic.getKind() + " at line " + diagnostic.getLineNumber() + ": "
                        + diagnostic.getMessage(null));
            }
            if (reported.isEmpty() && !succeeded) {
                reported.add("javac produced no classes and no diagnostic");
            }
            return reported;
        }
    }

    /** Resolves the staged core jar shared by both fragment compilations. */
    private static Path publishedCoreJar() {
        String version = property("artifactVersion");
        Path coreJar = Path.of(property("artifactRepository"), "io/github/graphmaquina/graphmaquina-core", version,
                "graphmaquina-core-" + version + ".jar");
        assertTrue(Files.isRegularFile(coreJar), "the published core jar is missing: " + coreJar);
        return coreJar;
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "run this test through :graphmaquina-test:publishedArtifactTest; missing " + name);
        return value;
    }
}
