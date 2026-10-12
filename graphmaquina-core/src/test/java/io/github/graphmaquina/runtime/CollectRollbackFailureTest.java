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
import io.github.graphmaquina.fixtures.LibraryLog;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * An identity whose hashCode throws while COLLECT rolls an aborted element back stops that rollback, which is then a
 * failure that leaves the cascade like any other: an abort of the step that ran the cascade, which names the cascade
 * and carries what stopped the rollback as its cause, poisons the call in place of the element's abort, which it holds
 * as suppressed, reaches that step's action, and stops every enclosing COLLECT at once. The call aborts with it.
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
        try (LibraryLog log = LibraryLog.runtime(Level.FINER);
             MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus).identity((Doc doc) -> doc == child ? childId : doc.id())).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            List<String> lines = log.records().stream().map(LogRecord::getMessage).toList();
            assertTrue(lines.stream().noneMatch((String line) -> line.contains("collect-rollback")), lines.toString());
            assertTrue(lines.stream().anyMatch((String line) -> line.startsWith("call=")
                    && line.endsWith(" depth=0 poison error=" + thrown)), lines.toString());
            assertEquals(StatesWritten.NONE, thrown.statesWritten());
            assertEquals(1, caught.size());
            assertSame(caught.get(0), thrown);
            assertSame(hashFailure, thrown.getCause());
            assertTrue(thrown.getMessage().contains("the cascade to Doc/DocStatus failed: " + hashFailure),
                    thrown.getMessage());
            TransitionAbortedException element = assertInstanceOf(TransitionAbortedException.class,
                    thrown.getSuppressed()[0]);
            assertTrue(element.getMessage().contains("the action Publish threw"), element.getMessage());
        }
        assertEquals(List.of(DRAFT, REVIEW), List.of(root.status(), child.status()));
    }

    @Test
    void anIdentityThatStopsANestedCollectRollbackStopsTheEnclosingCollectToo() {
        Doc root = new Doc("root", DRAFT);
        Doc first = new Doc("first", DRAFT);
        Doc second = new Doc("second", DRAFT);
        Doc element = new Doc("element", REVIEW);
        ArmedId elementId = new ArmedId("element");
        IllegalStateException hashFailure = new IllegalStateException("identity hash failed during rollback");
        List<String> ran = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ran.add("submit " + ctx.entity().id());
                        try {
                            if (ctx.entity() == root) {
                                ctx.cascadeAll(DOCS, List.of(first, second), SUBMIT, CascadePolicy.COLLECT);
                            } else if (ctx.entity() == first) {
                                ctx.cascadeAll(DOCS, List.of(element), PUBLISH, CascadePolicy.COLLECT);
                            }
                        } catch (RuntimeException thrown) {
                            // the call is poisoned anyway
                        }
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .action("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ran.add("publish " + ctx.entity().id());
                        elementId.onHash = () -> {
                            elementId.onHash = () -> { };
                            throw hashFailure;
                        };
                        throw new IllegalArgumentException("the element failed");
                    })
                    .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus).identity((Doc doc) -> doc == element ? elementId : doc.id()))
                .build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertSame(hashFailure, thrown.getCause());
        }
        assertEquals(List.of("submit root", "submit first", "publish element"), ran);
        assertEquals(List.of(DRAFT, DRAFT, DRAFT, REVIEW),
                List.of(root.status(), first.status(), second.status(), element.status()));
    }
}
