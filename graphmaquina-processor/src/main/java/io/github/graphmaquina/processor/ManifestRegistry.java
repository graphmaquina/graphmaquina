package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.GraphAction;
import io.github.graphmaquina.annotation.GraphBindings;
import io.github.graphmaquina.annotation.GraphCondition;
import io.github.graphmaquina.annotation.GraphGuard;
import io.github.graphmaquina.annotation.GraphHook;
import io.github.graphmaquina.annotation.GraphLookup;
import io.github.graphmaquina.annotation.GraphPeers;
import io.github.graphmaquina.annotation.GraphProperty;
import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.annotation.Timeout;
import io.github.graphmaquina.annotation.internal.GeneratedCompanion;
import io.github.graphmaquina.annotation.internal.GeneratedSymbol;
import io.github.graphmaquina.annotation.internal.GeneratedVocabulary;
import io.github.graphmaquina.annotation.internal.GraphCompilation;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.ProcessingEnvironment;

/**
 * The exact set of annotation types the processor claims for one compilation: the fixed ones it always owns
 * plus the ones this compilation's manifest lists, once every one of them has been matched against the real
 * declarations javac sees.
 *
 * <p>The set is enumerated, never a wildcard: a pattern would claim annotations of other tools that happen to
 * share a package, and it could not name a generated type at all. The fixed part is known before {@code init}, so a
 * processor asked for its supported types before it is initialized answers with it; the union is published only
 * after a manifest has been read and checked.</p>
 *
 * <p>A manifest that cannot be read, that was written by another version of the tool or that no longer
 * describes the declarations of this compilation is not repaired and is not partially trusted: the registry
 * keeps the fixed set, records why, and the processor reports that reason as {@code SG602} from its first
 * round. {@code init} has no round to anchor a diagnostic at, which is why the failure is recorded here
 * instead of being reported here.</p>
 *
 * <p>A compilation that names no manifest at all is refused the same way once it carries one of these
 * declarations: a graph is declared with the vocabulary a generation emitted, so a build that ran no
 * generation has nothing to declare one with. The fixed set is still claimed, so the declarations that were
 * written are met with {@code SG602} and not with an annotation nobody owns. A compilation that carries none of
 * them, such as one whose only annotation is the {@code @Generated} of another tool, never reaches the
 * processor, and is neither refused nor claimed.</p>
 */
final class ManifestRegistry {

    /** The javac option carrying the path of the manifest the generator wrote for this compilation. */
    static final String OPTION = "graphmaquina.annotationManifest";

    private static final Set<String> FIXED = fixed();

    private final Set<String> supported;
    private final Optional<CompileManifest> manifest;
    private final Optional<String> failure;

    private ManifestRegistry(Set<String> supported, Optional<CompileManifest> manifest, Optional<String> failure) {
        this.supported = supported;
        this.manifest = manifest;
        this.failure = failure;
    }

    /** The registry a processor holds before {@code init}: the fixed set and nothing else. */
    static ManifestRegistry unloaded() {
        return new ManifestRegistry(FIXED, Optional.empty(), Optional.empty());
    }

    /**
     * Reads the manifest named by {@link #OPTION} and checks it against the declarations of {@code environment}.
     * A registry whose {@link #failure()} is present claims the fixed set alone.
     */
    static ManifestRegistry load(ProcessingEnvironment environment) {
        String option = environment.getOptions().get(OPTION);
        if (option == null || option.isBlank()) {
            return failed("this compilation names no annotation manifest: run the StateGraph generator before"
                    + " javac and pass -A" + OPTION + "=<its annotation-manifest.json>");
        }
        Path file;
        try {
            file = Path.of(option);
        } catch (InvalidPathException invalid) {
            return failed("the annotation manifest " + option + " is not a usable path: " + invalid.getMessage());
        }
        if (!Files.isRegularFile(file)) {
            return failed("the annotation manifest " + file + " does not exist");
        }
        CompileManifest read;
        try {
            read = ManifestCodec.readCompile(file);
        } catch (IOException | CodegenException failure) {
            return failed("the annotation manifest " + file + " cannot be read: " + failure.getMessage());
        }
        Optional<String> problem = ManifestChecks.verify(read, environment.getElementUtils());
        if (problem.isPresent()) {
            return failed("the annotation manifest " + file + " " + problem.get());
        }
        return new ManifestRegistry(union(read), Optional.of(read), Optional.empty());
    }

    /** Every annotation type javac must match this processor against, fixed and generated alike. */
    Set<String> supportedAnnotationTypes() {
        return supported;
    }

    /** The manifest of this compilation, empty when none could be trusted. */
    Optional<CompileManifest> manifest() {
        return manifest;
    }

    /** Why no manifest could be trusted, to be reported once as {@code SG602}. */
    Optional<String> failure() {
        return failure;
    }

    private static ManifestRegistry failed(String reason) {
        return new ManifestRegistry(FIXED, Optional.empty(), Optional.of(reason));
    }

    /** The fixed set together with every annotation type {@code manifest} lists, local and dependencies. */
    private static Set<String> union(CompileManifest manifest) {
        Set<String> types = new LinkedHashSet<>(FIXED);
        types.addAll(manifest.local().annotationTypes());
        for (ExportIndex dependency : manifest.dependencies()) {
            types.addAll(dependency.annotationTypes());
        }
        return Collections.unmodifiableSet(types);
    }

    /**
     * The annotations this processor owns whatever a compilation generates: every stable marker, the internal
     * metadata the generator writes, the {@code @GeneratedCompanion} the processor marks its own companions
     * with, so it coexists with other processors instead of claiming a package. Nothing here belongs to another
     * tool: the JDK's {@code @Generated}, which other source generators write, is left to the processors that
     * read it.
     */
    private static Set<String> fixed() {
        List<Class<? extends Annotation>> types = List.of(
            GenerateGraphApi.class, GraphPeers.class, GraphAction.class, GraphGuard.class, GraphHook.class,
            GraphCondition.class, GraphLookup.class, GraphProperty.class, GraphBindings.class, Timeout.class,
            GeneratedVocabulary.class, GeneratedSymbol.class, GraphCompilation.class, GeneratedCompanion.class,
            StateGraphs.class);
        Set<String> names = new TreeSet<>();
        for (Class<? extends Annotation> type : types) {
            names.add(type.getCanonicalName());
        }
        return Collections.unmodifiableSet(names);
    }
}
