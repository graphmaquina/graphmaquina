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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An identity whose hashCode throws while COLLECT rolls an aborted element back stops that rollback; the action that
 * ran the cascade receives what stopped it as it receives any other failure that leaves a cascade: an abort of its own
 * step that names the cascade and carries it as its cause. The call still aborts with the element's abort.
 */
class CollectRollbackFailureTest {

    /** An identity whose hashCode runs {@link #onHash} once it is armed; it prints by name. */
    static final class ArmedId {
        private final String name;
        private Runnable onHash = () -> { };

        ArmedId(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ArmedId that && that.name.equals(name);
        }

        @Override
        public int hashCode() {
            onHash.run();
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    void anIdentityThatStopsACollectRollbackReachesTheActionAsAnAbortOfTheCascade() {
        Doc root = new Doc("root", DRAFT);
        Doc child = new Doc("child", REVIEW);
        ArmedId childId = new ArmedId("child");
        IllegalStateException hashFailure = new IllegalStateException("identity hash failed during rollback");
        List<RuntimeException> caught = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        try {
                            ctx.cascadeAll(DOCS, List.of(child), PUBLISH, CascadePolicy.COLLECT);
                        } catch (RuntimeException thrown) {
                            caught.add(thrown);
                        }
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .action("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        childId.onHash = () -> {
                            throw hashFailure;
                        };
                        throw new IllegalArgumentException("the child failed");
                    })
                    .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus).identity((Doc doc) -> doc == child ? childId : doc.id())).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertEquals(StatesWritten.NONE, thrown.statesWritten());
            assertTrue(thrown.getMessage().contains("the action Publish threw"), thrown.getMessage());
        }
        assertEquals(1, caught.size());
        TransitionAbortedException received = assertInstanceOf(TransitionAbortedException.class, caught.get(0));
        assertSame(hashFailure, received.getCause());
        assertTrue(received.getMessage().contains("the cascade to Doc/DocStatus failed: " + hashFailure),
                received.getMessage());
        assertEquals(List.of(DRAFT, REVIEW), List.of(root.status(), child.status()));
    }
}
