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

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CallRequest;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 tells the call how far it got in writing states. While a setter runs, the states may be partially written;
 * once the last setter returned, the call is in HOOK with every state written, before the root builds the records and
 * starts it committed. An Error that no application code throws, running out of memory while those lists are built
 * for instance, then reports every state written too.
 */
class CommitPhaseTest {

    /** DRAFT -SUBMIT-> REVIEW -PUBLISH-> PUBLISHED -ARCHIVE-> ARCHIVED, with DRAFT -ARCHIVE-> ARCHIVED too. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> graph() {
        return StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(ARCHIVE, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    @Test
    void onceItsLastSetterReturnedTheCallReportsEveryStateWritten() {
        Doc doc = new Doc("1", DRAFT);
        List<Transaction> running = new ArrayList<>();
        List<CallFailure> inTheSetter = new ArrayList<>();
        OutOfMemoryError during = new OutOfMemoryError("while the setter runs");
        MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph()).state(Doc::status, (Doc entity, DocStatus state) -> {
                    inTheSetter.add(running.getFirst().failureOf(during));
                    entity.setStatus(state);
                }).identity(Doc::id))
                .build();
        Transaction tx = new Transaction(registry, new UnitOfWork(10, Long.MAX_VALUE), DOCS, doc, Optional.of(SUBMIT),
                () -> new MachineCall(DOCS, doc, CallRequest.EXECUTE, Optional.of(SUBMIT), CallOrigin.APPLICATION),
                Optional.empty(), CallLog.OFF);
        running.add(tx);
        tx.transition(registry.machineFor(DOCS), doc, SUBMIT, 0);
        tx.enter(CallPhase.COMMIT);

        Commit.apply(registry, tx);

        OutOfMemoryError after = new OutOfMemoryError("once the last setter returned");
        assertEquals(REVIEW, doc.status());
        assertEquals(List.of(new CallFailure(CallPhase.COMMIT, StatesWritten.PARTIAL, during)), inTheSetter);
        assertEquals(new CallFailure(CallPhase.HOOK, StatesWritten.ALL, after), tx.failureOf(after));
    }
}
