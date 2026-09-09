package io.stategraph.processor;

import io.stategraph.annotation.GenerateGraphApi;
import io.stategraph.annotation.StateGraph;
import io.stategraph.annotation.StateGraphs;
import io.stategraph.codegen.manifest.CompileManifest;
import io.stategraph.codegen.model.SymbolDescriptor;
import io.stategraph.processor.gen.BoundInvocation;
import io.stategraph.processor.gen.MachineGenerator;
import io.stategraph.processor.gen.SetGenerator;
import io.stategraph.processor.model.MachineModel;
import io.stategraph.processor.model.SetModel;
import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/**
 * Entry point. Claims the annotations {@link ManifestRegistry} enumerates for this compilation, the fixed ones
 * and the generated ones alike, so it coexists with other processors and never matches a name it does not own.
 *
 * <p>What the processor learns is kept for the whole compilation, not for one round: a machine is resolved
 * once, its companion is handed to the {@code Filer} once, and a machine that failed is not built again by the
 * set that holds it. Each declaration is handled in the round it appears in, and the round that ends the
 * compilation publishes what the compilation verified, if nothing went wrong.</p>
 */
public final class StateGraphProcessor extends AbstractProcessor {

    private ManifestRegistry manifests = ManifestRegistry.unloaded();
    private boolean reportedManifest;
    private boolean validatedExports;
    private boolean validExports = true;
    private Annotations annotations;
    private Diagnostics diagnostics;
    private ExportValidator exports;
    private ExportIndexWriter indexes;
    private BoundInvocation invocations;
    private MachineModelBuilder machines;
    private Optional<TypedMachineModelBuilder> typed = Optional.empty();
    private SetModelBuilder sets;
    private Map<String, MachineModel> models;
    private Set<String> failed;
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
        diagnostics = new Diagnostics(env.getMessager());
        models = new LinkedHashMap<>();
        failed = new HashSet<>();
        generated = new LinkedHashSet<>();
        verified = new LinkedHashSet<>();
        Signatures signatures = new Signatures(env.getElementUtils(), env.getTypeUtils());
        annotations = new Annotations(env.getElementUtils());
        Accessors accessors = new Accessors(signatures);
        Bindings bindings = new Bindings(signatures, diagnostics);
        EffectiveMethods effective = new EffectiveMethods(signatures);
        exports = new ExportValidator(signatures, effective, diagnostics);
        indexes = new ExportIndexWriter(env);
        invocations = new BoundInvocation(env.getElementUtils(), env.getTypeUtils());
        machines = new MachineModelBuilder(annotations, signatures, accessors, bindings, diagnostics);
        sets = new SetModelBuilder(annotations, signatures, diagnostics);
        typed = manifests.manifest().map((CompileManifest manifest) -> {
            Map<String, SymbolDescriptor> symbols = SymbolResolver.symbols(manifest);
            VocabularyRegistry vocabulary = new VocabularyRegistry(manifest, env.getElementUtils());
            PropertyResolver propertyResolver = new PropertyResolver(symbols, signatures, accessors, diagnostics);
            SymbolResolver symbolResolver = new SymbolResolver(symbols, signatures, effective, bindings, diagnostics);
            return new TypedMachineModelBuilder(vocabulary, machines, propertyResolver,
                    new TypedTransitions(machines, symbolResolver, new TypedTimeouts(annotations, diagnostics),
                            annotations, diagnostics),
                    new TypedWatchReader(vocabulary, machines, propertyResolver, symbolResolver, signatures, diagnostics),
                    signatures, diagnostics);
        });
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotationTypes, RoundEnvironment round) {
        reportManifestFailure();
        if (!validateExports()) {
            return true;
        }
        for (Element element : round.getElementsAnnotatedWith(StateGraph.class)) {
            resolve((TypeElement) element, round);
        }
        if (typed.isPresent()) {
            for (Element element : round.getElementsAnnotatedWith(GenerateGraphApi.class)) {
                resolve((TypeElement) element, round);
            }
        }
        for (Element element : round.getElementsAnnotatedWith(StateGraphs.class)) {
            Optional<SetModel> set = sets.build((TypeElement) element,
                    (TypeElement machine) -> resolve(machine, round));
            if (set.isPresent()) {
                write(SetGenerator.qualifiedName(set.get()), SetGenerator.generate(set.get()), (TypeElement) element);
            }
        }
        publish(round);
        return true;
    }

    /** A manifest the registry refused is reported once: init has no round to anchor a diagnostic at. */
    private void reportManifestFailure() {
        if (reportedManifest) {
            return;
        }
        reportedManifest = true;
        manifests.failure().ifPresent(reason -> diagnostics.error("SG602", reason));
    }

    /**
     * Whether the members this compilation exports hold, checked once against javac's own declarations. A
     * graph built on an export that does not hold would only repeat, at every use, what its declaration
     * already reported.
     */
    private boolean validateExports() {
        if (!validatedExports) {
            validatedExports = true;
            validExports = manifests.manifest().map(this::validate).orElse(true);
        }
        return validExports;
    }

    /** Records every local export as verified once they all hold; a single failure verifies none of them. */
    private boolean validate(CompileManifest manifest) {
        if (!exports.validate(manifest)) {
            return false;
        }
        for (SymbolDescriptor symbol : manifest.local().model().symbols()) {
            verified.add(symbol.qualifiedName());
        }
        return true;
    }

    /**
     * A machine's model, built once for the whole compilation by the frontend its own marker names; a machine
     * from the classpath is built on demand and carries its companion in its own jar already.
     */
    private Optional<MachineModel> resolve(TypeElement type, RoundEnvironment round) {
        String name = type.getQualifiedName().toString();
        if (models.containsKey(name)) {
            return Optional.of(models.get(name));
        }
        if (failed.contains(name)) {
            return Optional.empty();
        }
        Optional<MachineModel> model = build(type);
        if (model.isPresent()) {
            models.put(name, model.get());
            verified.add(name);
            if (round.getRootElements().contains(type)) {
                write(MachineGenerator.qualifiedName(model.get()), MachineGenerator.generate(model.get(), invocations), type);
            }
        } else {
            failed.add(name);
        }
        return model;
    }

    /** The frontend that reads {@code type}: the typed one for a generated API, the legacy one otherwise. */
    private Optional<MachineModel> build(TypeElement type) {
        if (annotations.find(type, GenerateGraphApi.class).isEmpty()) {
            return machines.build(type);
        }
        if (typed.isEmpty()) {
            diagnostics.error("SG602", type.getQualifiedName() + " declares its graph with generated vocabulary,"
                    + " but this compilation names no annotation manifest to read that vocabulary with", type);
            return Optional.empty();
        }
        return typed.get().build(type);
    }

    /**
     * Publishes the provisional index of this compilation, once, in the round that ends it. An error of this
     * processor or of javac leaves nothing published: what a compilation that failed exports is not known.
     */
    private void publish(RoundEnvironment round) {
        if (!round.processingOver() || round.errorRaised() || diagnostics.errors() > 0) {
            return;
        }
        manifests.manifest().ifPresent((CompileManifest manifest) ->
                indexes.publish(manifest, Set.copyOf(verified), Set.copyOf(generated)));
    }

    /** Writes one generated source, and only once: the Filer refuses a name it has already created a file for. */
    private void write(String qualifiedName, String source, TypeElement origin) {
        if (!generated.add(qualifiedName)) {
            return;
        }
        try (Writer writer = processingEnv.getFiler().createSourceFile(qualifiedName, origin).openWriter()) {
            writer.write(source);
        } catch (IOException e) {
            diagnostics.error("SG599", "could not write " + qualifiedName + ": " + e.getMessage(), origin);
        }
    }
}
