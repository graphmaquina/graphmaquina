package io.stategraph.runtime;

/** Reference identity for standalone machines with no identity(...) declared. */
record IdentityKey(Object target) {

    @Override
    public boolean equals(Object other) {
        return other instanceof IdentityKey that && that.target == target;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(target);
    }

    @Override
    public String toString() {
        return "@" + Integer.toHexString(System.identityHashCode(target));
    }
}
