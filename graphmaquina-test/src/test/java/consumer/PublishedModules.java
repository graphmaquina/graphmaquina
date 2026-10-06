package consumer;

import java.util.List;

/**
 * The modules a release publishes, listed once for the tests that walk the published artifacts, so that a module
 * added here is visited by every one of them. Each module ships a POM and a binary, a sources and a javadoc jar to
 * the local repository that the Gradle task of those tests publishes to before they start.
 */
final class PublishedModules {

    /** The artifact id of every published module. */
    static final List<String> ALL = List.of("graphmaquina-core", "graphmaquina-codegen", "graphmaquina-processor",
            "graphmaquina-test", "graphmaquina-maven-plugin", "graphmaquina-gradle-plugin");

    private PublishedModules() { }
}
