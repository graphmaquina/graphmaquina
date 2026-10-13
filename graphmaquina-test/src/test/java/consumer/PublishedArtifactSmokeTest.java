package consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Contract of the artifacts a consumer actually downloads. The tests of this module's {@code test} task are
 * checked against the classes Gradle compiled, which says nothing about what the published jar and POM
 * contain: so this test, which {@code publishedArtifactTest} runs instead, reads the files from a local Maven
 * repository, compiles a consumer against those jars alone and runs it in a separate JVM whose classpath holds
 * nothing else — no JUnit, no project classes and no test runtime. The repository and the release are passed
 * as the {@code artifactRepository} and {@code artifactVersion} system properties by the Gradle task that
 * publishes them first.
 */
class PublishedArtifactSmokeTest {

    /**
     * The chain a consumer is promised, written as a consumer writes it and run from the jars; typed recovery and the
     * sub-machine assertions each have a consumer of their own.
     */
    private static final String CONSUMER_SOURCE = """
            import static io.github.graphmaquina.test.GraphAssertions.assertThat;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.CallFailure;
            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.GuardResult;
            import io.github.graphmaquina.api.MachineCall;
            import io.github.graphmaquina.api.MachineKey;
            import io.github.graphmaquina.api.MachineObserver;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.OutcomeCode;
            import io.github.graphmaquina.api.OutcomeStatus;
            import io.github.graphmaquina.api.StateMachine;
            import io.github.graphmaquina.api.StatesWritten;
            import io.github.graphmaquina.dsl.StateGraph;
            import io.github.graphmaquina.error.AfterCommitException;
            import io.github.graphmaquina.error.CallFailedException;
            import io.github.graphmaquina.runtime.MachineRegistry;
            import io.github.graphmaquina.runtime.StateMachines;
            import io.github.graphmaquina.test.GraphSnapshots;
            import io.github.graphmaquina.test.StateSnapshot;
            import io.github.graphmaquina.test.probe.ActionProbe;
            import io.github.graphmaquina.test.probe.CallTrace;
            import io.github.graphmaquina.test.probe.GuardProbe;
            import io.github.graphmaquina.test.snapshot.GoldenOptions;
            import io.github.graphmaquina.test.snapshot.GraphDiff;
            import io.github.graphmaquina.test.snapshot.GraphSnapshot;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.nio.file.Path;
            import java.time.Duration;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Optional;

            public class PublishedConsumer {
                enum State { NEW, DONE }
                enum Id { FINISH, ABANDON }
                static final class Entity { State state = State.NEW; }
                private static final MachineKey<Entity, State, Id> KEY =
                        MachineKey.of(Entity.class, State.class, Id.class);

                private static GraphDefinition<Entity, State, Id> graph(String action) {
                    return StateGraph.define(KEY).initial(State.NEW).terminal(State.DONE)
                            .statePolicy(State.NEW, "finish first")
                            .transition(Id.FINISH, State.NEW, State.DONE)
                            .action(action, context -> ActionOutcome.ok())
                            .endTransition().build();
                }

                public static void main(String[] args) throws Exception {
                    GraphDefinition<Entity, State, Id> definition = graph("finish");
                    assertThat(definition).hasInitial(State.NEW).hasTerminalsExactly(State.DONE)
                            .hasTransitionsExactly(State.NEW, Id.FINISH)
                            .hasNoTransition(State.NEW, Id.ABANDON)
                            .transition(State.NEW, Id.FINISH).hasTarget(State.DONE).isAction()
                            .hasActionNamed("finish").hasNoGuards();
                    assertThat(definition).policy(State.NEW).hasDefaultReason("finish first")
                            .rejects(Id.ABANDON, "finish first");
                    GraphSnapshot snapshot = GraphSnapshot.capture(definition);
                    if (!snapshot.serialize().startsWith("graphmaquina-snapshot-v1\\n")) {
                        throw new AssertionError("the published snapshot must be version 2");
                    }
                    GraphDiff diff = GraphDiff.compare(snapshot, GraphSnapshot.capture(graph("complete")));
                    if (!List.of(new GraphDiff.Change("/transitions/NEW/FINISH/action/name",
                            Optional.of("finish"), Optional.of("complete"))).equals(diff.changes())) {
                        throw new AssertionError("the action change must be detected structurally");
                    }
                    Path root = Path.of(args[0]);
                    try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
                        GraphSnapshots.assertMatches(definition, "consumer.graph",
                                GoldenOptions.updating(loader, root, path -> { }));
                        GraphSnapshots.assertMatches(definition, "consumer.graph", GoldenOptions.readOnly(loader));
                    }
                    Entity entity = new Entity();
                    StateSnapshot before = StateSnapshot.builder().track(KEY, entity, value -> value.state).capture();
                    StateMachine<Entity, State, Id> machine = StateMachines.from(definition)
                            .state(value -> value.state, (value, state) -> value.state = state)
                            .asyncExecutor(Runnable::run).build();
                    Outcome<Entity, State, Id> finished = machine.execute(entity, Id.FINISH);
                    assertThat(finished).isApplied().movedTo(State.DONE)
                            .requestedTransition(Id.FINISH)
                            .hasEntityState(value -> value.state, State.DONE).records().hasSize(1);
                    // No default: this switch compiles only while OutcomeCode holds exactly these eight codes.
                    String verdict = switch (finished.code()) {
                        case APPLIED -> "applied";
                        case NO_TRANSITION, GUARD_REJECTED, DETACHED, RESERVED_EXIT -> "rejected";
                        case FAILURE_ROUTED -> "failed";
                        case NO_WATCH, NO_RULE_MATCHED -> "ignored";
                    };
                    if (!verdict.equals("applied") || finished.status() != OutcomeStatus.APPLIED
                            || finished.explanation().isPresent() || finished.records().size() != 1
                            || finished.asApplied().isEmpty() || finished.asRejected().isPresent()
                            || finished.recordedState() != State.DONE) {
                        throw new AssertionError("the published outcome must classify itself: " + finished);
                    }
                    Outcome<Entity, State, Id> again = machine.execute(entity, Id.FINISH);
                    if (again.code() != OutcomeCode.NO_TRANSITION || again.status() != OutcomeStatus.REJECTED
                            || again.asRejected().isEmpty()
                            || !again.explanation().equals(Optional.of("terminal state DONE accepts no transitions"))) {
                        throw new AssertionError("a request with no edge must explain itself: " + again);
                    }
                    if (before.changes().size() != 1) {
                        throw new AssertionError("the actual state write must be observed");
                    }
                    CallTrace trace = CallTrace.create();
                    GuardProbe<Entity, State, Id> guard =
                            GuardProbe.<Entity, State, Id>create("gate", trace).thenReturn(GuardResult.pass());
                    ActionProbe<Entity, State, Id> action =
                            ActionProbe.<Entity, State, Id>create("finish", trace).thenReturn(ActionOutcome.ok());
                    GraphDefinition<Entity, State, Id> inputGraph = StateGraph.define(KEY)
                            .initial(State.NEW).terminal(State.DONE)
                            .statePolicy(State.NEW, "finish first")
                            .transition(Id.FINISH, State.NEW, State.DONE)
                            .action("finish", action).guard("gate", guard)
                            .endTransition().build();
                    StateMachine<Entity, State, Id> inputMachine = StateMachines.from(inputGraph)
                            .state(value -> value.state, (value, state) -> value.state = state)
                            .asyncExecutor(Runnable::run).build();
                    String input = new String("approved");
                    assertThat(inputMachine.execute(new Entity(), Id.FINISH, input)).isApplied();
                    assertThat(guard.calls().single()).hasInput("approved").hasInputSameAs(input);
                    assertThat(action.calls().single()).inputSatisfies(CharSequence.class, value -> {
                        if (value != input) { throw new AssertionError("input identity was lost"); }
                    });
                    if (guard.calls().single().input(String.class).orElseThrow() != input
                            || action.calls().single().input(String.class).orElseThrow() != input) {
                        throw new AssertionError("recorded input was lost");
                    }
                    if (guard.calls().single().transition() != Id.FINISH
                            || action.calls().single().transition() != Id.FINISH) {
                        throw new AssertionError("the recorded identifier was lost");
                    }
                    List<String> events = new ArrayList<>();
                    List<MachineCall> calls = new ArrayList<>();
                    List<Outcome<?, ?, ?>> completed = new ArrayList<>();
                    MachineObserver observer = new MachineObserver() {
                        @Override
                        public void onStarted(MachineCall call) {
                            events.add("started " + call);
                            calls.add(call);
                        }

                        @Override
                        public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                            events.add("completed " + outcome.status());
                            calls.add(call);
                            completed.add(outcome);
                        }

                        @Override
                        public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                            events.add("failed " + failure.phase() + " " + failure.statesWritten());
                            calls.add(call);
                        }
                    };
                    try (MachineRegistry registry = MachineRegistry.builder()
                            .add(StateMachines.from(definition)
                                    .state((Entity value) -> value.state,
                                            (Entity value, State state) -> value.state = state)
                                    .identity((Entity value) -> value))
                            .observer(observer)
                            .build()) {
                        Outcome<Entity, State, Id> done = registry.of(KEY).execute(new Entity(), Id.FINISH);
                        if (completed.size() != 1 || completed.get(0) != done || calls.size() != 2
                                || calls.get(0) != calls.get(1)) {
                            throw new AssertionError(
                                    "the published observer must see the call that completed: " + events);
                        }
                    }
                    GraphDefinition<Entity, State, Id> hooked = StateGraph.define(KEY).initial(State.NEW)
                            .terminal(State.DONE)
                            .statePolicy(State.NEW, "finish first")
                            .transition(Id.FINISH, State.NEW, State.DONE)
                            .afterCommit("audit", (Entity value) -> {
                                throw new IllegalStateException("audit down");
                            })
                            .endTransition().build();
                    try (MachineRegistry registry = MachineRegistry.builder()
                            .add(StateMachines.from(hooked)
                                    .state((Entity value) -> value.state,
                                            (Entity value, State state) -> value.state = state)
                                    .identity((Entity value) -> value))
                            .observer(observer)
                            .build()) {
                        try {
                            registry.of(KEY).execute(new Entity(), Id.FINISH);
                            throw new AssertionError("the failing hook must fail the call");
                        } catch (CallFailedException failure) {
                            // No default: this switch compiles only while CallPhase holds exactly these six phases.
                            String where = switch (failure.phase()) {
                                case RESOLVE, GUARD, ACTION -> "before the commit";
                                case COMMIT -> "during the commit";
                                case HOOK -> "after the commit";
                                case REACTIONS -> "in a reaction";
                            };
                            if (!where.equals("after the commit") || failure.statesWritten() != StatesWritten.ALL
                                    || !(failure instanceof AfterCommitException after) || !after.hook().equals("audit")
                                    || after.hop().transition() != Id.FINISH || after.records().size() != 1
                                    || failure.call() != calls.get(2)) {
                                throw new AssertionError("the published call failure must classify itself: " + failure);
                            }
                        }
                    }
                    if (!events.equals(List.of("started Entity/State FINISH", "completed APPLIED",
                            "started Entity/State FINISH", "failed HOOK ALL"))
                            || calls.get(2) != calls.get(3) || calls.get(0) == calls.get(2)) {
                        throw new AssertionError("the published observer must see each call start and end: " + events);
                    }
                    System.out.println("PUBLIC_API_OK");
                }
            }
            """;

