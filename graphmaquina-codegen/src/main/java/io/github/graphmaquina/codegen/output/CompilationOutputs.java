package io.github.graphmaquina.codegen.output;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.manifest.OwnedOutputs;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;

/**
 * The two steps a build runs around javac: {@code prepare} before it and {@code complete} after it succeeded.
 *
 * <p>javac never deletes a class file it no longer produces, so a compilation that renames or removes a graph
 * member would keep publishing the binaries of the previous one. {@code prepare} retires them, and it does so
 * from an inventory rather than from a name prefix: it removes the exact class files and resources the inventories
 * recorded and the processor's sources the sealed inventory recorded and, after an interrupted cycle, the sources the
 * processor inventory names and the class files that reading them attributes to a unit whose source an inventory or
 * the manifest lists, or that the processor inventory names, so {@code fx/OldRefs$Manual.class} written by hand
 * survives a run that retires {@code fx/OldRefs.class}, and the class files of an application class {@code fx.My}
 * survive one that retires {@code fx/My$HostRefs.class}. It never removes a source the generator wrote for the
 * current generation, whatever path names the directory it stands in: the generator retires the sources of earlier
 * generations itself, through its own manifests, when it publishes the next one. It also removes the published index
 * and the provisional one, so no consumer and no packager can read what this compilation has not yet verified.</p>
 *
 * <p>Between the two steps the inventory of what is being retired lives in
 * {@code <workDirectory>/owned-outputs.pending.json}, written before the first deletion to a file beside it that
 * is then moved into place, atomically where the file system supports that, so a preparation that is itself
 * interrupted leaves the previous inventory whole. A compilation that javac rejects, or that never finishes, leaves
 * that file behind: {@code complete} is not called, so nothing is published, and the next {@code prepare} consumes
 * the evidence and cleans up what the interrupted run left. {@code complete} enumerates the class files the
 * compilation really produced for the units it owns, keeps the carrier out of the jar by deleting its exact
 * binaries, promotes the provisional index to {@code META-INF/graphmaquina/exports.json} byte for byte and seals
 * {@code <workDirectory>/owned-outputs.json}, moved into place the same way.</p>
 *
 * <p>That enumeration reads the {@code SourceFile} attribute of each class file, so a class output compiled
 * without it — under {@code -g:none}, or a {@code -g:} list that leaves {@code source} out such as
 * {@code -g:lines,vars} — would look empty. Since every generation emits a carrier, a class output that
 * yields no carrier binary is refused with {@code SG602} rather than sealed: publishing an inventory that
 * claims the compilation produced nothing would put the carrier in the jar and leave every stale binary behind,
 * silently.</p>
 */
public final class CompilationOutputs {

    private static final String INVENTORY = "owned-outputs.json";
    private static final String PENDING_INVENTORY = "owned-outputs.pending.json";
    private static final String PUBLISHED_INDEX = "META-INF/graphmaquina/exports.json";
    private static final String PENDING_INDEX = "META-INF/graphmaquina/pending-exports.json";
    private static final String CARRIER_PACKAGE = "io.github.graphmaquina.generated.bootstrap";
    private static final String UNUSABLE = "SG602";
    private static final String COLLISION = "SG603";
    private static final int SCHEMA_VERSION = 2;
    private static final String JAVA_SOURCE = ".java";
    private static final String CLASS_FILE = ".class";

    private CompilationOutputs() {
    }

