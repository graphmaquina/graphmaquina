package io.github.graphmaquina.codegen.emit;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SourceLocation;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.NonNull;

/**
 * Turns one resolved model into the compilation units of a generation: the vocabulary of every machine, the pair
 * vocabulary of every machine over each peer it declares, the references container of every host and the carrier
 * of the compilation itself. A peer is a machine of the model or one of the dependency peers given beside it: the
 * descriptors that the export indexes of the dependencies give the peers this compilation does not generate. The
 * vocabulary of such a peer is its producer's and is not emitted here.
 *
 * <p>The units are produced in the order the model is given: the bootstrap sorts the machines and the symbols, and
 * a machine's peers keep the order its declaration gives them, so two runs over the same sources emit the same
 * files with the same bytes. Two declarations that would claim one generated name are refused here, before anything
 * is written, rather than silently overwriting one another. So is a name a generated unit must write but cannot,
 * because a type this generator creates takes over its first segment inside that unit: a type nested in the unit,
 * or any top-level type generated in the unit's package, the unit included.</p>
 */
public final class ApiEmitter {

    private ApiEmitter() {
    }

    /**
     * The compilation units of {@code model} without dependency peers, as
     * {@link #emit(GenerationModel, List, String)} emits them: every declared peer must be a machine of the model.
     */
    public static List<GeneratedSource> emit(@NonNull GenerationModel model, @NonNull String compilationId) {
        return emit(model, List.of(), compilationId);
    }

    /**
     * The compilation units of {@code model}, whose machines may declare peers of {@code dependencyPeers}, ending with
     * the carrier of {@code compilationId}, which is emitted even when the model declares no machine and no symbol.
     *
     * @throws CodegenException {@code SG608} if a declared peer is neither a machine of the model nor one of
     *     {@code dependencyPeers}, as {@link #unknownPeer} words it; {@code SG603} if two declarations claim one
     *     generated type, a machine's name does not normalize to a valid type name or a generated unit cannot name a
     *     type it must name
     */
    public static List<GeneratedSource> emit(@NonNull GenerationModel model,
            @NonNull List<GraphDescriptor> dependencyPeers, @NonNull String compilationId) {
        Map<String, Set<String>> generated = generatedTypes(model, dependencyPeers);
        Map<String, Claim> claims = new LinkedHashMap<>();
        List<GeneratedSource> sources = new ArrayList<>();
        for (GraphDescriptor graph : model.graphs()) {
            sources.add(claim(claims, MachineVocabularyEmitter.emit(graph, generated),
                new Claim("the machine " + graph.facade(), Optional.of(graph.origin()))));
            for (GraphDescriptor peer : peers(model, dependencyPeers, graph)) {
                sources.add(claim(claims, WatchVocabularyEmitter.emit(graph, peer, generated),
                    new Claim("the peer " + peer.facade() + " declared by " + graph.facade(),
                        Optional.of(graph.origin()))));
            }
        }
        for (GeneratedSource container : SymbolEmitter.emit(model.symbols(), generated)) {
            sources.add(claim(claims, container,
                new Claim("the exported members of " + container.qualifiedName(), Optional.empty())));
        }
        sources.add(CompilationMarkerEmitter.emit(compilationId));
        return List.copyOf(sources);
    }

    /**
     * The annotations {@code model} generates without dependency peers, as
     * {@link #annotationTypes(GenerationModel, List)} lists them.
     */
    public static List<String> annotationTypes(@NonNull GenerationModel model) {
        return annotationTypes(model, List.of());
    }

    /**
     * The canonical names of every annotation {@code model} generates, its machines' peers resolved among its own
     * machines and {@code dependencyPeers}, nested types and repeatable containers alike, in the order the files
     * declare them. Symbol types and the compilation carrier are not annotations and are not listed.
     *
     * @throws CodegenException {@code SG608} if a declared peer is neither a machine of the model nor one of
     *     {@code dependencyPeers}
     */
    public static List<String> annotationTypes(@NonNull GenerationModel model,
            @NonNull List<GraphDescriptor> dependencyPeers) {
        List<String> types = new ArrayList<>();
        for (GraphDescriptor graph : model.graphs()) {
            types.addAll(MachineVocabularyEmitter.annotationTypes(graph));
            for (GraphDescriptor peer : peers(model, dependencyPeers, graph)) {
                types.addAll(WatchVocabularyEmitter.annotationTypes(graph, peer));
            }
        }
        return List.copyOf(types);
    }

    /**
     * The simple names of the top-level types {@code model} generates, by the package each is generated in: the
     * vocabulary of every machine, the pair vocabulary of every machine over each peer it declares and the
     * references container of every host. Each of them is in scope inside every unit generated in its package.
     */
    private static Map<String, Set<String>> generatedTypes(GenerationModel model,
            List<GraphDescriptor> dependencyPeers) {
        Map<String, Set<String>> generated = new LinkedHashMap<>();
        for (GraphDescriptor graph : model.graphs()) {
            addGenerated(generated, MachineVocabularyEmitter.vocabularyName(graph));
            for (GraphDescriptor peer : peers(model, dependencyPeers, graph)) {
                addGenerated(generated, WatchVocabularyEmitter.vocabularyName(graph, peer));
            }
        }
        for (SymbolDescriptor symbol : model.symbols()) {
            addGenerated(generated, symbol.qualifiedName().substring(0, symbol.qualifiedName().lastIndexOf('.')));
        }
        return generated;
    }

