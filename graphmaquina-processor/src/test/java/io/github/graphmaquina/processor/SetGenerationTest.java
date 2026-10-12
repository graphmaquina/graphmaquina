package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Machines;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The companion generated for a {@code @StateGraphs} set: its builder, whose signature follows the dependencies and
 * the asynchronous machines of the set, which refuses what the builders it mirrors refuse and which records the trail
 * of every machine of the set when asked to; which methods are accessors; the machines it holds and runs; and the
 * names it keeps apart. A set with a machine that failed is not generated.
 */
class SetGenerationTest {

    /**
     * The emitted Javadoc of {@code recordTrail(InstantSource)}, its lines joined. It never names the parameter, whose
     * name depends on the other variables of the companion.
     */
    private static final String RECORD_TRAIL_DOC = "Records the trail of every call through the set on the given clock,"
            + " by handing it to the StateMachines.Builder of every machine of the set. The root machine of a call"
            + " decides for the whole call, so every root call made through the set records its trail, the peers it"
            + " cascades to and the exits of sub-machines its steps run included, and so does each watch reaction, a"
            + " call of its own that its own machine, one of the set, decides. The flow such a call hands out, in its"
            + " outcome or in its failure, lists in trail() each hop and each sub-machine start the call took, each at"
            + " the instant that clock read right after the call took it, then the rejection or the failure that"
            + " ended the call, at the instant it ended. A call that records its trail creates its flow when its step"
            + " starts, so it hands out its flow, with the root entity and the trail, even when none of its steps or"
            + " hooks wrote to it; a reevaluation that selects no edge runs no step, and its ignored outcome hands out"
            + " the empty flow. The clock is read on the thread running the call, once per entry the call appends, an"
            + " entry a rollback later drops included, so a clock that advances on each read leaves a gap for each"
            + " dropped entry; a test can pass a clock it controls, such as a java.time.Clock. Anything but an Error"
            + " that the clock throws, a checked exception included, fails the call as what an identity function"
            + " throws does: the entry it could not"
            + " mark is left out of the trail. When it throws"
            + " while the call reads the instant of the Failed entry that ends a failed call, whatever failed the call,"
            + " the call's CallFailedException carries what it threw as suppressed, unless it threw that very"
            + " exception, and its trail ends without that entry; an InterruptedException suppressed there"
            + " interrupts the thread again, and an Error propagates. A clock that answers null counts as one that"
            + " throws a NullPointerException. A set built without recordTrail reads no clock and keeps no mark.";

