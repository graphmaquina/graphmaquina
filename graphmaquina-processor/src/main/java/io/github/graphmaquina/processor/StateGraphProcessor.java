package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.codegen.output.CaseFolding;
import io.github.graphmaquina.processor.gen.BoundInvocation;
import io.github.graphmaquina.processor.gen.Emitted;
import io.github.graphmaquina.processor.gen.MachineGenerator;
import io.github.graphmaquina.processor.gen.SetGenerator;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.SetModel;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Entry point. Claims the annotations {@link ManifestRegistry} enumerates for this compilation, the fixed ones
 * and the generated ones alike, so it coexists with other processors and never matches a name it does not own.
 *
 * <p>Because exact annotation-value positions need javac's public {@code Trees} API, which Gradle's wrapped processing
 * environment cannot expose, this processor does not register for Gradle incremental annotation processing. Where no
 * {@code Trees} is available to the processor, because the environment is not plain javac or the processor's class
 * loader cannot load {@code com.sun.source}, the diagnostics keep Messager's anchors. Build adapters may still skip
 * unchanged compilation tasks through their normal up-to-date checks.</p>
 *
 * <p>What the processor learns is kept for the whole compilation, not for one round: a machine is resolved
 * once, its companion is handed to the {@code Filer} once, and a machine that failed is not built again by the
 * set that holds it. Each declaration is handled in the round it appears in, unless it waits for a later round as
 * the last paragraph says, and the round that ends the compilation publishes what the compilation verified, unless
 * this processor reported an error or javac says the round before raised one.</p>
 *
 * <p>Every round reports the generated vocabulary written where no builder reads it, and the machine that
 * vocabulary belongs to is not generated, nor is a machine whose vocabulary sits on the facade of another machine,
 * which that facade's builder refuses. An export that does not hold stops the generation and the publication
 * of the whole compilation, but not its validation: every other declaration is still checked, and only a machine
 * that uses such an export is left out without a word of its own. A generated name an existing type already
 * takes is a collision, unless that type is this processor's own output of a previous compilation that the build
 * did not retire, which is reported as such. Two companion names that differ only in case are a collision too where
 * the volume of the companions' sources or of the class output does not tell names apart; and a facade or a set
 * whose companion would write a name that a type or a field of the companion's scope takes is refused, as
 * {@link CompanionScope} decides, before its companion is written. A refused declaration withholds only what depends
 * on it: its own companion and, for a machine, the companion of a set that holds it. The round goes on, and every
 * declaration of it is still checked. A type that a later round brings into the package of a companion already
 * written, and that takes a name the companion writes, is reported at that companion's facade or set.</p>
 *
 * <p>Only a machine some round hands to this processor gets a companion. javac hands none of the sources it
 * compiles implicitly, from the source path, so a set that holds such a machine of this compilation is reported
 * and gets no companion either, and an index that names one is not published.</p>
 *
 * <p>A value javac could not resolve yet, a class literal or a constant that gives a text or a number, may name a type
 * a later round brings: one another processor generates, or a companion this one writes. javac hands such a value to
 * the processor unread, resolves it in the round that brings that type, and reports it once processing is over only if
 * no round did. The values of an annotation this processor does not claim, the application's or another tool's, are
 * never read, so they never hold a machine back, even where they name its own companion. A machine some round handed
 * whose own declarations, on the facade or on one of its members, hold such a value waits instead, by name, for the
 * first round that resolves it, and is built and generated in that round; so does a set that holds it. A value the
 * round that ends processing still finds unresolved is javac's alone to refuse: that machine is then built like a
 * machine whose vocabulary is misplaced, every other problem of its declarations is reported, but nothing is concluded
 * from its graph as a whole, which misses what that value declares, and neither it nor a set that holds it gets a
 * companion; nor is the index, which names it, published.</p>
 */
public final class StateGraphProcessor extends AbstractProcessor {

