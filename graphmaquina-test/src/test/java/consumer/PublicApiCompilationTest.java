package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.GraphAssertions;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract of the types a consumer sees. A fluent chain is only typed if the compiler rejects the wrong
 * state enum at each step, and that cannot be shown from inside a source file that must itself compile: so
 * this test runs javac over a consumer source of its own, once as written and once per deliberate mistake,
 * and asks the compiler rather than a message. It also pins the other half of the contract, that nothing a
 * consumer has to name lives in the implementation-detail package.
 */
class PublicApiCompilationTest {

    private static final String INTERNAL_PACKAGE = "io.github.graphmaquina.test.internal";

    /** A consumer of the facade, with one usage per shape whose typing the negative cases then break. */
    private static final String SOURCE = """
            package contract;

            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.FailureKind;
            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.MachineKey;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.Reaction;
            import io.github.graphmaquina.api.RecoveryRule;
            import io.github.graphmaquina.test.ExitExpectation;
            import io.github.graphmaquina.test.GraphAssertions;
            import java.util.List;

            /** Consumer source exercising the public assertion surface. */
            public final class Use {

                /** The machine states accepted by the assertions. */
                enum State { NEW, READY }

                /** A foreign state type used in negative compilation cases. */
                enum OtherState { READY }

                /** The machine transition identifiers. */
                enum Id { PREPARE }

                /** A foreign transition identifier used in negative compilation cases. */
                enum OtherId { PREPARE }

                /** A kind accepted by the recovery assertion. */
                record Declined() implements FailureKind { }

                /** The states of a sub-machine bound to the machine. */
                enum ChildState { DONE }

                /** The transition identifiers of that sub-machine. */
                enum ChildId { GO }

                public static void run() {
                    Outcome.Applied<String, State, Id> result = new Outcome.Applied<>(
                            "entity", State.NEW, Id.PREPARE, State.READY, List.of(), List.of());
                    GraphAssertions.assertThat(result).isApplied().movedTo(State.READY);
                }

                public static void inspect(GraphDefinition<String, State, Id> definition) {
                    GraphAssertions.assertThat(definition).transition(State.NEW, Id.PREPARE).isAction();
                }

                public static void policy(GraphDefinition<String, State, Id> definition) {
                    GraphAssertions.assertThat(definition).policy(State.NEW).rejects(Id.PREPARE, "no");
                }

                public static void reaction() {
                    MachineKey<String, State, Id> key =
                            MachineKey.of(String.class, State.class, Id.class);
                    Outcome.Applied<String, State, Id> child = new Outcome.Applied<>(
                            "entity", State.NEW, Id.PREPARE, State.READY, List.of(), List.of());
                    Outcome.Applied<String, State, Id> root = new Outcome.Applied<>(
                            "root", State.NEW, Id.PREPARE, State.READY, List.of(),
                            List.of(new Reaction.Done(key, "entity", child)));
                    GraphAssertions.assertThat(root).isApplied().reactions().forEntity(key, "entity")
                            .single().isDone().outcome().isApplied().movedTo(State.READY);
                }

                public static void recovery(Outcome<String, State, Id> outcome,
                        GraphDefinition<String, State, Id> definition) {
                    ActionOutcome declined = ActionOutcome.failed(Declined.class, "declined");
                    GraphAssertions.assertThat(declined).hasFailureKind(Declined.class);
                    GraphAssertions.assertThat(outcome).isFailed().hasFailureKind(Declined.class);
                    GraphAssertions.assertThat(definition).transition(State.NEW, Id.PREPARE)
                            .recoversExactly(List.of(new RecoveryRule<>(Declined.class, Id.PREPARE, List.of())));
                }

                public static void submachine(GraphDefinition<String, State, Id> definition) {
                    MachineKey<String, ChildState, ChildId> child =
                            MachineKey.of(String.class, ChildState.class, ChildId.class);
                    GraphAssertions.assertThat(definition).submachine(State.NEW, child)
                            .hasExitsExactly(ExitExpectation.exit(ChildState.DONE, Id.PREPARE))
                            .hasExit(ChildState.DONE, Id.PREPARE);
                }

                public static void starts(Outcome<String, State, Id> outcome) {
                    MachineKey<String, ChildState, ChildId> child =
                            MachineKey.of(String.class, ChildState.class, ChildId.class);
                    MachineKey<String, State, Id> parent = MachineKey.of(String.class, State.class, Id.class);
                    GraphAssertions.assertThat(outcome).starts().forChild(child, "child").single()
                            .startedAt(ChildState.DONE).hadPrevious(ChildState.DONE)
                            .byParent(parent, "parent", State.NEW).atDepth(0);
                }
            }
            """;

