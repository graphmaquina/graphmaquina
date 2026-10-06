package io.github.graphmaquina.codegen.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Verifies the names derived for a host and its members: underscores and dots are dropped, the first letter of a
 * name and the one after each of them are upper-cased and no other letter changes case, the runtime and container
 * names add their suffix, and a name that does not normalize to a Java identifier is refused.
 */
class SymbolNamesTest {
    @Test
    void normalizesWordSeparatorsAndPreservesExistingCase() {
        assertEquals("OuterInner", SymbolNames.hostName("Outer.Inner"));
        assertEquals("HTTPActions", SymbolNames.hostName("HTTP_actions"));
        assertEquals("Id", SymbolNames.typeName("Host", "_id"));
        assertEquals("HasTitle", SymbolNames.typeName("Host", "has_title"));
        assertEquals("URL", SymbolNames.typeName("Host", "URL"));
        assertEquals("Authorize", SymbolNames.typeName("Host", "Authorize"));
        assertEquals("HostRefs", SymbolNames.typeName("Host", "hostRefs"));
        assertEquals("TicketsRuntime", SymbolNames.runtimeName("Tickets"));
        assertEquals("OuterHTTPActionsRuntime", SymbolNames.runtimeName("Outer.HTTP_actions"));
        assertEquals("OuterInnerRefs", SymbolNames.containerName("Outer.Inner"));
    }

    @Test
    void rejectsNamesThatCannotNormalizeToJavaIdentifiers() {
        for (String name : new String[] {"", "___", "_1id", "bad-name"}) {
            assertThrows(IllegalArgumentException.class, () -> SymbolNames.hostName(name), name);
            assertThrows(IllegalArgumentException.class, () -> SymbolNames.typeName("Host", name), name);
        }
    }
}
