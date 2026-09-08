package io.stategraph.processor;

import io.stategraph.annotation.StateGraph;
import io.stategraph.annotation.StateGraphs;
import io.stategraph.processor.model.MachineModel;
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
 * Entry point. Claims only {@code io.stategraph.annotation.*}, so it coexists with other processors. One round
 * suffices: nothing generated is referenced by the annotations themselves.
 */
public final class StateGraphProcessor extends AbstractProcessor {

    private Diagnostics diagnostics;
    private MachineModelBuilder machines;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("io.stategraph.annotation.*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        diagnostics = new Diagnostics(env.getMessager());
        Signatures signatures = new Signatures(env.getElementUtils(), env.getTypeUtils());
        machines = new MachineModelBuilder(new Annotations(env.getElementUtils()), signatures, new Accessors(signatures),
                new Bindings(signatures, diagnostics), diagnostics);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        Map<String, MachineModel> models = new LinkedHashMap<>();
        for (Element element : round.getElementsAnnotatedWith(StateGraph.class)) {
            Optional<MachineModel> model = machines.build((TypeElement) element);
            model.ifPresent((MachineModel built) -> models.put(built.types().facade().getQualifiedName().toString(), built));
        }
        for (Element element : round.getElementsAnnotatedWith(StateGraphs.class)) {
            // Task 10 builds the set model here.
            element.getSimpleName();
        }
        return true;
    }
}
