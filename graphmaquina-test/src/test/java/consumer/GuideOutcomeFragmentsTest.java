package consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
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
 * Compiles the Java fragments of the guide's "Handle transition outcomes" section, plus its async completion and
 * observer examples, against the published core jar. The fragments are cut out of {@code docs/guide.md} verbatim
 * and wrapped only by stand-in types and methods, so a renamed accessor in the guide breaks this test the way it
 * would break a reader. A heading or block that cannot be found fails the test instead of compiling nothing. The
 * guide, the repository and the release come from the {@code readmePath}, {@code artifactRepository} and
 * {@code artifactVersion} system properties that {@code publishedArtifactTest} sets.
 */
class GuideOutcomeFragmentsTest {

    private static final String SECTION_HEADING = "## Handle transition outcomes";
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

    @Test
    void everyOutcomeFragmentOfTheGuideCompilesAgainstThePublishedCore(@TempDir Path workDir) throws IOException {
        List<String> guide = Files.readAllLines(Path.of(property("readmePath")), UTF_8);
        String version = property("artifactVersion");
        Path coreJar = Path.of(property("artifactRepository"), "io/github/graphmaquina/graphmaquina-core", version,
                "graphmaquina-core-" + version + ".jar");
        assertTrue(Files.isRegularFile(coreJar), "the published core jar is missing: " + coreJar);

        StringBuilder source = new StringBuilder(HEADER);
        source.append(blockAfter(guide, 1)).append('\n');
        source.append("    // --- block 2 (TransitionResponse and httpStatus)\n");
        source.append(blockAfter(guide, 2)).append('\n');
        source.append("    // --- block 3 (asX), wrapped in a method\n");
        source.append("    static void narrow(Outcome<Ticket, TicketState, TicketTransition> outcome) {\n");
        source.append(blockAfter(guide, 3)).append("    }\n\n");
        source.append("    // --- block 4 (detail switch)\n");
        source.append(blockAfter(guide, 4)).append('\n');
        source.append("    // --- the async completion callback block, wrapped in a method\n").append(STUBS);
        source.append("    static void handle(StubTickets tickets, Ticket ticket) {\n");
        source.append(blockStarting(guide, FUTURE_PREFIX)).append("    }\n\n");
        source.append("    // --- the observer example, wrapped in a method\n");
        source.append("    static void observeTheSet() {\n");
        source.append(blockStarting(guide, OBSERVER_PREFIX)).append("    }\n}\n");

        List<String> diagnostics = compile(workDir, source.toString(), coreJar);
        assertTrue(diagnostics.isEmpty(), "the guide's outcome fragments must compile against the published core:\n"
                + String.join("\n", diagnostics) + "\n--- generated GuideFragments.java ---\n" + source);
    }

    /** Returns the {@code n}th java block after the section heading and before the next level-two heading. */
    private static String blockAfter(List<String> guide, int n) {
        boolean inside = false;
        boolean taking = false;
        int count = 0;
        StringBuilder block = new StringBuilder();
        for (String line : guide) {
            if (!inside) {
                inside = line.equals(SECTION_HEADING);
            } else if (taking) {
                if (line.startsWith("```")) {
                    break;
                }
                block.append(line).append('\n');
            } else if (line.startsWith("## ")) {
                break;
            } else if (line.startsWith("```java")) {
                count++;
                taking = count == n;
            }
        }
        assertFalse(block.isEmpty(), "no non-empty java block number " + n + " after the heading '"
                + SECTION_HEADING + "' of the guide");
        return block.toString();
    }

    /** Returns the java block whose first line starts with the prefix. */
    private static String blockStarting(List<String> guide, String prefix) {
        StringBuilder block = new StringBuilder();
        boolean open = false;
        boolean first = false;
        boolean taking = false;
        for (String line : guide) {
            if (!open) {
                open = line.startsWith("```java");
                first = open;
            } else if (line.startsWith("```")) {
                if (taking) {
                    break;
                }
                open = false;
            } else {
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

    private static List<String> compile(Path workDir, String content, Path coreJar) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "this test needs a JDK compiler, not a JRE");
        Path source = Files.writeString(workDir.resolve("GuideFragments.java"), content);
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

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "run this test through :graphmaquina-test:publishedArtifactTest; missing " + name);
        return value;
    }
}
