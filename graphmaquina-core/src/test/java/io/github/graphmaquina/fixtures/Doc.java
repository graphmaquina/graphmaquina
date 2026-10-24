package io.github.graphmaquina.fixtures;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Test entity of the doc machine: an id and a mutable status. */
@Getter
@AllArgsConstructor
public final class Doc {

    private final String id;
    private DocStatus status;

    public void setStatus(DocStatus status) { this.status = status; }

    @Override
    public String toString() { return "Doc#" + id; }
}
