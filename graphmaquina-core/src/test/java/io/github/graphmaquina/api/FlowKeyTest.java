package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A flow key is a name and a type: two keys are equal when both match, whether either is visible or not. It prints as
 * {@code name:SimpleType}, and it refuses a null or blank name, a null type and a primitive class token.
 */
class FlowKeyTest {

    @Test
    void aKeyKeepsItsNameItsTypeAndWhetherItIsVisible() {
        FlowKey<String> hidden = FlowKey.of("batch", String.class);
        FlowKey<String> shown = FlowKey.visible("batch", String.class);
        assertEquals("batch", hidden.name());
        assertSame(String.class, hidden.type());
        assertFalse(hidden.visible());
        assertEquals("batch", shown.name());
        assertSame(String.class, shown.type());
        assertTrue(shown.visible());
    }

    @Test
    void twoKeysAreEqualByNameAndTypeWhateverTheirVisibility() {
        FlowKey<String> hidden = FlowKey.of("batch", String.class);
        FlowKey<String> shown = FlowKey.visible("batch", String.class);
        assertEquals(hidden, shown);
        assertEquals(shown, hidden);
        assertEquals(hidden.hashCode(), shown.hashCode());
        assertEquals(hidden, FlowKey.of("batch", String.class));
        assertNotEquals(hidden, FlowKey.of("lot", String.class));
        assertNotEquals(hidden, FlowKey.of("batch", CharSequence.class));
        assertNotEquals(FlowKey.of("count", Integer.class), FlowKey.of("count", Long.class));
        assertNotEquals(hidden, "batch:String");
    }

    @Test
    void aKeyPrintsItsNameAndTheSimpleNameOfItsType() {
        assertEquals("batch:String", FlowKey.of("batch", String.class).toString());
        assertEquals("batch:String", FlowKey.visible("batch", String.class).toString());
        assertEquals("lines:List", FlowKey.of("lines", List.class).toString());
    }

    @Test
    void aNullNameOrTypeIsRefusedByName() {
        assertEquals("name is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> FlowKey.of(null, String.class)).getMessage());
        assertEquals("type is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> FlowKey.of("batch", null)).getMessage());
        assertEquals("name is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> FlowKey.visible(null, String.class)).getMessage());
        assertEquals("type is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> FlowKey.visible("batch", null)).getMessage());
    }

    @Test
    void aBlankNameIsRefused() {
        for (String name : List.of("", " ", "\t\n")) {
            assertEquals("flow key name must not be blank", assertThrows(IllegalArgumentException.class,
                    () -> FlowKey.of(name, String.class)).getMessage());
            assertEquals("flow key name must not be blank", assertThrows(IllegalArgumentException.class,
                    () -> FlowKey.visible(name, String.class)).getMessage());
        }
    }

    @Test
    void aPrimitiveClassTokenIsRefused() {
        assertEquals("flow key type must be a reference type; use its wrapper class",
                assertThrows(IllegalArgumentException.class, () -> FlowKey.of("count", int.class)).getMessage());
        assertEquals("flow key type must be a reference type; use its wrapper class",
                assertThrows(IllegalArgumentException.class, () -> FlowKey.visible("done", void.class)).getMessage());
        assertEquals(Integer.class, FlowKey.of("count", Integer.class).type());
    }
}