    /**
     * Typed recovery as a consumer writes it with the DSL, run from the jars: two failure kinds with their recover
     * rules, a failsOn type and the fallback, read back through the definition, the outcomes, an action probe, the
     * snapshot and the DOT, and selected again by a controlled asynchronous action behind a public asynchronous
     * call. The consumer has a package of its own, which the DOT leaves out of the kind it draws.
     */
    private static final String RECOVERY_CONSUMER_SOURCE = """
            package published.recovery;

            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.FailureKind;
            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.MachineKey;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.OutcomeCode;
            import io.github.graphmaquina.api.OutcomeStatus;
            import io.github.graphmaquina.api.RecoveryRule;
            import io.github.graphmaquina.api.StateMachine;
            import io.github.graphmaquina.dsl.StateGraph;
            import io.github.graphmaquina.dsl.TransitionBuilder;
            import io.github.graphmaquina.export.GraphView;
            import io.github.graphmaquina.export.GraphvizExporter;
            import io.github.graphmaquina.runtime.StateMachines;
            import io.github.graphmaquina.test.AsyncAssertions;
            import io.github.graphmaquina.test.GraphAssertionError;
            import io.github.graphmaquina.test.GraphAssertions;
            import io.github.graphmaquina.test.probe.ActionCall;
            import io.github.graphmaquina.test.probe.ActionProbe;
            import io.github.graphmaquina.test.probe.CallTrace;
            import io.github.graphmaquina.test.probe.ControlledAsyncAction;
            import io.github.graphmaquina.test.probe.PendingInvocation;
            import io.github.graphmaquina.test.snapshot.GraphSnapshot;
            import java.time.Duration;
            import java.util.List;
            import java.util.Map;
            import java.util.concurrent.CompletableFuture;
            import java.util.function.UnaryOperator;

            /** Typed recovery as a consumer of the published jars writes it, run from those jars alone. */
            public final class RecoveryConsumer {

                /** The states of the publication machine. */
                enum TicketState { READY, PUBLISHED, RETRY_PENDING, REJECTED_BY_PROVIDER, INTEGRATION_FAILED, CLOSED }

                /** The identifiers of the publication machine. */
                enum TicketChange { PUBLISH, SCHEDULE_RETRY, RECORD_REJECTION, MARK_INTEGRATION_FAILED, RETRY, CLOSE }

                /** The failure kinds a publication adapter reports. */
                public static final class PublicationFailures {
                    private PublicationFailures() { }

                    /** The provider could not take the publication now. */
                    public record Unavailable() implements FailureKind { }

                    /** The provider refused the content. */
                    public record Rejected() implements FailureKind { }
                }

                /** Thrown when the provider is temporarily unavailable. */
                public static final class ServiceUnavailableException extends RuntimeException {
                    public ServiceUnavailableException(String message) {
                        super(message);
                    }
                }

                /** Thrown when the provider rejects the request. */
                public static final class ProviderRejectedException extends RuntimeException {
                    public ProviderRejectedException(String message) {
                        super(message);
                    }
                }

                /** Thrown when the provider's response cannot be read. */
                public static final class InvalidResponseException extends RuntimeException {
                    public InvalidResponseException(String message) {
                        super(message);
                    }
                }

                /** The entity, whose state the machine reads and writes. */
                static final class Ticket {
                    TicketState state = TicketState.READY;
                }

                private static final MachineKey<Ticket, TicketState, TicketChange> KEY =
                        MachineKey.of(Ticket.class, TicketState.class, TicketChange.class);

                /** How long the consumer waits for an asynchronous call: the consumer's own wait, not the engine's. */
                private static final Duration BUDGET = Duration.ofSeconds(30);

                private RecoveryConsumer() { }

                /** The publication graph: {@code action} gives PUBLISH its action, and the rules follow. */
                private static GraphDefinition<Ticket, TicketState, TicketChange> graph(
                        UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> action) {
                    TransitionBuilder<Ticket, TicketState, TicketChange> publish = action.apply(StateGraph.define(KEY)
                            .initial(TicketState.READY).terminal(TicketState.PUBLISHED).terminal(TicketState.CLOSED)
                            .allowsCycle(TicketState.READY)
                            .statePolicy(TicketState.READY, "publish the ticket")
                            .statePolicy(TicketState.RETRY_PENDING, "retry the publication explicitly")
                            .statePolicy(TicketState.REJECTED_BY_PROVIDER, "close the rejected ticket")
                            .statePolicy(TicketState.INTEGRATION_FAILED, "close after manual review")
                            .transition(TicketChange.PUBLISH, TicketState.READY, TicketState.PUBLISHED));
                    return publish
                            .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                    ServiceUnavailableException.class)
                            .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                                    ProviderRejectedException.class)
                            .failsOn(InvalidResponseException.class)
                            .onFailure(TicketChange.MARK_INTEGRATION_FAILED)
                            .endTransition()
                            .transition(TicketChange.SCHEDULE_RETRY, TicketState.READY, TicketState.RETRY_PENDING)
                            .endTransition()
                            .transition(TicketChange.RECORD_REJECTION, TicketState.READY,
                                    TicketState.REJECTED_BY_PROVIDER)
                            .endTransition()
                            .transition(TicketChange.MARK_INTEGRATION_FAILED, TicketState.READY,
                                    TicketState.INTEGRATION_FAILED)
                            .endTransition()
                            .transition(TicketChange.RETRY, TicketState.RETRY_PENDING, TicketState.READY)
                            .endTransition()
                            .transition(TicketChange.CLOSE, TicketState.REJECTED_BY_PROVIDER, TicketState.CLOSED)
                            .endTransition()
                            .transition(TicketChange.CLOSE, TicketState.INTEGRATION_FAILED, TicketState.CLOSED)
                            .endTransition()
                            .build();
                }

                /** A standalone machine over {@code definition}; its engine waits far longer than the consumer does. */
                private static StateMachine<Ticket, TicketState, TicketChange> machine(
                        GraphDefinition<Ticket, TicketState, TicketChange> definition) {
                    return StateMachines.from(definition)
                            .state((Ticket ticket) -> ticket.state,
                                    (Ticket ticket, TicketState state) -> ticket.state = state)
                            .asyncTimeout(Duration.ofMinutes(1))
                            .build();
                }

                private static void expect(Map<String, String> fields, String path, String value) {
                    if (!value.equals(fields.get(path))) {
                        throw new AssertionError(path + ": expected " + value + " but was " + fields.get(path));
                    }
                }

                public static void main(String[] args) {
                    CallTrace trace = CallTrace.create();
                    ProviderRejectedException rejected = new ProviderRejectedException("content rejected");
                    InvalidResponseException invalid = new InvalidResponseException("unreadable response");
                    ActionProbe<Ticket, TicketState, TicketChange> probe =
                            ActionProbe.<Ticket, TicketState, TicketChange>create("publish", trace)
                                    .thenReturn(ActionOutcome.failed(PublicationFailures.Unavailable.class,
                                            "provider down"))
                                    .thenThrow(rejected)
                                    .thenReturn(ActionOutcome.failed("invalid response"))
                                    .thenThrow(invalid);
                    GraphDefinition<Ticket, TicketState, TicketChange> definition =
                            graph((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                                    publish.action("publish", probe));

                    // The declared rules, read back from the definition in declaration order.
                    List<Class<? extends Throwable>> unavailableTypes = List.of(ServiceUnavailableException.class);
                    List<Class<? extends Throwable>> rejectedTypes = List.of(ProviderRejectedException.class);
                    List<RecoveryRule<TicketChange>> rules = List.of(
                            new RecoveryRule<>(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                    unavailableTypes),
                            new RecoveryRule<>(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                                    rejectedTypes));
                    GraphAssertions.assertThat(definition)
                            .transition(TicketState.READY, TicketChange.PUBLISH)
                            .isAction()
                            .hasActionNamed("publish")
                            .recoversExactly(rules)
                            .failsOnExactly(InvalidResponseException.class)
                            .hasFailureTransition(TicketChange.MARK_INTEGRATION_FAILED);

                    // A returned kind selects its rule.
                    StateMachine<Ticket, TicketState, TicketChange> machine = machine(definition);
                    Outcome<Ticket, TicketState, TicketChange> routedByKind =
                            machine.execute(new Ticket(), TicketChange.PUBLISH);
                    GraphAssertions.assertThat(routedByKind)
                            .isFailed()
                            .hasStatus(OutcomeStatus.FAILED)
                            .hasCode(OutcomeCode.FAILURE_ROUTED)
                            .hasFailureKind(PublicationFailures.Unavailable.class)
                            .hasFailureTransition(TicketChange.SCHEDULE_RETRY)
                            .movedTo(TicketState.RETRY_PENDING)
                            .hasReason("provider down")
                            .hasNoCause()
                            .hasEntityState((Ticket ticket) -> ticket.state, TicketState.RETRY_PENDING);
                    Class<? extends FailureKind> kind =
                            routedByKind.asFailed().orElseThrow().failureKind().orElseThrow();
                    if (kind != PublicationFailures.Unavailable.class) {
                        throw new AssertionError("the published outcome must carry its failure kind: " + routedByKind);
                    }
                    boolean refused = false;
                    try {
                        GraphAssertions.assertThat(routedByKind).isFailed()
                                .hasFailureKind(PublicationFailures.Rejected.class);
                    } catch (GraphAssertionError expected) {
                        refused = true;
                    }
                    if (!refused) {
                        throw new AssertionError("hasFailureKind must refuse another failure kind");
                    }

                    // A thrown type selects the rule that declares it, and the cause keeps its identity.
                    GraphAssertions.assertThat(machine.execute(new Ticket(), TicketChange.PUBLISH))
                            .isFailed()
                            .hasFailureKind(PublicationFailures.Rejected.class)
                            .hasFailureTransition(TicketChange.RECORD_REJECTION)
                            .movedTo(TicketState.REJECTED_BY_PROVIDER)
                            .hasCauseSameAs(rejected)
                            .hasNoExplanation();

                    // A plain returned failure and a failsOn type take the fallback, without a kind.
                    GraphAssertions.assertThat(machine.execute(new Ticket(), TicketChange.PUBLISH))
                            .isFailed()
                            .hasNoFailureKind()
                            .hasFailureTransition(TicketChange.MARK_INTEGRATION_FAILED)
                            .movedTo(TicketState.INTEGRATION_FAILED)
                            .hasNoCause()
                            .hasExplanation("invalid response");
                    GraphAssertions.assertThat(machine.execute(new Ticket(), TicketChange.PUBLISH))
                            .isFailed()
                            .hasNoFailureKind()
                            .hasFailureTransition(TicketChange.MARK_INTEGRATION_FAILED)
                            .movedTo(TicketState.INTEGRATION_FAILED)
                            .hasCauseSameAs(invalid);
                    probe.calls().assertCount(4);
                    ActionOutcome returnedKind =
                            ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected");
                    GraphAssertions.assertThat(returnedKind)
                            .isFailed()
                            .hasReason("content rejected")
                            .hasFailureKind(PublicationFailures.Rejected.class);
                    GraphAssertions.assertThat(ActionOutcome.failed("invalid response")).isFailed().hasNoFailureKind();

                    // The snapshot carries the rules by index, and an edge without rules adds no subtree.
                    GraphSnapshot snapshot = GraphSnapshot.capture(definition);
                    Map<String, String> fields = snapshot.fields();
                    String recoveries = "/transitions/READY/PUBLISH/recoveries/";
                    expect(fields, recoveries + "count", "2");
                    expect(fields, recoveries + "0/kind", PublicationFailures.Unavailable.class.getName());
                    expect(fields, recoveries + "0/transition", "SCHEDULE_RETRY");
                    expect(fields, recoveries + "0/failsOn/count", "1");
                    expect(fields, recoveries + "0/failsOn/0", ServiceUnavailableException.class.getName());
                    expect(fields, recoveries + "1/kind", PublicationFailures.Rejected.class.getName());
                    expect(fields, recoveries + "1/transition", "RECORD_REJECTION");
                    expect(fields, recoveries + "1/failsOn/count", "1");
                    expect(fields, recoveries + "1/failsOn/0", ProviderRejectedException.class.getName());
                    int recoveryPaths = 0;
                    for (String path : fields.keySet()) {
                        if (path.startsWith(recoveries)) {
                            recoveryPaths++;
                        } else if (path.contains("/recoveries/")) {
                            throw new AssertionError("an edge without rules must add no recoveries subtree: " + path);
                        }
                    }
                    if (recoveryPaths != 9 || !GraphSnapshot.parse(snapshot.serialize()).fields().equals(fields)) {
                        throw new AssertionError("the snapshot must hold and read back the two rules: " + fields);
                    }

                    // The DOT draws one fragment per rule, in declaration order, before the fallback.
                    String dot = GraphvizExporter.render(GraphView.of(definition));
                    String label = "PUBLISH publish"
                            + " [recover RecoveryConsumer$PublicationFailures$Unavailable -> SCHEDULE_RETRY]"
                            + " [recover RecoveryConsumer$PublicationFailures$Rejected -> RECORD_REJECTION]"
                            + " [onFailure MARK_INTEGRATION_FAILED]";
                    int fragments = 0;
                    for (int at = dot.indexOf("[recover "); at >= 0; at = dot.indexOf("[recover ", at + 1)) {
                        fragments++;
                    }
                    if (!dot.contains(label) || fragments != 2) {
                        throw new AssertionError("the published DOT must draw each rule once, on its edge: " + dot);
                    }

                    // An asynchronous action selects the same routes, through a public asynchronous call.
                    try (ControlledAsyncAction<Ticket, TicketState, TicketChange> controlled =
                            ControlledAsyncAction.create()) {
                        StateMachine<Ticket, TicketState, TicketChange> asyncMachine =
                                machine(graph((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                                        publish.asyncAction("publish", controlled)));
                        CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> completion = null;
                        CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> failing = null;
                        PendingInvocation<ActionCall<Ticket, TicketState, TicketChange>, ActionOutcome> pending = null;
                        PendingInvocation<ActionCall<Ticket, TicketState, TicketChange>, ActionOutcome> \
            failingPending = null;
                        try {
                            Ticket returned = new Ticket();
                            completion = asyncMachine.async().execute(returned, TicketChange.PUBLISH);
                            pending = controlled.awaitInvocation(BUDGET);
                            ActionCall<Ticket, TicketState, TicketChange> held = pending.call();
                            if (held.entity() != returned || held.transition() != TicketChange.PUBLISH) {
                                throw new AssertionError(\
            "the controlled action must record the call it holds: " + held);
                            }
                            pending.complete(\
            ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected"));
                            AsyncAssertions.await(completion, BUDGET)
                                    .isFailed()
                                    .hasFailureKind(PublicationFailures.Rejected.class)
                                    .hasFailureTransition(TicketChange.RECORD_REJECTION)
                                    .movedTo(TicketState.REJECTED_BY_PROVIDER)
                                    .hasNoCause();
                            ServiceUnavailableException down = new ServiceUnavailableException("provider down");
                            failing = asyncMachine.async().execute(new Ticket(), TicketChange.PUBLISH);
                            failingPending = controlled.awaitInvocation(BUDGET);
                            failingPending.fail(down);
                            AsyncAssertions.await(failing, BUDGET)
                                    .isFailed()
                                    .hasFailureKind(PublicationFailures.Unavailable.class)
                                    .hasFailureTransition(TicketChange.SCHEDULE_RETRY)
                                    .movedTo(TicketState.RETRY_PENDING)
                                    .hasCauseSameAs(down);
                        } finally {
                            if (pending != null) pending.cancelStage();
                            if (failingPending != null) failingPending.cancelStage();
                            if (completion != null) completion.cancel(true);
                            if (failing != null) failing.cancel(true);
                        }
                    }
                    System.out.println("TYPED_RECOVERY_OK");
                }
            }
            """;

