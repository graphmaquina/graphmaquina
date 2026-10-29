package io.github.graphmaquina.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The post-commit of a root call, from its hooks to its automatic chains, as the entities that calls nested in it
 * commit see it, so that the deepest commit that moved an entity is the one that chains it. Each thread keeps the
 * scopes it has open, innermost on top: every commit reports its entities to all of them before it opens its own,
 * whether the call that committed is a hook's call, a watch reaction or an automatic attempt. A call made on another
 * thread reports to none of them.
 *
 * <p>The scope holds what was reported as a list, which takes no identity's hashCode or equals, so a commit reports
 * without running code of the application; {@link #movedBelow} compares identities as the commit log does, and its
 * caller runs it where what an identity throws ends no more than one chain. A scope is closed by a store first, as
 * the reentrancy marker is released, so that a StackOverflowError that interrupts the rest of its closing leaves a
 * closed scope behind, which no commit reports to and which the next scope opened or closed on the thread drops.
 */
final class PostCommitScope {

    /** The open scopes of each thread, innermost first; empty when the thread runs no post-commit. */
    private static final ThreadLocal<ArrayDeque<PostCommitScope>> OPEN = ThreadLocal.withInitial(ArrayDeque::new);

    /** The deque of the thread that opened this scope. */
    private final ArrayDeque<PostCommitScope> open;
    /** Each entity a nested commit reported, in the order reported, repeats included. */
    private final List<EntityRef> moved = new ArrayList<>();
    /** The entities of {@link #moved} before the position {@link #indexed}, as the commit log compares them. */
    private final Set<EntityRef> index = new HashSet<>();
    private int indexed;
    /**
     * Whether the scope is closed: the first statement of the finally that closes it stores true here, before it calls
     * {@link #close()}.
     */
    boolean closed;

    private PostCommitScope(ArrayDeque<PostCommitScope> open) {
        this.open = open;
    }

    /** Adds {@code refs}, the entities of a commit, to every scope this thread has open. */
    static void report(Collection<EntityRef> refs) {
        for (PostCommitScope scope : OPEN.get()) {
            if (!scope.closed) {
                scope.moved.addAll(refs);
            }
        }
    }

    /** Opens a scope on this thread, innermost, once the closed scopes left on top are dropped. */
    static PostCommitScope open() {
        ArrayDeque<PostCommitScope> open = OPEN.get();
        while (!open.isEmpty() && open.peek().closed) {
            open.pop();
        }
        PostCommitScope scope = new PostCommitScope(open);
        open.push(scope);
        return scope;
    }

    /**
     * Whether a commit nested in this scope committed something of {@code ref}: a hop or the start of a sub-machine.
     * It compares identities as the commit log does, by machine key and identity, so it runs each identity's hashCode
     * and equals that it compares.
     */
    boolean movedBelow(EntityRef ref) {
        while (indexed < moved.size()) {
            index.add(moved.get(indexed));
            indexed++;
        }
        return index.contains(ref);
    }

    /** Each entity a nested commit reported, in order, repeats included. */
    List<EntityRef> moved() {
        return List.copyOf(moved);
    }

    /**
     * Closes this scope, the innermost one open on this thread once the closed scopes above it are dropped: those that
     * a StackOverflowError left, interrupting their own closing.
     *
     * @throws IllegalStateException if this scope is not the innermost open one on this thread
     */
    void close() {
        closed = true;
        while (open.peek() != this && open.peek() != null && open.peek().closed) {
            open.pop();
        }
        if (open.peek() != this) {
            throw new IllegalStateException("a post-commit scope closes as the innermost one open on its thread");
        }
        open.pop();
    }
}
