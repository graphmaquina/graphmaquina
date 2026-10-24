package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * Characterizes {@link EntityRef#hashCode()} against the {@link Objects#hash(Object...)} formula it used to
 * delegate to directly. The replacement inlines that exact arithmetic to drop the varargs array {@code hash}
 * allocated per call; every case here must still equal the varargs formula, key by key and id by id, so this
 * test passes unchanged on the code before that replacement too. What it cannot show from the outside is the
 * allocation itself, which only a profiler or an allocation-counting benchmark observes.
 */
class EntityRefHashTest {

    /** States of the fixture machine. */
    enum S { A, B }

    /** Transition identifiers of the fixture machine. */
    enum T { GO }

    static final MachineKey<Object, S, T> KEY = MachineKey.of(Object.class, S.class, T.class);

    @Test
    void aDifferentInstanceDoesNotChangeTheHash() {
        EntityRef a = new EntityRef(KEY, 42, new Object());
        EntityRef b = new EntityRef(KEY, 42, new Object());
        assertEquals(Objects.hash(a.key(), a.id()), a.hashCode());
        assertEquals(Objects.hash(b.key(), b.id()), b.hashCode());
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void negativeIdsMatchTheVarargsFormula() {
        EntityRef negative = new EntityRef(KEY, -7, new Object());
        EntityRef minValue = new EntityRef(KEY, Integer.MIN_VALUE, new Object());
        assertEquals(Objects.hash(negative.key(), negative.id()), negative.hashCode());
        assertEquals(Objects.hash(minValue.key(), minValue.id()), minValue.hashCode());
    }

    /** {@code "Aa"} and {@code "BB"} share the classic {@code String.hashCode()} collision (both 2112). */
    @Test
    void collidingStringIdsMatchTheVarargsFormula() {
        EntityRef aa = new EntityRef(KEY, "Aa", new Object());
        EntityRef bb = new EntityRef(KEY, "BB", new Object());
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertEquals(Objects.hash(aa.key(), aa.id()), aa.hashCode());
        assertEquals(Objects.hash(bb.key(), bb.id()), bb.hashCode());
    }

    /** No memoization: a hash that changes between calls must be reflected immediately, never cached. */
    @Test
    void aMutatingIdHashIsNeverCached() {
        MutableHashId id = new MutableHashId(1);
        EntityRef ref = new EntityRef(KEY, id, new Object());
        int before = ref.hashCode();
        assertEquals(Objects.hash(ref.key(), ref.id()), before);
        id.value = 2;
        int after = ref.hashCode();
        assertEquals(Objects.hash(ref.key(), ref.id()), after);
        assertNotEquals(before, after, "hashCode() must recompute from id every call, never cache it");
    }

    /** Identity value whose hash the test changes mid-run to prove {@link EntityRef} caches nothing. */
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    static final class MutableHashId {

        int value;

        @Override
        public int hashCode() { return value; }
    }
}
