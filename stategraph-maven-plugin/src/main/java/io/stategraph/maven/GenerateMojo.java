package io.stategraph.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code generate} goal: emits the typed StateGraph vocabulary of the main compilation and the manifest the
 * annotation processor reads, before javac runs.
 *
 * <p>The directory it generated into becomes a compile source root of the project, so the compiler of this
 * build — and every consumer of the project model, an IDE import included — compiles what was generated without
 * the POM naming that directory itself.</p>
 */
public final class GenerateMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        Request request = mainRequest();
        run("generate", request);
        project().addCompileSourceRoot(request.sourcesOutput());
    }
}
