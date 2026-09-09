package io.github.graphmaquina.fixtures;

public final class Doc {

    private final String id;
    private DocStatus status;

    public Doc(String id, DocStatus status) {
        this.id = id;
        this.status = status;
    }

    public String id() { return id; }

    public DocStatus status() { return status; }

    public void setStatus(DocStatus status) { this.status = status; }

    @Override
    public String toString() { return "Doc#" + id; }
}
