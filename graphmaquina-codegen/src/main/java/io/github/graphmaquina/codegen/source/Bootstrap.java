package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.manifest.PublishedIndex;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/**
 * Reads one generation request into the model the emitters work from, without compiling the application.
 *
 * <p>The original sources are only parsed; their headers, the classpath headers and the marked declarations
 * they carry are enough to resolve every machine and every exported symbol. They are parsed at the release and
 * in the encoding the request names, which are the ones the compilation that follows reads them with. Machines
 * are ordered by canonical name and symbols by qualified name, so the order the build happened to list its files
 * in changes neither order. It only decides which declaration is kept when two declare one canonical name, and
 * under which path a file reached through two paths is recorded.</p>
 *
 * <p>The sources are walked once. What that walk read is kept beside the model — every file, the path it stands
 * at below its source root and the top-level types it declares — so the publication can check a generated type
 * against exactly the sources the model was read from, without reading them again. A publication run on its own
 * reads them with {@link #originals}, which walks and parses them but resolves nothing and opens no classpath.</p>
 */
public final class Bootstrap {

    static final String GENERATE_GRAPH_API = GenerateGraphApi.class.getName();
    private static final String STATE_GRAPHS = StateGraphs.class.getName();

    /**
     * The names the default excludes of Ant 1.10 name outright: the files and directories of version control systems,
     * and the {@code .DS_Store} of macOS.
     */
    private static final Set<String> EXCLUDED_NAMES = Set.of("CVS", ".cvsignore", "SCCS", "vssver.scc", ".svn",
            ".git", ".gitattributes", ".gitignore", ".gitmodules", ".hg", ".hgignore", ".hgsub", ".hgsubstate",
            ".hgtags", ".bzr", ".bzrignore", ".DS_Store");

    private Bootstrap() {
    }

    /**
     * The graphs and symbols {@code request} declares, resolved against its sources and its classpath, as
     * {@link #read(GenerationRequest)} reads them, without dependencies: every declared peer must be a machine of the
     * request. A declaration the generated vocabulary or references could not name from where they are generated,
     * because it is not accessible there or a type of that package or of {@code java.lang} takes its name over, is
     * refused here, before anything is emitted. One whose name a type the generator itself creates takes over is
     * refused later, by {@link io.github.graphmaquina.codegen.emit.ApiEmitter#emit}.
     */
    public static GenerationModel inspect(GenerationRequest request) {
        return read(request).model();
    }

    /**
     * The model of {@code request} read without dependencies, as {@link #read(GenerationRequest, List)} reads it
     * with none: every declared peer must be a machine of the request.
     *
     * @param request the request to read
     * @return the model and the original sources it was read from, with no dependency and no dependency peer
     */
    public static Inspection read(@NonNull GenerationRequest request) {
        return read(request, List.of());
    }

    /**
     * The model of {@code request}, as {@link #inspect} reads it, with every original source it was read from, its
     * declared peers resolved against {@code dependencies} too. A peer that is no machine of the request, read from the
     * classpath, is the machine the one index of {@code dependencies} that describes it gives.
     *
     * @param request the request to read
     * @param dependencies the export indexes the dependencies of the request publish, each with its classpath entry
     * @return the model, the original sources it was read from, {@code dependencies} and the descriptors of the
     *     dependency peers
     * @throws CodegenException {@code SG602} for a machine of the request that an index of {@code dependencies}
     *     describes too, before any machine or peer is resolved, and for a declared peer that is a
     *     {@code @GenerateGraphApi} machine read from a dependency on the classpath, not from the class output of the
     *     request, that no index of {@code dependencies} describes, naming the jar or directory of classes whose index
     *     is missing or does not list it when that can be told; {@code SG608} for any other declared peer that is
     *     neither a machine of the request nor a classpath type an index describes
     */
    public static Inspection read(@NonNull GenerationRequest request, @NonNull List<PublishedIndex> dependencies) {
        Parsed parsed = parse(request);
        GenerationModel model;
        List<GraphDescriptor> dependencyPeers;
        try (DeclarationIndex index = DeclarationIndex.open(parsed.distinctUnits(), request.classpath(),
                request.release(), request.encoding())) {
            List<TypeHeader> facades = machines(index);
            DependencyPeers.refuseDescribedMachines(facades, dependencies);
            List<MachineHeaders.Resolved> resolved = new ArrayList<>();
            List<GraphDescriptor> graphs = new ArrayList<>();
            for (TypeHeader facade : facades) {
                MachineHeaders.Resolved machine = MachineHeaders.resolveDeclaration(index, facade);
                resolved.add(machine);
                graphs.add(machine.graph());
            }
            MachineHeaders.requireWatchablePeers(index, resolved);
            dependencyPeers = DependencyPeers.resolve(index, resolved, dependencies,
                    request.classesOutput().toAbsolutePath().normalize());
            model = new GenerationModel(graphs, ExportDeclarations.collect(index));
        }
        return new Inspection(model, parsed.originals(), dependencies, dependencyPeers);
    }

