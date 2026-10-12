package io.github.graphmaquina.internal;

import io.github.graphmaquina.api.ActionRecord;
import java.util.AbstractList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A call's journal as the runtime hands it out with a failure: the first {@code size} slots of the array the call keeps
 * its journal in, read in place. The call only appends past those slots and never writes them again once this list
 * exists: it writes a status that would change one of them to a copy of the array. So every snapshot of a journal
 * shares its array instead of copying the entries, and none ever changes; an
 * {@link io.github.graphmaquina.error.AbortDetails} keeps one as it is, where it copies any other list. Like the list
 * {@link List#copyOf} returns, it is a random-access list whose mutators, and those of its sub-lists and of its
 * reversed view, throw UnsupportedOperationException, even when the call would change nothing, and it compares, hashes
 * and prints as the list of its entries. Not part of the API.
 */
public final class JournalSnapshot extends AbstractList<ActionRecord> implements RandomAccess {

    private final ActionRecord[] entries;
    private final int size;

    /** The first {@code size} slots of {@code entries}, which whoever passes them never writes again. */
    public JournalSnapshot(ActionRecord[] entries, int size) {
        this.entries = entries;
        this.size = size;
    }

    @Override
    public ActionRecord get(int index) {
        return entries[Objects.checkIndex(index, size)];
    }

    @Override
    public int size() { return size; }

    @Override
    public List<ActionRecord> subList(int fromIndex, int toIndex) {
        return Collections.unmodifiableList(super.subList(fromIndex, toIndex));
    }

    @Override
    public List<ActionRecord> reversed() { return Collections.unmodifiableList(super.reversed()); }

    @Override
    public boolean addAll(Collection<? extends ActionRecord> c) { throw new UnsupportedOperationException(); }

    @Override
    public boolean addAll(int index, Collection<? extends ActionRecord> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) { throw new UnsupportedOperationException(); }

    @Override
    public boolean removeAll(Collection<?> c) { throw new UnsupportedOperationException(); }

    @Override
    public boolean retainAll(Collection<?> c) { throw new UnsupportedOperationException(); }

    @Override
    public boolean removeIf(Predicate<? super ActionRecord> filter) { throw new UnsupportedOperationException(); }

    @Override
    public void replaceAll(UnaryOperator<ActionRecord> operator) { throw new UnsupportedOperationException(); }

    @Override
    public void sort(Comparator<? super ActionRecord> c) { throw new UnsupportedOperationException(); }

    @Override
    public void clear() { throw new UnsupportedOperationException(); }
}
