package io.stategraph.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the packaged Maven plugin the way a consumer does: a real {@code mvn} process resolves it from the file
 * repository this build published to, reads its descriptor and runs its goals. Nothing here instantiates a Mojo.
 */
class MavenPluginIntegrationTest {

    /** The version every StateGraph artifact of the fixture repository is published under. */
    private static final String VERSION = "0.3.0-SNAPSHOT";

    /** Where the published plugin lives inside the fixture repository. */
    private static final String MODULE = "io/stategraph/stategraph-maven-plugin/" + VERSION;

    /** The descriptor Maven looks a plugin's goals up in. */
    private static final String DESCRIPTOR = "META-INF/maven/plugin.xml";

    /** The file repository the Gradle build published every StateGraph artifact of this version to. */
    private final Path fixtureRepository = Path.of(requireProperty("stategraph.fixtureRepository"));

    @TempDir
    Path tempDirectory;

    @Test
    void publishedArtifactContainsAnExecutableDescriptor() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        MavenResult result = fixture.run(
                "io.stategraph:stategraph-maven-plugin:0.3.0-SNAPSHOT:generate");
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.exists("target/stategraph/main/annotation-manifest.json"));
        assertTrue(fixture.generatedSourcesContainGraphCompilation());
    }

    @Test
    void publishedArtifactIsAMavenPluginThatCarriesOnlyTheGenerator() throws IOException {
        Path module = fixtureRepository.resolve(MODULE);
        Element pom = parse(newest(module, ".pom"));
        assertEquals("maven-plugin", text(pom, "packaging"));

        Element descriptor = descriptorOf(newest(module, ".jar"));
        assertEquals(text(pom, "groupId"), text(descriptor, "groupId"));
        assertEquals(text(pom, "artifactId"), text(descriptor, "artifactId"));
        assertEquals(text(pom, "version"), text(descriptor, "version"));
        assertEquals(List.of("generate", "prepare", "complete", "generate-test", "prepare-test",
                "complete-test"), goals(descriptor));

        // Maven's own API and core are supplied by Maven at execution time and must not be published; the
        // generator is a dependency of the plugin alone, so nothing it carries reaches a consumer's classpath.
        assertEquals(List.of("io.stategraph:stategraph-codegen:runtime"), scopedDependencies(pom));
        assertEquals(List.of("io.stategraph:stategraph-codegen:" + VERSION), dependencies(descriptor));
    }

    /** The goals the descriptor declares, in the order it declares them. */
    private static List<String> goals(Element descriptor) {
        List<String> goals = new ArrayList<>();
        for (Element mojo : children(child(descriptor, "mojos"), "mojo")) {
            goals.add(text(mojo, "goal"));
        }
        return goals;
    }

    /** The dependencies of the published POM, as {@code groupId:artifactId:scope}. */
    private static List<String> scopedDependencies(Element pom) {
        List<String> dependencies = new ArrayList<>();
        for (Element dependency : children(child(pom, "dependencies"), "dependency")) {
            dependencies.add(text(dependency, "groupId") + ':' + text(dependency, "artifactId")
                    + ':' + text(dependency, "scope"));
        }
        return dependencies;
    }

    /** The dependencies the descriptor names, as {@code groupId:artifactId:version}. */
    private static List<String> dependencies(Element descriptor) {
        List<String> dependencies = new ArrayList<>();
        for (Element dependency : children(child(descriptor, "dependencies"), "dependency")) {
            dependencies.add(text(dependency, "groupId") + ':' + text(dependency, "artifactId")
                    + ':' + text(dependency, "version"));
        }
        return dependencies;
    }

    /** The descriptor packaged inside {@code jar}, which is what Maven reads to find a goal. */
    private static Element descriptorOf(Path jar) throws IOException {
        try (ZipFile archive = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = archive.getEntry(DESCRIPTOR);
            if (entry == null) {
                throw new IllegalStateException("the published jar " + jar.getFileName() + " carries no "
                        + DESCRIPTOR);
            }
            try (InputStream stream = archive.getInputStream(entry)) {
                return read(builder -> builder.parse(stream), jar + "!/" + DESCRIPTOR);
            }
        }
    }

    /** The newest published file of {@code module} whose name ends with {@code suffix}. */
    private static Path newest(Path module, String suffix) throws IOException {
        if (!Files.isDirectory(module)) {
            throw new IllegalStateException("the plugin was not published to " + module + "; run"
                    + " ./gradlew :stategraph-maven-plugin:publishMavenPublicationToAdapterTestsRepository");
        }
        try (Stream<Path> files = Files.list(module)) {
            // Snapshot file names carry the publication timestamp, so their order is chronological.
            return files.filter(file -> file.getFileName().toString().endsWith(suffix))
                    .max(Comparator.comparing(file -> file.getFileName().toString()))
                    .orElseThrow(() -> new IllegalStateException("no " + suffix + " was published to " + module));
        }
    }

    private static Element parse(Path file) throws IOException {
        return read(builder -> builder.parse(file.toFile()), file.toString());
    }

    /** Parses one document with a plain, namespace-unaware builder and returns its root element. */
    private static Element read(Parser parser, String what) throws IOException {
        try {
            Document document = parser.parse(DocumentBuilderFactory.newInstance().newDocumentBuilder());
            return document.getDocumentElement();
        } catch (ParserConfigurationException | SAXException failure) {
            throw new IllegalStateException("cannot read " + what, failure);
        }
    }

    /** The single child element of {@code parent} named {@code name}. */
    private static Element child(Element parent, String name) {
        List<Element> children = children(parent, name);
        if (children.size() != 1) {
            throw new IllegalStateException(parent.getTagName() + " declares " + children.size() + " <" + name
                    + "> elements, not one");
        }
        return children.get(0);
    }

    /** Every child element of {@code parent} named {@code name}, in document order. */
    private static List<Element> children(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                children.add(element);
            }
        }
        return children;
    }

    /** The text of the single child element of {@code parent} named {@code name}. */
    private static String text(Element parent, String name) {
        return child(parent, name).getTextContent().strip();
    }

    /** The value of {@code name}, which the Gradle test task sets and these tests cannot run without. */
    private static String requireProperty(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            throw new IllegalStateException(name + " is not set; run these tests through"
                    + " ./gradlew :stategraph-maven-plugin:test");
        }
        return value;
    }

    /** One document to parse, so the two sources of XML share the builder and its failure handling. */
    @FunctionalInterface
    private interface Parser {

        Document parse(DocumentBuilder builder) throws IOException, SAXException;
    }
}
