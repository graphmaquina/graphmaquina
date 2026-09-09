package io.stategraph.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Passes the generator's annotation manifest to javac, as the option the StateGraph annotation processor reads.
 *
 * <p>The manifest is declared as an input file of the compilation rather than folded into a plain compiler
 * argument: what matters is its content, so a regeneration that changes what the processor must see invalidates
 * the compilation, while the absolute path the option carries never does.</p>
 *
 * <p>It is held as a collection rather than as a single file because whether this compilation has a manifest at
 * all is only known once the build script has been read: a source set that declares no StateGraph processor
 * leaves the collection empty, and this provider then contributes no argument.</p>
 */
public abstract class ManifestArguments implements CommandLineArgumentProvider {

    /**
     * The manifest the generator wrote for this compilation, or nothing when the source set takes no part.
     *
     * @return the annotation manifest
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getManifest();

    @Override
    public Iterable<String> asArguments() {
        Set<File> manifests = getManifest().getFiles();
        return manifests.isEmpty()
                ? List.of()
                : List.of("-Astategraph.annotationManifest=" + manifests.iterator().next().getAbsolutePath());
    }
}
