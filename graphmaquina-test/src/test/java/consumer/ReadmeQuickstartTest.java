package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Executes the Gradle and Ticket sources of the guide against locally staged Maven artifacts. The guide is the file
 * the {@code readmePath} system property names, which the build points at {@code docs/guide.md}. The settings script
 * gets the staged repository in front of every {@code mavenCentral()}, the build script is followed by the guide's
 * test dependencies and {@code gradle.properties} is written with the staged version; every other source is copied
 * from its fence as written.
 */
class ReadmeQuickstartTest {

    @Test
    void documentedTicketQuickstartCompilesAndRunsItsJUnitTest(@TempDir Path project) throws Exception {
        String readme = Files.readString(Path.of(property("readmePath")));
        String repository = Path.of(property("artifactRepository")).toUri().toASCIIString();
        String settings = fenceAfter(readme, "### settings.gradle.kts", "kotlin")
                .replace("mavenCentral()", "maven { url = uri(\"" + repository + "\") }\n        mavenCentral()");
        Files.writeString(project.resolve("settings.gradle.kts"), settings);
        Files.writeString(project.resolve("gradle.properties"),
                "graphmaquinaVersion=" + property("artifactVersion") + "\n");
        String build = fenceAfter(readme, "### build.gradle.kts", "kotlin");
        String testDependencies = fenceAfter(readme, "## Test your machines", "kotlin");
        Files.writeString(project.resolve("build.gradle.kts"), build + "\n" + testDependencies);

        Path mainSources = Files.createDirectories(project.resolve("src/main/java/com/example/tickets"));
        for (String file : List.of("TicketState.java", "TicketTransition.java", "Ticket.java",
                "TicketActions.java", "Tickets.java", "TicketMachines.java", "App.java")) {
            Files.writeString(mainSources.resolve(file), fenceAfter(readme, "### " + file, "java"));
        }
        Path testSources = Files.createDirectories(project.resolve("src/test/java/com/example/tickets"));
        Files.writeString(testSources.resolve("TicketsTest.java"),
                fenceAfter(readme, "Create `src/test/java/com/example/tickets/TicketsTest.java`:", "java"));

        Path transcript = project.resolve("gradle-output.txt");
        ProcessBuilder builder = new ProcessBuilder(property("gradleExecutable"), "--no-daemon", "--offline",
                "--console=plain", "--max-workers=2", "test", "run")
                .directory(project.toFile()).redirectErrorStream(true).redirectOutput(transcript.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(3, TimeUnit.MINUTES),
                    () -> "README consumer timed out; transcript: " + transcript);
            String output = Files.readString(transcript);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.lines().anyMatch((String line) -> line.equals("> Task :test")), output);
            assertTrue(output.contains("All ticket scenarios passed."), output);
            assertTicketTestResult(project.resolve(
                    "build/test-results/test/TEST-com.example.tickets.TicketsTest.xml"));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "README consumer did not stop after termination");
                System.out.println(Files.readString(transcript));
            }
        }
    }

    private static void assertTicketTestResult(Path report) throws Exception {
        assertTrue(Files.isRegularFile(report), "Ticket JUnit report was not created: " + report);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Element suite = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        assertEquals("testsuite", suite.getTagName());
        assertEquals("com.example.tickets.TicketsTest", suite.getAttribute("name"));
        assertEquals("1", suite.getAttribute("tests"));
        for (String attribute : List.of("failures", "errors", "skipped")) {
            assertEquals("0", suite.getAttribute(attribute), "unexpected " + attribute + " in " + report);
        }
        NodeList cases = suite.getElementsByTagName("testcase");
        assertEquals(1, cases.getLength());
        Element test = (Element) cases.item(0);
        assertEquals("com.example.tickets.TicketsTest", test.getAttribute("classname"));
        assertEquals("aDomainFailureCommitsItsDeclaredFailureState()", test.getAttribute("name"));
    }

    /**
     * Returns the content of the first fence whose first info word is {@code language}, in the section that the first
     * line equal to {@code context} outside any fence opens. The section runs until the next line outside a fence
     * that starts with {@code #}, of any heading level, or until the end of the guide. A fence of another language is
     * skipped up to its own closing line. Each content line is returned as written, followed by a newline. Fails
     * when no line outside a fence equals the context, when the section holds no such fence, or when that fence is
     * never closed.
     */
    private static String fenceAfter(String readme, String context, String language) {
        List<String> lines = readme.lines().toList();
        int index = 0;
        boolean found = false;
        MarkdownFence skipping = null;
        while (index < lines.size() && !found) {
            String line = lines.get(index++);
            if (skipping != null) {
                skipping = skipping.closes(line) ? null : skipping;
            } else {
                found = line.equals(context);
                skipping = found ? null : MarkdownFence.opening(line).orElse(null);
            }
        }
        assertTrue(found, "README context missing: " + context);
        MarkdownFence block = null;
        while (index < lines.size() && block == null && !lines.get(index).startsWith("#")) {
            MarkdownFence fence = MarkdownFence.opening(lines.get(index++)).orElse(null);
            if (fence != null && fence.language(language)) {
                block = fence;
            } else if (fence != null) {
                while (index < lines.size() && !fence.closes(lines.get(index))) {
                    index++;
                }
                index++;
            }
        }
        assertNotNull(block, "README source missing in section: " + context);
        StringBuilder source = new StringBuilder();
        while (index < lines.size() && !block.closes(lines.get(index))) {
            source.append(lines.get(index++)).append('\n');
        }
        assertTrue(index < lines.size(), "README source not closed after: " + context);
        return source.toString();
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "run this test through :graphmaquina-test:publishedArtifactTest; missing " + name);
        return value;
    }
}
