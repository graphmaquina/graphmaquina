package io.github.graphmaquina.codegen.output;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.GenerationResult;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.codegen.source.Bootstrap;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <p>Only the files the previous manifest and the pending one listed are removed, so a hand-written file that
 * happens to live under the generated source root is never touched, and a generated type an original source already
 * declares, in the file it would overwrite or in a file of another name, is refused instead. So is one whose file or
 * class file a volume that does not tell names apart by case would take for an original's, and so are two generated
 * types whose files or class files such a volume would take for one another's. The original sources it
 * is checked against are the ones the generator read, as the one walk of {@link Bootstrap} read them; below every
 * directory the request names, a file standing where a generated file would stand is refused as well. A listed file
 * is removed only below the generated source root: one whose path does not resolve below it, or whose directory, by
 * its real path, a symbolic link inside the root takes out of it, is left alone. A generated source that such a link
 * would take out of the root is refused, whether it would replace a file there, even one the previous generation
 * listed, or create one. A separate pending manifest retains ownership across failures, including a partially
 * published generation, so the next run can retire its files: before it touches any source, a publication records
 * there the sources the previous manifests list. A publication that fails after that deletes the manifest, so no
 * build ever reads a manifest that does not describe the sources on disk. One that fails before it, as when a
 * previous manifest cannot be read or the first write of the pending manifest fails, leaves the manifest in place,
 * since no source has changed yet. A previous manifest that cannot be read at all, such as the retired format of
 * GraphMaquina 1.x, therefore stays, so the refusal repeats until the user cleans the build rather than the next run
 * calling the files it owned unowned. Both manifests are written beside their place and moved into it, so an
 * interrupted run never leaves half of one.</p>
 *
 * <p>The files carry exactly the emitted text with LF endings, in the encoding of the compilation that reads
 * them, so a run that changes nothing writes byte-identical files. A character that encoding cannot represent is
 * written as the Unicode escape javac reads in any encoding.</p>
 */
public final class GenerationOutput {

    private static final String MANIFEST = "annotation-manifest.json";
    private static final String PENDING = "annotation-manifest.pending.json";
    private static final String STAGING = "generated-staging";
    private static final String COLLISION = "SG603";

    private GenerationOutput() {
    }

    /**
     * Writes {@code sources} under {@code request.sourcesOutput()} and {@code manifest} in the work directory,
     * checking the generated types against the original sources of {@code request}. It reads those sources itself,
     * as {@link Bootstrap#originals} reads them: it walks and parses them, and that reading resolves nothing and
     * opens no classpath. The checks that follow are the ones of the four-argument form, which looks for a class a
     * generated type would shadow in every entry of the classpath but the class output, opening each file there as
     * an archive.
     *
     * @throws CodegenException {@code SG603} if a generated type is already declared by an original source, or
     *     takes the name of one, or of another generated type, where the volume does not tell names apart by case,
     *     would shadow a class of the classpath, would overwrite a file the previous generation did not own or
     *     would be written through a symbolic link inside the generated source output that leads out of it;
     *     {@code SG602} if a previous manifest cannot be read, which leaves that manifest in place; {@code SG601} if
     *     javac cannot read or parse an original source, as when the file may not be read
     * @throws java.io.UncheckedIOException if a source directory, or a directory below it, cannot be walked, or the
     *     file manager javac read the original sources with cannot be closed
     * @throws IOException if the work directory, the manifests or the staged sources in it, or the published files
     *     cannot be handled, a file of the classpath cannot be read, or the original sources and the class output
     *     the collision check looks up cannot be examined
     */
    public static GenerationResult publish(GenerationRequest request, List<GeneratedSource> sources,
            CompileManifest manifest) throws IOException {
        Objects.requireNonNull(request, "request");
        return publish(request, sources, manifest, Bootstrap.originals(request));
    }