    @Test
    void builderSignatureFollowsTheDependenciesAndAsyncness() {
        Compilation.Result async = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(async.success(), async.describe());
        String source = async.generated("fx.LibraryRuntime");
        assertTrue(source.contains("public static Builder builder(Duration asyncTimeout, DocActions docActions)"), source);
        assertTrue(source.contains("public final class LibraryRuntime implements Library"), source);
        assertTrue(source.contains(".as(Docs.class)"), source);
        assertTrue(source.contains(".as(Pages.class)"), source);

        Compilation.Result sync = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY.replace(Fixtures.ASYNC_ARCHIVE, "action = DocActionsRefs.Publish.class"),
                Fixtures.LIBRARY));
        assertTrue(sync.success(), sync.describe());
        assertTrue(sync.generated("fx.LibraryRuntime").contains("public static Builder builder(DocActions docActions)"),
                sync.generated("fx.LibraryRuntime"));
    }

    /**
     * A set may declare a method of {@code Machines}, {@code AutoCloseable} or {@code Object} again: such a
     * redeclaration is no accessor, since the companion writes or inherits that method already. An overload of one
     * of them is no redeclaration, and stays refused.
     */
    @Test
    void redeclarationsOfWhatTheCompanionImplementsAreNoAccessors() {
        Compilation.Result redeclared = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY.replace(
                        "    Pages pages();\n", "    Pages pages();\n    @Override void close();\n"
                                + "    @Override String toString();\n    boolean equals(Object other);\n    int hashCode();\n")));
        assertTrue(redeclared.success(), redeclared.describe());
        assertTrue(redeclared.generated("fx.LibraryRuntime").contains("public void close()"),
                redeclared.generated("fx.LibraryRuntime"));

        Compilation.Result overload = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY.replace(
                        "    Pages pages();\n", "    Pages pages();\n    void close(boolean now);\n")));
        assertEquals(1, overload.countError("SG510"), overload.describe());
        assertTrue(overload.errors().get(0).contains("close must be a no-argument accessor"), overload.describe());
    }

    @Test
    void builtSetRunsCascadesWatchesHooksAndObservers() throws Exception {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object actions = docActions.getConstructor().newInstance();
        Class<?> companion = result.load("fx.LibraryRuntime");
        Object builder = companion.getMethod("builder", Duration.class, docActions).invoke(null, Duration.ofSeconds(5), actions);
        List<String> seen = new ArrayList<>();
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                seen.add(call.key().entity().getSimpleName() + ":" + outcome.status().name());
            }
        };
        List<String> events = new ArrayList<>();
        Method register = builder.getClass().getMethod("observer", MachineObserver.class);
        register.invoke(builder, observer);
        register.invoke(builder, recording("first", events));
        register.invoke(builder, recording("second", events));
        Object library = builder.getClass().getMethod("build").invoke(builder);
        assertInstanceOf(Machines.class, library);
        try {
            Object docs = companion.getMethod("docs").invoke(library);
            Object pages = companion.getMethod("pages").invoke(library);
            assertInstanceOf(Machine.class, docs);
            Class<?> docClass = result.load("fx.Doc");
            Class<?> pageClass = result.load("fx.Page");
            Object doc = docClass.getConstructor(String.class).newInstance("d1");
            Object page = pageClass.getConstructor(String.class, docClass).newInstance("p1", doc);
            assertInstanceOf(Outcome.Applied.class, docs.getClass().getMethod("submit", docClass).invoke(docs, doc));
            Object finished = pages.getClass().getMethod("finish", pageClass).invoke(pages, page);
            Outcome.Applied<?, ?, ?> applied = assertInstanceOf(Outcome.Applied.class, finished);
            Reaction.Done reaction = assertInstanceOf(Reaction.Done.class, applied.reactions().get(0));
            assertInstanceOf(Outcome.Applied.class, reaction.outcome());
            assertEquals("PUBLISHED", docClass.getMethod("getStatus").invoke(doc).toString());
            @SuppressWarnings("unchecked")
            List<String> calls = (List<String>) docActions.getField("calls").get(actions);
            assertEquals(List.of("publish", "notify:d1"), calls);
            assertEquals(List.of("Doc:APPLIED", "Doc:APPLIED", "Page:APPLIED"), seen);
            assertEquals(12, events.size(), events.toString());
            for (int index = 0; index < events.size(); index += 2) {
                assertTrue(events.get(index).startsWith("first "), events.toString());
                assertEquals(events.get(index).replaceFirst("^first ", "second "), events.get(index + 1));
            }
            assertEquals(3, events.stream().filter((String event) -> event.startsWith("first completed")).count());
        } finally {
            ((Machines) library).close();
        }
    }

    /** An observer that appends "name event" to the shared list for every start and completion it is told of. */
    private static MachineObserver recording(String name, List<String> events) {
        return new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                events.add(name + " started " + call.key().entity().getSimpleName());
            }

            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                events.add(name + " completed " + call.key().entity().getSimpleName());
            }
        };
    }

    @Test
    void perMachineTimeoutOverrideRejectsMachinesWithoutAsyncTransitions() throws Exception {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object actions = docActions.getConstructor().newInstance();
        Object builder = result.load("fx.LibraryRuntime").getMethod("builder", Duration.class, docActions).invoke(null, Duration.ofSeconds(5), actions);
        Method override = builder.getClass().getMethod("asyncTimeout", Class.class, Duration.class);
        override.invoke(builder, result.load("fx.Docs"), Duration.ofSeconds(1));
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> override.invoke(builder, result.load("fx.Pages"), Duration.ofSeconds(1)));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
    }

    /**
     * The generated Builder mirrors MachineRegistry.Builder and StateMachines.Builder: a setting is refused when
     * it is given, a null clock by name, and a refused setting configures nothing, so a set whose clock was refused
     * records no trail; observers add up instead of replacing one another, and the builder is single-use, so
     * once build() succeeded, build() itself and every setting fail loudly, each with valid arguments. The Builder
     * refuses them itself, naming itself: the builders it hands its settings to would refuse them too, naming
     * builders the author never wrote.
     */
    @Test
    void theGeneratedBuilderRefusesWhatTheBuildersItMirrorsRefuse() throws Exception {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object builder = result.load("fx.LibraryRuntime").getMethod("builder", Duration.class, docActions)
                .invoke(null, Duration.ofSeconds(5), docActions.getConstructor().newInstance());
        Method observer = builder.getClass().getMethod("observer", MachineObserver.class);
        Method depth = builder.getClass().getMethod("maxCascadeDepth", int.class);
        Method chain = builder.getClass().getMethod("maxWatchChain", int.class);
        Method timeout = builder.getClass().getMethod("asyncTimeout", Class.class, Duration.class);
        Method executor = builder.getClass().getMethod("asyncExecutor", Executor.class);
        Method trail = builder.getClass().getMethod("recordTrail");
        Method clock = builder.getClass().getMethod("recordTrail", InstantSource.class);
        Method build = builder.getClass().getMethod("build");
        MachineObserver first = new MachineObserver() { };
        MachineObserver second = new MachineObserver() { };
        Executor direct = (Runnable command) -> command.run();

        observer.invoke(builder, first);
        observer.invoke(builder, second);
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(InvocationTargetException.class, () -> depth.invoke(builder, 0)).getCause());
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(InvocationTargetException.class, () -> chain.invoke(builder, -3)).getCause());
        NullPointerException noClock = assertInstanceOf(NullPointerException.class,
                assertThrows(InvocationTargetException.class, () -> clock.invoke(builder, (Object) null)).getCause());
        assertEquals("clock", noClock.getMessage());

        try (Machines library = (Machines) build.invoke(builder)) {
            assertInstanceOf(Machine.class, library.getClass().getMethod("docs").invoke(library));
            assertSame(FlowView.empty(), submit(result, library).flow());
            refusedAsUsed(() -> build.invoke(builder));
            refusedAsUsed(() -> observer.invoke(builder, second));
            refusedAsUsed(() -> depth.invoke(builder, 3));
            refusedAsUsed(() -> chain.invoke(builder, 3));
            refusedAsUsed(() -> timeout.invoke(builder, result.load("fx.Docs"), Duration.ofSeconds(1)));
            refusedAsUsed(() -> executor.invoke(builder, direct));
            refusedAsUsed(() -> trail.invoke(builder));
            refusedAsUsed(() -> clock.invoke(builder, InstantSource.system()));
        }
    }

    /**
     * The Builder offers the trail of the set in two methods, each documented in the companion: one on the system
     * clock and one on a clock of the caller's, both handed to the builder of every machine of the set after the
     * checks of the Builder itself.
     */
    @Test
    void theBuilderHandsRecordTrailToEveryMachineAndDocumentsIt() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(result.success(), result.describe());
        String source = result.generated("fx.LibraryRuntime");
        String body = "\n" + " ".repeat(12);
        assertTrue(source.contains("    public Builder recordTrail() {" + body + "checkUnused();" + body
                + "docsBuilder.recordTrail();" + body + "pagesBuilder.recordTrail();" + body + "return this;\n"),
                source);
        assertTrue(source.contains("    public Builder recordTrail(InstantSource clock) {" + body + "checkUnused();"
                + body + "Objects.requireNonNull(clock, \"clock\");" + body + "docsBuilder.recordTrail(clock);" + body
                + "pagesBuilder.recordTrail(clock);" + body + "return this;\n"), source);
        assertEquals("Records the trail of every call through the set on the system clock, InstantSource.system(), by"
                + " calling recordTrail() on the StateMachines.Builder of every machine of the set, as"
                + " recordTrail(InstantSource) describes.", doc(source, "public Builder recordTrail()"));
        assertEquals(RECORD_TRAIL_DOC, doc(source, "public Builder recordTrail(InstantSource clock)"));
    }

    /**
     * The text of the documentation comment the companion emits right above {@code signature}, its lines joined by a
     * space; every line of it stays within 120 columns.
     */
    private static String doc(String source, String signature) {
        String before = source.substring(0, source.indexOf(signature));
        String comment = before.substring(before.lastIndexOf("/**"), before.lastIndexOf("*/"));
        List<String> words = new ArrayList<>();
        for (String line : comment.split("\n")) {
            assertTrue(line.length() <= 120, line);
            String text = line.strip().replaceFirst("^/\\*\\*", "").replaceFirst("^\\*", "").strip();
            if (!text.isEmpty()) {
                words.add(text);
            }
        }
        return String.join(" ", words);
    }

    /**
     * {@code recordTrail(clock)} reaches every machine of the set: a root call of each machine records its trail on
     * that clock, and so does the watch reaction a page's call triggers, a call of its own that its own machine,
     * one of the set, decides.
     */
    @Test
    void recordTrailRecordsTheTrailOfEveryMachineOfTheSet() throws Exception {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(result.success(), result.describe());
        Instant noon = Instant.parse("2026-10-10T12:00:00Z");
        Object builder = libraryBuilder(result);
        assertSame(builder, builder.getClass().getMethod("recordTrail", InstantSource.class)
                .invoke(builder, InstantSource.fixed(noon)));
        try (Machines library = (Machines) builder.getClass().getMethod("build").invoke(builder)) {
            Outcome<?, ?, ?> submitted = submit(result, library);
            Class<?> pageClass = result.load("fx.Page");
            Object page = pageClass.getConstructor(String.class, result.load("fx.Doc"))
                    .newInstance("p1", submitted.entity());
            Object pages = library.getClass().getMethod("pages").invoke(library);
            Outcome<?, ?, ?> finished =
                    (Outcome<?, ?, ?>) pages.getClass().getMethod("finish", pageClass).invoke(pages, page);

            assertEquals(List.of("SUBMIT@" + noon), hops(submitted.flow()));
            assertEquals(List.of("FINISH@" + noon), hops(finished.flow()));
            Reaction.Done reaction = assertInstanceOf(Reaction.Done.class, finished.reactions().get(0));
            assertEquals(List.of("PUBLISH@" + noon), hops(reaction.outcome().flow()));
        }
    }

    /** {@code recordTrail()} reads the system clock: the instant of the entry lies within the call. */
    @Test
    void recordTrailWithoutAClockReadsTheSystemClock() throws Exception {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertTrue(result.success(), result.describe());
        Object builder = libraryBuilder(result);
        assertSame(builder, builder.getClass().getMethod("recordTrail").invoke(builder));
        try (Machines library = (Machines) builder.getClass().getMethod("build").invoke(builder)) {
            Instant before = Instant.now();
            Outcome<?, ?, ?> submitted = submit(result, library);
            Instant after = Instant.now();
            Instant at = submitted.flow().trail().get(0).at();
            assertTrue(!at.isBefore(before) && !at.isAfter(after), before + " " + at + " " + after);
        }
    }

    /** A new LibraryRuntime.Builder, with a five-second shared timeout and a new DocActions. */
    private static Object libraryBuilder(Compilation.Result result) throws Exception {
        Class<?> docActions = result.load("fx.DocActions");
        return result.load("fx.LibraryRuntime").getMethod("builder", Duration.class, docActions)
                .invoke(null, Duration.ofSeconds(5), docActions.getConstructor().newInstance());
    }

    /** Submits a new doc through the docs machine of {@code library}. */
    private static Outcome<?, ?, ?> submit(Compilation.Result result, Machines library) throws Exception {
        Object docs = library.getClass().getMethod("docs").invoke(library);
        Class<?> docClass = result.load("fx.Doc");
        Object doc = docClass.getConstructor(String.class).newInstance("d1");
        return (Outcome<?, ?, ?>) docs.getClass().getMethod("submit", docClass).invoke(docs, doc);
    }

    /** Each entry of the trail of {@code flow}, every one a hop, as its transition and its instant. */
    private static List<String> hops(FlowView flow) {
        List<String> hops = new ArrayList<>();
        for (FlowEntry entry : flow.trail()) {
            FlowEntry.Hop hop = assertInstanceOf(FlowEntry.Hop.class, entry);
            hops.add(hop.hop().transition() + "@" + hop.at());
        }
        return hops;
    }

    /** Asserts that {@code call}, a call of the generated LibraryRuntime.Builder, is refused because it was used. */
    private static void refusedAsUsed(Executable call) {
        IllegalStateException refused = assertInstanceOf(IllegalStateException.class,
                assertThrows(InvocationTargetException.class, call).getCause());
        assertEquals("this LibraryRuntime.Builder was already used", refused.getMessage());
    }

    /**
     * The set reads and writes the state with the accessors the property selected, and only with those: a
     * static overload of the same name, which a method reference would also consider, changes nothing.
     */
    @Test
    void staticOverloadsOfTheStateAndIdentityAccessorsDoNotMakeTheSetAmbiguous() throws Exception {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY, Fixtures.LIBRARY);
        sources.put("fx.Page", sources.get("fx.Page").replace("    public PageStatus status() { return status; }\n",
                "    public PageStatus status() { return status; }\n"
                        + "    public static PageStatus status(Page page) { return page.status; }\n"
                        + "    public static void status(Page page, PageStatus value) { page.status = value; }\n"
                        + "    public static String id(Page page) { return page.id; }\n"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object builder = result.load("fx.LibraryRuntime").getMethod("builder", Duration.class, docActions)
                .invoke(null, Duration.ofSeconds(5), docActions.getConstructor().newInstance());
        try (Machines library = (Machines) builder.getClass().getMethod("build").invoke(builder)) {
            Object pages = library.getClass().getMethod("pages").invoke(library);
            Class<?> pageClass = result.load("fx.Page");
            Object page = pageClass.getConstructor(String.class, result.load("fx.Doc")).newInstance("p1", null);
            assertInstanceOf(Outcome.Applied.class, pages.getClass().getMethod("finish", pageClass).invoke(pages, page));
            assertEquals("DONE", pageClass.getMethod("status").invoke(page).toString());
        }
    }

    /**
     * A package holding Status, Step, Thing and a minimal Things machine over them: four simple names shared with
     * every twin.
     */
    private static void twin(Map<String, String> sources, String name) {
        sources.put(name + ".Status", "package " + name + ";\npublic enum Status { OPEN, DONE }\n");
        sources.put(name + ".Step", "package " + name + ";\npublic enum Step { FINISH }\n");
        sources.put(name + ".Thing", """
                package %s;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Thing {
                    private final String id;
                    @GraphProperty private Status status = Status.OPEN;
                    public Thing(String id) { this.id = id; }
                    @GraphProperty public String getId() { return id; }
                    public Status getStatus() { return status; }
                    public void setStatus(Status status) { this.status = status; }
                }
                """.formatted(name));
        sources.put(name + ".Things", """
                package %1$s;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static %1$s.Status.DONE;
                import static %1$s.Status.OPEN;
                import static %1$s.Step.FINISH;
                @GenerateGraphApi
                @ThingsGraph.Definition(initial = OPEN, terminals = DONE, state = ThingRefs.Status.class, id = ThingRefs.Id.class)
                @ThingsGraph.StatePolicy(state = OPEN, otherwise = "a thing is finished")
                public interface Things extends Machine<Thing, Status, Step> {
                    @ThingsGraph.Transition(id = FINISH, from = OPEN, to = DONE)
                    Outcome<Thing, Status, Step> finish(Thing thing);
                }
                """.formatted(name));
    }

    @Test
    void twoPackagesSharingEverySimpleNameStillCompile() {
        Map<String, String> sources = new LinkedHashMap<>();
        twin(sources, "a");
        twin(sources, "b");
        sources.put("fx.Both", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Both extends Machines {
                    a.Things first();
                    b.Things second();
                }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("a.ThingsRuntime"), result.describe());
        assertTrue(result.hasGenerated("b.ThingsRuntime"), result.describe());
        assertTrue(result.hasGenerated("fx.BothRuntime"), result.describe());
    }

    @Test
    void aDependencyNamedLikeAGeneratedFieldStillCompiles() {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY.replace(Fixtures.PUBLISH_BINDING, "action = ExecutorRefs.Publish.class"),
                Fixtures.LIBRARY);
        sources.put("fx.Executor", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public class Executor {
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> ctx) { return ActionOutcome.ok(); }
                }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
    }

    /**
     * An accessor named {@code clock} names the field that keeps its facade, so the parameter of
     * {@code recordTrail(InstantSource)} takes another name, as every variable of the companion does, and its Javadoc,
     * which names no parameter, stays the same.
     */
    @Test
    void anAccessorNamedClockLeavesRecordTrailAParameterOfAnotherName() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY,
                Fixtures.LIBRARY.replace("    Pages pages();\n", "    Pages clock();\n")));
        assertTrue(result.success(), result.describe());
        String source = result.generated("fx.LibraryRuntime");
        assertTrue(source.contains("private final Pages clock;"), source);
        assertTrue(source.contains("public Builder recordTrail(InstantSource clock2) {"), source);
        assertTrue(source.contains("Objects.requireNonNull(clock2, \"clock\");"), source);
        assertTrue(source.contains("clockBuilder.recordTrail(clock2);"), source);
        assertEquals(RECORD_TRAIL_DOC, doc(source, "public Builder recordTrail(InstantSource clock2)"));
    }

    @Test
    void setIsNotGeneratedWhenAMachineFailed() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER.replace("otherwise = \"a draft is sent to review\"", "otherwise = \" \"")
                        + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertFalse(result.success());
        assertFalse(result.hasGenerated("fx.LibraryRuntime"));
        assertFalse(result.hasGenerated("fx.DocsRuntime"));
        assertTrue(result.hasGenerated("fx.PagesRuntime"));
    }
}
