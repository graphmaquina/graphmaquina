package io.stategraph.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

import java.util.List;

/**
 * Passes the generator's annotation manifest to javac, as the option the StateGraph annotation processor reads.
 *
 * <p>The manifest is declared as an input file of the compilation rather than folded into a plain compiler
 * argument: what matters is its content, so a regeneration that changes what the processor must see invalidates
 * the compilation, while the absolute path the option carries never does.</p>
 */
public abstract class ManifestArguments implements CommandLineArgumentProvider {

    /**
     * The manifest the generator wrote for this compilation.
     *
     * @return the annotation manifest
     */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getManifest();

    @Override
    public Iterable<String> asArguments() {
        return List.of("-Astategraph.annotationManifest="
                + getManifest().get().getAsFile().getAbsolutePath());
    }
}
