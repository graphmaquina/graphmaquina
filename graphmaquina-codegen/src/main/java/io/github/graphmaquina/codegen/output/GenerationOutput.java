package io.github.graphmaquina.codegen.output;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.GenerationResult;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

/**
 * Publishes one generation as a coherent set: every source is staged first, the whole set replaces the sources
 * the previous run owned, and the manifest is written last.
 *
 * <p>Only the files the previous manifest listed are removed, so a hand-written file that happens to live under
 * the generated source root is never touched, and a generated type that would overwrite an original source is
 * refused instead. A run that fails at any point deletes the manifest, so no build ever reads a manifest that
 * does not describe the sources on disk. A separate pending manifest retains ownership across failures,
 * including a partially published generation, so the next run can retire its files. The files carry exactly
 * the emitted text in UTF-8 with LF endings, so a
 * run that changes nothing writes byte-identical files.</p>
 */
public final class GenerationOutput {

    private static final String MANIFEST = "annotation-manifest.json";
    private static final String PENDING = "annotation-manifest.pending.json";
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
        Path manifestPath = manifestPath(request);
        Path pending = work.resolve(PENDING);
        Path staging = work.resolve(STAGING);
        try {
            Set<String> previouslyOwned = new TreeSet<>();
            for (Path inventory : List.of(manifestPath, pending)) {
                if (Files.isRegularFile(inventory)) {
                    previouslyOwned.addAll(ManifestCodec.readCompile(inventory).ownedSources());
                }
            }
            // Save the last generation before anything that can fail invalidates its active manifest.
            remember(pending, manifest, previouslyOwned);
            delete(staging);
            requireNoClasspathCollision(request, sources);
            List<Path> staged = stage(request, sources, staging, previouslyOwned);
            Path output = Files.createDirectories(request.sourcesOutput().toAbsolutePath());
            Set<String> publishing = new TreeSet<>(previouslyOwned);
            publishing.addAll(manifest.ownedSources());
            // Only claim new targets after stage has established that none belongs to somebody else.
            remember(pending, manifest, publishing);
            for (String owned : previouslyOwned) {
                Files.deleteIfExists(output.resolve(owned));
            }
            List<Path> published = publish(staging, staged, output);
            ManifestCodec.writeCompile(manifestPath, manifest);
            Files.delete(pending);
            return new GenerationResult(published, manifestPath);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(manifestPath);
            throw failure;
        } finally {
            delete(staging);
        }
    }

    /**
     * Where the combined manifest of {@code request} lives, which is what {@link CompilationOutputs} reads to
     * know the generator ran for this very compilation before javac did.
     */
    static Path manifestPath(GenerationRequest request) {
        return request.workDirectory().toAbsolutePath().resolve(MANIFEST);
    }

    /** Writes every source under {@code staging}, refusing one that would overwrite an original source. */
    private static List<Path> stage(GenerationRequest request, List<GeneratedSource> sources, Path staging,
            Set<String> previouslyOwned)
            throws IOException {
        List<Path> staged = new ArrayList<>();
        for (GeneratedSource source : sources) {
            String relative = source.qualifiedName().replace('.', '/') + ".java";
            requireNoOriginal(request, source.qualifiedName(), relative);
            Path target = request.sourcesOutput().resolve(relative);
            if (Files.exists(target) && !previouslyOwned.contains(relative)) {
                throw new CodegenException(COLLISION, "the generated type " + source.qualifiedName()
                    + " would overwrite the unowned file " + target);
            }
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

    /** javac allows a source to shadow a dependency class, so reject that collision before publishing sources. */
    private static void requireNoClasspathCollision(GenerationRequest request, List<GeneratedSource> sources)
            throws IOException {
        Path classes = request.classesOutput().toAbsolutePath().normalize();
        for (Path entry : request.classpath()) {
            if (entry.toAbsolutePath().normalize().equals(classes)) {
                continue; // A caller may include the output of this same compilation from its previous run.
            }
            if (Files.isDirectory(entry)) {
                for (GeneratedSource source : sources) {
                    if (Files.isRegularFile(entry.resolve(source.qualifiedName().replace('.', '/') + ".class"))) {
                        throw classpathCollision(source, entry);
                    }
                }
            } else if (Files.isRegularFile(entry)) {
                try (JarFile jar = new JarFile(entry.toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
                    for (GeneratedSource source : sources) {
                        if (jar.getJarEntry(source.qualifiedName().replace('.', '/') + ".class") != null) {
                            throw classpathCollision(source, entry);
                        }
                    }
                }
            }
        }
    }

    private static CodegenException classpathCollision(GeneratedSource source, Path entry) {
        return new CodegenException(COLLISION, "the generated type " + source.qualifiedName()
                + " would shadow a class from " + entry);
    }

    /** Atomically retains ownership without making an incomplete generation usable by the compiler. */
    private static void remember(Path pending, CompileManifest manifest, Set<String> owned) throws IOException {
        Path temporary = pending.resolveSibling(pending.getFileName() + ".tmp");
        ManifestCodec.writeCompile(temporary, new CompileManifest(manifest.schemaVersion(), manifest.toolVersion(),
            manifest.local(), manifest.dependencies(), manifest.processorInventory(), List.copyOf(owned)));
        try {
            Files.move(temporary, pending, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, pending, StandardCopyOption.REPLACE_EXISTING);
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