    /**
     * The original sources of {@code request}, as {@link #read} reports them, read without resolving any of them:
     * the sources are walked and parsed, and no classpath is opened. That is all checking a generated type against
     * them needs.
     *
     * @param request the request whose original sources to read
     * @return every original source the walk reached, in the order it reached them
     * @throws io.github.graphmaquina.codegen.CodegenException {@code SG601} if javac cannot read or parse an
     *     original source, as when the file may not be read
     * @throws UncheckedIOException if a source directory, or a directory below it, cannot be walked, or the file
     *     manager javac read the sources with cannot be closed
     */
    public static List<OriginalSource> originals(@NonNull GenerationRequest request) {
        return parse(request).originals();
    }

    /** Walks the sources of {@code request} and parses every file the walk reached. */
    private static Parsed parse(GenerationRequest request) {
        List<Reached> reached = sources(request);
        return new Parsed(reached, SourceDeclarations.parseEach(request.projectDirectory(),
                reached.stream().map(Reached::file).toList(), request.release(), request.encoding()));
    }

    /**
     * The original declarations marked with {@code @GenerateGraphApi}, ordered by canonical name. A manifest knows a
     * machine by its canonical name alone, so two machines that share one, one of a named package and a member type
     * of a class of the unnamed package, are refused. A local or anonymous class marked with {@code @GenerateGraphApi}
     * or {@code @StateGraphs} is refused too: the processor, which refuses a machine or a set that is not top-level,
     * never sees such a class, so the marker would generate nothing without a word. An annotation whose name a member
     * type of a class between takes over is another annotation, and one below a class whose supertype the generator
     * cannot resolve is let through, as {@link DeclarationIndex#marksInLocalClass} tells.
     *
     * @throws CodegenException {@code SG603} naming both machines, anchored at the one read first, or
     *     {@code SG502} anchored at the first marker written on a local or anonymous class
     */
    private static List<TypeHeader> machines(DeclarationIndex index) {
        List<TypeHeader> declarations = new ArrayList<>(index.sourceHeaders());
        declarations.sort(Comparator.comparing(TypeHeader::canonicalName));
        for (TypeHeader declaration : declarations) {
            for (TypeHeader.LocalMarker local : declaration.localMarkers()) {
                if (index.marksInLocalClass(declaration, local, GENERATE_GRAPH_API)
                        || index.marksInLocalClass(declaration, local, STATE_GRAPHS)) {
                    throw new CodegenException(DiagnosticCode.SG502.name(),
                            "the marker @" + local.marker().name() + " is written on a"
                            + " local or anonymous class of " + declaration.canonicalName() + ", but a machine or a set"
                            + " must be a top-level interface: the generated companion lives next to it in the package",
                            local.marker().origin().orElseThrow());
                }
            }
        }
        List<TypeHeader> facades = new ArrayList<>();
        for (TypeHeader header : index.sourceHeaders()) {
            for (TypeHeader.Marker marker : header.markers()) {
                if (index.marksInHeader(header, marker, GENERATE_GRAPH_API)) {
                    facades.add(header);
                    break;
                }
            }
        }
        facades.sort(Comparator.comparing(TypeHeader::canonicalName));
        for (int at = 1; at < facades.size(); at++) {
            TypeHeader first = facades.get(at - 1);
            TypeHeader second = facades.get(at);
            if (first.canonicalName().equals(second.canonicalName())) {
                throw new CodegenException(DiagnosticCode.SG603.name(),
                        "the machines " + index.describe(first) + " and "
                        + index.describe(second) + " share one canonical name, which is all a manifest names a machine"
                        + " by; rename one of them", first.origin().orElseThrow());
            }
        }
        return facades;
    }

