package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.processor.gen.BoundInvocation;
import io.github.graphmaquina.processor.gen.Emitted;
import io.github.graphmaquina.processor.gen.MachineGenerator;
import io.github.graphmaquina.processor.gen.SetGenerator;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.SetModel;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
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
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementFilter;

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
    private Diagnostics diagnostics;
    private ExportValidator exports;
    private ExportIndexWriter indexes;
    private BoundInvocation invocations;
    private Signatures signatures;
    private EffectiveMethods effective;
    private Optional<TypedMachineModelBuilder> typed = Optional.empty();
    private Optional<VocabularyPlacement> placement = Optional.empty();
    private RuntimeNameCheck runtimeNames;
    private CompanionScope scope;
    private MissingExportIndex missingIndex;
    private SetModelBuilder sets;
    private Map<String, MachineModel> models;
    private Set<String> failed;
    private Set<String> withheld;
    private Set<String> handed;
    private Set<String> deferred;
    private Set<String> deferredSets;
    private Set<String> generated;
    private Set<String> verified;

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
        generated = new LinkedHashSet<>();
        verified = new LinkedHashSet<>();
        signatures = new Signatures(env.getElementUtils(), env.getTypeUtils());
        Annotations annotations = new Annotations(env.getElementUtils());
        effective = new EffectiveMethods(signatures);
        exports = new ExportValidator(signatures, effective, diagnostics);
        indexes = new ExportIndexWriter(env);
        invocations = new BoundInvocation(env.getElementUtils(), env.getTypeUtils());
        sets = new SetModelBuilder(annotations, signatures, diagnostics, invocations);
        runtimeNames = new RuntimeNameCheck(env, manifests.manifest(), diagnostics);
        scope = new CompanionScope(env.getElementUtils(), diagnostics, runtimeNames.companions());
        missingIndex = new MissingExportIndex(env.getElementUtils(), env.getFiler());
        placement = manifests.manifest().map((CompileManifest manifest) ->
                new VocabularyPlacement(manifest, annotations, diagnostics));
        typed = manifests.manifest().map((CompileManifest manifest) -> TypedPipeline.modelBuilder(manifest,
                env.getElementUtils(), annotations, signatures, effective, invocations, missingIndex, diagnostics));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotationTypes, RoundEnvironment round) {
        forgetMembers();
        reportManifestFailure();
        scope.checkLaterTypes();
        placement.ifPresent((VocabularyPlacement check) -> withheld.addAll(check.report(annotationTypes, round)));
        validateExports();
        runtimeNames.validate(round, scope);
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
                    .filter((SetModel model) -> !runtimeNames.refused(type.getQualifiedName().toString()))
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

    /** A manifest the registry refused is reported once: init has no round to anchor a diagnostic at. */
    private void reportManifestFailure() {
        if (reportedManifest) {
            return;
        }
        reportedManifest = true;
        manifests.failure().ifPresent((String reason) -> diagnostics.error(DiagnosticCode.SG602, reason));
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
     * The model of {@code machine}, which the set {@code set} holds. A machine the index of this compilation names
     * that no round handed to this processor, which is what javac does with a source it compiles implicitly, from
     * the source path, has no companion, and the set's companion could not build it without one: that is reported
     * as {@code SG602} at the set, whose companion is not generated either. So is a machine that no index lists and
     * that comes from a dependency whose export index is missing or does not list it: its vocabulary is read only
     * where an index lists it.
     */
    private Optional<MachineModel> held(TypeElement machine, TypeElement set, RoundEnvironment round) {
        String name = machine.getQualifiedName().toString();
        if (!models.containsKey(name) && !failed.contains(name) && manifests.isLocal(name)) {
            diagnostics.error(DiagnosticCode.SG602,
                    set.getQualifiedName() + " holds " + name + ", which this compilation never"
                    + " processed, so neither gets a companion: javac does not process a source it compiles"
                    + " implicitly, from the source path, so pass every source of the compilation to javac", set);
            return Optional.empty();
        }
        Optional<String> unlisted = manifests.isListed(name) ? Optional.empty() : missingIndex.unlisted(machine);
        if (unlisted.isPresent()) {
            diagnostics.error(DiagnosticCode.SG602,
                    set.getQualifiedName() + " holds " + name + ", but " + name + unlisted.get(),
                    set);
            return Optional.empty();
        }
        return resolve(machine, round);
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
        if (failed.contains(name) || AnnotationValueScan.usesInvalidExport(type, invalidExports)) {
            failed.add(name);
            return Optional.empty();
        }
        boolean unresolved = AnnotationValueScan.holdsUnresolvedValue(type, manifests.supportedAnnotationTypes());
        if (unresolved && handed.contains(name) && !round.processingOver()) {
            deferred.add(name);
            return Optional.empty();
        }
        Optional<MachineModel> model = build(type, !withheld.contains(name) && !unresolved);
        if (model.isEmpty() || withheld.contains(name) || runtimeNames.refused(name) || unresolved) {
            failed.add(name);
            return Optional.empty();
        }
        return keepAndGenerate(type, name, model.get());
    }

    /**
     * Keeps {@code model}, the model of the machine {@code type} named {@code name}, for the whole compilation and,
     * for a machine some round handed to the processor, emits its companion, which counts the machine as verified
     * and is written unless an export does not hold. A companion that would write a name its scope takes is refused,
     * as {@link CompanionScope} decides, and so is the model: the machine failed.
     */
    private Optional<MachineModel> keepAndGenerate(TypeElement type, String name, MachineModel model) {
        Optional<Emitted> companion = handed.contains(name)
                ? Optional.of(MachineGenerator.emit(model, invocations)) : Optional.empty();
        if (companion.isPresent() && !scope.admits(type, SymbolNames.runtimeName(model.facadeName()), Set.of(),
                MachineGenerator.FIXED_FIELDS, companion.get().written())) {
            failed.add(name);
            return Optional.empty();
        }
        models.put(name, model);
        if (companion.isPresent()) {
            verified.add(name);
            if (invalidExports.isEmpty()) {
                write(MachineGenerator.qualifiedName(model), companion.get(), type);
            }
        }
        return Optional.of(model);
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
            diagnostics.error(DiagnosticCode.SG599, "could not write " + qualifiedName + ": " + e.getMessage(), origin);
            return;
        }
        scope.wrote(qualifiedName, origin, companion.written());
    }
}
