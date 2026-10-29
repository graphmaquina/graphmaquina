package io.github.graphmaquina.runtime;

import io.github.graphmaquina.internal.Interrupts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The post-commit of a root call, from its hooks to its automatic chains, as the entities that calls nested in it
 * commit see it, so that the deepest commit that moved an entity is the one that chains it. Each thread keeps the
 * scopes it has open, each linked to the one around it, the innermost in a cell of the thread: every commit reports
 * its entities to all of them, whether the call that committed is a hook's call, a watch reaction or an automatic
 * attempt, before it opens its own, which only a registry with an automatic edge does. A call made on another thread
 * reports to none of them.
 *
 * <p>The scope holds what was reported as a list, which takes no identity's hashCode or equals, so a commit reports
 * without running code of the application. {@link #movedBelow} compares identities as the commit log does: it indexes
 * the reported entities by hash, and sets aside, to compare one by one, an entity whose identity throws as it is
 * indexed, so the only identity whose throw reaches its caller is the one of the entity it looks up.
 *
 * <p>Opening a scope links it with a store, its last step, so a failure while it opens leaves nothing linked. Closing
 * one starts with a store too, of {@link #closed}, as the reentrancy marker is released, so that a StackOverflowError
 * that interrupts the rest of its closing leaves a closed scope behind, which no commit reports to and which the next
 * scope opened or closed on the thread drops.
 */
final class PostCommitScope {

    /**
     * The cell of each thread: its only element is the innermost scope open on the thread, empty when none is. It is
     * an array of Optional, so a thread with no scope open keeps nothing of the library, except a closed scope that a
     * StackOverflowError left there by interrupting the closing of the outermost one, which the next scope opened or
     * closed on the thread drops.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final ThreadLocal<Optional<PostCommitScope>[]> INNERMOST =
            ThreadLocal.withInitial(() -> new Optional[] {Optional.empty()});

    /** The cell of the thread that opened this scope. */
    private final Optional<PostCommitScope>[] innermost;
    /** The scope this one is open in, on the same thread. */
    private final Optional<PostCommitScope> parent;
    /** This scope, as the cell holds it while it is the innermost one. */
    private final Optional<PostCommitScope> self = Optional.of(this);
    /** Each entity a nested commit reported, in the order reported, repeats included. */
    private final List<EntityRef> moved = new ArrayList<>();
    /**
     * The entities of {@link #moved} before the position {@link #indexed}, as the commit log compares them, but those
     * whose identity threw as they were indexed, which {@link #unindexed} holds instead.
     */
    private final Set<EntityRef> index = new HashSet<>();
    /** The entities of {@link #moved} whose identity threw as they were indexed. */
    private final List<EntityRef> unindexed = new ArrayList<>();
    private int indexed;
    /**
     * Whether the scope is closed: the first statement of the finally that closes it stores true here, before it calls
     * {@link #close()}.
     */
    boolean closed;

    private PostCommitScope(Optional<PostCommitScope>[] innermost, Optional<PostCommitScope> parent) {
        this.innermost = innermost;
        this.parent = parent;
    }

    /**
     * Whether this thread keeps a scope, which a commit reports its entities to: a commit lists them only then, so one
     * that no post-commit surrounds allocates nothing to report. A closed scope that a StackOverflowError left counts
     * until the next scope opened or closed on the thread drops it.
     */
    static boolean anyOpen() {
        return INNERMOST.get()[0].isPresent();
    }

    /** Adds {@code refs}, the entities of a commit, to every scope this thread has open. */
    static void report(Collection<EntityRef> refs) {
        for (Optional<PostCommitScope> scope = INNERMOST.get()[0]; scope.isPresent(); scope = scope.get().parent) {
            if (!scope.get().closed) {
                scope.get().moved.addAll(refs);
            }
        }
    }

    /**
     * Opens a scope on this thread, innermost, inside the innermost one still open: the closed scopes left on top are
     * dropped. The store that links it is the last thing it does.
     */
    static PostCommitScope open() {
        Optional<PostCommitScope>[] innermost = INNERMOST.get();
        Optional<PostCommitScope> parent = innermost[0];
        while (parent.isPresent() && parent.get().closed) {
            parent = parent.get().parent;
        }
        PostCommitScope scope = new PostCommitScope(innermost, parent);
        innermost[0] = scope.self;
        return scope;
    }

    /**
     * Whether a commit nested in this scope committed something of {@code ref}: a hop or the start of a sub-machine.
     * It compares identities as the commit log does, by machine key and identity. What the hashCode or equals of
     * {@code ref}'s identity throws propagates; what another identity throws as this scope indexes it does not: that
     * entity is set aside and compared with {@code ref}'s equals instead. An InterruptedException set aside so
     * interrupts the thread again; an Error propagates.
     */
    boolean movedBelow(EntityRef ref) {
        while (indexed < moved.size()) {
            EntityRef entry = moved.get(indexed);
            try {
                index.add(entry);
            } catch (Error fatal) {
                throw fatal;
            } catch (Throwable unindexable) {
                Interrupts.keep(unindexable);
                unindexed.add(entry);
            }
            indexed++;
        }
        return index.contains(ref) || unindexed.contains(ref);
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
        Optional<PostCommitScope> top = innermost[0];
        while (top.isPresent() && top.get() != this && top.get().closed) {
            top = top.get().parent;
        }
        if (top.isEmpty() || top.get() != this) {
            throw new IllegalStateException("a post-commit scope closes as the innermost one open on its thread");
        }
        innermost[0] = parent;
    }
}
