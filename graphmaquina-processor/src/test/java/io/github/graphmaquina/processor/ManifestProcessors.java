package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.VocabularyKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * The processors {@link ManifestDiscoveryTest} runs instead of, or beside, the real one. They are public and
 * top-level because javac instantiates a processor reflectively from the name given to {@code -processor}.
 */
public final class ManifestProcessors {

    private ManifestProcessors() { }

    /**
     * Stands in for the real processor and records the exact annotation set the registry asks javac to claim,
     * plus what the vocabulary registry reads back from the machine of the typed fixture.
     */
    public static final class Recording extends AbstractProcessor {

        private static final Set<String> CLAIMED = new LinkedHashSet<>();
        private static final List<String> POLICIES = new ArrayList<>();
        private static final List<String> ACTIONS = new ArrayList<>();

        private ManifestRegistry registry = ManifestRegistry.unloaded();

        /** Forgets what a previous compilation recorded. */
        static void reset() {
            CLAIMED.clear();
            POLICIES.clear();
            ACTIONS.clear();
        }

        /** The annotation types javac was told to match this processor against. */
        static Set<String> claimed() {
            return Set.copyOf(CLAIMED);
        }

        /** The state each {@code StatePolicy} of {@code fx.Docs} declares, in the order the machine spells them. */
        static List<String> policies() {
            return List.copyOf(POLICIES);
        }

        /** The action each {@code Transition} of {@code fx.Docs} names, in the order the machine spells them. */
        static List<String> actions() {
            return List.copyOf(ACTIONS);
        }

        @Override
        public synchronized void init(ProcessingEnvironment environment) {
            super.init(environment);
            registry = ManifestRegistry.load(environment);
        }

        @Override
        public Set<String> getSupportedOptions() {
            return Set.of(ManifestRegistry.OPTION);
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            Set<String> supported = registry.supportedAnnotationTypes();
            CLAIMED.addAll(supported);
            return supported;
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (round.processingOver() || registry.manifest().isEmpty()) {
                return true;
            }
            Annotations values = new Annotations(processingEnv.getElementUtils());
            VocabularyRegistry vocabulary =
                    new VocabularyRegistry(registry.manifest().orElseThrow(), processingEnv.getElementUtils());
            for (Element root : round.getRootElements()) {
                if (!(root instanceof TypeElement type) || !type.getQualifiedName().contentEquals("fx.Docs")) {
                    continue;
                }
                for (AnnotationMirror policy : vocabulary.findAll(type, VocabularyKind.STATE_POLICY)) {
                    POLICIES.add(TypedValues.enumConstant(values.value(policy, "state")).getSimpleName().toString());
                }
                for (Element member : type.getEnclosedElements()) {
                    if (member instanceof ExecutableElement method) {
                        for (AnnotationMirror transition : vocabulary.findAll(method, VocabularyKind.TRANSITION)) {
                            for (javax.lang.model.element.AnnotationValue action : values.array(transition, "action")) {
                                ACTIONS.add(TypedValues.classLiteral(action).toString());
                            }
                        }
                    }
                }
            }
            return true;
        }
    }

    /** Claims an annotation StateGraph does not own, so the test can tell the two claims apart. */
    public static final class Other extends AbstractProcessor {

        static final String MARKER = "fx.OtherMarker";

        private static final Set<String> SEEN = new LinkedHashSet<>();

        /** Forgets what a previous compilation recorded. */
        static void reset() {
            SEEN.clear();
        }

        /** The annotation types this processor was actually handed. */
        static Set<String> seen() {
            return Set.copyOf(SEEN);
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of(MARKER);
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            for (TypeElement annotation : annotations) {
                SEEN.add(annotation.getQualifiedName().toString());
            }
            return true;
        }
    }
}
