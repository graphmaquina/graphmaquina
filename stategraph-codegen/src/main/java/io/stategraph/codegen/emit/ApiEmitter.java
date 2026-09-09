package io.stategraph.codegen.emit;

import io.stategraph.codegen.CodegenException;
import io.stategraph.codegen.model.GeneratedSource;
import io.stategraph.codegen.model.GenerationModel;
import io.stategraph.codegen.model.GraphDescriptor;
import io.stategraph.codegen.model.SourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns one resolved model into the compilation units of a generation: the vocabulary of every machine, the watch
 * vocabulary of every ordered pair of local machines, the references container of every host and the carrier of
 * the compilation itself.
 *
 * <p>The units are produced in the order the model is given, which the bootstrap already sorted, so two runs over
 * the same sources emit the same files with the same bytes. Two declarations that would claim one generated name
 * are refused here, before anything is written, rather than silently overwriting one another.</p>
 */
public final class ApiEmitter {

    private static final String COLLISION = "SG603";
    private static final String UNUSABLE_PEER = "SG608";

    private ApiEmitter() {
    }

    /**
     * The compilation units of {@code model}, ending with the carrier of {@code compilationId}, which is emitted
     * even when the model declares no machine and no symbol.
     *
     * @throws CodegenException {@code SG608} if a declared peer is not a machine of this compilation,
     *     {@code SG603} if two declarations claim one generated type
     */
    public static List<GeneratedSource> emit(GenerationModel model, String compilationId) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(compilationId, "compilationId");
        Map<String, Claim> claims = new LinkedHashMap<>();
        List<GeneratedSource> sources = new ArrayList<>();
        for (GraphDescriptor graph : model.graphs()) {
            sources.add(claim(claims, MachineVocabularyEmitter.emit(graph),
                new Claim("the machine " + graph.facade(), Optional.of(graph.origin()))));
            for (GraphDescriptor peer : peers(model, graph)) {
                sources.add(claim(claims, WatchVocabularyEmitter.emit(graph, peer),
                    new Claim("the peer " + peer.facade() + " declared by " + graph.facade(),
                        Optional.of(graph.origin()))));
            }
        }
        for (GeneratedSource container : SymbolEmitter.emit(model.symbols())) {
            sources.add(claim(claims, container,
                new Claim("the exported members of " + container.qualifiedName(), Optional.empty())));
        }
        sources.add(CompilationMarkerEmitter.emit(compilationId));
        return List.copyOf(sources);
    }

    /**
     * The canonical names of every annotation {@code model} generates, nested types and repeatable containers
     * alike, in the order the files declare them. Symbol types and the compilation carrier are not annotations
     * and are not listed.
     */
    public static List<String> annotationTypes(GenerationModel model) {
        Objects.requireNonNull(model, "model");
        List<String> types = new ArrayList<>();
        for (GraphDescriptor graph : model.graphs()) {
            types.addAll(MachineVocabularyEmitter.annotationTypes(graph));
            for (GraphDescriptor peer : peers(model, graph)) {
                types.addAll(WatchVocabularyEmitter.annotationTypes(graph, peer));
            }
        }
        return List.copyOf(types);
    }

    /**
     * The machines {@code graph} declares as peers, resolved against this compilation. Watch vocabulary needs the
     * peer's own state enum, so a peer that is not generated here cannot be typed at all.
     *
     * @throws CodegenException {@code SG608} anchored at the owner, naming the peer it cannot resolve
     */
    private static List<GraphDescriptor> peers(GenerationModel model, GraphDescriptor graph) {
        List<GraphDescriptor> peers = new ArrayList<>();
        for (String peer : graph.peers()) {
            peers.add(model.graphs().stream().filter(candidate -> candidate.facade().equals(peer))
                    .findFirst().orElseThrow(() -> new CodegenException(UNUSABLE_PEER, "the peer machine " + peer
                        + " declared by " + graph.facade()
                        + " is not a @GenerateGraphApi machine of this compilation", graph.origin())));
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
        throw origin.map(location -> new CodegenException(COLLISION, message, location))
                .orElseGet(() -> new CodegenException(COLLISION, message));
    }

    /** One declaration that generates a type: how to name it in a diagnostic and where it was read. */
    private record Claim(String declaration, Optional<SourceLocation> origin) {
    }
}
