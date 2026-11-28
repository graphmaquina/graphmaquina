package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocGraphs;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * Pins which request an eligibility check reads the clock in: every read of the machine's clock and every instant a
 * guard sees, in order, for a check of its own, for a check a guard of a call starts, for one a guard of a check
 * starts, and for a choice check whose first candidate is refused, so its second runs and starts a check too.
 */
class CheckRequestScopeCharacterizationTest {

    /** A clock that moves one second per read and writes each read to the log. */
    @RequiredArgsConstructor
    private static final class CountingClock implements InstantSource {
        private final StringBuilder log;
        private long seconds;

        @Override
        public Instant instant() {
            seconds++;
            log.append("clock").append(seconds).append(' ');
            return Instant.ofEpochSecond(seconds);
        }
    }

    @Test
    void everyClockReadOfAnEligibilityCheckKeepsItsRequest() {
        StringBuilder log = new StringBuilder();
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc peer = new Doc("peer", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(DocGraphs.docs(
                submit -> submit.guard("nested", (GuardContext<Doc, DocStatus, DocTransition> context) -> {
                    log.append("submit:").append(context.now().getEpochSecond()).append(' ');
                    if (context.entity() != peer) {
                        self.get().eligibility(peer, DISCARD);
                    }
                    return GuardResult.pass();
                }),
                discard -> discard.guard("plain", (GuardContext<Doc, DocStatus, DocTransition> context) -> {
                    log.append("discard:").append(context.now().getEpochSecond()).append(' ');
                    return context.entity().id().equals("3") ? GuardResult.reject("not this one") : GuardResult.pass();
                })))
                .state(Doc::status, Doc::setStatus).clock(new CountingClock(log)).build();
        self.set(machine);
        machine.eligibility(peer, DISCARD);
        String own = drain(log);
        machine.execute(new Doc("1", DRAFT), SUBMIT);
        String fromCall = drain(log);
        machine.eligibility(new Doc("2", DRAFT), SUBMIT);
        String fromCheck = drain(log);
        machine.chooseEligibility(new Doc("3", DRAFT), List.of(DISCARD, SUBMIT));
        String choice = drain(log);
        assertEquals("discard:clock1 1 ", own);
        assertEquals("submit:clock2 2 discard:2 ", fromCall);
        assertEquals("submit:clock3 3 discard:3 ", fromCheck);
        assertEquals("discard:clock4 4 submit:4 discard:4 ", choice);
    }

    private static String drain(StringBuilder log) {
        String drained = log.toString();
        log.setLength(0);
        return drained;
    }
}
