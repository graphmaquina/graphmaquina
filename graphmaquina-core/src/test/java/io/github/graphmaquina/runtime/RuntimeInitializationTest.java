package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Cell;
import io.github.graphmaquina.fixtures.CellChains;
import io.github.graphmaquina.fixtures.CellStatus;
import io.github.graphmaquina.fixtures.CellTransition;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Assembling a registry initialises every class of the call packages, api, error, internal and runtime, that has a
 * static initializer, so that none runs for the first time inside a call: a StackOverflowError that interrupts a
 * static initializer leaves its class, and its subclasses, unusable for as long as it is loaded. A javap inventory of
 * the compiled main classes pins that MachineRegistry.staticallyInitialized() lists exactly those classes and that no
 * switch on another class's enum leaves a synthetic holder class there, so a new static initializer fails this test
 * until it is listed. A JVM of its own, logging class initialisation, shows that no class of the library initialises
 * once a registry is assembled, through the calls it runs; another shows that a cascade deeper than its thread's
 * stack, as the first failure of a fresh JVM, leaves the failures of later calls intact. The annotation package is
 * outside the inventory: its enums describe generated sources to the build tools, and no call reads them.
 */
class RuntimeInitializationTest {

    private static final List<String> CALL_PACKAGES = List.of("io/github/graphmaquina/api/",
            "io/github/graphmaquina/error/", "io/github/graphmaquina/internal/", "io/github/graphmaquina/runtime/");

