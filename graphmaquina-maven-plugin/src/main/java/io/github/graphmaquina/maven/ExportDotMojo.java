package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code export-dot} goal: writes the Graphviz DOT of every StateGraph machine of the main compilation into
 * {@code target/graphmaquina/main/dot}, one file per machine named after the qualified name of its facade, once that
 * compilation completed.
 *
 * <p>Its descriptor binds it to no phase and forks the default lifecycle up to {@code compile} before it runs, so
 * {@code mvn graphmaquina:export-dot} alone generates, compiles and completes the main compilation first. It does
 * nothing, and says so, when the build compiles no main sources, as {@code generate} does, and when the compilation
 * publishes no StateGraph index, as a module that binds none of this plugin's goals leaves it.</p>
 */
public final class ExportDotMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        if (skips(CompilerExecution.MAIN)) {
            return;
        }
        export(CompilerExecution.MAIN);
    }
}
