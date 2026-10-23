package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.FlowKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The flow of a call against a reference that cannot be wrong for want of being simple: a list of every write, read
 * by scanning it, the way the flow was first written. Fixed-seed random sequences of puts, removes, hops, nested
 * savepoints and rollbacks, made as the engine makes them, each write at the size of the log, run on both; after each
 * operation, every key reads the same value, and the keys and the values come in the same order, each under a key of
 * the same visibility. The sequences read the flow both while it scans its few writes and once it has its index.
 */
class CallFlowModelTest {

    private static final List<FlowKey<?>> KEYS = List.of(
            FlowKey.of("a", String.class), FlowKey.visible("a", String.class), FlowKey.of("a", Integer.class),
            FlowKey.of("b", String.class), FlowKey.visible("c", Integer.class), FlowKey.of("c", Integer.class),
            FlowKey.of("d", String.class), FlowKey.of("e", Integer.class));

    /** One key and value as the flow lists them, with the visibility of the key, which equality ignores. */
    private record Listed(FlowKey<?> key, boolean visible, Object value) { }

    /** The reference: every write in order, a read scanning back to the last write of the key. */
    private static final class WriteLog {

        /** One put, with its value, or one remove, without, made when the log had {@code atLog} entries. */
        private record Write(FlowKey<?> key, Optional<Object> value, int atLog) { }

        private final List<Write> writes = new ArrayList<>();

        Optional<Object> get(FlowKey<?> key) {
            for (int index = writes.size() - 1; index >= 0; index--) {
                if (writes.get(index).key().equals(key)) {
                    return writes.get(index).value();
                }
            }
            return Optional.empty();
        }

        void write(FlowKey<?> key, Optional<Object> value, int atLog) {
            writes.add(new Write(key, value, atLog));
        }

        void rollbackTo(int savepoint) {
            int kept = writes.size();
            while (kept > 0 && writes.get(kept - 1).atLog() > savepoint) {
                kept--;
            }
            writes.subList(kept, writes.size()).clear();
        }

        List<Listed> values() {
            Map<FlowKey<?>, Write> latest = new LinkedHashMap<>();
            for (Write write : writes) {
                latest.remove(write.key());
                latest.put(write.key(), write);
            }
            List<Listed> listed = new ArrayList<>();
            for (Write write : latest.values()) {
                if (write.value().isPresent()) {
                    listed.add(new Listed(write.key(), write.key().visible(), write.value().get()));
                }
            }
            return listed;
        }
    }

    private static List<Listed> listed(CallFlow flow) {
        List<Listed> listed = new ArrayList<>();
        for (Map.Entry<FlowKey<?>, Object> entry : flow.values().entrySet()) {
            listed.add(new Listed(entry.getKey(), entry.getKey().visible(), entry.getValue()));
        }
        return listed;
    }

    private static List<Listed> listedKeys(List<Listed> values) {
        List<Listed> keys = new ArrayList<>();
        for (Listed value : values) {
            keys.add(new Listed(value.key(), value.visible(), "key"));
        }
        return keys;
    }

    private static List<Listed> keysOf(CallFlow flow) {
        List<Listed> keys = new ArrayList<>();
        for (FlowKey<?> key : flow.keys()) {
            keys.add(new Listed(key, key.visible(), "key"));
        }
        return keys;
    }

    /** A value of the type of {@code key}, distinct from every other value of the run. */
    private static Object valueFor(FlowKey<?> key, int serial) {
        return key.type() == String.class ? "v" + serial : Integer.valueOf(1_000 + serial);
    }

    private static void assertSameReads(WriteLog reference, CallFlow flow, String step) {
        for (FlowKey<?> key : KEYS) {
            assertEquals(reference.get(key), flow.get(key).map((Object value) -> value), step + " get " + key);
        }
        List<Listed> values = reference.values();
        assertEquals(values, listed(flow), step + " values");
        assertEquals(listedKeys(values), keysOf(flow), step + " keys");
    }