    private ManifestRegistry manifests = ManifestRegistry.unloaded();
    private boolean reportedManifest;
    private boolean validatedExports;
    private Set<String> invalidExports = Set.of();
    private Set<String> previousInventory;
    private Diagnostics diagnostics;
    private ExportValidator exports;
    private ExportIndexWriter indexes;
    private BoundInvocation invocations;
    private Signatures signatures;
    private EffectiveMethods effective;
    private Optional<TypedMachineModelBuilder> typed = Optional.empty();
    private Optional<VocabularyPlacement> placement = Optional.empty();
    private CompanionScope scope;
    private MissingExportIndex missingIndex;
    private SetModelBuilder sets;
    private Map<String, MachineModel> models;
    private Set<String> failed;
    private Set<String> withheld;
    private Set<String> handed;
    private Set<String> deferred;
    private Set<String> deferredSets;
    private Set<String> refused;
    private Set<String> generated;
    private Set<String> verified;
    private final Map<String, TypeElement> runtimeOrigins = new LinkedHashMap<>();
    private final Map<String, String> runtimeCases = new LinkedHashMap<>();

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(ManifestRegistry.OPTION);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return manifests.supportedAnnotationTypes();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        manifests = ManifestRegistry.load(env);
        diagnostics = new Diagnostics(env);
        models = new LinkedHashMap<>();
        failed = new HashSet<>();
        withheld = new HashSet<>();
        handed = new HashSet<>();
        deferred = new LinkedHashSet<>();
        deferredSets = new LinkedHashSet<>();
        refused = new HashSet<>();
        generated = new LinkedHashSet<>();
        verified = new LinkedHashSet<>();
        signatures = new Signatures(env.getElementUtils(), env.getTypeUtils());
        Annotations annotations = new Annotations(env.getElementUtils());
        Accessors accessors = new Accessors(signatures);
        Bindings bindings = new Bindings(signatures);
        effective = new EffectiveMethods(signatures);
        exports = new ExportValidator(signatures, effective, diagnostics);
        indexes = new ExportIndexWriter(env);
        invocations = new BoundInvocation(env.getElementUtils(), env.getTypeUtils());
        StructuralChecks structure = new StructuralChecks(signatures, diagnostics);
        sets = new SetModelBuilder(annotations, signatures, diagnostics, invocations);
        scope = new CompanionScope(env.getElementUtils(), diagnostics, runtimeOrigins);
        missingIndex = new MissingExportIndex(env.getElementUtils(), env.getFiler());
        placement = manifests.manifest().map((CompileManifest manifest) ->
                new VocabularyPlacement(manifest, annotations, diagnostics));
        typed = manifests.manifest().map((CompileManifest manifest) -> {
            Map<String, SymbolDescriptor> symbols = SymbolResolver.symbols(manifest);
            VocabularyRegistry vocabulary = new VocabularyRegistry(manifest, env.getElementUtils());
            PropertyResolver propertyResolver = new PropertyResolver(symbols, missingIndex, signatures, accessors,
                    diagnostics);
            SymbolResolver symbolResolver = new SymbolResolver(symbols, missingIndex, signatures, effective, bindings,
                    invocations, diagnostics);
            TypedPairs pairs = new TypedPairs(structure, signatures, diagnostics);
            return new TypedMachineModelBuilder(vocabulary, structure, propertyResolver,
                    new TypedTransitions(structure, symbolResolver, new TypedTimeouts(annotations, diagnostics),
                            annotations, diagnostics),
                    pairs,
                    new TypedWatchReader(vocabulary, pairs, propertyResolver, symbolResolver, signatures, diagnostics),
                    new TypedSubmachineReader(vocabulary, pairs, propertyResolver, symbolResolver, signatures,
                            diagnostics),
                    signatures, diagnostics);
        });
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotationTypes, RoundEnvironment round) {
        forgetMembers();
        reportManifestFailure();
        scope.checkLaterTypes();
        placement.ifPresent((VocabularyPlacement check) -> withheld.addAll(check.report(annotationTypes, round)));
        validateExports();
        validateRuntimeNames(round);
        for (TypeElement type : waited(deferred)) {
            resolve(type, round);
        }
        for (Element element : round.getElementsAnnotatedWith(GenerateGraphApi.class)) {
            handed.add(((TypeElement) element).getQualifiedName().toString());
            resolve((TypeElement) element, round);
        }
        List<TypeElement> declaredSets = waited(deferredSets);
        declaredSets.addAll(ElementFilter.typesIn(round.getElementsAnnotatedWith(StateGraphs.class)));
        for (TypeElement type : declaredSets) {
            if (holdsDeferred(type)) {
                deferredSets.add(type.getQualifiedName().toString());
                continue;
            }
            Optional<SetModel> set = sets.build(type, (TypeElement machine) -> held(machine, type, round));
            Optional<Emitted> companion = set
                    .filter((SetModel model) -> !refused.contains(type.getQualifiedName().toString()))
                    .map((SetModel model) -> SetGenerator.emit(model, invocations));
            if (companion.isPresent() && scope.admits(type, SymbolNames.runtimeName(set.get().setName()),
                    SetGenerator.MEMBER_TYPES, Set.of(), companion.get().written()) && invalidExports.isEmpty()) {
                write(SetGenerator.qualifiedName(set.get()), companion.get(), type);
            }
        }
        publish(round);
        return true;
    }

    /**
     * The declarations {@code names} kept waiting for a later round, looked up again by name, since javac enters
     * every source again in each round and only the elements of that round see the types it brought. {@code names}
     * is emptied: a declaration that must wait still is put back in it.
     */
    private List<TypeElement> waited(Set<String> names) {
        List<TypeElement> types = new ArrayList<>();
        for (String name : names) {
            types.add(processingEnv.getElementUtils().getTypeElement(name));
        }
        names.clear();
        return types;
    }

    /**
     * Whether a method the set {@code type} declares returns a machine that waits for a later round, so that the
     * set, whose companion could not build that machine yet, waits with it.
     */
    private boolean holdsDeferred(TypeElement type) {
        for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
            if (method.getReturnType() instanceof DeclaredType returned
                    && returned.asElement() instanceof TypeElement machine
                    && deferred.contains(machine.getQualifiedName().toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops every listing of members an earlier round made, which the members javac enters afresh in this round
     * replace: the members each type declares and inherits, the effective methods of each host and the methods of
     * each owner a generated call competes with. Within a round each of them is listed once, however many
     * declarations name it.
     */
    private void forgetMembers() {
        signatures.members().forget();
        effective.forget();
        invocations.forget();
    }

    /**
     * Reserves the normalized runtime names of this round's top-level declarations before any source of the round is
     * written. A declaration is refused, at the declaration itself, when its simple name does not normalize to a
     * runtime name, when another declaration or an existing type takes that name already, when the companion name of
     * another declaration differs from it only in case and the volume of the companions' sources or of the class
     * output does not tell names apart, which would hold the two companions, or their class files, as one file, or
     * when its companion would take a name that a companion written in an earlier round writes. A refused declaration
     * is still checked like every other declaration of the round, so all their problems are reported together, but
     * its companion is never written, nor is the companion of a set that holds it, which would name that companion.
     *
     * <p>The existing type is looked up by the companion's qualified name as javac's {@code Filer} looks up a type
     * it is asked to create, so a member type whose canonical name that is takes the name too, as one nested in a
     * class of the unnamed package named like the first segment of the package does. The {@code Filer} refuses to
     * create a type over one declared in a source the compilation was given, over one a processor created in it
     * already, or over a class it was given by name; it warns of any other existing type under
     * {@code -Xlint:processing}.</p>
     */
    private void validateRuntimeNames(RoundEnvironment round) {
        Set<Element> declarations = new LinkedHashSet<>(round.getElementsAnnotatedWith(GenerateGraphApi.class));
        declarations.addAll(round.getElementsAnnotatedWith(StateGraphs.class));
        for (Element declaration : declarations) {
            TypeElement type = (TypeElement) declaration;
            if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
                continue; // The model builder reports unsupported nested declarations as SG502.
            }
            String declared = type.getQualifiedName().toString();
            String name;
            try {
                name = SymbolNames.qualified(processingEnv.getElementUtils().getPackageOf(type)
                        .getQualifiedName().toString(), SymbolNames.runtimeName(type.getSimpleName().toString()));
            } catch (IllegalArgumentException failure) {
                diagnostics.error("SG603", failure.getMessage(), type);
                refused.add(declared);
                continue;
            }
            TypeElement previous = runtimeOrigins.putIfAbsent(name, type);
            if (previous != null) {
                if (!previous.getQualifiedName().contentEquals(type.getQualifiedName())) {
                    diagnostics.error("SG603", "the generated runtime " + name + " is claimed by both "
                            + previous.getQualifiedName() + " and " + type.getQualifiedName(), type);
                    refused.add(declared);
                }
                continue;
            }
            String twin = runtimeCases.putIfAbsent(CaseFolding.key(name), name);
            Optional<String> volume = twin == null ? Optional.empty() : caseBlindVolume();
            if (volume.isPresent()) {
                diagnostics.error("SG603", "the generated runtimes " + twin + " of "
                        + runtimeOrigins.get(twin).getQualifiedName() + " and " + name + " of "
                        + type.getQualifiedName() + " differ only in case, and the volume of " + volume.get()
                        + " does not tell their files apart", type);
                refused.add(declared);
                continue;
            }
            TypeElement existing = processingEnv.getElementUtils().getTypeElement(name);
            if (existing == null) {
                if (!scope.admitsLater(type, name)) {
                    refused.add(declared);
                }
                continue;
            }
            refused.add(declared);
            if (isStaleOutput(existing, round)) {
                diagnostics.error("SG602", "the generated runtime " + name + " of " + type.getQualifiedName()
                        + " is still compiled from a previous compilation that nothing retired: run the generation"
                        + " lifecycle, whose prepare-compile step retires it, or clean the build, then compile again",
                        type);
            } else {
                diagnostics.error("SG603", "the generated runtime " + name + " for " + type.getQualifiedName()
                        + " conflicts with existing type " + existing.getQualifiedName(), type);
            }
        }
    }

    /**
     * The first output of the companions whose volume does not tell names apart by case, named as a diagnostic names
     * it: the output of their sources, then the class output, each the directory the {@code Filer} resolves a file of
     * the unnamed package in. Empty when each tells names apart, and for an output that is no directory of the
     * default file system or that cannot be examined, as one a file manager keeps in memory.
     */
    private Optional<String> caseBlindVolume() {
        Map<String, StandardLocation> outputs = new LinkedHashMap<>();
        outputs.put("the generated sources ", StandardLocation.SOURCE_OUTPUT);
        outputs.put("the class output ", StandardLocation.CLASS_OUTPUT);
        for (Map.Entry<String, StandardLocation> output : outputs.entrySet()) {
            try {
                FileObject probe = processingEnv.getFiler().getResource(output.getValue(), "", "graphmaquina-case");
                Path directory = Path.of(probe.toUri()).getParent();
                if (directory != null && CaseFolding.folds(directory)) {
                    return Optional.of(output.getKey() + directory);
                }
            } catch (IOException | IllegalArgumentException | FileSystemNotFoundException | SecurityException unknown) {
                // An output that is no directory, or that cannot be examined, is not known to hold two names as one.
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code existing} is what a previous compilation of this processor generated, still on the classpath
     * because nothing retired it: a type this round does not compile from source, which the inventory the
     * previous run wrote lists. A source of this round that takes the generated name is a collision instead.
     */
    private boolean isStaleOutput(TypeElement existing, RoundEnvironment round) {
        Element topLevel = existing;
        while (topLevel.getEnclosingElement() instanceof TypeElement enclosing) {
            topLevel = enclosing;
        }
        return !round.getRootElements().contains(topLevel)
                && previousInventory().contains(existing.getQualifiedName().toString());
    }

    /**
     * The qualified names the previous run of this processor generated, read once from the inventory the manifest
     * names; none when there is no manifest, no inventory yet, or one that cannot be read, so that nothing which
     * is not certainly this processor's own output is ever taken for it.
     */
    private Set<String> previousInventory() {
        if (previousInventory == null) {
            previousInventory = manifests.manifest().map((CompileManifest manifest) -> {
                try {
                    Path inventory = Path.of(manifest.processorInventory());
                    return Files.isRegularFile(inventory)
                            ? Set.copyOf(ManifestCodec.readProcessorInventory(inventory))
                            : Set.<String>of();
                } catch (IOException | CodegenException | InvalidPathException unreadable) {
                    return Set.<String>of();
                }
            }).orElse(Set.of());
        }
        return previousInventory;
    }

    /** A manifest the registry refused is reported once: init has no round to anchor a diagnostic at. */
    private void reportManifestFailure() {
        if (reportedManifest) {
            return;
        }
        reportedManifest = true;
        manifests.failure().ifPresent((String reason) -> diagnostics.error("SG602", reason));
    }

    /**
     * Checks once, against javac's own declarations, whether the members this compilation exports hold, and
     * records every local export as verified once they all hold; a single failure verifies none of them.
     *
     * <p>An export that does not hold stops what the compilation generates and publishes, and nothing else: every
     * declaration is still validated, so the build reports all its problems at once. Only a machine that uses such
     * an export is left out, without a word, since a graph built on it would only repeat at every use what the
     * export's own {@code SG604} already reported.</p>
     */
    private void validateExports() {
        if (validatedExports) {
            return;
        }
        validatedExports = true;
        manifests.manifest().ifPresent((CompileManifest manifest) -> {
            invalidExports = exports.validate(manifest);
            if (invalidExports.isEmpty()) {
                for (SymbolDescriptor symbol : manifest.local().model().symbols()) {
                    verified.add(symbol.qualifiedName());
                }
            }
        });
    }

    /**
     * Whether a declaration of {@code type}, on the facade or on one of its members, names an export that does
     * not hold: its class literal is read wherever the vocabulary puts it, nested annotations and arrays included.
     */
    private boolean usesInvalidExport(TypeElement type) {
        if (invalidExports.isEmpty()) {
            return false;
        }
        if (names(type)) {
            return true;
        }
        for (Element member : type.getEnclosedElements()) {
            if (names(member)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a declaration of {@code type}, on the facade or on one of its members, holds a value of an annotation
     * this processor claims that javac has not resolved, nested annotations and arrays included: a class literal, or
     * a constant that gives a text or a number. javac hands such a value to the processor as the text of an error: a
     * string where its element declares something else, or, where its element declares a string, one whose source
     * form, which {@link AnnotationValue#toString()} returns, is not a string literal, so a text that reads like that
     * error is still a text. javac enters every source again in the next round, where the value names a type that
     * round brought, and reports it once processing is over if none did. The values of any other annotation are never
     * read: no model is built from them.
     */
    private boolean holdsUnresolvedValue(TypeElement type) {
        if (unresolved(type)) {
            return true;
        }
        for (Element member : type.getEnclosedElements()) {
            if (unresolved(member)) {
                return true;
            }
        }
        return false;
    }

    private boolean unresolved(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().asElement() instanceof TypeElement annotation
                    && manifests.supportedAnnotationTypes().contains(annotation.getQualifiedName().toString())
                    && unresolved(mirror)) {
                return true;
            }
        }
        return false;
    }

    private static boolean unresolved(AnnotationMirror mirror) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> value
                : mirror.getElementValues().entrySet()) {
            if (unresolved(value.getKey().getReturnType(), value.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean unresolved(TypeMirror declared, AnnotationValue value) {
        return switch (value.getValue()) {
            case String text -> !(declared instanceof DeclaredType type
                    && ((TypeElement) type.asElement()).getQualifiedName().contentEquals("java.lang.String"))
                    || !value.toString().startsWith("\"");
            case AnnotationMirror nested -> unresolved(nested);
            case List<?> values -> declared instanceof ArrayType array && values.stream()
                    .anyMatch((Object item) -> unresolved(array.getComponentType(), (AnnotationValue) item));
            default -> false;
        };
    }

    private boolean names(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (names(mirror)) {
                return true;
            }
        }
        return false;
    }

    private boolean names(AnnotationMirror mirror) {
        for (AnnotationValue value : mirror.getElementValues().values()) {
            if (names(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean names(AnnotationValue value) {
        return switch (value.getValue()) {
            case DeclaredType type -> type.asElement() instanceof TypeElement element
                    && invalidExports.contains(element.getQualifiedName().toString());
            case AnnotationMirror nested -> names(nested);
            case List<?> values -> values.stream().anyMatch((Object item) -> names((AnnotationValue) item));
            default -> false;
        };
    }

    /**
     * The model of {@code machine}, which the set {@code set} holds. A machine the index of this compilation names
     * that no round handed to this processor, which is what javac does with a source it compiles implicitly, from
     * the source path, has no companion, and the set's companion could not build it without one: that is reported
     * as {@code SG602} at the set, whose companion is not generated either. So is a machine that no index lists and
     * that comes from a dependency whose export index is missing or does not list it: its vocabulary is read only
     * where an index lists it.
     */
    private Optional<MachineModel> held(TypeElement machine, TypeElement set, RoundEnvironment round) {
        String name = machine.getQualifiedName().toString();
        if (!models.containsKey(name) && !failed.contains(name) && isLocal(name)) {
            diagnostics.error("SG602", set.getQualifiedName() + " holds " + name + ", which this compilation never"
                    + " processed, so neither gets a companion: javac does not process a source it compiles"
                    + " implicitly, from the source path, so pass every source of the compilation to javac", set);
            return Optional.empty();
        }
        Optional<String> unlisted = isListed(name) ? Optional.empty() : missingIndex.unlisted(machine);
        if (unlisted.isPresent()) {
            diagnostics.error("SG602", set.getQualifiedName() + " holds " + name + ", but " + name + unlisted.get(),
                    set);
            return Optional.empty();
        }
        return resolve(machine, round);
    }

    /** Whether the index of this compilation names the machine {@code name}, which one of its sources declares. */
    private boolean isLocal(String name) {
        return manifests.manifest().map((CompileManifest manifest) -> manifest.local().model().graphs().stream()
                .anyMatch((GraphDescriptor graph) -> graph.facade().equals(name))).orElse(false);
    }

    /**
     * Whether an index this compilation reads, its own or a dependency's, lists the machine {@code name}. Without a
     * manifest, which has been reported already, nothing is known not to be listed.
     */
    private boolean isListed(String name) {
        return manifests.manifest().map((CompileManifest manifest) -> Stream.concat(Stream.of(manifest.local()),
                        manifest.dependencies().stream())
                .flatMap((ExportIndex index) -> index.model().graphs().stream())
                .anyMatch((GraphDescriptor graph) -> graph.facade().equals(name))).orElse(true);
    }

    /**
     * A machine's model, built once for the whole compilation. Only a machine some round handed to the processor
     * gets its companion generated, and only such a machine counts as verified for the index; one that is not, a
     * machine from the classpath, which carries its companion in its own jar already, is built on demand for the
     * set that holds it. A handed machine whose declarations hold a value javac has not resolved is not built yet:
     * it waits for a later round, which may bring the type that value names, and is built in the first round that
     * resolves it. One that the round ending processing still finds unresolved, like a machine from the classpath
     * whose declarations hold such a value, is built as a machine whose vocabulary is misplaced is: javac refuses
     * that value. A machine whose vocabulary is written where no builder reads it, or on the facade of another
     * machine, is still built, so every other problem of its declarations is reported, but without the checks of
     * its graph as a whole, which misses that vocabulary, and it is never generated. Nor is a machine whose
     * companion name was refused, which is still built and checked like every other machine of the round.
     */
    private Optional<MachineModel> resolve(TypeElement type, RoundEnvironment round) {
        String name = type.getQualifiedName().toString();
        if (models.containsKey(name)) {
            return Optional.of(models.get(name));
        }
        if (failed.contains(name) || usesInvalidExport(type)) {
            failed.add(name);
            return Optional.empty();
        }
        boolean unresolved = holdsUnresolvedValue(type);
        if (unresolved && handed.contains(name) && !round.processingOver()) {
            deferred.add(name);
            return Optional.empty();
        }
        Optional<MachineModel> model = build(type, !withheld.contains(name) && !unresolved);
        if (model.isEmpty() || withheld.contains(name) || refused.contains(name) || unresolved) {
            failed.add(name);
            return Optional.empty();
        }
        Optional<Emitted> companion = handed.contains(name)
                ? Optional.of(MachineGenerator.emit(model.get(), invocations)) : Optional.empty();
        if (companion.isPresent() && !scope.admits(type, SymbolNames.runtimeName(model.get().facadeName()), Set.of(),
                MachineGenerator.FIXED_FIELDS, companion.get().written())) {
            failed.add(name);
            return Optional.empty();
        }
        models.put(name, model.get());
        if (companion.isPresent()) {
            verified.add(name);
            if (invalidExports.isEmpty()) {
                write(MachineGenerator.qualifiedName(model.get()), companion.get(), type);
            }
        }
        return model;
    }

    /**
     * The model of {@code type}, read with the vocabulary generated for it; {@code complete} is false for a machine
     * whose graph misses vocabulary of its own: written where no builder reads it, or on the facade of another
     * machine, whose builder refuses it, or holding a value of its own declarations that javac did not resolve. A
     * compilation whose manifest could not be trusted has no vocabulary to read anything with, and has already said
     * so once as {@code SG602}.
     */
    private Optional<MachineModel> build(TypeElement type, boolean complete) {
        return typed.flatMap((TypedMachineModelBuilder builder) -> builder.build(type, complete));
    }

    /**
     * Publishes the provisional index of this compilation, once, in the round that ends it, unless this processor
     * reported an error or {@code round.errorRaised()} says the round before raised one: what a compilation that
     * failed exports is not known. An error javac reports once processing is over, such as one in a generated
     * source or in a value of an annotation this processor does not claim, comes after the publication and does not
     * undo it; a value of a handed machine's own declarations that no round resolved kept that machine from being
     * verified, which keeps the index from being published.
     */
    private void publish(RoundEnvironment round) {
        if (!round.processingOver() || round.errorRaised() || diagnostics.errors() > 0) {
            return;
        }
        manifests.manifest().ifPresent((CompileManifest manifest) ->
                indexes.publish(manifest, Set.copyOf(verified), Set.copyOf(handed), Set.copyOf(generated)));
    }

    /**
     * Writes one generated source, and only once: the Filer refuses a name it has already created a file for. Once
     * the source is written, the companion's scope checks what it writes against the types of its package that a
     * later round brings; a companion whose source could not be written is reported as {@code SG599}, and nothing
     * is checked against it.
     */
    private void write(String qualifiedName, Emitted companion, TypeElement origin) {
        if (!generated.add(qualifiedName)) {
            return;
        }
        try (Writer writer = processingEnv.getFiler().createSourceFile(qualifiedName, origin).openWriter()) {
            writer.write(companion.source());
        } catch (IOException e) {
            diagnostics.error("SG599", "could not write " + qualifiedName + ": " + e.getMessage(), origin);
            return;
        }
        scope.wrote(qualifiedName, origin, companion.written());
    }
}