    /**
     * Expands the requested sources, in the order the request names them: a file is read as it is given, a
     * directory is walked recursively, following symbolic links as Gradle's walk of a source set does, for its
     * {@code .java} files, which are then sorted by path. An entry that does not exist contributes nothing, so a
     * source set whose directory the build has not created yet is not a failure. A link that leads back to a
     * directory on the path the walk is following, the requested directory or one between it and the link, is
     * skipped, as Gradle skips it, so the walk always ends. Every other link is followed wherever it points: a link
     * out of the directory brings in the sources it reaches, and a link to a directory the walk visits elsewhere
     * reaches its files a second time. A file reached through two paths by way of symbolic links is still one file
     * to javac, which parses it once, through the first of those paths in the order of the walk: the order the
     * request names its sources in and, below each directory it names, the sorted order of the paths. Its
     * declarations are recorded under that path, and each path the walk reached it through remains one of the
     * original sources it reports. A file with two hard links is two files to javac, which parses each of them.
     *
     * <p>Below a directory, the walk leaves out what Gradle leaves out under the default excludes of Ant 1.10: every
     * file and every directory whose name one of them names, a directory with everything inside it. Those are the
     * names {@code ._*}, {@code *~}, {@code #*#}, {@code .#*} and {@code %*%}, which cover the AppleDouble companion a
     * copy from macOS leaves beside a source and an editor's backup or lock file, {@code .DS_Store}, and the names of
     * the files and directories of version control systems, such as {@code .git}, {@code .gitignore}, {@code CVS} or
     * {@code .svn}. So a directory named like such a file, {@code old~} for one, is left out as well. Gradle leaves
     * out the same files while the build keeps those default excludes, which a settings script can change through
     * Ant's {@code DirectoryScanner}; the Gradle plugin hands a directory whole only while the build's default
     * excludes are Ant's and nothing else filters it, and names its files one by one otherwise, as Gradle selects
     * them.</p>
     *
     * <p>The scanner of maven-compiler-plugin applies its default excludes otherwise. plexus-utils leaves out the
     * files they name, but the files inside a directory only when an exclude ending in {@code /**} names that
     * directory, as one does for the directory of every version control system on its list, so javac compiles a
     * source inside {@code old~}. Its list is also longer: it excludes the directories {@code RCS},
     * {@code BitKeeper}, {@code ChangeSet}, {@code _darcs}, {@code .metadata}, {@code .arch-ids} and
     * {@code .darcsrepo} and the files {@code -darcs-backup*} too. The Maven plugin applies that list itself whenever
     * it lists files. It hands a root the execution does not narrow to the generator whole unless javac compiles a
     * source below it inside a directory this walk leaves out, or this walk would fail on a directory that cannot be
     * listed, which plexus-utils reads as an empty one, and lists its files otherwise; a root handed whole is walked
     * as described here, not with plexus-utils' longer list.</p>
     */
    private static List<Reached> sources(GenerationRequest request) {
        List<Reached> files = new ArrayList<>();
        for (Path source : request.sources()) {
            if (Files.isDirectory(source)) {
                for (Path file : javaFiles(source)) {
                    files.add(new Reached(file, Optional.of(source)));
                }
            } else if (Files.isRegularFile(source)) {
                files.add(new Reached(source, Optional.empty()));
            }
        }
        return List.copyOf(files);
    }