    /**
     * Records the top-level type {@code qualifiedName} under its package: what precedes its last dot, or the unnamed
     * package when the name has no dot.
     */
    private static void addGenerated(Map<String, Set<String>> generated, String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        generated.computeIfAbsent(lastDot < 0 ? "" : qualifiedName.substring(0, lastDot),
                (String packageName) -> new LinkedHashSet<>()).add(qualifiedName.substring(lastDot + 1));
    }

    /**
     * The refusal of the peer {@code peer} that {@code owner} declares when no descriptor of it is known: it is
     * neither a {@code @GenerateGraphApi} machine of this compilation nor a machine the export index of a dependency
     * describes. The vocabulary of a pair needs the peer's own state enum, so such a peer cannot be typed at all.
     *
     * @return {@code SG608} anchored at the owner, naming the peer
     */
    public static CodegenException unknownPeer(@NonNull String peer, @NonNull GraphDescriptor owner) {
        return new CodegenException(DiagnosticCode.SG608.name(), "the peer machine " + peer + " declared by "
            + owner.facade() + " is not a @GenerateGraphApi machine of this compilation or of a dependency export"
            + " index", owner.origin());
    }

    /**
     * The machines {@code graph} declares as peers, each one found by its facade among the machines of
     * {@code model} first and among {@code dependencyPeers} then.
     *
     * @throws CodegenException {@link #unknownPeer} for a peer found in neither
     */
    private static List<GraphDescriptor> peers(GenerationModel model, List<GraphDescriptor> dependencyPeers,
            GraphDescriptor graph) {
        List<GraphDescriptor> peers = new ArrayList<>();
        for (String peer : graph.peers()) {
            peers.add(Stream.concat(model.graphs().stream(), dependencyPeers.stream())
                    .filter((GraphDescriptor candidate) -> candidate.facade().equals(peer))
                    .findFirst().orElseThrow(() -> unknownPeer(peer, graph)));
        }
        return peers;
    }

    /**
     * Returns {@code source} after recording that {@code claim} generates it, refusing a generated name two
     * declarations would claim. The failure is anchored at the first declaration that has an origin.
     *
     * @throws CodegenException {@code SG603} naming both declarations
     */
    private static GeneratedSource claim(Map<String, Claim> claims, GeneratedSource source, Claim claim) {
        Claim previous = claims.putIfAbsent(source.qualifiedName(), claim);
        if (previous == null) {
            return source;
        }
        String message = "the generated type " + source.qualifiedName() + " is claimed by " + previous.declaration()
            + " and by " + claim.declaration();
        Optional<SourceLocation> origin = previous.origin().or(claim::origin);
        throw origin.map(location -> new CodegenException(DiagnosticCode.SG603.name(), message, location))
                .orElseGet(() -> new CodegenException(DiagnosticCode.SG603.name(), message));
    }

    /**
     * What each simple name taken by a type of this generator means inside the unit {@code simpleName} generated in
     * {@code packageName}: the types {@code nested} in the unit first, since they shadow everything else, then every
     * top-level type generated in that package, the unit itself included.
     *
     * @param generated the simple names of the top-level types generated in each package
     */
    static Map<String, String> meanings(String simpleName, String packageName, Collection<String> nested,
            Map<String, Set<String>> generated) {
        Map<String, String> meanings = new LinkedHashMap<>();
        for (String type : nested) {
            meanings.put(type, "its own nested type " + simpleName + '.' + type);
        }
        for (String type : generated.getOrDefault(packageName, Set.of())) {
            meanings.putIfAbsent(type, "the type " + SymbolNames.qualified(packageName, type) + " generated in "
                + (packageName.isEmpty() ? "the unnamed package" : "package " + packageName));
        }
        return meanings;
    }

    /**
     * Refuses {@code name}, which the generated unit {@code unit} must write, when {@code meanings} gives its first
     * segment a meaning inside the unit. A nested type there shadows a type of that name (JLS 6.4.1) and obscures a
     * package of that name, and a top-level type of the unit's package obscures a package of that name (JLS 6.4.2);
     * no qualified spelling in the body of the unit, where every name is written, reaches past either. Only a type
     * of the unnamed package, which nothing can qualify, or a package whose first segment is such a name, can be
     * taken over this way.
     *
     * @param unit the generated unit as a diagnostic names it, for example {@code the vocabulary DocsGraph}
     * @param meanings what each simple name taken by a type of this generator means inside the unit, as
     *     {@link #meanings} gives it
     * @param described what {@code name} is, as a diagnostic names it
     * @param name the name the unit writes, qualified or, for a type of the unnamed package, simple
     * @param origin the declaration the refusal is anchored at
     * @throws CodegenException {@code SG603} anchored at {@code origin}
     */
    static void requireNameable(String unit, Map<String, String> meanings, String described, String name,
            SourceLocation origin) {
        String first = firstSegment(name);
        String meaning = meanings.get(first);
        if (meaning != null) {
            throw new CodegenException(DiagnosticCode.SG603.name(),
                unit + " cannot name " + described + ": inside it, " + first
                + " means " + meaning, origin);
        }
    }

    /** The first segment of {@code name}, the whole of it when it has no dot. */
    static String firstSegment(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** One declaration that generates a type: how to name it in a diagnostic and where it was read. */
    private record Claim(String declaration, Optional<SourceLocation> origin) {
    }
}
