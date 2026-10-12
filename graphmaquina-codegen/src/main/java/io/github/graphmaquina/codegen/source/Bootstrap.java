package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

    private static final String GENERATE_GRAPH_API = "io.github.graphmaquina.annotation.GenerateGraphApi";
    private static final String COLLISION = "SG603";

    /**
     * The directories of a version control system that Ant's default excludes leave out of a source directory with
     * everything inside them.
     */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", ".svn", ".hg", ".bzr", "CVS", "SCCS");

    private Bootstrap() {
    }

    /**
     * The graphs and symbols {@code request} declares, resolved against its sources and its classpath. A
     * declaration the generated vocabulary or references could not name from where they are generated, because it
     * is not accessible there or a type of that package or of {@code java.lang} takes its name over, is refused
     * here, before anything is emitted. One whose name a type the generator itself creates takes over is refused
     * later, by {@link io.github.graphmaquina.codegen.emit.ApiEmitter#emit}.
     */
    public static GenerationModel inspect(GenerationRequest request) {
        return read(request).model();
    }

    /**
     * The model of {@code request}, as {@link #inspect} reads it, with every original source it was read from.
     *
     * @param request the request to read
     * @return the model and the original sources it was read from
     */
    public static Inspection read(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
        Parsed parsed = parse(request);
        GenerationModel model;
        try (DeclarationIndex index = DeclarationIndex.open(parsed.distinctUnits(), request.classpath(),
                request.release(), request.encoding())) {
            List<MachineHeaders.Resolved> resolved = new ArrayList<>();
            List<GraphDescriptor> graphs = new ArrayList<>();
            for (TypeHeader facade : machines(index)) {
                MachineHeaders.Resolved machine = MachineHeaders.resolveDeclaration(index, facade);
                resolved.add(machine);
                graphs.add(machine.graph());
            }
            MachineHeaders.requireWatchablePeers(index, resolved);
            model = new GenerationModel(graphs, ExportDeclarations.collect(index));
        }
        return new Inspection(model, parsed.originals());
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
    public static List<OriginalSource> originals(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
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
     * of a class of the unnamed package, are refused.
     *
     * @throws CodegenException {@code SG603} naming both machines, anchored at the one read first
     */
    private static List<TypeHeader> machines(DeclarationIndex index) {
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
                throw new CodegenException(COLLISION, "the machines " + index.describe(first) + " and "
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
     * <p>Below a directory, the walk leaves out of its Java sources what the default excludes of Ant 1.10 leave out:
     * a file named {@code ._*}, {@code .DS_Store}, {@code *~}, {@code #*#}, {@code .#*} or {@code %*%}, such as the
     * AppleDouble companion a copy from macOS leaves beside a source, and every directory named {@code .git},
     * {@code .svn}, {@code .hg}, {@code .bzr}, {@code CVS} or {@code SCCS}, with everything inside it. Gradle leaves
     * out the same files while the build keeps those default excludes, which a settings script can change through
     * Ant's {@code DirectoryScanner}; the Gradle plugin hands a directory whole only while the build's default
     * excludes are Ant's and nothing else filters it, and names its files one by one otherwise, as Gradle selects
     * them. The scanner of maven-compiler-plugin leaves out more:
     * plexus-utils also excludes the directories {@code RCS}, {@code BitKeeper}, {@code ChangeSet}, {@code _darcs},
     * {@code .metadata}, {@code .arch-ids} and {@code .darcsrepo} and the files {@code -darcs-backup*}. The Maven
     * plugin applies that longer list itself whenever it lists files. A root the execution does not narrow is
     * handed to the generator whole, and the generator walks it with Ant's default excludes, not with
     * plexus-utils' longer list.</p>
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
                                    && EXCLUDED_DIRECTORIES.contains(visited.getFileName().toString())
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
     * Whether a file named {@code name} is one the Ant-style default excludes leave out:
     * {@code ._*}, {@code .DS_Store}, {@code *~}, {@code #*#}, {@code .#*} or {@code %*%}.
     */
    private static boolean excludedByDefault(String name) {
        return name.startsWith("._")
                || name.equals(".DS_Store")
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
     * What one reading of a request found: its model, and every original source the walk reached to read it, in
     * the order the walk reached them.
     *
     * @param model the graphs and symbols the request declares
     * @param sources the original sources the model was read from
     */
    public record Inspection(GenerationModel model, List<OriginalSource> sources) {

        /** Copies the list, so an inspection never reflects a later change of the list it was built from. */
        public Inspection {
            Objects.requireNonNull(model, "model");
            sources = List.copyOf(sources);
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
    public record OriginalSource(Path file, String origin, String path, List<String> types) {

        /** Copies the list, so a source never reflects a later change of the list it was built from. */
        public OriginalSource {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(path, "path");
            types = List.copyOf(types);
        }
    }
}
