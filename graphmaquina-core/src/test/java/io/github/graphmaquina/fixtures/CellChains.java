package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The cell machine, {@code IDLE -GO-> DONE}, whose action cascades GO into the next cell of its chain, and what the
 * tests that run calls deeper than a thread's stack need with it: a stack padded by frames and a thread of a given
 * stack size.
 */
public final class CellChains {

    public static final MachineKey<Cell, CellStatus, CellTransition> CELLS =
            MachineKey.of(Cell.class, CellStatus.class, CellTransition.class);

    private CellChains() { }

    /** {@code length} cells, each linked to the next; the first one is at index 0. */
    public static Cell[] chain(int length) {
        Cell[] cells = new Cell[length];
        for (int index = 0; index < length; index++) {
            cells[index] = new Cell(index);
            if (index > 0) {
                cells[index - 1].setNext(cells[index]);
            }
        }
        return cells;
    }

    /**
     * The cell machine in a registry of its own under {@code maxCascadeDepth}. Its action marks the cell entered, then,
     * under {@code padding} extra frames, cascades into the next cell if there is one and returns ok; when
     * {@code catching}, it catches an Error that the cascade throws, marks the cell, and returns ok all the same.
     */
    public static StateMachine<Cell, CellStatus, CellTransition> cells(int padding, boolean catching,
                                                                       int maxCascadeDepth) {
        GraphDefinition<Cell, CellStatus, CellTransition> graph = StateGraph.define(CELLS)
                .initial(CellStatus.IDLE)
                .terminal(CellStatus.DONE)
                .statePolicy(CellStatus.IDLE, "a cell goes")
                .transition(CellTransition.GO, CellStatus.IDLE, CellStatus.DONE)
                    .action("Go", (TransitionContext<Cell, CellStatus, CellTransition> ctx) -> {
                        Cell cell = ctx.entity();
                        cell.entered = true;
                        return padded(padding, () -> {
                            if (cell.next().isPresent()) {
                                if (catching) {
                                    try {
                                        ctx.cascade(CELLS, cell.next().get(), CellTransition.GO);
                                    } catch (Error swallowed) {
                                        cell.caught = true;
                                    }
                                } else {
                                    ctx.cascade(CELLS, cell.next().get(), CellTransition.GO);
                                }
                            }
                            cell.returned = true;
                            return ActionOutcome.ok();
                        });
                    })
                    .endTransition()
                .build();
        return MachineRegistry.builder()
                .maxCascadeDepth(maxCascadeDepth)
                .add(StateMachines.from(graph).state(Cell::status, Cell::setStatus).identity(Cell::identify))
                .build()
                .of(CELLS);
    }

    /** Runs {@code body} under about {@code frames} extra frames of stack. */
    public static <R> R padded(int frames, Supplier<R> body) {
        if (frames <= 0) {
            return body.get();
        }
        return padded(frames - 1, body);
    }

    /**
     * Runs {@code body} on a new platform thread of {@code stackSize} bytes, waits for it, and returns what it threw,
     * if anything; null when it returned.
     */
    public static Throwable onStack(long stackSize, Runnable body) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                body.run();
            } catch (Throwable error) {
                thrown.set(error);
            }
        }, "small-stack", stackSize);
        thread.start();
        thread.join();
        return thrown.get();
    }
}
