package io.stategraph.codegen.output;

import io.stategraph.codegen.CodegenException;
import io.stategraph.codegen.GenerationRequest;
import io.stategraph.codegen.GenerationResult;
import io.stategraph.codegen.manifest.CompileManifest;
import io.stategraph.codegen.manifest.ManifestCodec;
import io.stategraph.codegen.model.GeneratedSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Publishes one generation as a coherent set: every source is staged first, the whole set replaces the sources
 * the previous run owned, and the manifest is written last.
 *
 * <p>Only the files the previous manifest listed are removed, so a hand-written file that happens to live under
 * the generated source root is never touched, and a generated type that would overwrite an original source is
 * refused instead. A run that fails at any point deletes the manifest, so no build ever reads a manifest that
 * does not describe the sources on disk. The files carry exactly the emitted text in UTF-8 with LF endings, so a
 * run that changes nothing writes byte-identical files.</p>
 */
public final class GenerationOutput {

    private static final String MANIFEST = "annotation-manifest.json";
    private static final String STAGING = "generated-staging";
    private static final String COLLISION = "SG603";

    private GenerationOutput() {
    }

    /**
     * Writes {@code sources} under {@code request.sourcesOutput()} and {@code manifest} in the work directory.
     *
     * @throws CodegenException {@code SG603} if a generated type would overwrite an original source
     * @throws IOException if the staged sources, the previous manifest or the published files cannot be handled
     */
    public static GenerationResult publish(GenerationRequest request, List<GeneratedSource> sources,
            CompileManifest manifest) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(manifest, "manifest");
        Path work = Files.createDirectories(request.workDirectory().toAbsolutePath());
        Path manifestPath = work.resolve(MANIFEST);
        Path staging = work.resolve(STAGING);
        try {
            delete(staging);
            List<Path> staged = stage(request, sources, staging);
            Path output = Files.createDirectories(request.sourcesOutput().toAbsolutePath());
            removePreviouslyOwned(manifestPath, output);
            List<Path> published = publish(staging, staged, output);
            ManifestCodec.writeCompile(manifestPath, manifest);
            return new GenerationResult(published, manifestPath);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(manifestPath);
            throw failure;
        } finally {
            delete(staging);
        }
    }

    /** Writes every source under {@code staging}, refusing one that would overwrite an original source. */
    private static List<Path> stage(GenerationRequest request, List<GeneratedSource> sources, Path staging)
            throws IOException {
        List<Path> staged = new ArrayList<>();
        for (GeneratedSource source : sources) {
            String relative = source.qualifiedName().replace('.', '/') + ".java";
            requireNoOriginal(request, source.qualifiedName(), relative);
            Path file = staging.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.content(), StandardCharsets.UTF_8);
            staged.add(file);
        }
        return staged;
    }

    /**
     * Refuses a generated type an original source already declares.
     *
     * @throws CodegenException {@code SG603} naming the generated type and the source it would overwrite
     */
    private static void requireNoOriginal(GenerationRequest request, String qualifiedName, String relative) {
        Path candidate = Path.of(relative);
        for (Path source : request.sources()) {
            Path original = Files.isDirectory(source) ? source.resolve(candidate) : source;
            if (original.endsWith(candidate) && Files.isRegularFile(original)) {
                throw new CodegenException(COLLISION, "the generated type " + qualifiedName
                    + " would overwrite the original source " + original);
            }
        }
    }

    /** Deletes the files the previous manifest listed, which are the only files this generation owns. */
    private static void removePreviouslyOwned(Path manifestPath, Path output) throws IOException {
        if (!Files.isRegularFile(manifestPath)) {
            return;
        }
        for (String owned : ManifestCodec.readCompile(manifestPath).ownedSources()) {
            Files.deleteIfExists(output.resolve(owned));
        }
    }

    /** Moves the staged files into {@code output}, keeping the package path each of them was staged at. */
    private static List<Path> publish(Path staging, List<Path> staged, Path output) throws IOException {
        List<Path> published = new ArrayList<>();
        for (Path file : staged) {
            Path target = output.resolve(staging.relativize(file).toString());
            Files.createDirectories(target.getParent());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            published.add(target);
        }
        return List.copyOf(published);
    }

    /** Removes {@code directory} and everything below it, deepest entry first. */
    private static void delete(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
