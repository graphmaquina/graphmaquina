package io.github.graphmaquina.annotation.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The receiver a companion's {@code structure()} calls an instance member of a collaborator on never answers. */
class UnboundCollaboratorTest {

    @Test
    void ofAlwaysThrowsNamingTheCollaboratorAndTheCompanion() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> UnboundCollaborator.of("a.Actions", "a.ThingsRuntime"));
        assertEquals("a.ThingsRuntime.structure() binds no a.Actions: build a machine from a.ThingsRuntime.graph(...)",
                thrown.getMessage());
    }

    @Test
    void ofRefusesANullName() {
        assertThrows(NullPointerException.class, () -> UnboundCollaborator.of(null, "a.ThingsRuntime"));
        assertThrows(NullPointerException.class, () -> UnboundCollaborator.of("a.Actions", null));
    }

    @Test
    void theMethodTheCompanionDeclaresIsNamedStructure() {
        assertEquals("structure", UnboundCollaborator.STRUCTURE);
    }
}