    /**
     * The sub-machine assertions as a consumer writes them with the DSL, run from the jars: the binding read off the
     * definition, the start a call reports, the start an after-commit failure keeps, and the two refusals of a
     * sub-machine.
     */
    private static final String SUBMACHINE_CONSUMER_SOURCE = """
            package published.submachines;

            import static io.github.graphmaquina.test.ExitExpectation.exit;
            import static io.github.graphmaquina.test.GraphAssertions.assertThat;
            import static io.github.graphmaquina.test.GraphAssertions.captureFailure;

            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.MachineKey;
            import io.github.graphmaquina.dsl.StateGraph;
            import io.github.graphmaquina.runtime.MachineRegistry;
            import io.github.graphmaquina.runtime.StateMachines;
            import io.github.graphmaquina.test.StartsAssert;
            import java.util.List;
            import java.util.Optional;

            /** A ticket whose REVIEW holds its check, asserted through the published jars alone. */
            public final class SubmachineConsumer {
                private SubmachineConsumer() { }

                /** The states of a ticket. */
                enum TicketState { OPEN, REVIEW, DONE, DROPPED }

                /** The transition identifiers of a ticket; ACCEPT and REJECT are the exits of its check. */
                enum TicketChange { SUBMIT, ACCEPT, REJECT, DROP }

                /** The states of a check. */
                enum CheckState { RUNNING, PASSED, FAILED }

                /** The transition identifiers of a check. */
                enum CheckChange { PASS, FAIL }

                /** The check of a ticket, which knows its ticket once the ticket is created. */
                static final class Check {
                    CheckState state = CheckState.RUNNING;
                    Optional<Ticket> ticket = Optional.empty();
                }

                /** A ticket, which its check decides while the ticket is in REVIEW. */
                static final class Ticket {
                    final Check check;
                    TicketState state = TicketState.OPEN;

                    Ticket(Check check) {
                        this.check = check;
                        check.ticket = Optional.of(this);
                    }
                }

                static final MachineKey<Ticket, TicketState, TicketChange> TICKETS =
                        MachineKey.of(Ticket.class, TicketState.class, TicketChange.class);

                static final MachineKey<Check, CheckState, CheckChange> CHECKS =
                        MachineKey.of(Check.class, CheckState.class, CheckChange.class);

                static GraphDefinition<Check, CheckState, CheckChange> checks() {
                    return StateGraph.define(CHECKS).initial(CheckState.RUNNING)
                            .terminal(CheckState.PASSED).terminal(CheckState.FAILED)
                            .statePolicy(CheckState.RUNNING, "the check passes or fails")
                            .transition(CheckChange.PASS, CheckState.RUNNING, CheckState.PASSED).endTransition()
                            .transition(CheckChange.FAIL, CheckState.RUNNING, CheckState.FAILED).endTransition()
                            .build();
                }

                /** The ticket graph over {@code checks}; SUBMIT runs {@code audit} after its commit. */
                static GraphDefinition<Ticket, TicketState, TicketChange> tickets(
                        GraphDefinition<Check, CheckState, CheckChange> checks, Runnable audit) {
                    return StateGraph.define(TICKETS).initial(TicketState.OPEN)
                            .terminal(TicketState.DONE).terminal(TicketState.DROPPED)
                            .statePolicy(TicketState.OPEN, "submit the ticket first")
                            .statePolicy(TicketState.REVIEW, "the check decides")
                            .transition(TicketChange.SUBMIT, TicketState.OPEN, TicketState.REVIEW)
                            .afterCommit("audit", (Ticket ticket) -> audit.run()).endTransition()
                            .transition(TicketChange.ACCEPT, TicketState.REVIEW, TicketState.DONE).endTransition()
                            .transition(TicketChange.REJECT, TicketState.REVIEW, TicketState.DROPPED).endTransition()
                            .transition(TicketChange.DROP, TicketState.REVIEW, TicketState.DROPPED).endTransition()
                            .submachine(TicketState.REVIEW, checks, (Ticket ticket) -> Optional.of(ticket.check),
                                    (Check check) -> check.ticket)
                            .exit(CheckState.PASSED, TicketChange.ACCEPT)
                            .exit(CheckState.FAILED, TicketChange.REJECT)
                            .endSubmachine()
                            .build();
                }

                static MachineRegistry registry(Runnable audit) {
                    GraphDefinition<Check, CheckState, CheckChange> checks = checks();
                    return MachineRegistry.builder()
                            .add(StateMachines.from(tickets(checks, audit))
                                    .state((Ticket ticket) -> ticket.state,
                                            (Ticket ticket, TicketState state) -> ticket.state = state)
                                    .identity((Ticket ticket) -> ticket))
                            .add(StateMachines.from(checks)
                                    .state((Check check) -> check.state,
                                            (Check check, CheckState state) -> check.state = state)
                                    .identity((Check check) -> check))
                            .build();
                }

                public static void main(String[] args) {
                    assertThat(tickets(checks(), () -> { }))
                            .hasNoSubmachine(TicketState.OPEN)
                            .submachine(TicketState.REVIEW, CHECKS)
                            .hasExitsExactly(exit(CheckState.PASSED, TicketChange.ACCEPT),
                                    exit(CheckState.FAILED, TicketChange.REJECT))
                            .hasExit(CheckState.FAILED, TicketChange.REJECT);
                    Check check = new Check();
                    Ticket ticket = new Ticket(check);
                    try (MachineRegistry registry = registry(() -> { })) {
                        assertThat(registry.of(TICKETS).execute(ticket, TicketChange.SUBMIT)).isApplied()
                                .starts().hasSize(1)
                                .forChild(CHECKS, check).single()
                                .startedAt(CheckState.RUNNING).hadPrevious(CheckState.RUNNING)
                                .byParent(TICKETS, ticket, TicketState.REVIEW).atDepth(0);
                        assertThat(registry.of(TICKETS).execute(ticket, TicketChange.ACCEPT)).isRejected()
                                .isReservedExit().hasReason("ACCEPT leaves REVIEW only when its sub-machine ends");
                        assertThat(registry.of(TICKETS).execute(ticket, TicketChange.DROP)).isApplied()
                                .starts().isEmpty();
                        assertThat(registry.of(CHECKS).execute(check, CheckChange.PASS)).isRejected()
                                .isDetached().hasReason("no parent holds it in REVIEW");
                    }
                    Check audited = new Check();
                    Ticket failing = new Ticket(audited);
                    try (MachineRegistry registry = registry(() -> {
                        throw new IllegalStateException("audit down");
                    })) {
                        assertThat(captureFailure(() -> registry.of(TICKETS).execute(failing, TicketChange.SUBMIT)))
                                .isAfterCommit().starts().forChild(CHECKS, audited).single()
                                .byParent(TICKETS, failing, TicketState.REVIEW);
                    }
                    StartsAssert.assertThat(List.of()).isEmpty();
                    System.out.println("SUBMACHINE_ASSERTIONS_OK");
                }
            }
            """;

