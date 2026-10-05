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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

/**
 * Publishes one generation as a coherent set: every source is staged first, the whole set replaces the sources
 * the previous run owned, and the manifest is written last.
 *
 * <p>Only the files the previous manifest listed are removed, so a hand-written file that happens to live under
 * the generated source root is never touched, and a generated type an original source already declares, in the
 * file it would overwrite or in a file of another name, is refused instead. A publication that fails once it has
 * read the previous manifests deletes the manifest, so no build ever reads a manifest that does not describe the
 * sources on disk. A previous manifest that cannot be read at all, such as the retired format of GraphMaquina
 * 1.x, is left in place instead, so the refusal repeats until the user cleans the build rather than the next run
 * calling the files it owned unowned. A separate pending manifest retains ownership across failures, including a
 * partially published generation, so the next run can retire its files. Both manifests are written beside their
 * place and moved into it, so an interrupted run never leaves half of one. The files carry exactly the emitted
 * text in UTF-8 with LF endings, so a run that changes nothing writes byte-identical files.</p>
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
     * @throws CodegenException {@code SG603} if a generated type is already declared by an original source,
     *     would shadow a class of the classpath or would overwrite a file the previous generation did not own;
     *     {@code SG602} if a previous manifest cannot be read, which leaves that manifest in place
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
        // Read outside the failure path below: a previous manifest this run cannot read stays where it is, so the
        // refusal repeats until the user cleans the build instead of the files it owned turning unowned.
        Set<String> previouslyOwned = new TreeSet<>();
        for (Path inventory : List.of(manifestPath, pending)) {
            if (Files.isRegularFile(inventory)) {
                previouslyOwned.addAll(ManifestCodec.readCompile(inventory).ownedSources());
            }
        }
        try {
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
            replace(manifestPath, manifest);
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

    /**
     * Writes every source under {@code staging}, refusing one an original source already declares and one that
     * would overwrite a file the previous generation did not own.
     */
    private static List<Path> stage(GenerationRequest request, List<GeneratedSource> sources, Path staging,
            Set<String> previouslyOwned)
            throws IOException {
        Set<String> generated = new HashSet<>();
        for (GeneratedSource source : sources) {
            generated.add(source.qualifiedName());
        }
        Map<String, String> declared = OriginalTypes.declaring(request, generated);
        List<Path> staged = new ArrayList<>();
        for (GeneratedSource source : sources) {
            String relative = source.qualifiedName().replace('.', '/') + ".java";
            requireNoOriginal(request, source.qualifiedName(), relative, declared);
            Path target = request.sourcesOutput().resolve(relative);
            if (Files.exists(target) && !ownedBefore(request.sourcesOutput(), target, relative, previouslyOwned)) {
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
     * Whether the existing file at {@code target} is one the previous generation owned. A case-insensitive volume
     * also finds a file whose name differs from {@code relative} in case only, as after a machine is renamed in
     * case; that file is still the previous generation's own when the name it carries on disk is one it listed.
     * Symbolic links are left unresolved, so a link somebody placed there stays somebody else's file.
     */
    private static boolean ownedBefore(Path root, Path target, String relative, Set<String> previouslyOwned)
            throws IOException {
        if (previouslyOwned.contains(relative)) {
            return true;
        }
        Path onDisk = root.toRealPath(LinkOption.NOFOLLOW_LINKS)
                .relativize(target.toRealPath(LinkOption.NOFOLLOW_LINKS));
        return previouslyOwned.contains(onDisk.toString().replace(onDisk.getFileSystem().getSeparator(), "/"));
    }

    /**
     * Refuses a generated type an original source already declares: one whose file would overwrite an original
     * source, or one that an original source declares as a top-level type in a file of another name, which javac
     * would only report as a duplicate class.
     *
     * @param declared the original source declaring each generated type that one declares, as
     *     {@link OriginalTypes#declaring} finds it
     * @throws CodegenException {@code SG603} naming the generated type and the original source
     */
    private static void requireNoOriginal(GenerationRequest request, String qualifiedName, String relative,
            Map<String, String> declared) {
        Path candidate = Path.of(relative);
        for (Path source : request.sources()) {
            Path original = Files.isDirectory(source) ? source.resolve(candidate) : source;
            if (original.endsWith(candidate) && Files.isRegularFile(original)) {
                throw new CodegenException(COLLISION, "the generated type " + qualifiedName
                    + " would overwrite the original source " + original);
            }
        }
        String declaring = declared.get(qualifiedName);
        if (declaring != null) {
            throw new CodegenException(COLLISION, "the generated type " + qualifiedName
                + " is already declared by the original source " + declaring);
        }
    }

    /**
     * javac allows a source to shadow a dependency class, so reject that collision before publishing sources. A
     * directory entry is searched for the class file of each generated type, and so is a regular file that opens
     * as an archive. A regular file that does not open as one, whatever its name, is skipped, since no class can
     * be read from it; this run's own class output is skipped too.
     */
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
                } catch (ZipException notAnArchive) {
                    // Not an archive, whatever its name, so it holds no class a generated type could shadow.
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
        replace(pending, new CompileManifest(manifest.schemaVersion(), manifest.toolVersion(),
            manifest.local(), manifest.dependencies(), manifest.processorInventory(), List.copyOf(owned)));
    }

    /**
     * Writes {@code manifest} beside {@code target} and moves it into place, so no run is ever left with half a
     * manifest, which every later run would refuse to read.
     */
    private static void replace(Path target, CompileManifest manifest) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        ManifestCodec.writeCompile(temporary, manifest);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
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