    /**
     * Runs {@code operations} random operations from {@code seed}. A step opens a savepoint at the size of the log,
     * then appends its hop; it closes by keeping what it did or by a rollback to its savepoint, innermost first. A
     * write is made at the size of the log, often of the key written just before, at times by a step that writes
     * nothing but that key. Now and then a rollback goes to any size of the log at or below the current one, as one
     * that an identity stopped midway does, and closes the steps it undid.
     */
    private static void run(long seed, int operations, Set<Boolean> indexed) {
        Simulation simulation = new Simulation(seed);
        for (int operation = 0; operation < operations; operation++) {
            int dice = simulation.rollTheDice();
            String step = "seed " + seed + " operation " + operation;
            simulation.apply(dice, operation);
            assertSameReads(simulation.reference, simulation.flow, step);
            indexed.add(simulation.flow.indexed());
        }
    }

    /** One run: the reference and the flow under test, and the bookkeeping of the engine that drives them both. */
    private static final class Simulation {

        private final Random random;
        private final WriteLog reference = new WriteLog();
        private final CallFlow flow = new CallFlow(new Object());
        private final Deque<Integer> savepoints = new ArrayDeque<>();
        private int logSize = 0;
        private FlowKey<?> lastKey = KEYS.get(0);

        private Simulation(long seed) {
            random = new Random(seed);
        }

        int rollTheDice() {
            return random.nextInt(100);
        }

        void apply(int dice, int operation) {
            if (dice < 40) {
                putAValue(operation);
            } else if (dice < 52) {
                removeAKey();
            } else if (dice < 72) {
                openStepWithSavepoint();
            } else if (dice < 77) {
                appendHop();
            } else {
                closeOrRollBack(dice);
            }
        }

        private FlowKey<?> pickKey() {
            return random.nextInt(3) == 0 ? lastKey : KEYS.get(random.nextInt(KEYS.size()));
        }

        private void putAValue(int operation) {
            FlowKey<?> key = pickKey();
            Object value = valueFor(key, operation);
            reference.write(key, Optional.of(value), logSize);
            put(flow, key, value, logSize);
            lastKey = key;
        }

        private void removeAKey() {
            FlowKey<?> key = pickKey();
            reference.write(key, Optional.empty(), logSize);
            flow.remove(key, logSize);
            lastKey = key;
        }

        private void openStepWithSavepoint() {
            savepoints.push(logSize);
            logSize++;
        }

        private void appendHop() {
            logSize++;
        }

        private void closeOrRollBack(int dice) {
            if (dice < 87) {
                rollBackTheInnermostStep();
            } else if (dice < 95) {
                keepTheInnermostStep();
            } else {
                rollBackToAnySize();
            }
        }

        private void rollBackTheInnermostStep() {
            if (!savepoints.isEmpty()) {
                int savepoint = savepoints.pop();
                reference.rollbackTo(savepoint);
                flow.rollbackTo(savepoint);
                logSize = savepoint;
            }
        }

        private void keepTheInnermostStep() {
            if (!savepoints.isEmpty()) {
                savepoints.pop();
            }
        }

        private void rollBackToAnySize() {
            int savepoint = random.nextInt(logSize + 1);
            reference.rollbackTo(savepoint);
            flow.rollbackTo(savepoint);
            logSize = savepoint;
            while (!savepoints.isEmpty() && savepoints.peek() >= savepoint) {
                savepoints.pop();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(CallFlow flow, FlowKey<?> key, Object value, int atLog) {
        flow.put((FlowKey<Object>) key, value, atLog);
    }

    @Test
    void randomSequencesOfWritesAndRollbacksReadAsTheListOfEveryWriteDoes() {
        Set<Boolean> indexed = new HashSet<>();
        for (long seed = 1; seed <= 300; seed++) {
            run(seed, 400, indexed);
        }
        assertEquals(Set.of(false, true), indexed);
    }

    /** Long runs, where a key is written many times over and the log grows deep before it is rolled back. */
    @Test
    void longRandomSequencesReadAsTheListOfEveryWriteDoes() {
        Set<Boolean> indexed = new HashSet<>();
        for (long seed = 1_001; seed <= 1_010; seed++) {
            run(seed, 5_000, indexed);
        }
        assertEquals(Set.of(false, true), indexed);
    }
}