    /** A standalone consumer that reports its PID before holding the runner in a process wait. */
    private static final String INTERRUPTED_CONSUMER_SOURCE = """
            package published.recovery;

            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.concurrent.CountDownLatch;

            /** Reports its PID to the parent test, then waits until that test terminates this JVM. */
            public final class WaitingConsumer {
                private WaitingConsumer() { }

                public static void main(String[] args) throws Exception {
                    Path directory = Path.of(args[0]);
                    Path written = Files.writeString(directory.resolve("consumer.pid.tmp"),
                            Long.toString(ProcessHandle.current().pid()));
                    Files.move(written, directory.resolve("consumer.pid"));
                    new CountDownLatch(1).await();
                }
            }
            """;

    private static final List<String> SUFFIXES = List.of(".jar", ".pom", "-sources.jar", "-javadoc.jar");

    @Test
    void everyPublishedFileExistsAndCarriesContent() throws IOException {
        for (String module : PublishedModules.ALL) {
            for (String suffix : SUFFIXES) {
                Path artifact = artifact(module, suffix);
                assertTrue(Files.isRegularFile(artifact), "missing published artifact: " + artifact);
                assertTrue(Files.size(artifact) > 0L, "empty published artifact: " + artifact);
            }
            assertTrue(carriesAnEntryEndingWith(module, ".jar", ".class"),
                    module + ": the binary jar must carry compiled classes");
            assertTrue(carriesAnEntryEndingWith(module, "-sources.jar", ".java"),
                    module + ": the sources jar must carry sources, not just a manifest");
            assertTrue(carriesAnEntryEndingWith(module, "-javadoc.jar", ".html"),
                    module + ": the javadoc jar must carry rendered documentation, not just a manifest");
        }
        assertTrue(entries("graphmaquina-test", ".jar").contains("io/github/graphmaquina/test/GraphAssertions.class"),
                "the binary jar must carry the compiled facade");
        assertTrue(entries("graphmaquina-test", "-sources.jar").contains("io/github/graphmaquina/test/GraphAssertions.java"),
                "the sources jar must carry the sources, not just a manifest");
        assertTrue(entries("graphmaquina-test", "-javadoc.jar").contains("io/github/graphmaquina/test/GraphAssertions.html"),
                "the javadoc jar must carry the rendered documentation, not just a manifest");
    }

