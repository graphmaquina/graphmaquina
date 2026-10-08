package consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Pins the CommonMark fence rules that the guide fragment tests rely on to cut java blocks out of the guides. */
class MarkdownFenceTest {

    private static MarkdownFence open(String line) {
        Optional<MarkdownFence> fence = MarkdownFence.opening(line);
        assertTrue(fence.isPresent(), "expected a fence opening: " + line);
        return fence.get();
    }

    @Test
    void backtickAndTildeLinesOpenFences() {
        assertTrue(open("```").closes("```"));
        assertTrue(open("~~~").closes("~~~"));
        assertTrue(MarkdownFence.opening("``").isEmpty());
        assertTrue(MarkdownFence.opening("text").isEmpty());
        assertTrue(MarkdownFence.opening("").isEmpty());
    }

    @Test
    void javaIsTheFirstWordOfTheInfoString() {
        assertTrue(open("```java").java());
        assertTrue(open("```java title=x").java());
        assertTrue(open("~~~java").java());
        assertFalse(open("```javascript").java());
        assertFalse(open("```").java());
        assertFalse(open("```text java").java());
    }

    @Test
    void aBacktickInABacktickInfoStringIsNotAFence() {
        assertTrue(MarkdownFence.opening("```java `x`").isEmpty());
        assertTrue(open("~~~java `x`").java());
    }

    @Test
    void aShorterRunDoesNotCloseALongerFence() {
        MarkdownFence fence = open("````java");
        assertFalse(fence.closes("```"));
        assertTrue(fence.closes("````"));
        assertTrue(fence.closes("`````"));
    }

    @Test
    void aTildeLineDoesNotCloseABacktickFence() {
        assertFalse(open("```java").closes("~~~"));
        assertFalse(open("~~~java").closes("```"));
    }

    @Test
    void aClosingFenceMayHaveTrailingWhitespaceOnly() {
        MarkdownFence fence = open("```java");
        assertTrue(fence.closes("```   "));
        assertTrue(fence.closes("```\t"));
        assertFalse(fence.closes("``` text"));
        assertFalse(fence.closes("```java"));
        assertFalse(fence.closes("text"));
    }
}
