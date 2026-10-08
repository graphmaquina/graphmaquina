package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.processor.gen.BoundInvocation;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.StateAccess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The processor {@link TypedBindingsTest} and {@link TypedPropertiesTest} run instead of the real one,
 * {@link Resolving}: it asks the typed resolvers for the symbols a test named, over the very elements javac gives
 * it, and records what came back. It is a public static class of this public top-level class because javac
 * instantiates a processor reflectively from the name given to {@code -processor}.
 */
public final class TypedProcessors {

    private TypedProcessors() { }

    /** Which of the four property uses a request exercises; each one has its own requirements. */
    public enum Use { ID, STATE, PEERS, OWNER }

    /**
     * Resolves what the test asked for in the first round. A machine is written {@code entity/states/transitions},
     * the three qualified names the resolvers need; the facade of a request is the entity itself, which
     * {@link SymbolResolver} reads for the package the companion would be generated in, and every fixture lives in
     * package {@code fx}.
     */
    public static final class Resolving extends AbstractProcessor {

        private static final List<Ask> ASKS = new ArrayList<>();
        private static final Map<String, String> RESOLVED = new LinkedHashMap<>();

        private ManifestRegistry registry = ManifestRegistry.unloaded();
        private boolean answered;

        /** One resolution a test asks for: the symbol, what it is used as, and the machines that use it. */
        private record Ask(String symbol, String use, Optional<Bindings.Role> role, Optional<Use> property,
                String machine, Optional<String> peer) { }

        /** Forgets the requests and the answers of a previous compilation. */
        static void reset() {
            ASKS.clear();
            RESOLVED.clear();
        }

        /** Asks for the method {@code symbol} names when it is used as {@code role} by {@code machine}. */
        static void method(String symbol, Bindings.Role role, String machine) {
            ASKS.add(new Ask(symbol, role.name(), Optional.of(role), Optional.empty(), machine, Optional.empty()));
        }

        /** The same, for a role whose signature also mentions {@code peer}. */
        static void method(String symbol, Bindings.Role role, String machine, String peer) {
            ASKS.add(new Ask(symbol, role.name(), Optional.of(role), Optional.empty(), machine, Optional.of(peer)));
        }

        /** Asks for the accessors {@code symbol} names when {@code machine} uses it as {@code use}. */
        static void property(String symbol, Use use, String machine) {
            ASKS.add(new Ask(symbol, use.name(), Optional.empty(), Optional.of(use), machine, Optional.empty()));
        }

        /** The same, for a use that reads or returns the entity of {@code peer}. */
        static void property(String symbol, Use use, String machine, String peer) {
            ASKS.add(new Ask(symbol, use.name(), Optional.empty(), Optional.of(use), machine, Optional.of(peer)));
        }

        /** What the resolver answered for a method request, or empty when it reported a diagnostic instead. */
        static Optional<String> resolved(String symbol, Bindings.Role role) {
            return Optional.ofNullable(RESOLVED.get(symbol + ' ' + role.name()));
        }

        /** The call the emitter writes for a method request, on a receiver named {@code host}. */
        static Optional<String> emitted(String symbol, Bindings.Role role) {
            return Optional.ofNullable(RESOLVED.get(symbol + ' ' + role.name() + " call"));
        }

        /** What the resolver answered for a property request, or empty when it reported a diagnostic. */
        static Optional<String> resolved(String symbol, Use use) {
            return Optional.ofNullable(RESOLVED.get(symbol + ' ' + use.name()));
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
            return registry.supportedAnnotationTypes();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (answered || round.processingOver() || registry.manifest().isEmpty()) {
                return true;
            }
            answered = true;
            Elements elements = processingEnv.getElementUtils();
            Types types = processingEnv.getTypeUtils();
            Signatures signatures = new Signatures(elements, types);
            Diagnostics diagnostics = new Diagnostics(processingEnv);
            Map<String, SymbolDescriptor> symbols = SymbolResolver.symbols(registry.manifest().orElseThrow());
            SymbolResolver methods = new SymbolResolver(symbols, signatures, new EffectiveMethods(signatures),
                    new Bindings(signatures), diagnostics);
            PropertyResolver properties = new PropertyResolver(symbols, signatures, new Accessors(signatures),
                    diagnostics);
            BoundInvocation invocations = new BoundInvocation(elements, types);
            for (Ask ask : ASKS) {
                answer(ask, methods, properties, invocations, elements);
            }
            return true;
        }