    /**
     * The Gradle plugin jar is the one that declares no name, because its build configures no manifest attribute
     * for it; that is asserted for it instead of leaving the jar out. The test visits the modules listed in
     * {@code PublishedModules.ALL}: a listed module with no arm in {@code expectedAutomaticModuleName} fails it
     * with an {@link AssertionError} naming that module, and a module that is published but not listed is not
     * checked at all.
     */
    @Test
    void everyJarDeclaresItsAutomaticModuleNameExceptTheGradlePluginJarWhichHasNone() throws IOException {
        for (String module : PublishedModules.ALL) {
            assertEquals(expectedAutomaticModuleName(module), Optional.ofNullable(automaticModuleName(module)),
                    module + ": the Automatic-Module-Name of the published jar");
        }
    }

    @Test
    void thePomPullsCoreAloneAtTheSameVersion() throws IOException, ParserConfigurationException, SAXException {
        assertEquals(List.of("io.github.graphmaquina:graphmaquina-core:" + version() + ":compile"),
                dependenciesOf(artifact("graphmaquina-test", ".pom")),
                "a consumer must inherit core and nothing else: no JUnit, AssertJ, Gson, codegen or processor");
    }

    @Test
    void aConsumerCompilesAndRunsAgainstThePublishedJarsAlone(@TempDir Path workDir)
            throws IOException, InterruptedException {
        String jars = artifact("graphmaquina-core", ".jar") + File.pathSeparator + artifact("graphmaquina-test", ".jar");
        runConsumer(workDir, jars);
    }

