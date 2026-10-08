package consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the CommonMark fence rules that the guide fragment tests and ReadmeQuickstartTest rely on to cut java and
 * kotlin blocks out of the guides.
 */
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
        assertTrue(MarkdownFence.opening("").isEmpty(), "an empty line");
    }

    @Test
    void theLanguageIsTheFirstWordOfTheInfoString() {
        assertTrue(open("```java").language("java"), "```java");
        assertTrue(open("```java title=x").language("java"), "```java title=x");
        assertTrue(open("~~~java").language("java"), "~~~java");
        assertTrue(open("```  java").language("java"), "```  java");
        assertFalse(open("```javascript").language("java"), "```javascript");
        assertFalse(open("```").language("java"), "```");
        assertFalse(open("```text java").language("java"), "```text java");
        assertTrue(open("```kotlin").language("kotlin"), "```kotlin");
        assertFalse(open("```kotlin").language("java"), "```kotlin as java");
    }

    @Test
    void aBacktickInABacktickInfoStringIsNotAFence() {
        assertTrue(MarkdownFence.opening("```java `x`").isEmpty(), "```java `x`");
        assertTrue(open("~~~java `x`").language("java"), "~~~java `x`");
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
    void aClosingFenceMayHaveTrailingSpacesOrTabsOnly() {
        MarkdownFence fence = open("```java");
        assertTrue(fence.closes("```   "), "``` and three spaces");
        assertTrue(fence.closes("```\t"), "``` and a tab");
        assertFalse(fence.closes("``` text"), "``` text");
        assertFalse(fence.closes("```java"), "```java");
        assertFalse(fence.closes("text"), "text");
        assertFalse(fence.closes("```\u000B"), "``` and a vertical tab");
        assertFalse(fence.closes("```\u00A0"), "``` and a no-break space");
    }
}