    private static List<Path> javaFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(directory, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {

                        @Override
                        public FileVisitResult preVisitDirectory(Path visited, BasicFileAttributes attributes) {
                            return !visited.equals(directory)
                                    && excludedByDefault(visited.getFileName().toString())
                                    ? FileVisitResult.SKIP_SUBTREE
                                    : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            String name = file.getFileName().toString();
                            if (attributes.isRegularFile() && name.endsWith(".java") && !excludedByDefault(name)) {
                                files.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                            if (failure instanceof FileSystemLoopException) {
                                return FileVisitResult.CONTINUE;
                            }
                            throw failure;
                        }
                    });
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read the original sources under " + directory, failure);
        }
        files.sort(Comparator.<Path>naturalOrder());
        return List.copyOf(files);
    }

    /**
     * Whether a file or a directory named {@code name} is one the default excludes of Ant 1.10 name: {@code ._*},
     * {@code *~}, {@code #*#}, {@code .#*}, {@code %*%} or one of {@link #EXCLUDED_NAMES}.
     */
    private static boolean excludedByDefault(String name) {
        return name.startsWith("._")
                || EXCLUDED_NAMES.contains(name)
                || name.endsWith("~")
                || (name.length() > 1 && name.startsWith("#") && name.endsWith("#"))
                || name.startsWith(".#")
                || (name.length() > 1 && name.startsWith("%") && name.endsWith("%"));
    }

    /** The files one walk reached, in the order it reached them, and the unit each of them was parsed into. */
    private record Parsed(List<Reached> reached, Map<Path, SourceUnit> units) {

        /** Every unit once, in the order javac parsed them. */
        List<SourceUnit> distinctUnits() {
            return List.copyOf(new LinkedHashSet<>(units.values()));
        }

        /** One original source for every file the walk reached, in the order it reached them. */
        List<OriginalSource> originals() {
            List<OriginalSource> originals = new ArrayList<>();
            for (Reached source : reached) {
                SourceUnit unit = units.get(source.file());
                originals.add(new OriginalSource(source.file(), unit.origin().path(), source.path(unit),
                        SourceDeclarations.topLevelTypes(unit)));
            }
            return List.copyOf(originals);
        }
    }

    /**
     * One file the walk reached, and the directory of the request it reached it under; none for a file the
     * request names itself.
     */
    private record Reached(Path file, Optional<Path> directory) {

        /**
         * Where the file stands below its source root: below the directory it was reached under, or, for a file
         * the request names itself, in the directories of the package {@code unit} declares, under its own name.
         */
        String path(SourceUnit unit) {
            if (directory.isPresent()) {
                Path relative = directory.get().relativize(file);
                return SourceDeclarations.portable(relative.toString(), relative.getFileSystem().getSeparator());
            }
            String packageName = SourceDeclarations.packageName(unit);
            String name = file.getFileName().toString();
            return packageName.isEmpty() ? name : packageName.replace('.', '/') + '/' + name;
        }
    }

    /**
     * What one reading of a request found: its model, every original source the walk reached to read it, in the
     * order the walk reached them, the dependency indexes it was read against and the peers they describe.
     *
     * @param model the graphs and symbols the request declares
     * @param sources the original sources the model was read from
     * @param dependencies the export indexes of the dependencies the request was read against
     * @param dependencyPeers the descriptors, from {@code dependencies}, of the machines some {@code @GraphPeers} of
     *     the request declares that the request does not generate, each once, in the order of their first declaration
     */
    public record Inspection(@NonNull GenerationModel model, List<OriginalSource> sources,
            @NonNull List<PublishedIndex> dependencies, @NonNull List<GraphDescriptor> dependencyPeers) {

        /** Copies the lists, so an inspection never reflects a later change of the lists it was built from. */
        public Inspection {
            sources = List.copyOf(sources);
            dependencies = List.copyOf(dependencies);
            dependencyPeers = List.copyOf(dependencyPeers);
        }
    }

    /**
     * One original source the walk reached.
     *
     * @param file the file, as the walk reached it through the paths the request names
     * @param origin the path diagnostics name the file by: relative to the project directory, or absolute for a
     *     file on another root, separated by {@code /}
     * @param path where the file stands below its source root, separated by {@code /}: below the directory the
     *     request names when the walk found it there; for a file the request names itself, in the directories of
     *     the package it declares, which is where javac and every build place it
     * @param types the qualified names of the top-level types the file declares, in declaration order
     */
    public record OriginalSource(@NonNull Path file, @NonNull String origin, @NonNull String path, List<String> types) {

        /** Copies the list, so a source never reflects a later change of the list it was built from. */
        public OriginalSource {
            types = List.copyOf(types);
        }
    }
}
