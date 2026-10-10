package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.processor.gen.BoundInvocation;
import io.github.graphmaquina.processor.gen.Emitted;
import io.github.graphmaquina.processor.gen.MachineGenerator;
import io.github.graphmaquina.processor.gen.SetGenerator;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.SetModel;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;

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
 * set that holds it. Each declaration is handled in the round it appears in, and the round that ends the
 * compilation publishes what the compilation verified, unless this processor reported an error or javac says the
 * round before raised one.</p>
 *
 * <p>Every round reports the generated vocabulary written where no builder reads it, and the machine that
 * vocabulary belongs to is not generated, nor is a machine whose vocabulary sits on the facade of another machine,
 * which that facade's builder refuses. An export that does not hold stops the generation and the publication
 * of the whole compilation, but not its validation: every other declaration is still checked, and only a machine
 * that uses such an export is left out without a word of its own. A generated name an existing type already
 * takes is a collision, unless that type is this processor's own output of a previous compilation that the build
 * did not retire, which is reported as such; and a facade or a set whose companion would write a name that a
 * type or a field of the companion's scope takes is refused, as {@link CompanionScope} decides, before its
 * companion is written. A refused declaration withholds only what depends on it: its own companion and, for a
 * machine, the companion of a set that holds it. The round goes on, and every declaration of it is still checked.
 * A type that a later round brings into the package of a companion already written, and that takes a name the
 * companion writes, is reported at that companion's facade or set.</p>
 *
 * <p>Only a machine some round hands to this processor gets a companion. javac hands none of the sources it
 * compiles implicitly, from the source path, so a set that holds such a machine of this compilation is reported
 * and gets no companion either, and an index that names one is not published.</p>
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
    private LegacyDeclarations legacy;
    private Optional<TypedMachineModelBuilder> typed = Optional.empty();
    private Optional<VocabularyPlacement> placement = Optional.empty();
    private CompanionScope scope;
    private SetModelBuilder sets;
    private Map<String, MachineModel> models;
    private Set<String> failed;
    private Set<String> withheld;
    private Set<String> refused;
    private Set<String> generated;
    private Set<String> verified;
    private final Map<String, TypeElement> runtimeOrigins = new LinkedHashMap<>();

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
        refused = new HashSet<>();
        generated = new LinkedHashSet<>();
        verified = new LinkedHashSet<>();
        Signatures signatures = new Signatures(env.getElementUtils(), env.getTypeUtils());
        Annotations annotations = new Annotations(env.getElementUtils());
        Accessors accessors = new Accessors(signatures);
        Bindings bindings = new Bindings(signatures);
        EffectiveMethods effective = new EffectiveMethods(signatures);
        exports = new ExportValidator(signatures, effective, diagnostics);
        indexes = new ExportIndexWriter(env);
        invocations = new BoundInvocation(env.getElementUtils(), env.getTypeUtils());
        legacy = new LegacyDeclarations(diagnostics);
        StructuralChecks structure = new StructuralChecks(signatures, diagnostics);
        sets = new SetModelBuilder(annotations, signatures, diagnostics, invocations);
        scope = new CompanionScope(env.getElementUtils(), diagnostics, runtimeOrigins);
        placement = manifests.manifest().map((CompileManifest manifest) ->
                new VocabularyPlacement(manifest, annotations, diagnostics));
        typed = manifests.manifest().map((CompileManifest manifest) -> {
            Map<String, SymbolDescriptor> symbols = SymbolResolver.symbols(manifest);
            VocabularyRegistry vocabulary = new VocabularyRegistry(manifest, env.getElementUtils());
            PropertyResolver propertyResolver = new PropertyResolver(symbols, signatures, accessors, diagnostics);
            SymbolResolver symbolResolver = new SymbolResolver(symbols, signatures, effective, bindings, diagnostics);
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
        reportManifestFailure();
        scope.checkLaterTypes();
        placement.ifPresent((VocabularyPlacement check) -> withheld.addAll(check.report(annotationTypes, round)));
        validateExports();
        validateRuntimeNames(round);
        failed.addAll(legacy.report(round));
        for (Element element : round.getElementsAnnotatedWith(GenerateGraphApi.class)) {
            resolve((TypeElement) element, round);
        }
        for (Element element : round.getElementsAnnotatedWith(StateGraphs.class)) {
            TypeElement type = (TypeElement) element;
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
     * Reserves the normalized runtime names of this round's top-level declarations before any source of the round is
     * written. A declaration is refused, at the declaration itself, when its simple name does not normalize to a
     * runtime name, when another declaration or an existing type takes that name already, or when its companion
     * would take a name that a companion written in an earlier round writes. A refused declaration is still
     * checked like every other declaration of the round, so all their problems are reported together, but its
     * companion is never written, nor is the companion of a set that holds it, which would name that companion.
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
     * as {@code SG602} at the set, whose companion is not generated either.
     */
    private Optional<MachineModel> held(TypeElement machine, TypeElement set, RoundEnvironment round) {
        String name = machine.getQualifiedName().toString();
        if (!models.containsKey(name) && !failed.contains(name) && isLocal(name)) {
            diagnostics.error("SG602", set.getQualifiedName() + " holds " + name + ", which this compilation never"
                    + " processed, so neither gets a companion: javac does not process a source it compiles"
                    + " implicitly, from the source path, so pass every source of the compilation to javac", set);
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
     * A machine's model, built once for the whole compilation. Only a machine that is a root element of the round
     * gets its companion generated, and only such a machine counts as verified for the index; one that is not, a
     * machine from the classpath, which carries its companion in its own jar already, is built on demand for the
     * set that holds it. A machine whose vocabulary is written where no builder reads it, or on the facade of
     * another machine, is still built, so every other problem of its declarations is reported, but without the
     * checks of its graph as a whole, which misses that vocabulary, and it is never generated. Nor is a machine
     * whose companion name was refused, which is still built and checked like every other machine of the round.
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
        Optional<MachineModel> model = build(type, !withheld.contains(name));
        if (model.isEmpty() || withheld.contains(name) || refused.contains(name)) {
            failed.add(name);
            return Optional.empty();
        }
        boolean rootThisRound = round.getRootElements().contains(type);
        Optional<Emitted> companion = rootThisRound
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
     * machine, whose builder refuses it. A compilation whose manifest could not be trusted has no vocabulary to read
     * anything with, and has already said so once as {@code SG602}.
     */
    private Optional<MachineModel> build(TypeElement type, boolean complete) {
        return typed.flatMap((TypedMachineModelBuilder builder) -> builder.build(type, complete));
    }

    /**
     * Publishes the provisional index of this compilation, once, in the round that ends it, unless this processor
     * reported an error or {@code round.errorRaised()} says the round before raised one: what a compilation that
     * failed exports is not known. An error javac reports once processing is over, such as one in a generated
     * source, comes after the publication and does not undo it.
     */
    private void publish(RoundEnvironment round) {
        if (!round.processingOver() || round.errorRaised() || diagnostics.errors() > 0) {
            return;
        }
        manifests.manifest().ifPresent((CompileManifest manifest) ->
                indexes.publish(manifest, Set.copyOf(verified), Set.copyOf(generated)));
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