    /**
     * Writes {@code sources} under {@code request.sourcesOutput()} and {@code manifest} in the work directory,
     * checking the generated types against {@code originals}, the original sources the generation read.
     *
     * @param request the request the sources were generated for
     * @param sources the generated sources
     * @param manifest the manifest of the compilation that follows
     * @param originals every original source the generation of {@code sources} read, as
     *     {@link Bootstrap#read} and {@link Bootstrap#originals} report them
     * @return the published sources and the manifest
     * @throws CodegenException {@code SG603} if a generated type is already declared by an original source, or
     *     takes the name of one, or of another generated type, where the volume does not tell names apart by case,
     *     would shadow a class of the classpath, would overwrite a file the previous generation did not own or
     *     would be written through a symbolic link inside the generated source output that leads out of it;
     *     {@code SG602} if a previous manifest cannot be read, which leaves that manifest in place
     * @throws IOException if the work directory, the manifests or the staged sources in it, or the published files
     *     cannot be handled, a file of the classpath cannot be read, or the original sources and the class output
     *     the collision check looks up cannot be examined
     */
    public static GenerationResult publish(GenerationRequest request, List<GeneratedSource> sources,
            CompileManifest manifest, List<Bootstrap.OriginalSource> originals) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(originals, "originals");
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
        // Save the last generation before anything that can fail invalidates its active manifest. A failure to save it
        // leaves that manifest in place, since no source has changed yet.
        remember(pending, manifest, previouslyOwned);
        try {
            delete(staging);
            requireNoClasspathCollision(request, sources);
            requireDistinctInCase(request, sources, manifest.local().model().symbols());
            List<Path> staged = stage(request, sources, staging, previouslyOwned, originals);
            Path output = Files.createDirectories(request.sourcesOutput().toAbsolutePath());
            Set<String> publishing = new TreeSet<>(previouslyOwned);
            publishing.addAll(manifest.ownedSources());
            // Only claim new targets after stage has established that none belongs to somebody else.
            remember(pending, manifest, publishing);
            Path root = output.normalize();
            for (String owned : previouslyOwned) {
                Path target = output.resolve(owned);
                Path resolved = target.normalize();
                if (resolved.startsWith(root) && !resolved.equals(root)
                        && CompilationOutputs.standsUnder(target, output)) {
                    Files.deleteIfExists(target);
                }
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
     * Writes every source under {@code staging}, in the encoding of the compilation, refusing one an original
     * source already declares, one that takes the name of an original source or type where the volume does not
     * tell names apart by case, one that a symbolic link inside the generated source output would take out of it,
     * and one that would overwrite a file the previous generation did not own.
     */
    private static List<Path> stage(GenerationRequest request, List<GeneratedSource> sources, Path staging,
            Set<String> previouslyOwned, List<Bootstrap.OriginalSource> originals)
            throws IOException {
        List<Path> directories = new ArrayList<>();
        for (Path source : request.sources()) {
            if (Files.isDirectory(source)) {
                directories.add(source);
            }
        }
        // Keyed by name in one case, so the names that differ from a generated one only in case are found as well.
        Map<String, List<Bootstrap.OriginalSource>> standing = new HashMap<>();
        Map<String, List<Declared>> declaring = new HashMap<>();
        for (Bootstrap.OriginalSource original : originals) {
            standing.computeIfAbsent(CaseFolding.key(original.path()), (String key) -> new ArrayList<>()).add(original);
            for (String type : original.types()) {
                declaring.computeIfAbsent(CaseFolding.key(type), (String key) -> new ArrayList<>())
                        .add(new Declared(type, original.origin()));
            }
        }
        List<Path> staged = new ArrayList<>();
        for (GeneratedSource source : sources) {
            String relative = source.qualifiedName().replace('.', '/') + ".java";
            requireNoOriginal(source.qualifiedName(), relative, directories,
                    standing.getOrDefault(CaseFolding.key(relative), List.of()),
                    declaring.getOrDefault(CaseFolding.key(source.qualifiedName()), List.of()),
                    request.classesOutput());
            Path target = request.sourcesOutput().resolve(relative);
            if (!writesUnder(request.sourcesOutput(), target)) {
                throw new CodegenException(COLLISION, "the generated type " + source.qualifiedName()
                    + " would be written through a symbolic link that leads out of " + request.sourcesOutput()
                    + ": " + target);
            }
            if (Files.exists(target) && !ownedBefore(request.sourcesOutput(), target, relative, previouslyOwned)) {
                throw new CodegenException(COLLISION, "the generated type " + source.qualifiedName()
                    + " would overwrite the unowned file " + target);
            }
            Path file = staging.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.write(file, encoded(source.content(), request.encoding()));
            staged.add(file);
        }
        return staged;
    }

    /**
     * {@code text} in {@code encoding}, with every character that encoding cannot represent written as the Unicode
     * escape javac reads in any encoding, so the compilation reads back exactly the emitted text. A generated source
     * holds a backslash only inside the string literal of the carrier, where each backslash belongs to one of its
     * escape sequences, so no escape written here follows a backslash that would turn it into plain text. An encoding
     * that only decodes, such as {@code ISO-2022-CN}, reads ASCII as itself, so the text is then written in ASCII and
     * escaped.
     */
    private static byte[] encoded(String text, Charset encoding) {
        CharsetEncoder encoder = (encoding.canEncode() ? encoding : StandardCharsets.US_ASCII).newEncoder();
        if (encoder.canEncode(text)) {
            return text.getBytes(encoder.charset());
        }
        StringBuilder escaped = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            String character = Character.toString(codePoint);
            if (encoder.canEncode(character)) {
                escaped.append(character);
            } else {
                for (char unit : character.toCharArray()) {
                    escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) unit));
                }
            }
            index += Character.charCount(codePoint);
        }
        return escaped.toString().getBytes(encoder.charset());
    }

    /**
     * Whether publishing {@code target} writes nothing through a symbolic link that leads out of {@code root}: the
     * nearest of its directories that exists, looking no higher than {@code root}, must
     * {@linkplain CompilationOutputs#standsUnder stand under} {@code root} by its real path. True when none of them
     * exists as a directory, {@code root} included: publishing then creates them, or fails where something else, such
     * as a dangling link, stands in the way.
     */
    private static boolean writesUnder(Path root, Path target) throws IOException {
        Path directory = target.getParent() == null ? root : target.getParent();
        while (!Files.isDirectory(directory) && !directory.equals(root) && directory.getParent() != null) {
            directory = directory.getParent();
        }
        return !Files.isDirectory(directory)
                || CompilationOutputs.standsUnder(directory.resolve(target.getFileName()), root);
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
     * Refuses a generated type an original source already declares: one whose file would stand where an original
     * source stands below its source root, or one that an original source declares as a top-level type in a file
     * of another name, which javac would only report as a duplicate class.
     *
     * <p>Below a directory the request names, the generated file's path is looked up as the volume of that directory
     * resolves it. A case-insensitive source volume therefore also finds an original whose name differs only in
     * case, and that original is refused, whatever it declares. When it declares a top-level type named like the
     * generated type but for case, such as the type it is named after, and the volume of the class output does not
     * tell names apart by case either, javac compiling both would also write their two class files as one file there
     * and keep one of them: by default without saying so, while the {@code output-file-clash} lint, which
     * {@code -Xlint:all} turns on, warns about it, and {@code -Werror} turns that warning into a failure. An original
     * that declares no such type loses no class file. Every original source is also compared by the path it stands
     * at, whole: for a file the request names itself, that is the path its package declaration gives it. An original
     * source that merely ends like the generated file, in a package of another name, is therefore no collision.</p>
     *
     * <p>An original source whose path differs from the generated file's in the case of its file name alone, its
     * directories spelled the same, is looked up under the generated file name in its own directory. Where its
     * volume finds that very file there, the volume does not tell the two names apart, and the source is refused,
     * named by its real path. A top-level type whose qualified name differs from the generated type's only in case
     * is refused where the volume of the class output does not tell names apart by case: javac would write the two
     * class files as one file there and keep one of them, by default without saying so.</p>
     *
     * @param relative the path of the generated file below the generated source root
     * @param directories the directories the request names among its sources
     * @param standing the original sources whose path below their source root, as
     *     {@link Bootstrap.OriginalSource#path()} names it, is {@code relative} but for case
     * @param declaring the top-level types the original sources declare whose qualified name is
     *     {@code qualifiedName} but for case, with the source that declares each
     * @param classesOutput the class output of the compilation
     * @throws CodegenException {@code SG603} naming the generated type and the original source
     * @throws IOException if an original source found under another case, or the class output or a directory
     *     above it, cannot be examined
     */
    private static void requireNoOriginal(String qualifiedName, String relative, List<Path> directories,
            List<Bootstrap.OriginalSource> standing, List<Declared> declaring, Path classesOutput)
            throws IOException {
        for (Path directory : directories) {
            Path original = directory.resolve(relative);
            if (Files.isRegularFile(original)) {
                throw new CodegenException(COLLISION, "the generated type " + qualifiedName
                    + " would overwrite the original source " + original);
            }
        }
        for (Bootstrap.OriginalSource original : standing) {
            if (original.path().equals(relative)) {
                throw new CodegenException(COLLISION, "the generated type " + qualifiedName
                    + " would overwrite the original source " + original.file());
            }
        }
        String fileName = relative.substring(relative.lastIndexOf('/') + 1);
        for (Bootstrap.OriginalSource original : standing) {
            if (!directories(original.path()).equals(directories(relative))) {
                // The lookup asks the volume about the file name alone, and an original whose directories differ
                // in case may carry the generated file name itself, which the lookup finds on any volume.
                continue;
            }
            Path underGeneratedName = original.file().resolveSibling(fileName);
            if (Files.exists(underGeneratedName) && Files.isSameFile(underGeneratedName, original.file())) {
                throw new CodegenException(COLLISION, "the generated type " + qualifiedName
                    + " would overwrite the original source " + original.file().toRealPath());
            }
        }
        for (Declared declared : declaring) {
            if (declared.type().equals(qualifiedName)) {
                throw new CodegenException(COLLISION, "the generated type " + qualifiedName
                    + " is already declared by the original source " + declared.origin());
            }
        }
        if (!declaring.isEmpty() && CaseFolding.folds(classesOutput)) {
            Declared declared = declaring.get(0);
            throw new CodegenException(COLLISION, "the generated type " + qualifiedName + " differs only in case"
                + " from " + declared.type() + ", which the original source " + declared.origin() + " declares,"
                + " and the volume of the class output " + classesOutput + " does not tell their class files apart");
        }
    }

    /**
     * Refuses two generated types whose names differ only in case where a volume that holds their files does not
     * tell names apart by case, which would keep one file of the two. Two top-level types are refused where the
     * volume of the generated sources, of the work directory their sources are staged in or of the class output does
     * not, and two symbol types of one references container, whose class files alone are files of their own, where
     * the volume of the class output does not. The symbols are the ones of this compilation, in the order the local
     * index of the manifest lists them; a refusal of two of them names both of their members and is anchored at the
     * second.
     *
     * @throws CodegenException {@code SG603} naming both generated types and the volume
     * @throws IOException if an output directory, or a directory above it, cannot be examined
     */
    private static void requireDistinctInCase(GenerationRequest request, List<GeneratedSource> sources,
            List<SymbolDescriptor> symbols) throws IOException {
        Map<String, String> types = new HashMap<>();
        for (GeneratedSource source : sources) {
            String previous = types.putIfAbsent(CaseFolding.key(source.qualifiedName()), source.qualifiedName());
            if (previous == null || previous.equals(source.qualifiedName())) {
                continue;
            }
            Map<String, Path> volumes = new LinkedHashMap<>();
            volumes.put("the generated sources ", request.sourcesOutput());
            volumes.put("the work directory ", request.workDirectory());
            volumes.put("the class output ", request.classesOutput());
            for (Map.Entry<String, Path> volume : volumes.entrySet()) {
                if (CaseFolding.folds(volume.getValue())) {
                    throw new CodegenException(COLLISION, "the generated types " + previous + " and "
                        + source.qualifiedName() + " differ only in case, and the volume of " + volume.getKey()
                        + volume.getValue() + " does not tell their files apart");
                }
            }
        }
        Map<String, SymbolDescriptor> nested = new HashMap<>();
        for (SymbolDescriptor symbol : symbols) {
            SymbolDescriptor previous = nested.putIfAbsent(CaseFolding.key(symbol.qualifiedName()), symbol);
            if (previous != null && !previous.qualifiedName().equals(symbol.qualifiedName())
                    && CaseFolding.folds(request.classesOutput())) {
                throw new CodegenException(COLLISION, "the generated symbols " + previous.qualifiedName() + ", of "
                    + member(previous) + ", and " + symbol.qualifiedName() + ", of " + member(symbol)
                    + ", differ only in case, and the volume of the class output " + request.classesOutput()
                    + " does not tell their class files apart", symbol.origin());
            }
        }
    }

    /** The member {@code symbol} names, with its host and where it is declared. */
    private static String member(SymbolDescriptor symbol) {
        return symbol.host() + '#' + symbol.member() + " at " + symbol.origin().path() + ':' + symbol.origin().line();
    }

    /** The directories of {@code path}, a path separated by {@code /}, with the last separator; empty for none. */
    private static String directories(String path) {
        return path.substring(0, path.lastIndexOf('/') + 1);
    }

    /**
     * A top-level type an original source declares.
     *
     * @param type the qualified name of the type
     * @param origin the original source that declares it, as diagnostics name it
     */
    private record Declared(String type, String origin) {
    }

    /**
     * javac allows a source to shadow a dependency class, so reject that collision before publishing sources. A
     * directory entry is searched for the class file of each generated type, and so is a regular file that opens
     * as an archive. A regular file that is not an archive, whatever its name, is skipped, since no class can be
     * read from it, while one that cannot be read at all fails with an {@link IOException}; this run's own class
     * output is skipped too.
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

    /**
     * Retains ownership in the pending manifest, which {@link #replace} moves into place atomically where the file
     * system supports that, without making an incomplete generation usable by the compiler.
     */
    private static void remember(Path pending, CompileManifest manifest, Set<String> owned) throws IOException {
        replace(pending, new CompileManifest(manifest.schemaVersion(), manifest.toolVersion(),
            manifest.local(), manifest.dependencies(), manifest.processorInventory(), List.copyOf(owned)));
    }

    /**
     * Writes {@code manifest} beside {@code target} and moves it into place, so no run is ever left with half a
     * manifest, which every later run would refuse to read. A write or a move that fails removes the temporary file it
     * wrote, and nothing else that stands there, such as a directory.
     */
    private static void replace(Path target, CompileManifest manifest) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            ManifestCodec.writeCompile(temporary, manifest);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                if (Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(temporary);
                }
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
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
