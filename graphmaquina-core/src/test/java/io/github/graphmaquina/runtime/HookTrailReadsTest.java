package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.TrailFixtures.recording;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.TickingClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The trail the post-commit hooks of one call read: every hook, whichever hop it runs for, and every read of one hook,
 * answer the whole committed trail of the call, equal to the outcome's; the list is unmodifiable; a hook's write to
 * the flow adds nothing to it; and reading it reads no clock, so the clock is read once per committed entry whatever
 * the hooks read.
 */
class HookTrailReadsTest {

    private static final FlowKey<String> NOTE = FlowKey.of("note", String.class);
    private static final int CHILDREN = 3;

    @Test
    void everyHookReadOfACascadeAnswersTheCommittedTrail() {
        TickingClock clock = new TickingClock();
        List<List<FlowEntry>> read = new ArrayList<>();
        List<Optional<String>> notes = new ArrayList<>();
        List<Doc> children = new ArrayList<>();
        for (int child = 0; child < CHILDREN; child++) {
            children.add(new Doc("c" + child, DRAFT));
        }
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .afterCommitWith("Audit", (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            read.add(commit.flow().trail());
                            commit.flow().put(NOTE, committed.id());
                            read.add(commit.flow().trail());
                            notes.add(commit.flow().get(NOTE));
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            for (Doc child : children) {
                                ctx.cascade(DOCS, child, SUBMIT);
                            }
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("Persist", (Doc committed, CommitContext<DocStatus, DocTransition> commit) ->
                                read.add(commit.flow().trail())));
        Outcome<Doc, DocStatus, DocTransition> outcome =
                recording(graph, clock).build().execute(new Doc("p", DRAFT), DISCARD);
        assertEquals(1 + CHILDREN, clock.reads());
        assertEquals(1 + 2 * CHILDREN, read.size());
        assertEveryReadIsTheCommittedTrail(outcome, read);
        assertEquals(List.of(Optional.of("c0"), Optional.of("c1"), Optional.of("c2")), notes);
        assertEquals(Optional.of("c2"), outcome.flow().get(NOTE));
    }

    /** Each of {@code read} equals the outcome's trail, which holds one entry per hop at the clock's next read. */
    private static void assertEveryReadIsTheCommittedTrail(Outcome<Doc, DocStatus, DocTransition> outcome,
                                                           List<List<FlowEntry>> read) {
        List<FlowEntry> trail = outcome.flow().trail();
        assertEquals(1 + CHILDREN, trail.size());
        for (List<FlowEntry> hookTrail : read) {
            assertEquals(trail, hookTrail);
            assertEquals(trail.hashCode(), hookTrail.hashCode());
            assertThrows(UnsupportedOperationException.class, () -> hookTrail.add(trail.get(0)));
            assertThrows(UnsupportedOperationException.class, () -> hookTrail.remove(0));
        }
        for (int hop = 0; hop < trail.size(); hop++) {
            FlowEntry.Hop entry = (FlowEntry.Hop) trail.get(hop);
            assertEquals(outcome.records().get(hop), entry.hop());
            assertEquals(TickingClock.at(hop + 1), entry.at());
        }
    }

    @Test
    void aHookOfACallThatRecordsNoTrailReadsTheEmptyTrail() {
        List<List<FlowEntry>> read = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .afterCommitWith("Audit", (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            read.add(commit.flow().trail());
                            read.add(commit.flow().trail());
                        }));
        StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id).build()
                .execute(new Doc("1", DRAFT), SUBMIT);
        assertEquals(List.of(List.of(), List.of()), read);
    }
}
