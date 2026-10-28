package io.github.graphmaquina.internal;

import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CallRequest;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The bridge through which the runtime sets the flow of a call failure: {@code CallFailedException} registers it once,
 * from its static initializer, so a failure starts with the empty flow and a second registration is refused without
 * replacing it. A call failure, flow included, is not serializable.
 */
class CallFailureFlowsTest {

    private static final FlowKey<String> BATCH = FlowKey.of("batch", String.class);

    /** An abort of a call on {@code doc}, built by hand: building it initializes {@code CallFailedException}. */
    private static TransitionAbortedException abortOf(Doc doc) {
        AbortDetails details = new AbortDetails(DOCS, doc, Optional.of(DRAFT), Optional.of(SUBMIT), Optional.empty(),
                Optional.empty(), List.of(), List.of());
        return new TransitionAbortedException(new MachineCall(DOCS, doc,
                CallRequest.EXECUTE, Optional.of(SUBMIT), CallOrigin.APPLICATION),
                CallPhase.ACTION, details, "boom");
    }

    @Test
    void aFailureStartsWithTheEmptyFlowAndTheBridgeAttachesAnother() {
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException failure = abortOf(doc);
        assertSame(FlowView.empty(), failure.flow());
        FlowView flow = new FrozenFlow(Optional.of(doc), Map.of(BATCH, "b-1"));
        CallFailureFlows.attach(failure, flow);
        assertSame(flow, failure.flow());
        assertEquals("failure is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> CallFailureFlows.attach(null, flow)).getMessage());
        assertEquals("flow is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> CallFailureFlows.attach(failure, null)).getMessage());
        assertSame(flow, failure.flow());
    }

    /** {@code CallFailedException} registered first, so any later registration fails and the bridge stays its own. */
    @Test
    void aSecondRegistrationIsRefusedAndKeepsTheBridge() {
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException failure = abortOf(doc);
        List<String> replaced = new ArrayList<>();
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> CallFailureFlows.register(
                (CallFailedException other, FlowView flow) -> replaced.add("attached")));
        assertEquals("already registered", refused.getMessage());
        assertEquals("access is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> CallFailureFlows.register(null)).getMessage());
        FlowView flow = new FrozenFlow(Optional.of(doc), Map.of(BATCH, "b-1"));
        CallFailureFlows.attach(failure, flow);
        assertSame(flow, failure.flow());
        assertEquals(List.of(), replaced);
    }

    /**
     * Writing a call failure fails, as {@code StateMachineException} says: its call is not serializable, and neither is
     * its flow. So no deserialized copy of a failure that the library threw exists, with or without a flow.
     */
    @Test
    void aCallFailureCannotBeSerialized() {
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException failure = abortOf(doc);
        CallFailureFlows.attach(failure, new FrozenFlow(Optional.of(doc), Map.of(BATCH, "b-1")));
        assertEquals(MachineCall.class.getName(), refusedToWrite(failure).getMessage());
        assertEquals(FrozenFlow.class.getName(), refusedToWrite(failure.flow()).getMessage());
        assertEquals(FrozenFlow.class.getName(), refusedToWrite(FlowView.empty()).getMessage());
    }

    /** What writing {@code value} to a stream of its own throws. */
    private static NotSerializableException refusedToWrite(Object value) {
        return assertThrows(NotSerializableException.class, () -> {
            try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
                out.writeObject(value);
            }
        });
    }
}
