package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MemoKey;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * What the transactions of one request share: the instant {@code now()} answers and the values {@code memo} keeps for
 * each entity, each created the first time a context asks. A request is a root call, with the
 * steps it cascades to, the exits its steps run and the checks its guards start, or a check of its own; a choice check
 * is one request for all its candidates. The instant is read from {@code clock}, that of the request's root machine,
 * the first time a context of the request asks, and kept: a request that never asks reads no clock. Only the thread
 * running the request reads it, as only that thread may use the contexts that ask.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class RequestScope {

    private final InstantSource clock;
    private Optional<Instant> instant = Optional.empty();
    private Optional<Map<EntityRef, Map<MemoKey<?>, Object>>> memos = Optional.empty();

    /**
     * The instant of the request: read from the clock the first time, the same one afterwards. What the clock throws
     * propagates, and a null instant is refused with a NullPointerException; either leaves the instant unread, so a
     * later ask reads the clock again.
     */
    Instant now() {
        if (instant.isEmpty()) {
            instant = Optional.of(Objects.requireNonNull(clock.instant(), "the clock answered null"));
        }
        return instant.get();
    }

    /**
     * The value {@code key} keeps in the request for the entity {@code owner}: the one kept already for that entity,
     * or the one {@code supplier} computes now, kept for the rest of the request. Entities are told apart as
     * {@link EntityRef} tells them, by machine and identity; keys are compared by reference. What the supplier throws
     * propagates and keeps nothing; a null value keeps nothing and throws {@link NullMemoValue}.
     */
    @SuppressWarnings("unchecked")
    <V> V memo(EntityRef owner, MemoKey<V> key, Supplier<? extends V> supplier) {
        if (memos.isEmpty()) {
            memos = Optional.of(new HashMap<>());
        }
        Map<MemoKey<?>, Object> kept =
                memos.get().computeIfAbsent(owner, (EntityRef absent) -> new IdentityHashMap<>());
        Object value = kept.get(key);
        if (value != null) {
            return (V) value;
        }
        V computed = supplier.get();
        if (computed == null) {
            throw new NullMemoValue(key);
        }
        kept.put(key, computed);
        return computed;
    }
}