    /**
     * Typed recovery as the jars promise it, compiled and run against the core and test jars alone. The consumer
     * renders DOT, so it runs against the complete core jar only.
     */
    @Test
    void aRecoveryConsumerCompilesAndRunsAgainstThePublishedJarsAlone(@TempDir Path workDir)
            throws IOException, InterruptedException {
        String jars = artifact("graphmaquina-core", ".jar") + File.pathSeparator
                + artifact("graphmaquina-test", ".jar");
        runConsumer(workDir, jars, "published.recovery.RecoveryConsumer", RECOVERY_CONSUMER_SOURCE,
                "TYPED_RECOVERY_OK");
    }

    /** The sub-machine assertions as the jars promise them, compiled and run against the core and test jars alone. */
    @Test
    void aSubmachineConsumerCompilesAndRunsAgainstThePublishedJarsAlone(@TempDir Path workDir)
            throws IOException, InterruptedException {
        String jars = artifact("graphmaquina-core", ".jar") + File.pathSeparator
                + artifact("graphmaquina-test", ".jar");
        runConsumer(workDir, jars, "published.submachines.SubmachineConsumer", SUBMACHINE_CONSUMER_SOURCE,
                "SUBMACHINE_ASSERTIONS_OK");
    }

    /** An interrupted parent must not leave the published consumer JVM alive. */
    @Test
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void interruptedConsumerWaitDestroysItsChild(@TempDir Path workDir) throws Exception {
        String jars = artifact("graphmaquina-core", ".jar") + File.pathSeparator
                + artifact("graphmaquina-test", ".jar");
        Path pidFile = workDir.resolve("consumer.pid");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        AtomicReference<Process> launched = new AtomicReference<>();
        CountDownLatch launchObserved = new CountDownLatch(1);
        Thread runner = new Thread(() -> {
            try {
                runConsumer(workDir, jars, "published.recovery.WaitingConsumer",
                        INTERRUPTED_CONSUMER_SOURCE, "NEVER_PRINTED", (Process child) -> {
                            launched.set(child);
                            launchObserved.countDown();
                        });
            } catch (Throwable failure) {
                observed.set(failure);
            }
        }, "published-consumer-interruption");
        runner.setDaemon(true);
        long childPid = -1;
        try (WatchService watch = FileSystems.getDefault().newWatchService()) {
            workDir.register(watch, StandardWatchEventKinds.ENTRY_CREATE);
            runner.start();
            while (!Files.isRegularFile(pidFile)) {
                WatchKey event = watch.poll(30, TimeUnit.SECONDS);
                assertNotNull(event, "the child did not publish its PID");
                event.pollEvents();
                assertTrue(event.reset(), "the watched consumer directory became unavailable");
            }
            childPid = Long.parseLong(Files.readString(pidFile, UTF_8));
            assertTrue(launchObserved.await(30, TimeUnit.SECONDS), "the runner did not report its launched child");
            Process owned = launched.get();
            assertNotNull(owned, "the fixture did not report the Process it launched");
            assertEquals(childPid, owned.pid(), "the PID file must come from the owned child");
            runner.interrupt();
            runner.join(10_000);
            assertFalse(runner.isAlive(), "the interrupted runner did not finish its bounded cleanup");
            assertTrue(observed.get() instanceof InterruptedException,
                    "the interrupted wait must propagate its original exception: " + observed.get());
            assertFalse(owned.isAlive(), "the interrupted runner left its consumer JVM alive: " + owned.pid());
        } finally {
            runner.interrupt();
            Process owned = launched.get();
            if (owned != null && owned.isAlive()) {
                owned.destroyForcibly();
                try {
                    owned.waitFor(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            runner.join(10_000);
        }
    }

    @Test
    void semanticAssertionsRunWithoutAnyGraphicExporter(@TempDir Path workDir)
            throws IOException, InterruptedException {
        Path core = workDir.resolve("core-without-exporters.jar");
        try (JarFile original = new JarFile(artifact("graphmaquina-core", ".jar").toFile());
                JarOutputStream filtered = new JarOutputStream(Files.newOutputStream(core))) {
            Enumeration<JarEntry> entries = original.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("io/github/graphmaquina/export/")) {
                    continue;
                }
                filtered.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream content = original.getInputStream(entry)) {
                    content.transferTo(filtered);
                }
                filtered.closeEntry();
            }
        }
        runConsumer(workDir, core + File.pathSeparator + artifact("graphmaquina-test", ".jar"));
    }

