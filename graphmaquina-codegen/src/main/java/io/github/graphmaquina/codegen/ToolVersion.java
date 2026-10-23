package io.github.graphmaquina.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The version of this generator, as the build stamped it into {@code io/github/graphmaquina/codegen/version.properties}.
 *
 * <p>The compile manifest and every export index this tool writes carry it; the inventories and the request do not.
 * Of what it reads back, only the export index a classpath entry publishes is compared with it: one written by
 * another version is refused, so a stale artifact is diagnosed instead of being interpreted with the wrong rules.
 * The manifests and inventories of its own work directory are read whatever tool version wrote them.</p>
 */
public final class ToolVersion {

    private static final String RESOURCE = "version.properties";

    private ToolVersion() {
    }

    /**
     * The version of the running generator.
     *
     * @throws IllegalStateException if the resource is missing or carries no {@code version}, which means the
     *     tool was packaged without its own build stamp; a value that still holds the unexpanded
     *     {@code ${version}} placeholder, as resources the build never filtered do, carries no version either
     */
    public static String current() {
        Properties properties = new Properties();
        try (InputStream resource = ToolVersion.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException("the StateGraph generator resource io/github/graphmaquina/codegen/"
                    + RESOURCE + " is missing; this tool jar is incomplete");
            }
            properties.load(resource);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read io/github/graphmaquina/codegen/" + RESOURCE, failure);
        }
        String version = properties.getProperty("version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("the StateGraph generator resource io/github/graphmaquina/codegen/"
                + RESOURCE + " declares no version; this tool jar is incomplete");
        }
        if (version.contains("${")) {
            throw new IllegalStateException("the StateGraph generator resource io/github/graphmaquina/codegen/"
                + RESOURCE + " declares " + version + ", a placeholder its build never replaced, instead of a"
                + " version; this tool runs from resources that carry no build stamp");
        }
        return version;
    }
}
