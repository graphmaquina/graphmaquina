package io.github.graphmaquina.fixtures;

import java.util.Optional;

/**
 * Test entity of the cell machine: a link of a chain, whose action cascades into the next cell, with flags that say
 * how far the call got with it. The flags are volatile because the calls run on threads of their own.
 */
public final class Cell {

    private final int id;
    private CellStatus status = CellStatus.IDLE;
    private Optional<Cell> next = Optional.empty();
    /** Set by the identity function: a cascade into this cell got as far as reading its identity. */
    public volatile boolean identified;
    /** Set when the action of this cell starts. */
    public volatile boolean entered;
    /** Set when the action of this cell caught an Error that its cascade threw. */
    public volatile boolean caught;
    /** Set when the action of this cell returns ok. */
    public volatile boolean returned;

    public Cell(int id) {
        this.id = id;
    }

    /** The identity function of the cell machine; marks the cell as identified. */
    public int identify() {
        identified = true;
        return id;
    }

    public CellStatus status() { return status; }

    public void setStatus(CellStatus status) { this.status = status; }

    public Optional<Cell> next() { return next; }

    public void setNext(Cell cell) { next = Optional.of(cell); }

    @Override
    public String toString() { return "Cell#" + id; }
}
