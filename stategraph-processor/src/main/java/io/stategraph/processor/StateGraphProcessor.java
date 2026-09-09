package io.stategraph.processor;

import io.stategraph.annotation.StateGraph;
import io.stategraph.annotation.StateGraphs;
import io.stategraph.processor.gen.MachineGenerator;
import io.stategraph.processor.gen.SetGenerator;
import io.stategraph.processor.model.MachineModel;
import io.stategraph.processor.model.SetModel;
import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * One round suffices: nothing generated is referenced by the annotations themselves.
 */
public final class StateGraphProcessor extends AbstractProcessor {

    private ManifestRegistry manifests = ManifestRegistry.unloaded();
    private boolean reportedManifest;
    private Diagnostics diagnostics;
    private MachineModelBuilder machines;
    private SetModelBuilder sets;

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
        Signatures signatures = new Signatures(env.getElementUtils(), env.getTypeUtils());
        machines = new MachineModelBuilder(new Annotations(env.getElementUtils()), signatures, new Accessors(signatures),
                new Bindings(signatures, diagnostics), diagnostics);
        sets = new SetModelBuilder(new Annotations(env.getElementUtils()), signatures, diagnostics);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        reportManifestFailure();
        Map<String, MachineModel> models = new LinkedHashMap<>();
        Set<String> failed = new HashSet<>();
        for (Element element : round.getElementsAnnotatedWith(StateGraph.class)) {
            resolve((TypeElement) element, round, models, failed);
        }
        for (Element element : round.getElementsAnnotatedWith(StateGraphs.class)) {
            Optional<SetModel> set = sets.build((TypeElement) element, (TypeElement machine) -> resolve(machine, round, models, failed));
            if (set.isPresent()) {
                write(SetGenerator.qualifiedName(set.get()), SetGenerator.generate(set.get()), (TypeElement) element);
            }
        }
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

    /** A machine's model, built once per round; a machine from the classpath is built on demand. */
    private Optional<MachineModel> resolve(TypeElement type, RoundEnvironment round, Map<String, MachineModel> models, Set<String> failed) {
        String name = type.getQualifiedName().toString();
        if (models.containsKey(name)) {
            return Optional.of(models.get(name));
        }
        if (failed.contains(name)) {
            return Optional.empty();
        }
        Optional<MachineModel> model = machines.build(type);
        if (model.isPresent()) {
            models.put(name, model.get());
            // A machine from the classpath already carries its companion in its own jar.
            if (round.getRootElements().contains(type)) {
                write(MachineGenerator.qualifiedName(model.get()), MachineGenerator.generate(model.get()), type);
            }
        } else {
            failed.add(name);
        }
        return model;
    }

    private void write(String qualifiedName, String source, TypeElement origin) {
        try (Writer writer = processingEnv.getFiler().createSourceFile(qualifiedName, origin).openWriter()) {
            writer.write(source);
        } catch (IOException e) {
            diagnostics.error("SG599", "could not write " + qualifiedName + ": " + e.getMessage(), origin);
        }
    }
}
