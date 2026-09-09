package io.stategraph.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Verifies the JavaBeans decapitalization a marked getter's logical property name is derived with. */
class PropertyNamesTest {

    @Test
    void getterNamesPreserveJavaBeansAcronyms() {
        assertEquals("status", PropertyNames.fromGetter("getStatus"));
        assertEquals("ready", PropertyNames.fromGetter("isReady"));
        assertEquals("URL", PropertyNames.fromGetter("getURL"));
        assertEquals("status", PropertyNames.fromGetter("status"));
    }

    @Test
    void prefixesThatAreNotFollowedByAnUpperCaseLetterAreKept() {
        assertEquals("getter", PropertyNames.fromGetter("getter"));
        assertEquals("island", PropertyNames.fromGetter("island"));
        assertEquals("get", PropertyNames.fromGetter("get"));
        assertEquals("is", PropertyNames.fromGetter("is"));
    }
}
