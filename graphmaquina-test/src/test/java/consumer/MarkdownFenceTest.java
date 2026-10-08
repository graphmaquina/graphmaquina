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
        assertTrue(open("```").closes("```"), "```");
        assertTrue(open("~~~").closes("~~~"), "~~~");
        assertTrue(MarkdownFence.opening("``").isEmpty(), "``");
        assertTrue(MarkdownFence.opening("text").isEmpty(), "text");
        assertTrue(MarkdownFence.opening("").isEmpty(), "");
    }

    @Test
    void javaIsTheFirstWordOfTheInfoString() {
        assertTrue(open("```java").java(), "```java");
        assertTrue(open("```java title=x").java(), "```java title=x");
        assertTrue(open("~~~java").java(), "~~~java");
        assertTrue(open("```  java").java(), "```  java");
        assertFalse(open("```javascript").java(), "```javascript");
        assertFalse(open("```").java(), "```");
        assertFalse(open("```text java").java(), "```text java");
    }

    @Test
    void aBacktickInABacktickInfoStringIsNotAFence() {
        assertTrue(MarkdownFence.opening("```java `x`").isEmpty(), "```java `x`");
        assertTrue(open("~~~java `x`").java(), "~~~java `x`");
    }

    @Test
    void aShorterRunDoesNotCloseALongerFence() {
        MarkdownFence fence = open("````java");
        assertFalse(fence.closes("```"), "```");
        assertTrue(fence.closes("````"), "````");
        assertTrue(fence.closes("`````"), "`````");
    }

    @Test
    void aTildeLineDoesNotCloseABacktickFence() {
        assertFalse(open("```java").closes("~~~"), "~~~ after ```java");
        assertFalse(open("~~~java").closes("```"), "``` after ~~~java");
    }

    @Test
    void aClosingFenceMayHaveTrailingWhitespaceOnly() {
        MarkdownFence fence = open("```java");
        assertTrue(fence.closes("```   "), "```   ");
        assertTrue(fence.closes("```\t"), "```\t");
        assertFalse(fence.closes("``` text"), "``` text");
        assertFalse(fence.closes("```java"), "```java");
        assertFalse(fence.closes("text"), "text");
        assertFalse(fence.closes("```\u000B"), "``` and a vertical tab");
        assertFalse(fence.closes("```\u00A0"), "``` and a no-break space");
    }
}
