package io.github.graphmaquina.codegen.output;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.manifest.OwnedOutputs;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The two steps a build runs around javac: {@code prepare} before it and {@code complete} after it succeeded.
 *
 * <p>javac never deletes a class file it no longer produces, so a compilation that renames or removes a graph
 * member would keep publishing the binaries of the previous one. {@code prepare} retires them, and it does so
 * from an inventory rather than from a name prefix: it removes the exact files a previous cycle recorded and,
 * after an interrupted cycle, the class files that reading them attributes to a unit an inventory or the manifest
 * names, so {@code fx/OldRefs$Manual.class} written by hand survives a run that retires {@code fx/OldRefs.class}. It
 * also removes the published index and the provisional one, so no consumer and no packager can read what this
 * compilation has not yet verified.</p>
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

    private CompilationOutputs() {
    }

    /**
     * Retires everything a previous cycle owned and invalidates the published index, before javac runs.
     *
     * <p>Only schema 2 inventories are accepted. A retired inventory stops preparation with {@code SG602}
     * before any output is changed; the user must clean the old build explicitly.</p>
     *
     * <p>When a pending inventory is already there, the cycle it belongs to was interrupted: javac may have
     * written class files for its units that no inventory could list, since only {@code complete} enumerates
     * them. Those are retired too, by asking {@link OwnedClassFiles} which binaries belong to the units that
     * generation owned, as the inventories and the current manifest name them; a class file the enumeration does
     * not attribute to one of them is never touched.</p>
     *
     * @throws CodegenException {@code SG602} if the generator wrote no manifest for this compilation, or
     *     {@code SG603} if an inventory names a path outside the root it belongs to
     * @throws IOException if the manifest, an inventory or a class file cannot be read, the pending inventory
     *     cannot be written or an output cannot be removed
     */
    public static void prepare(GenerationRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        CompileManifest manifest = manifest(request, "prepare");
        Path work = Files.createDirectories(root(request.workDirectory()));
        Path classes = root(request.classesOutput());
        Path processorSources = root(request.processorSourcesOutput());

        Path pendingInventory = work.resolve(PENDING_INVENTORY);
        boolean interrupted = Files.isRegularFile(pendingInventory);
        List<OwnedOutputs> previous = new ArrayList<>();
        previous.addAll(readInventory(work.resolve(INVENTORY), classes, processorSources));
        previous.addAll(readInventory(pendingInventory, classes, processorSources));
        OwnedOutputs retiring = new OwnedOutputs(SCHEMA_VERSION,
            union(previous.stream().map(OwnedOutputs::sources).toList(), manifest.ownedSources()),
            union(previous.stream().map(OwnedOutputs::binaries).toList(), List.of()),
            union(previous.stream().map(OwnedOutputs::resources).toList(), List.of()),
            union(previous.stream().map(OwnedOutputs::excludedBinaries).toList(), List.of()));
        replace(pendingInventory, retiring);

        Set<String> generated = Set.copyOf(manifest.ownedSources());
        List<Path> retired = new ArrayList<>();
        for (String source : retiring.sources()) {
            if (!generated.contains(source)) {
                retired.add(resolve(processorSources, source));
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
            retired.addAll(OwnedClassFiles.enumerate(classes, ownedTypes(retiring)));
        }
        delete(retired, List.of(classes, processorSources));
    }

    /**
     * Publishes what javac accepted: the index, the inventory of the outputs it produced, and no carrier.
     *
     * @throws CodegenException {@code SG602} if the generator wrote no manifest for this compilation, if it was
     *     never prepared or was prepared for another generation, or if it carries no provisional index, no processor
     *     inventory or no class file for its carrier, or {@code SG603} if a path of the new inventory escapes its
     *     root
     * @throws IOException if the manifest, an inventory or a class file cannot be read, or an output cannot be
     *     written or removed
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
        requireInside(sealed.binaries(), classes, INVENTORY);
        requireInside(sealed.resources(), classes, INVENTORY);
        requireInside(sealed.excludedBinaries(), classes, INVENTORY);

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

    /** The inventory at {@code path} when it exists, with every one of its paths checked against its root. */
    private static List<OwnedOutputs> readInventory(Path path, Path classes, Path processorSources)
            throws IOException {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        OwnedOutputs inventory = ManifestCodec.readOwned(path);
        requireInside(inventory.sources(), processorSources, path.getFileName().toString());
        requireInside(inventory.binaries(), classes, path.getFileName().toString());
        requireInside(inventory.resources(), classes, path.getFileName().toString());
        requireInside(inventory.excludedBinaries(), classes, path.getFileName().toString());
        return List.of(inventory);
    }

    /**
     * Refuses an inventory path that is blank, that is absolute or that lands on {@code root} itself or outside it
     * once resolved. A blank entry resolves to the root itself, which would hand the whole output directory to a
     * step that only ever deletes the files one compilation owns.
     *
     * @throws CodegenException {@code SG603} naming the entry, since an inventory may only claim files the
     *     compilation itself produced under its own output roots
     */
    private static void requireInside(List<String> entries, Path root, String inventory) {
        for (String entry : entries) {
            Path resolved = resolve(root, entry).normalize();
            if (entry.isBlank() || Path.of(entry).isAbsolute()
                    || resolved.equals(root) || !resolved.startsWith(root)) {
                throw new CodegenException(COLLISION, "the inventory " + inventory + " claims '" + entry
                    + "', which is not a file under " + root);
            }
        }
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
     * The top-level types a generation owns, as far as its inventory can say: the sources it generated, and the
     * types the binaries it already listed belong to. These are candidates only — {@link OwnedClassFiles}
     * decides which class files really belong to them by reading the class files themselves.
     */
    private static Set<String> ownedTypes(OwnedOutputs inventory) {
        Set<String> types = new TreeSet<>();
        for (String source : inventory.sources()) {
            types.add(typeName(source));
        }
        for (String binary : inventory.binaries()) {
            types.addAll(declaringTypes(binary));
        }
        for (String excluded : inventory.excludedBinaries()) {
            types.addAll(declaringTypes(excluded));
        }
        return types;
    }

    /**
     * The types a class file path could have been compiled from: the name it spells, and — since {@code $}
     * separates a nested class from its outer one but is also legal in a top-level name — the part before its
     * first {@code $}.
     */
    private static List<String> declaringTypes(String binary) {
        String name = binary.substring(0, binary.length() - ".class".length()).replace('/', '.');
        int nested = name.indexOf('$');
        return nested < 0 ? List.of(name) : List.of(name, name.substring(0, nested));
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

    /** Removes every file that is still there and the directories that stay empty behind them. */
    private static void delete(List<Path> files, List<Path> roots) throws IOException {
        Set<Path> parents = new LinkedHashSet<>();
        for (Path file : files) {
            if (Files.deleteIfExists(file) && file.getParent() != null) {
                parents.add(file.getParent());
            }
        }
        for (Path parent : parents) {
            prune(parent, roots);
        }
    }

    /** Removes {@code directory} and its own empty parents, never reaching or removing one of {@code roots}. */
    private static void prune(Path directory, List<Path> roots) throws IOException {
        Path current = directory;
        while (current != null && !roots.contains(current) && startsWithAny(current, roots)
                && Files.isDirectory(current)) {
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