    @Test
    void theConsumerSourceCompilesCleanly(@TempDir Path workDir) throws IOException {
        Compilation compilation = compile(workDir, SOURCE);
        assertEquals(List.of(), render(compilation.diagnostics()));
        assertTrue(compilation.succeeded(), "the consumer source must compile");
    }

    @Test
    void aForeignStateIsRejectedOnTheOutcomeChain(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, "GraphAssertions.assertThat(result).isApplied().movedTo(State.READY)",
                "GraphAssertions.assertThat(result).isApplied().movedTo(OtherState.READY)");
    }

    @Test
    void aForeignStateIsRejectedOnTheEdgeSelector(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, "transition(State.NEW, Id.PREPARE).isAction()",
                "transition(OtherState.READY, Id.PREPARE).isAction()");
    }

    @Test
    void aForeignIdentifierIsRejectedOnTheEdgeSelector(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, "transition(State.NEW, Id.PREPARE).isAction()",
                "transition(State.NEW, OtherId.PREPARE).isAction()");
    }

    @Test
    void aForeignIdentifierIsRejectedOnThePolicyView(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, "rejects(Id.PREPARE, \"no\")", "rejects(OtherId.PREPARE, \"no\")");
    }

    @Test
    void aForeignStateIsRejectedOnTheNestedReaction(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, ".outcome().isApplied().movedTo(State.READY)",
                ".outcome().isApplied().movedTo(OtherState.READY)");
    }

    @Test
    void aClassThatIsNotAFailureKindIsRejectedOnTheActionOutcome(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, "assertThat(declined).hasFailureKind(Declined.class)",
                "assertThat(declined).hasFailureKind(String.class)");
    }

    @Test
    void aClassThatIsNotAFailureKindIsRejectedOnTheFailedOutcome(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, "isFailed().hasFailureKind(Declined.class)", "isFailed().hasFailureKind(String.class)");
    }

    @Test
    void aForeignIdentifierIsRejectedInAnExpectedRecoveryRule(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, "new RecoveryRule<>(Declined.class, Id.PREPARE, List.of())",
                "new RecoveryRule<>(Declined.class, OtherId.PREPARE, List.of())");
    }

    @Test
    void aForeignStateIsRejectedOnTheSubmachineSelector(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, "submachine(State.NEW, child)", "submachine(OtherState.READY, child)");
    }

    /** The view is typed by the child's key: a state of the parent is no terminal state of the child. */
    @Test
    void aStateOfTheParentIsRejectedAsATerminalStateOfTheChild(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, ".hasExit(ChildState.DONE, Id.PREPARE)", ".hasExit(State.READY, Id.PREPARE)");
    }

    /** An exit is an identifier of the parent, never one of the child. */
    @Test
    void anIdentifierOfTheChildIsRejectedAsAnExit(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, ".hasExit(ChildState.DONE, Id.PREPARE)", ".hasExit(ChildState.DONE, ChildId.GO)");
    }

    @Test
    void aForeignStateIsRejectedInAnExpectedExit(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, "ExitExpectation.exit(ChildState.DONE, Id.PREPARE)",
                "ExitExpectation.exit(OtherState.READY, Id.PREPARE)");
    }

    /** A start is typed by the child's key: a state of the parent is no state the child started at. */
    @Test
    void aStateOfTheParentIsRejectedAsTheStateTheChildStartedAt(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, ".startedAt(ChildState.DONE)", ".startedAt(State.READY)");
    }

    /** The node a start names is a state of the parent key given beside it. */
    @Test
    void aForeignStateIsRejectedAsTheNodeOfTheParent(@TempDir Path workDir) throws IOException {
        assertRejected(workDir, ".byParent(parent, \"parent\", State.NEW)",
                ".byParent(parent, \"parent\", OtherState.READY)");
    }

    @Test
    void noPublicSignatureNamesAnInternalType() throws IOException {
        List<Class<?>> types = publicTypes();
        assertTrue(types.contains(GraphAssertions.class) && types.size() > 1,
                "the scan must reach the published classes, not an empty directory");
        List<String> probe = new ArrayList<>();
        inspect(GraphAssertions.class, "probe", io.github.graphmaquina.test.internal.Checks.class,
                Modifier.PUBLIC, probe);
        assertEquals(1, probe.size(),
                "the detector must flag a public member that names an internal type, or it proves nothing");
        List<String> leaks = new ArrayList<>();
        for (Class<?> type : types) {
            inspectMembers(type, leaks);
        }
        assertEquals(List.of(), leaks);
    }

    /** Inspects the fields, the methods and the constructors that {@code type} declares. */
    private static void inspectMembers(Class<?> type, List<String> leaks) {
        for (Field field : type.getDeclaredFields()) {
            inspect(type, field.getName(), field.getGenericType(), field.getModifiers(), leaks);
        }
        for (Method method : type.getDeclaredMethods()) {
            inspect(type, method.getName(), method.getGenericReturnType(), method.getModifiers(), leaks);
            inspectParameters(type, method, leaks);
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            inspectParameters(type, constructor, leaks);
        }
    }

    /** Compiles the mutated source and requires an error on the line the mutation changed, message aside. */
    private static void assertRejected(Path workDir, String original, String replacement) throws IOException {
        String mutated = SOURCE.replace(original, replacement);
        assertNotEquals(SOURCE, mutated, "the mutation must apply, otherwise the case proves nothing");
        Compilation compilation = compile(workDir, mutated);
        assertFalse(compilation.succeeded(), "the mutated source must not compile");
        long line = lineOf(mutated, replacement);
        assertTrue(compilation.diagnostics().stream().anyMatch(
                        diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR
                                && diagnostic.getLineNumber() == line),
                "expected an error on line " + line + " but got " + render(compilation.diagnostics()));
    }

    /** What javac answered: whether it produced classes, and every diagnostic it emitted on the way. */
    private record Compilation(boolean succeeded, List<Diagnostic<? extends JavaFileObject>> diagnostics) { }

    private static Compilation compile(Path workDir, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "this contract check needs a JDK compiler, not a JRE");
        Path file = Files.writeString(workDir.resolve("Use.java"), source);
        Path classes = Files.createDirectories(workDir.resolve("classes"));
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager =
                     compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(List.of(file));
            List<String> options = List.of("--release", "21", "-proc:none", "-Xlint:all,-serial", "-Werror",
                    "-classpath", classpath(), "-d", classes.toString());
            boolean succeeded = compiler.getTask(null, manager, collector, options, null, units).call();
            return new Compilation(succeeded, collector.getDiagnostics());
        }
    }

    private static long lineOf(String source, String text) {
        String[] lines = source.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains(text)) {
                return index + 1L;
            }
        }
        throw new IllegalStateException("no line contains " + text);
    }

    private static List<String> render(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
                .map(diagnostic -> diagnostic.getKind() + " at line " + diagnostic.getLineNumber())
                .toList();
    }

    /** The published classes plus the runtime they are compiled against; nothing else is on the path. */
    private static String classpath() {
        return Stream.of(GraphAssertions.class, Outcome.class)
                .map(PublicApiCompilationTest::locationOf)
                .distinct()
                .reduce((left, right) -> left + File.pathSeparator + right)
                .orElseThrow();
    }

    private static String locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException malformed) {
            throw new IllegalStateException("cannot locate " + type.getName(), malformed);
        }
    }

    private static List<Class<?>> publicTypes() throws IOException {
        Path root = Path.of(locationOf(GraphAssertions.class));
        assertTrue(Files.isDirectory(root), "this contract check reads the compiled classes from " + root);
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> className(root, path))
                    .filter(name -> !name.startsWith(INTERNAL_PACKAGE) && !name.endsWith("-info"))
                    .map(PublicApiCompilationTest::load)
                    .filter(type -> Modifier.isPublic(type.getModifiers()))
                    .toList();
        }
    }

    private static String className(Path root, Path file) {
        String relative = root.relativize(file).toString();
        return relative.substring(0, relative.length() - ".class".length())
                .replace(File.separatorChar, '.');
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, PublicApiCompilationTest.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException("cannot load " + name, missing);
        }
    }

    private static void inspectParameters(Class<?> owner, Executable member, List<String> leaks) {
        for (Type parameter : member.getGenericParameterTypes()) {
            inspect(owner, member.getName(), parameter, member.getModifiers(), leaks);
        }
    }

    private static void inspect(Class<?> owner, String member, Type type, int modifiers, List<String> leaks) {
        if ((Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))
                && type.getTypeName().contains(INTERNAL_PACKAGE)) {
            leaks.add(owner.getName() + "." + member + " names " + type.getTypeName());
        }
    }
}
