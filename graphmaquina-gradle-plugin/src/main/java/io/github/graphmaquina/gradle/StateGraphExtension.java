package io.github.graphmaquina.gradle;

import org.gradle.api.provider.Property;

/**
 * Configuration surface exposed by the StateGraph Gradle plugin as the {@code graphmaquina {}} project extension.
 */
public abstract class StateGraphExtension {

    /**
     * The Maven version of {@code io.github.graphmaquina:graphmaquina-codegen} that the
     * {@code graphmaquinaGenerator} configuration resolves as the generator. It is applied through the default
     * dependency of that configuration, so a dependency the build declares there itself replaces it. Defaults to
     * the plugin's own version.
     *
     * @return the configurable generator version property
     */
    public abstract Property<String> getGeneratorVersion();
}