    private static void runConsumer(Path workDir, String jars) throws IOException, InterruptedException {
        runConsumer(workDir, jars, "PublishedConsumer", CONSUMER_SOURCE, "PUBLIC_API_OK");
    }

    /**
     * Compiles {@code source}, whose public class is {@code mainClass}, against {@code jars} alone, then runs that
     * class in a separate JVM whose classpath holds the compiled consumer and those jars and nothing else, with
     * {@code workDir} as its only argument, and expects it to exit normally after printing {@code marker}. The
     * runner waits at most two minutes; on interruption, timeout or another failure, it attempts to forcibly stop a
     * live child and waits up to ten seconds to reap it, suppressing cleanup errors onto the original failure.
     */
    private static void runConsumer(Path workDir, String jars, String mainClass, String source, String marker)
            throws IOException, InterruptedException {
        runConsumer(workDir, jars, mainClass, source, marker, (Process ignored) -> { });
    }

    /** The same runner, with a test-only callback that observes the exact child {@link Process} after launch. */
    private static void runConsumer(Path workDir, String jars, String mainClass, String source, String marker,
            Consumer<Process> onStart) throws IOException, InterruptedException {
        Path classes = Files.createDirectories(workDir.resolve("classes"));
        List<String> diagnostics = compile(workDir, jars, classes, mainClass, source);
        assertEquals(List.of(), diagnostics, "the consumer source must compile against the published jars");

        Path transcript = workDir.resolve("consumer-output.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath", classes + File.pathSeparator + jars, mainClass, workDir.toString())
                .redirectErrorStream(true)
                .redirectOutput(transcript.toFile())
                .start();
        Throwable originalFailure = null;
        try {
            onStart.accept(process);
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                fail("the consumer process did not terminate");
            }
            String output = Files.readString(transcript, UTF_8);
            assertEquals(0, process.exitValue(), "the consumer process failed: " + output);
            String lastLine = output.lines().reduce((String earlier, String later) -> later).orElse("");
            assertEquals(marker, lastLine, "the consumer did not print its marker on the last line: " + output);
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    boolean reaped = false;
                    boolean cleanupInterrupted = false;
                    while (!reaped) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            break;
                        }
                        try {
                            reaped = process.waitFor(remaining, TimeUnit.NANOSECONDS);
                        } catch (InterruptedException interrupted) {
                            cleanupInterrupted = true;
                        }
                    }
                    if (cleanupInterrupted) {
                        Thread.currentThread().interrupt();
                    }
                    if (!reaped) {
                        throw new AssertionError("the consumer process remained alive after forcible destruction");
                    }
                }
            } catch (RuntimeException | Error cleanupFailure) {
                if (originalFailure == null) {
                    throw cleanupFailure;
                }
                originalFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * Compiles {@code source}, written to a file named after the simple name of {@code mainClass}, with the
     * published jars as its entire classpath; returns what javac reported.
     */
    private static List<String> compile(Path workDir, String jars, Path classes, String mainClass, String source)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "this smoke test needs a JDK compiler, not a JRE");
        String simpleName = mainClass.substring(mainClass.lastIndexOf('.') + 1);
        Path file = Files.writeString(workDir.resolve(simpleName + ".java"), source);
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(collector, null, UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(List.of(file));
            List<String> options = List.of("--release", "21", "-proc:none", "-Xlint:all,-serial", "-Werror",
                    "-classpath", jars, "-d", classes.toString());
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

    /** Each dependency of the POM as {@code group:artifact:version:scope}, in declaration order. */
    private static List<String> dependenciesOf(Path pom) throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        NodeList declared = builder.parse(pom.toFile()).getDocumentElement().getElementsByTagName("dependency");
        List<String> dependencies = new ArrayList<>();
        for (int index = 0; index < declared.getLength(); index++) {
            Element dependency = (Element) declared.item(index);
            dependencies.add(text(dependency, "groupId") + ":" + text(dependency, "artifactId") + ":"
                    + text(dependency, "version") + ":" + text(dependency, "scope"));
        }
        return dependencies;
    }

    private static String text(Element dependency, String field) {
        NodeList matches = dependency.getElementsByTagName(field);
        Node first = matches.getLength() == 0 ? null : matches.item(0);
        return first == null ? "" : first.getTextContent().trim();
    }

    /**
     * What the jar of {@code module} has to declare; the Gradle plugin's build configures no name, so it is empty.
     * A module with no arm here raises an {@link AssertionError} that names it.
     */
    private static Optional<String> expectedAutomaticModuleName(String module) {
        return switch (module) {
            case "graphmaquina-core" -> Optional.of("io.github.graphmaquina.core");
            case "graphmaquina-codegen" -> Optional.of("io.github.graphmaquina.codegen");
            case "graphmaquina-processor" -> Optional.of("io.github.graphmaquina.processor");
            case "graphmaquina-test" -> Optional.of("io.github.graphmaquina.test");
            case "graphmaquina-maven-plugin" -> Optional.of("io.github.graphmaquina.maven");
            case "graphmaquina-gradle-plugin" -> Optional.empty();
            default -> throw new AssertionError(module + " has no Automatic-Module-Name expectation: list it in "
                    + "expectedAutomaticModuleName");
        };
    }

    private static String automaticModuleName(String module) throws IOException {
        try (JarFile jar = new JarFile(artifact(module, ".jar").toFile())) {
            return jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name");
        }
    }

    private static List<String> entries(String module, String suffix) throws IOException {
        try (JarFile jar = new JarFile(artifact(module, suffix).toFile())) {
            List<String> names = new ArrayList<>();
            Enumeration<JarEntry> found = jar.entries();
            while (found.hasMoreElements()) {
                names.add(found.nextElement().getName());
            }
            return names;
        }
    }

    private static boolean carriesAnEntryEndingWith(String module, String suffix, String extension)
            throws IOException {
        return entries(module, suffix).stream().anyMatch((String name) -> name.endsWith(extension));
    }

    private static Path artifact(String module, String suffix) {
        String release = version();
        return repository().resolve("io/github/graphmaquina/" + module + "/" + release + "/"
                + module + "-" + release + suffix);
    }

    private static Path repository() {
        return Path.of(property("artifactRepository"));
    }

    private static String version() {
        return property("artifactVersion");
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "this smoke test reads published files and needs the " + name
                + " system property; run it through :graphmaquina-test:publishedArtifactTest");
        return value;
    }
}
