package io.stategraph.gradle;

import org.gradle.api.provider.Property;

/**
 * Configuration surface exposed by the StateGraph Gradle plugin as the {@code stategraph {}} project extension.
 */
public abstract class StateGraphExtension {

    /**
     * The Maven version of {@code io.stategraph:stategraph-codegen} to resolve as the generator. Defaults to the
     * plugin's own version.
     *
     * @return the configurable generator version property
     */
    public abstract Property<String> getGeneratorVersion();
}
