package io.stategraph.processor;

import io.stategraph.annotation.Action;
import io.stategraph.annotation.Cell;
import io.stategraph.annotation.Cells;
import io.stategraph.annotation.Forbid;
import io.stategraph.annotation.Forbids;
import io.stategraph.annotation.GenerateGraphApi;
import io.stategraph.annotation.GraphAction;
import io.stategraph.annotation.GraphBindings;
import io.stategraph.annotation.GraphCondition;
import io.stategraph.annotation.GraphGuard;
import io.stategraph.annotation.GraphHook;
import io.stategraph.annotation.GraphPeers;
import io.stategraph.annotation.GraphProperty;
import io.stategraph.annotation.Move;
import io.stategraph.annotation.Row;
import io.stategraph.annotation.Rows;
import io.stategraph.annotation.StateGraph;
import io.stategraph.annotation.StateGraphs;
import io.stategraph.annotation.Timeout;
import io.stategraph.annotation.Watch;
import io.stategraph.annotation.Watches;
import io.stategraph.annotation.When;
import io.stategraph.annotation.internal.GeneratedSymbol;
import io.stategraph.annotation.internal.GeneratedVocabulary;
import io.stategraph.annotation.internal.GraphCompilation;
import io.stategraph.codegen.CodegenException;
import io.stategraph.codegen.manifest.CompileManifest;
import io.stategraph.codegen.manifest.ExportIndex;
import io.stategraph.codegen.manifest.ManifestCodec;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.Generated;
import javax.annotation.processing.ProcessingEnvironment;

/**
 * The exact set of annotation types the processor claims for one compilation: the fixed ones it always owns
 * plus the ones this compilation's manifest lists, once every one of them has been matched against the real
 * declarations javac sees.
 *
 * <p>The set is enumerated, never a wildcard: a pattern would claim annotations of other tools that happen to
 * share a package, and it could not name a generated type at all. The fixed part is known before {@code init},
 * because javac may ask for the supported types of a processor it has not initialized; the union is published
 * only after a manifest has been read and checked.</p>
 *
 * <p>A manifest that cannot be read, that was written by another version of the tool or that no longer
 * describes the declarations of this compilation is not repaired and is not partially trusted: the registry
 * keeps the fixed set, records why, and the processor reports that reason as {@code SG602} from its first
 * round. {@code init} has no round to anchor a diagnostic at, which is why the failure is recorded here
 * instead of being reported here.</p>
 *
 * <p>A compilation that names no manifest at all is refused the same way: a graph is declared with the
 * vocabulary a generation emitted, so a build that ran no generation has nothing to declare one with. The
 * fixed set is still claimed, so the declarations that were written are met with {@code SG602} and not with an
 * annotation nobody owns.</p>
 */
final class ManifestRegistry {

    /** The javac option carrying the path of the manifest the generator wrote for this compilation. */
    static final String OPTION = "stategraph.annotationManifest";

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
        return Set.copyOf(types);
    }

    /**
     * The annotations this processor owns whatever a compilation generates: every stable marker, the internal
     * metadata the generator writes, the deprecated v0.2 vocabulary it only claims to report {@code SG609} at,
     * and the {@code @Generated} it writes itself, so it coexists with other processors instead of claiming a
     * package.
     */
    @SuppressWarnings("deprecation")
    private static Set<String> fixed() {
        List<Class<? extends Annotation>> types = List.of(
            GenerateGraphApi.class, GraphPeers.class, GraphAction.class, GraphGuard.class, GraphHook.class,
            GraphCondition.class, GraphProperty.class, GraphBindings.class, Timeout.class,
            GeneratedVocabulary.class, GeneratedSymbol.class, GraphCompilation.class,
            StateGraph.class, StateGraphs.class, Row.class, Rows.class, Action.class, Move.class,
            Cell.class, Cells.class, Forbid.class, Forbids.class, Watch.class, Watches.class, When.class,
            Generated.class);
        Set<String> names = new TreeSet<>();
        for (Class<? extends Annotation> type : types) {
            names.add(type.getCanonicalName());
        }
        return Set.copyOf(names);
    }
}
