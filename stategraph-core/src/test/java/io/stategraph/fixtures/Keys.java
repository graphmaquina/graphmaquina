package io.stategraph.fixtures;

import io.stategraph.api.MachineKey;

public final class Keys {

    public static final MachineKey<Doc, DocStatus> DOCS = MachineKey.of(Doc.class, DocStatus.class);

    private Keys() { }
}
