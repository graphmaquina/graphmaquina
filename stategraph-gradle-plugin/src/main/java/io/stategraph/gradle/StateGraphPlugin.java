package io.stategraph.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * Gradle plugin that generates the StateGraph typed API for each Java source set of the applying project.
 *
 * <p>It applies its configuration only once the {@code java} plugin is present, and registers one generation task
 * per source set (task 11.2 wires those tasks to invoke {@code stategraph-codegen}).
 */
public final class StateGraphPlugin implements Plugin<Project> {

    /** Name of the resolvable, non-consumable configuration carrying the generator's runtime classpath. */
    public static final String GENERATOR_CONFIGURATION_NAME = "stategraphGenerator";

    /** Maven group and artifact id of the code generator, applied as the configuration's default dependency. */
    private static final String CODEGEN_COORDINATES = "io.stategraph:stategraph-codegen";

    /**
     * The plugin's own version, used as the default {@code stategraph-codegen} version. Kept as a constant rather
     * than read from a packaged resource because TestKit's {@code withPluginClasspath()} loads plugin classes from
     * a classes directory, not a jar, so {@code Package.getImplementationVersion()} would return {@code null} there.
     */
    private static final String PLUGIN_VERSION = "0.3.0-SNAPSHOT";

    @Override
    public void apply(Project project) {
        project.getPlugins().withType(JavaPlugin.class, plugin -> configure(project));
    }

    private void configure(Project project) {
        StateGraphExtension extension = project.getExtensions().create("stategraph", StateGraphExtension.class);
        extension.getGeneratorVersion().convention(PLUGIN_VERSION);

        project.getConfigurations().register(GENERATOR_CONFIGURATION_NAME, configuration -> {
            configuration.setCanBeResolved(true);
            configuration.setCanBeConsumed(false);
            configuration.defaultDependencies(dependencies -> dependencies.add(project.getDependencies()
                    .create(CODEGEN_COORDINATES + ":" + extension.getGeneratorVersion().get())));
        });

        SourceSetContainer sourceSets = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        sourceSets.configureEach(sourceSet -> registerGenerationTask(project, sourceSet));
    }

    private void registerGenerationTask(Project project, SourceSet sourceSet) {
        String taskName = sourceSet.getTaskName("generate", "StateGraphApi");
        project.getTasks().register(taskName, GenerateGraphApi.class, task -> {
            task.setGroup("stategraph");
            task.setDescription("Generates the StateGraph API sources for the '" + sourceSet.getName()
                    + "' source set.");
        });
    }
}