    @Test
    void theRegistryInitialisesEveryClassOfTheCallPackagesThatHasAStaticInitializer() throws Exception {
        Set<String> withInitializer = new TreeSet<>();
        for (Map.Entry<String, String> javap : javapOfCallPackages().entrySet()) {
            if (javap.getValue().contains("static {};")) {
                withInitializer.add(javap.getKey());
            }
        }
        Set<String> listed = MachineRegistry.staticallyInitialized().stream().map(Class::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(withInitializer, listed, "MachineRegistry.staticallyInitialized() must list every class of the"
                + " call packages that has a static initializer, and only those");
    }

    @Test
    void noSwitchOnAnotherClassesEnumLeavesASyntheticHolderClassInTheCallPackages() throws Exception {
        List<String> holders = new ArrayList<>();
        for (Map.Entry<String, String> javap : javapOfCallPackages().entrySet()) {
            if (javap.getValue().contains("$SwitchMap$")) {
                holders.add(javap.getKey());
            }
        }
        assertEquals(List.of(), holders);
    }

    /**
     * Logs class initialisation in a JVM of its own while {@link CallsAfterAssembly} assembles a registry and runs
     * calls that succeed, fail, poison and notify observers: every listed class initialises before the registry is
     * assembled, and no class of the library afterwards.
     */
    @Test
    void noClassOfTheLibraryInitialisesOnceARegistryIsAssembled() throws Exception {
        List<String> output = runJava(List.of("-Xlog:class+init=info"), CallsAfterAssembly.class);
        Set<String> library = mainClassNames();
        Pattern initializing = Pattern.compile("Initializing '([^']+)'(\\(no method\\))?");
        Set<String> beforeAssembly = new TreeSet<>();
        List<String> afterAssembly = new ArrayList<>();
        boolean assembled = false;
        List<String> calls = new ArrayList<>();
        for (String line : output) {
            if (line.equals("ASSEMBLED")) {
                assembled = true;
                continue;
            }
            if (line.startsWith("CALL ")) {
                calls.add(line.substring(5));
                continue;
            }
            Matcher matcher = initializing.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String name = matcher.group(1).replace('/', '.');
            boolean runsAnInitializer = matcher.group(2) == null;
            if (!library.contains(name) || !runsAnInitializer) {
                continue;
            }
            if (assembled) {
                afterAssembly.add(name);
            } else {
                beforeAssembly.add(name);
            }
        }
        assertEquals(List.of("SUBMIT APPLIED", "PUBLISH TransitionAbortedException",
                "SUBMIT TransitionAbortedException", "SUBMIT AssertionError", "SUBMIT CascadeException",
                "PUBLISH GUARD_REJECTED", "reevaluate NO_WATCH", "ARCHIVE APPLIED", "supplyAsync done"), calls,
                String.join("\n", output));
        List<String> notBefore = new ArrayList<>();
        for (Class<?> type : MachineRegistry.staticallyInitialized()) {
            if (!beforeAssembly.contains(type.getName())) {
                notBefore.add(type.getName());
            }
        }
        assertEquals(List.of(), notBefore);
        assertEquals(List.of(), afterAssembly);
    }

    /**
     * In a fresh JVM, a cascade chain deeper than its thread's stack ends in a StackOverflowError before any call of
     * that JVM failed; a later call of another machine whose guard throws must still abort with
     * TransitionAbortedException, which it could not if building the deep call's failure had left a class of the
     * failures unusable.
     */
    @Test
    void aStackOverflowAsTheFirstFailureOfTheJvmLeavesTheFailuresOfLaterCallsIntact() throws Exception {
        List<String> output = runJava(List.of(), DeepCascadeThenFailure.class);
        List<String> ends = output.stream().filter((String line) -> line.startsWith("DEEP ")
                || line.startsWith("LATER ")).toList();
        assertEquals(List.of("DEEP java.lang.StackOverflowError",
                "LATER io.github.graphmaquina.error.TransitionAbortedException"), ends, String.join("\n", output));
    }

    /** What javap prints for each class of the call packages, by binary name. */
    private static Map<String, String> javapOfCallPackages() throws IOException, URISyntaxException {
        Path classes = mainClasses();
        ToolProvider javap = ToolProvider.findFirst("javap").orElseThrow();
        Map<String, String> printed = new TreeMap<>();
        for (String name : mainClassNames()) {
            String path = name.replace('.', '/');
            if (CALL_PACKAGES.stream().noneMatch((String root) -> path.startsWith(root)
                    && path.indexOf('/', root.length()) < 0)) {
                continue;
            }
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            int code = javap.run(new PrintWriter(out), new PrintWriter(err), "-p", "-cp", classes.toString(), name);
            assertEquals(0, code, "javap " + name + ": " + err);
            printed.put(name, out.toString());
        }
        return printed;
    }

    /** The binary name of every class compiled from the main sources. */
    private static Set<String> mainClassNames() throws IOException, URISyntaxException {
        Path classes = mainClasses();
        try (Stream<Path> files = Files.walk(classes)) {
            return files.filter((Path file) -> file.toString().endsWith(".class"))
                    .map((Path file) -> classes.relativize(file).toString().replace(file.getFileSystem().getSeparator(),
                            "/"))
                    .map((String file) -> file.substring(0, file.length() - ".class".length()).replace('/', '.'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** The directory of the compiled main classes, which the tests run against. */
    private static Path mainClasses() throws URISyntaxException {
        Path classes = Path.of(MachineRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "the main classes are not a directory: " + classes);
        return classes;
    }

    /** The lines that {@code main} prints, run in a JVM of its own with {@code options}, which must exit with 0. */
    private static List<String> runJava(List<String> options, Class<?> main) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(options);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(main.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "no exit after 60 s: " + output);
            assertEquals(0, process.exitValue(), output);
            return output.lines().toList();
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * Assembles a registry with an observer, prints {@code ASSEMBLED}, then runs calls through the paths a call can
     * take, printing {@code CALL} and how each ended: a cascade that commits with a hook, a guard that throws, a
     * nested abort and a nested Error that the action caught, a COLLECT that aggregates, a rejection, a reevaluation
     * with no watch, an asynchronous action and a supplyAsync.
     */
    public static final class CallsAfterAssembly {

        public static void main(String[] args) throws Exception {
            FlowKey<String> note = FlowKey.of("note", String.class);
            GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                    .initial(DRAFT).terminal(ARCHIVED)
                    .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                    .transition(SUBMIT, DRAFT, REVIEW)
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            String id = ctx.entity().id();
                            if (id.startsWith("cascade")) {
                                ctx.cascade(DOCS, new Doc("peer", REVIEW), PUBLISH);
                            } else if (id.startsWith("abort")) {
                                swallow(() -> ctx.cascade(DOCS, new Doc("bad", REVIEW), PUBLISH));
                            } else if (id.startsWith("error")) {
                                swallow(() -> ctx.cascade(DOCS, new Doc("error", REVIEW), PUBLISH));
                                throw new AssertionError("after the swallowed Error");
                            } else if (id.startsWith("collect")) {
                                ctx.cascadeAll(DOCS, List.of(new Doc("bad", REVIEW), new Doc("draft", DRAFT)),
                                        PUBLISH, CascadePolicy.COLLECT);
                            }
                            ctx.flow().put(note, id);
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("Note", (Doc doc, CommitContext<DocStatus, DocTransition> commit) ->
                                commit.flow().put(note, doc.id()))
                        .endTransition()
                    .transition(PUBLISH, REVIEW, PUBLISHED)
                        .guard("Throws", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity().id().equals("bad")) {
                                throw new IllegalStateException("bad");
                            }
                            return ctx.entity().id().equals("rejected") ? GuardResult.reject("rejected")
                                    : GuardResult.pass();
                        })
                        .action("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity().id().equals("error")) {
                                throw new AssertionError("in a nested action");
                            }
                            return ActionOutcome.ok();
                        })
                        .endTransition()
                    .transition(ARCHIVE, PUBLISHED, ARCHIVED)
                        .asyncAction("Archive", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                CompletableFuture.completedFuture(ActionOutcome.ok()))
                        .endTransition()
                    .build();
            MachineRegistry registry = MachineRegistry.builder()
                    .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id)
                            .asyncTimeout(Duration.ofSeconds(10)).recordTrail())
                    .observer(new MachineObserver() {
                        @Override
                        public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                            failure.phase();
                        }
                    })
                    .build();
            StateMachine<Doc, DocStatus, DocTransition> docs = registry.of(DOCS);
            System.out.println("ASSEMBLED");
            call("SUBMIT", () -> docs.execute(new Doc("cascade", DRAFT), SUBMIT));
            call("PUBLISH", () -> docs.execute(new Doc("bad", REVIEW), PUBLISH));
            call("SUBMIT", () -> docs.execute(new Doc("abort", DRAFT), SUBMIT));
            call("SUBMIT", () -> docs.execute(new Doc("error", DRAFT), SUBMIT));
            call("SUBMIT", () -> docs.execute(new Doc("collect", DRAFT), SUBMIT));
            call("PUBLISH", () -> docs.execute(new Doc("rejected", REVIEW), PUBLISH));
            call("reevaluate", () -> docs.reevaluate(new Doc("still", DRAFT)));
            call("ARCHIVE", () -> docs.execute(new Doc("archived", PUBLISHED), ARCHIVE));
            System.out.println("CALL supplyAsync " + docs.supplyAsync(() -> "done").get(10, TimeUnit.SECONDS));
            registry.close();
        }

        private static void swallow(Runnable cascade) {
            try {
                cascade.run();
            } catch (StateMachineException | Error swallowed) {
                // The call is poisoned all the same.
            }
        }

        private static void call(String what, Supplier<Outcome<Doc, DocStatus, DocTransition>> call) {
            String end;
            try {
                end = call.get().code().name();
            } catch (StateMachineException | Error thrown) {
                end = thrown.getClass().getSimpleName();
            }
            System.out.println("CALL " + what + " " + end);
        }
    }

    /**
     * Links the abort's class, then runs successful cascade chains of 50, 100 and 200 cells on a thread of 1 MiB, as
     * an application's earlier calls would, then a chain of 20,000 cells under a maxCascadeDepth that allows it, which
     * overflows that stack, and prints {@code DEEP} with the class of what it threw; then a call of another machine
     * whose guard throws, and {@code LATER} with the class of what that threw.
     */
    public static final class DeepCascadeThenFailure {

        public static void main(String[] args) throws InterruptedException {
            // Loaded and verified, as from a class data archive, but not initialized: reading the constructors links
            // the class, and its superclasses, and leaves their static initializers to their first use. Unverified, the
            // abort's verification would overflow first, at every level of the cascade, until enough stack was left.
            TransitionAbortedException.class.getDeclaredConstructors();
            StateMachine<Cell, CellStatus, CellTransition> cells = CellChains.cells(0, false, 100_000);
            for (int length : new int[] {50, 100, 200}) {
                Throwable warm = CellChains.onStack(1024 * 1024,
                        () -> cells.execute(CellChains.chain(length)[0], CellTransition.GO));
                if (warm != null) {
                    throw new AssertionError("a chain of " + length + " failed", warm);
                }
            }
            Cell[] chain = CellChains.chain(20_000);
            Throwable deep = CellChains.onStack(1024 * 1024, () -> cells.execute(chain[0], CellTransition.GO));
            System.out.println("DEEP " + (deep == null ? "returned" : deep.getClass().getName()));
            GraphDefinition<Doc, DocStatus, DocTransition> guarded = StateGraph.define(DOCS)
                    .initial(DRAFT).terminal(ARCHIVED)
                    .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                    .transition(SUBMIT, DRAFT, REVIEW)
                        .guard("Throws", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new IllegalStateException("the guard failed");
                        })
                        .endTransition()
                    .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                    .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                    .build();
            StateMachine<Doc, DocStatus, DocTransition> other =
                    StateMachines.from(guarded).state(Doc::status, Doc::setStatus).build();
            String later;
            try {
                later = "returned " + other.execute(new Doc("later", DRAFT), SUBMIT).code();
            } catch (Throwable thrown) {
                later = thrown.getClass().getName();
            }
            System.out.println("LATER " + later);
        }
    }
}