        private void answer(Ask ask, SymbolResolver methods, PropertyResolver properties,
                BoundInvocation invocations, Elements elements) {
            TypeElement element = elements.getTypeElement(ask.symbol());
            if (element == null) {
                throw new IllegalStateException("the fixture does not declare " + ask.symbol());
            }
            TypeMirror symbol = element.asType();
            DiagnosticAnchor anchor = DiagnosticAnchor.of(element);
            MachineTypes machine = machine(ask.machine(), elements);
            Optional<MachineTypes> peer = ask.peer().map(name -> machine(name, elements));
            if (ask.role().isPresent()) {
                methods.resolve(symbol, ask.role().get(), machine, peer, anchor).ifPresent((Binding binding) -> {
                    record(ask, describe(binding));
                    RESOLVED.put(ask.symbol() + ' ' + ask.use() + " call",
                            invocations.emit(binding, "host", lambdaParameters(binding)));
                });
                return;
            }
            switch (ask.property().orElseThrow()) {
                case ID -> properties.id(symbol, machine, anchor)
                        .ifPresent(property -> record(ask, describe(property)));
                case STATE -> properties.state(symbol, machine, anchor)
                        .ifPresent(access -> record(ask, describe(access)));
                case PEERS -> properties.peers(symbol, machine, peer.orElseThrow(), anchor)
                        .ifPresent(property -> record(ask, describe(property)));
                case OWNER -> properties.owner(symbol, machine, peer.orElseThrow(), anchor)
                        .ifPresent(property -> record(ask, describe(property)));
            }
        }

        private static void record(Ask ask, String answer) {
            RESOLVED.put(ask.symbol() + ' ' + ask.use(), answer);
        }

        /** The machine {@code description} spells as {@code entity/states/transitions}. */
        private MachineTypes machine(String description, Elements elements) {
            String[] parts = description.split("/");
            TypeElement entity = elements.getTypeElement(parts[0]);
            TypeElement states = elements.getTypeElement(parts[1]);
            TypeElement transitions = elements.getTypeElement(parts[2]);
            return new MachineTypes(entity, (DeclaredType) entity.asType(), entity,
                    (DeclaredType) states.asType(), states, constants(states),
                    (DeclaredType) transitions.asType(), transitions, constants(transitions));
        }

        /** The constants of an enum, in the order it declares them. */
        private static List<String> constants(TypeElement type) {
            List<String> constants = new ArrayList<>();
            for (Element member : type.getEnclosedElements()) {
                if (member.getKind() == ElementKind.ENUM_CONSTANT) {
                    constants.add(member.getSimpleName().toString());
                }
            }
            return constants;
        }

        /** The names the generated lambda gives its parameters, one per argument the call passes. */
        private static List<String> lambdaParameters(Binding binding) {
            return switch (binding.arguments().size()) {
                case 3 -> List.of("entity", "peers", "view");
                case 2 -> List.of("entity", "commit");
                default -> List.of(binding.role() == SymbolKind.HOOK ? "entity" : "context");
            };
        }

        /**
         * The host that owns the call, the declaration it selected and the signature that declaration has here,
         * then {@code static} and {@code async} for a binding that is either.
         */
        private static String describe(Binding binding) {
            TypeElement declaring = (TypeElement) binding.method().getEnclosingElement();
            StringJoiner parameters = new StringJoiner(", ", "(", ")");
            for (TypeMirror parameter : binding.effectiveType().getParameterTypes()) {
                parameters.add(parameter.toString());
            }
            return binding.owner().getQualifiedName() + "#" + declaring.getQualifiedName() + '.'
                    + binding.methodName() + parameters + (binding.isStatic() ? " static" : "")
                    + (binding.async() ? " async" : "");
        }

        /** The accessor a property selected and what it reads or writes here. */
        private static String describe(Property property) {
            StringJoiner parameters = new StringJoiner(", ", "(", ")");
            for (TypeMirror parameter : property.accessor().effectiveType().getParameterTypes()) {
                parameters.add(parameter.toString());
            }
            return property.method() + parameters + ": " + property.accessor().effectiveType().getReturnType();
        }

        private static String describe(StateAccess access) {
            return describe(access.reader()) + " / " + describe(access.writer());
        }
    }
}