    /**
     * Retires the outputs a previous cycle owned and invalidates the published index, before javac runs.
     *
     * <p>Only schema 2 inventories are accepted. A retired inventory stops preparation with {@code SG602}
     * before any output is changed; the user must clean the old build explicitly.</p>
     *
     * <p>A path an inventory records is only removed under the root it is relative to, and only when the directory
     * holding its file is, by its real path, the real directory of that root or one below it, so a symbolic link
     * inside a root never takes a removal out of it. An entry this step removes, a source of the sealed inventory or a
     * binary, a resource or an excluded binary of either inventory, whose file such a link would take out is refused
     * with {@code SG603} before any output is changed; any other file it would take out, such as a source the
     * processor inventory names or an export index, is left alone. The binaries, the resources and the excluded
     * binaries of both inventories are removed from {@code classesOutput}. The sources of the sealed inventory, which
     * are all the processor's, are removed from {@code processorSourcesOutput}, except one that is, by its file key
     * where the file system has one and otherwise by its real path, a source the current generation writes under
     * {@code sourcesOutput}, as when both source outputs are one directory, however each of them names it. No source
     * is removed because the pending inventory lists it: its sources serve only to name the units a generation owned.
     * Besides the processor's, copied from a sealed inventory, they list the generator's, which stand under
     * {@code sourcesOutput}, where the generator retires them itself when it publishes the next generation.</p>
     *
     * <p>When a pending inventory is already there, the cycle it belongs to was interrupted: javac may have
     * written class files for its units that no inventory could list, since only {@code complete} enumerates
     * them, and the annotation processor may have generated types that no inventory lists at all. The processor
     * inventory at {@link CompileManifest#processorInventory()} names the types the processor generated in the last
     * run that got as far as writing it, the interrupted one when javac did; their sources are removed from
     * {@code processorSourcesOutput}, with the same exception as the sealed inventory's. A processor inventory that
     * is missing, that cannot be read or that the codec refuses, as it refuses one javac stopped writing halfway,
     * names nothing. So does one that lists anything but a qualified or binary Java name as the processor writes
     * them, with no identifier-ignorable character that fits in one {@code char}, since javac leaves those out of
     * every identifier it reads: a path made of such an entry could name a file the processor never wrote, or no file
     * at all, so what only such an inventory could name is left alone, even what its valid names name. The class
     * files are retired by asking {@link OwnedClassFiles} which binaries belong to the units that generation owned, as
     * the sources of the inventories and of the current manifest and that processor inventory name them; a binary an
     * inventory lists names no unit, and a class file the enumeration does not attribute to one of them is never
     * touched.</p>
     *
     * @throws CodegenException {@code SG602} if the generator wrote no manifest for this compilation, or
     *     {@code SG603} if the sealed or the pending inventory names a path outside the root it belongs to, one no
     *     path can spell, a source that is no Java source, a binary or an excluded binary that is no class file, or
     *     one this step removes whose file a symbolic link inside that root takes out of it; all of them before the
     *     pending inventory is rewritten
     * @throws IOException if the manifest, an inventory or a class file cannot be read, the pending inventory
     *     cannot be written, or a source or an output cannot be examined or removed
     */
    public static void prepare(GenerationRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        CompileManifest manifest = manifest(request, "prepare");
        Path work = Files.createDirectories(root(request.workDirectory()));
        Path classes = root(request.classesOutput());
        Path processorSources = root(request.processorSourcesOutput());

        Path pendingInventory = work.resolve(PENDING_INVENTORY);
        boolean interrupted = Files.isRegularFile(pendingInventory);
        List<OwnedOutputs> sealed = readInventory(work.resolve(INVENTORY), classes, processorSources);
        for (OwnedOutputs inventory : sealed) {
            requireStaysInside(inventory.sources(), processorSources, INVENTORY);
        }
        List<OwnedOutputs> previous = new ArrayList<>(sealed);
        previous.addAll(readInventory(pendingInventory, classes, processorSources));
        List<String> processorTypes = interrupted
                ? processorTypes(Path.of(manifest.processorInventory()))
                : List.of();
        OwnedOutputs retiring = new OwnedOutputs(SCHEMA_VERSION,
            union(previous.stream().map(OwnedOutputs::sources).toList(), manifest.ownedSources()),
            union(previous.stream().map(OwnedOutputs::binaries).toList(), List.of()),
            union(previous.stream().map(OwnedOutputs::resources).toList(), List.of()),
            union(previous.stream().map(OwnedOutputs::excludedBinaries).toList(), List.of()));
        replace(pendingInventory, retiring);

        Path generatedSources = root(request.sourcesOutput());
        Set<Object> generated = new HashSet<>();
        for (String source : manifest.ownedSources()) {
            identity(resolve(generatedSources, source)).ifPresent(generated::add);
        }
        List<String> processorSourceFiles = new ArrayList<>(sourceFiles(processorTypes));
        for (OwnedOutputs inventory : sealed) {
            processorSourceFiles.addAll(inventory.sources());
        }
        List<Path> retired = new ArrayList<>();
        for (String source : processorSourceFiles) {
            Path file = resolve(processorSources, source);
            Optional<Object> identity = identity(file);
            if (identity.isEmpty() || !generated.contains(identity.get())) {
                retired.add(file);
            }
        }
        for (String binary : retiring.binaries()) {
            retired.add(resolve(classes, binary));
        }
        for (String resource : retiring.resources()) {
            retired.add(resolve(classes, resource));
        }
        for (String excluded : retiring.excludedBinaries()) {
            retired.add(resolve(classes, excluded));
        }
        retired.add(resolve(classes, PUBLISHED_INDEX));
        retired.add(resolve(classes, PENDING_INDEX));
        if (interrupted) {
            Set<String> owned = ownedTypes(retiring);
            owned.addAll(processorTypes);
            retired.addAll(OwnedClassFiles.enumerate(classes, owned));
        }
        delete(retired, List.of(classes, processorSources));
    }

