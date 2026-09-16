package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.processor.gen.BoundInvocation;
import io.github.graphmaquina.processor.gen.MachineGenerator;
import io.github.graphmaquina.processor.gen.SetGenerator;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.SetModel;
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
import javax.lang.model.element.NestingKind;
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
    private Diagnostics diagnostics;
    private ExportValidator exports;
    private ExportIndexWriter indexes;
    private BoundInvocation invocations;
    private LegacyDeclarations legacy;
    private Optional<TypedMachineModelBuilder> typed = Optional.empty();
    private SetModelBuilder sets;
    private Map<String, MachineModel> models;
    private Set<String> failed;
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
        diagnostics = new Diagnostics(env.getMessager());
        models = new LinkedHashMap<>();
        failed = new HashSet<>();
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
        sets = new SetModelBuilder(annotations, signatures, diagnostics);
        typed = manifests.manifest().map((CompileManifest manifest) -> {
            Map<String, SymbolDescriptor> symbols = SymbolResolver.symbols(manifest);
            VocabularyRegistry vocabulary = new VocabularyRegistry(manifest, env.getElementUtils());
            PropertyResolver propertyResolver = new PropertyResolver(symbols, signatures, accessors, diagnostics);
            SymbolResolver symbolResolver = new SymbolResolver(symbols, signatures, effective, bindings, diagnostics);
            return new TypedMachineModelBuilder(vocabulary, structure, propertyResolver,
                    new TypedTransitions(structure, symbolResolver, new TypedTimeouts(annotations, diagnostics),
                            annotations, diagnostics),
                    new TypedWatchReader(vocabulary, structure, propertyResolver, symbolResolver, signatures, diagnostics),
                    signatures, diagnostics);
        });
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotationTypes, RoundEnvironment round) {
        reportManifestFailure();
        if (!validateExports() || !validateRuntimeNames(round)) {
            return true;
        }
        failed.addAll(legacy.report(round));
        for (Element element : round.getElementsAnnotatedWith(GenerateGraphApi.class)) {
            resolve((TypeElement) element, round);
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

    /** Reserve normalized runtime names before emitting any source in this round. */
    private boolean validateRuntimeNames(RoundEnvironment round) {
        Set<Element> declarations = new LinkedHashSet<>(round.getElementsAnnotatedWith(GenerateGraphApi.class));
        declarations.addAll(round.getElementsAnnotatedWith(StateGraphs.class));
        boolean valid = true;
        for (Element declaration : declarations) {
            TypeElement type = (TypeElement) declaration;
            if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
                continue; // The model builder reports unsupported nested declarations as SG502.
            }
            String name;
            try {
                name = SymbolNames.qualified(processingEnv.getElementUtils().getPackageOf(type)
                        .getQualifiedName().toString(), SymbolNames.runtimeName(type.getSimpleName().toString()));
            } catch (IllegalArgumentException failure) {
                diagnostics.error("SG603", failure.getMessage(), type);
                valid = false;
                continue;
            }
            TypeElement previous = runtimeOrigins.putIfAbsent(name, type);
            if (previous != null) {
                if (!previous.getQualifiedName().contentEquals(type.getQualifiedName())) {
                    diagnostics.error("SG603", "the generated runtime " + name + " is claimed by both "
                            + previous.getQualifiedName() + " and " + type.getQualifiedName(), type);
                    valid = false;
                }
                continue;
            }
            TypeElement existing = processingEnv.getElementUtils().getTypeElement(name);
            if (existing != null) {
                diagnostics.error("SG603", "the generated runtime " + name + " for " + type.getQualifiedName()
                        + " conflicts with existing type " + existing.getQualifiedName(), type);
                valid = false;
            }
        }
        return valid;
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
     * A machine's model, built once for the whole compilation; a machine from the classpath is built on demand
     * and carries its companion in its own jar already.
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

    /**
     * The model of {@code type}, read with the vocabulary generated for it. A compilation whose manifest could
     * not be trusted has no vocabulary to read anything with, and has already said so once as {@code SG602}.
     */
    private Optional<MachineModel> build(TypeElement type) {
        return typed.flatMap((TypedMachineModelBuilder builder) -> builder.build(type));
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