    /** Whether {@code work}, a work directory, holds a sealed or a pending inventory of the outputs a cycle owned. */
    static boolean holdsInventory(Path work) {
        return Files.exists(work.resolve(INVENTORY), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(work.resolve(PENDING_INVENTORY), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Publishes what javac accepted: the index, the inventory of the outputs it produced, and no carrier.
     *
     * @throws CodegenException {@code SG602} if the generator wrote no manifest for this compilation, if it was
     *     never prepared or was prepared for another generation, or if it carries no provisional index, no processor
     *     inventory or no class file for its carrier, or {@code SG603} if a path of the new inventory escapes its
     *     root, or a symbolic link inside the class output takes one of its class-output paths out of it
     * @throws IOException if the manifest, an inventory or a class file cannot be read, or an output cannot be
     *     examined, written or removed
     */
    public static void complete(GenerationRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        CompileManifest manifest = manifest(request, "complete");
        Path work = root(request.workDirectory());
        Path classes = root(request.classesOutput());
        Path processorSources = root(request.processorSourcesOutput());

        Path pendingInventory = work.resolve(PENDING_INVENTORY);
        if (!Files.isRegularFile(pendingInventory)) {
            throw new CodegenException(UNUSABLE, "cannot complete the compilation of "
                + request.compilationId() + ": there is no " + PENDING_INVENTORY + " in " + work
                + ", so this compilation was never prepared");
        }
        if (!ManifestCodec.readOwned(pendingInventory).sources().containsAll(manifest.ownedSources())) {
            throw new CodegenException(UNUSABLE, "cannot complete the compilation of "
                + request.compilationId() + ": " + pendingInventory
                + " was written for another generation than the one " + manifestPath(request) + " describes");
        }
        Path pendingIndex = resolve(classes, PENDING_INDEX);
        if (!Files.isRegularFile(pendingIndex)) {
            throw new CodegenException(UNUSABLE, "cannot complete the compilation of "
                + request.compilationId() + ": " + pendingIndex + " does not exist, so the annotation"
                + " processor published nothing for javac to promote");
        }
        Path processorInventory = Path.of(manifest.processorInventory());
        if (!Files.isRegularFile(processorInventory)) {
            throw new CodegenException(UNUSABLE, "cannot complete the compilation of "
                + request.compilationId() + ": the annotation processor wrote no inventory at "
                + processorInventory);
        }
        List<String> generatedTypes = ManifestCodec.readProcessorInventory(processorInventory);

        Set<String> owned = new TreeSet<>(generatedTypes);
        for (String source : manifest.ownedSources()) {
            owned.add(typeName(source));
        }
        Set<String> carrier = new TreeSet<>();
        for (String name : owned) {
            if (CARRIER_PACKAGE.equals(packageOf(name))) {
                carrier.add(name);
            }
        }
        List<Path> carrierBinaries = OwnedClassFiles.enumerate(classes, carrier);
        if (carrierBinaries.isEmpty()) {
            throw new CodegenException(UNUSABLE, "cannot complete the compilation of "
                + request.compilationId() + ": no class file in " + classes + " belongs to the carrier "
                + String.join(", ", carrier) + ", which every compilation of this generator produces."
                + " StateGraph links a binary to the unit that declared it through the SourceFile attribute"
                + " of the class file, which javac omits when its debug setting leaves source out: under -g:none,"
                + " or under a -g: list without source such as -g:lines,vars. Compile with javac's default"
                + " setting, -g, or a -g: list that includes source, such as -g:source,lines");
        }
        List<Path> binaries = new ArrayList<>(OwnedClassFiles.enumerate(classes, owned));
        binaries.removeAll(carrierBinaries);

        OwnedOutputs sealed = new OwnedOutputs(SCHEMA_VERSION,
            sourceFiles(generatedTypes), relative(classes, binaries), List.of(PUBLISHED_INDEX),
            relative(classes, carrierBinaries));
        requireInside(sealed.sources(), processorSources, INVENTORY);
        for (List<String> removed : List.of(sealed.binaries(), sealed.resources(), sealed.excludedBinaries())) {
            requireInside(removed, classes, INVENTORY);
            requireStaysInside(removed, classes, INVENTORY);
        }

        delete(carrierBinaries, List.of(classes));
        Path published = resolve(classes, PUBLISHED_INDEX);
        Files.createDirectories(published.getParent());
        Files.copy(pendingIndex, published, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(pendingIndex);
        replace(work.resolve(INVENTORY), sealed);
        Files.delete(pendingInventory);
    }

    /** One output root of a request, as an absolute path with no redundant segment left in it. */
    private static Path root(Path directory) {
        return directory.toAbsolutePath().normalize();
    }

    /** The manifest of {@code request}, which is what says the generator ran for this very compilation. */
    private static CompileManifest manifest(GenerationRequest request, String step) throws IOException {
        Path path = manifestPath(request);
        if (!Files.isRegularFile(path)) {
            throw new CodegenException(UNUSABLE, "cannot " + step + " the compilation of "
                + request.compilationId() + ": there is no manifest at " + path
                + "; run the generator before the compiler");
        }
        return ManifestCodec.readCompile(path);
    }

    private static Path manifestPath(GenerationRequest request) {
        return GenerationOutput.manifestPath(request);
    }

    /**
     * The inventory at {@code path} when it exists, with its sources checked against {@code processorSources} and
     * its other paths against {@code classes}, which they are removed from, so they must also
     * {@linkplain #requireStaysInside stay inside} it. Whether its sources must is left to the caller: those of a
     * pending inventory are never removed. Each source must be a Java source and each binary and excluded binary a
     * class file, as {@link #requireKind} checks them.
     */
    private static List<OwnedOutputs> readInventory(Path path, Path classes, Path processorSources)
            throws IOException {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        OwnedOutputs inventory = ManifestCodec.readOwned(path);
        String name = path.getFileName().toString();
        requireInside(inventory.sources(), processorSources, name);
        requireKind(inventory.sources(), JAVA_SOURCE, "source file", processorSources, name);
        for (List<String> removed
                : List.of(inventory.binaries(), inventory.resources(), inventory.excludedBinaries())) {
            requireInside(removed, classes, name);
            requireStaysInside(removed, classes, name);
        }
        requireKind(inventory.binaries(), CLASS_FILE, "class file", classes, name);
        requireKind(inventory.excludedBinaries(), CLASS_FILE, "class file", classes, name);
        return List.of(inventory);
    }

    /**
     * Refuses an entry whose file name is not a name followed by {@code suffix}, which is how the type of each unit
     * an inventory names is read from it.
     *
     * @throws CodegenException {@code SG603} naming the inventory and the entry
     */
    private static void requireKind(List<String> entries, String suffix, String kind, Path root, String inventory) {
        for (String entry : entries) {
            String fileName = entry.substring(entry.lastIndexOf('/') + 1);
            if (!fileName.endsWith(suffix) || fileName.length() == suffix.length()) {
                throw new CodegenException(COLLISION, "the inventory " + inventory + " claims '" + entry
                    + "', which is not a " + kind + " under " + root);
            }
        }
    }

    /**
     * Refuses an inventory path that is blank, that no path can spell, such as one holding the NUL character, that is
     * absolute or has a root, as {@code \x} and {@code C:x} have on Windows, or that lands on {@code root} itself or
     * outside it once resolved. A blank entry resolves to the root itself, which would hand the whole output
     * directory to a step that only ever deletes the files one compilation owns.
     *
     * @throws CodegenException {@code SG603} naming the entry, since an inventory may only claim files the
     *     compilation itself produced under its own output roots
     */
    private static void requireInside(List<String> entries, Path root, String inventory) {
        for (String entry : entries) {
            boolean inside;
            try {
                Path spelled = Path.of(entry);
                Path resolved = resolve(root, entry).normalize();
                inside = !entry.isBlank() && !spelled.isAbsolute() && spelled.getRoot() == null
                        && !resolved.equals(root) && resolved.startsWith(root);
            } catch (InvalidPathException unspellable) {
                inside = false;
            }
            if (!inside) {
                throw new CodegenException(COLLISION, "the inventory " + inventory + " claims '" + entry
                    + "', which is not a file under " + root);
            }
        }
    }

    /**
     * Refuses an inventory path, already {@linkplain #requireInside inside} {@code root} as spelled, whose file a
     * symbolic link inside {@code root} would take out of it: one whose directory exists but does not
     * {@linkplain #standsUnder stand under} {@code root}. Only an entry a step removes under {@code root} is checked
     * this way.
     *
     * @throws CodegenException {@code SG603} naming the entry
     * @throws IOException if the directory of an entry or {@code root} cannot be examined
     */
    private static void requireStaysInside(List<String> entries, Path root, String inventory) throws IOException {
        for (String entry : entries) {
            Path file = resolve(root, entry);
            if (Files.isDirectory(file.getParent()) && !standsUnder(file, root)) {
                throw new CodegenException(COLLISION, "the inventory " + inventory + " claims '" + entry
                    + "', which a symbolic link takes out of " + root);
            }
        }
    }

    /**
     * The types the processor inventory at {@code path} lists: none when there is no regular file there, when it
     * cannot be read, when the codec refuses it, as it refuses one javac stopped writing halfway, or when it lists
     * anything {@link #isTypeName} refuses, which is no name the processor writes.
     */
    private static List<String> processorTypes(Path path) {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            List<String> types = ManifestCodec.readProcessorInventory(path);
            for (String type : types) {
                if (!isTypeName(type)) {
                    return List.of();
                }
            }
            return types;
        } catch (IOException | CodegenException unreadable) {
            return List.of();
        }
    }

    /**
     * Whether {@code name} is a qualified or binary name as the processor writes them: Java identifiers joined by
     * dots, none of them a keyword, and no identifier-ignorable character that fits in one {@code char}, such as NUL.
     * javac leaves those out of every identifier it reads, while it keeps a supplementary one, such as U+E0001, which
     * a name the processor writes may therefore hold.
     */
    private static boolean isTypeName(String name) {
        return SourceVersion.isName(name) && name.chars().noneMatch(Character::isIdentifierIgnorable);
    }

    /** Every distinct entry of {@code lists} and of {@code extra}, sorted. */
    private static List<String> union(List<List<String>> lists, List<String> extra) {
        Set<String> all = new TreeSet<>();
        for (List<String> list : lists) {
            all.addAll(list);
        }
        all.addAll(extra);
        return List.copyOf(all);
    }

    /** The source file each generated type was written to, relative to the processor's source output. */
    private static List<String> sourceFiles(List<String> generatedTypes) {
        Set<String> files = new TreeSet<>();
        for (String type : generatedTypes) {
            files.add(type.replace('.', '/') + ".java");
        }
        return List.copyOf(files);
    }

    /**
     * The top-level types a generation owns, as far as its inventory can say: the types of the sources it generated,
     * the generator's and the processor's, which name every unit javac could have compiled for it. Its binaries
     * name class files, which are retired by their exact paths, and never a unit: a class file named
     * {@code fx/My$HostRefs.class} does not make the application class {@code fx.My} one. These are candidates
     * only — {@link OwnedClassFiles} decides which class files really belong to them by reading the class files
     * themselves.
     */
    private static Set<String> ownedTypes(OwnedOutputs inventory) {
        Set<String> types = new TreeSet<>();
        for (String source : inventory.sources()) {
            types.add(typeName(source));
        }
        return types;
    }

    /** The canonical name of the type a source path relative to its source root declares. */
    private static String typeName(String source) {
        return source.substring(0, source.length() - ".java".length()).replace('/', '.');
    }

    /** The package of {@code canonicalName}, empty for the unnamed package. */
    private static String packageOf(String canonicalName) {
        int separator = canonicalName.lastIndexOf('.');
        return separator < 0 ? "" : canonicalName.substring(0, separator);
    }

    /** {@code files} as inventory entries relative to {@code root}, with forward slashes, sorted. */
    private static List<String> relative(Path root, List<Path> files) {
        Set<String> entries = new TreeSet<>();
        for (Path file : files) {
            entries.add(root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/"));
        }
        return List.copyOf(entries);
    }

    /**
     * What tells the file at {@code file} apart from every other file, whatever path leads to it: its file key where
     * the file system has one, as POSIX file systems do with the device and the inode, and otherwise its real path,
     * as {@link Path#toRealPath} derives it with every symbolic link resolved. Empty when no file stands there.
     */
    private static Optional<Object> identity(Path file) throws IOException {
        try {
            Object key = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
            return Optional.of(key != null ? key : file.toRealPath());
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
    }

    /** {@code entry} resolved segment by segment under {@code root}, whatever separator this system uses. */
    private static Path resolve(Path root, String entry) {
        Path resolved = root;
        for (String segment : entry.split("/")) {
            if (!segment.isEmpty()) {
                resolved = resolved.resolve(segment);
            }
        }
        return resolved;
    }

    /**
     * Whether the directory holding {@code file} is, by its real path, the real directory of {@code root} or one below
     * it, with every symbolic link on the way resolved, so that an output root that is itself a link still holds its
     * files. False when that directory or {@code root} does not exist, since no file can stand there.
     *
     * @throws IOException if a directory on the way cannot be examined, as when its links loop
     */
    static boolean standsUnder(Path file, Path root) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            return false;
        }
        try {
            return parent.toRealPath().startsWith(root.toRealPath());
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    /** Whether {@code file} {@linkplain #standsUnder stands under} one of {@code roots} it is named under. */
    private static boolean standsUnderAny(Path file, List<Path> roots) throws IOException {
        for (Path root : roots) {
            if (file.startsWith(root) && standsUnder(file, root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes every file that is still there and the directories that stay empty behind them. A file or a directory
     * that does not {@linkplain #standsUnder stand under} the root it is named under is left alone, so a symbolic link
     * inside a root never takes a removal out of it.
     */
    private static void delete(List<Path> files, List<Path> roots) throws IOException {
        Set<Path> parents = new LinkedHashSet<>();
        for (Path file : files) {
            if (standsUnderAny(file, roots) && Files.deleteIfExists(file) && file.getParent() != null) {
                parents.add(file.getParent());
            }
        }
        for (Path parent : parents) {
            prune(parent, roots);
        }
    }

    /**
     * Removes {@code directory} and its own empty parents, never reaching or removing one of {@code roots}, and
     * stopping at the first that does not {@linkplain #standsUnder stand under} the root it is named under.
     */
    private static void prune(Path directory, List<Path> roots) throws IOException {
        Path current = directory;
        while (current != null && !roots.contains(current) && startsWithAny(current, roots)
                && Files.isDirectory(current) && standsUnderAny(current, roots)) {
            try (Stream<Path> entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private static boolean startsWithAny(Path candidate, List<Path> roots) {
        for (Path root : roots) {
            if (candidate.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes {@code inventory} beside {@code target} and moves it into place, so no build reads a half file and
     * an interrupted write leaves the previous inventory whole.
     */
    private static void replace(Path target, OwnedOutputs inventory) throws IOException {
        Path staged = Files.createTempFile(target.getParent(), "owned-outputs", ".json");
        try {
            ManifestCodec.writeOwned(staged, inventory);
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
