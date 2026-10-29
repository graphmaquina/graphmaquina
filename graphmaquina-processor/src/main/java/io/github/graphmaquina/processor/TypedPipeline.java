package io.github.graphmaquina.processor;

import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.processor.gen.BoundInvocation;
import java.util.Map;
import javax.lang.model.util.Elements;

/**
 * Assembles the model builder of a compilation whose manifest could be trusted: the vocabulary and the symbols the
 * manifest lists, the resolvers of symbols and properties, and the readers of edges, timeouts, pairs, watches and
 * sub-machines that {@link TypedMachineModelBuilder} hands each part of a machine's declarations to.
 */
final class TypedPipeline {

    private TypedPipeline() { }

    /**
     * The model builder of a compilation over {@code manifest}, built on the services the processor shares across
     * its checks: the signatures, the effective methods, the bound invocations, the index of missing exports and the
     * diagnostics.
     */
    static TypedMachineModelBuilder modelBuilder(CompileManifest manifest, Elements elements, Annotations annotations,
            Signatures signatures, EffectiveMethods effective, BoundInvocation invocations,
            MissingExportIndex missingIndex, Diagnostics diagnostics) {
        StructuralChecks structure = new StructuralChecks(signatures, diagnostics);
        Map<String, SymbolDescriptor> symbols = SymbolResolver.symbols(manifest);
        VocabularyRegistry vocabulary = new VocabularyRegistry(manifest, elements);
        PropertyResolver propertyResolver = new PropertyResolver(symbols, missingIndex, signatures,
                new Accessors(signatures), diagnostics);
        SymbolResolver symbolResolver = new SymbolResolver(symbols, missingIndex, signatures, effective,
                new Bindings(signatures), invocations, diagnostics);
        TypedPairs pairs = new TypedPairs(structure, signatures, diagnostics);
        AutomaticChecks automaticChecks = new AutomaticChecks(diagnostics);
        return new TypedMachineModelBuilder(vocabulary, structure, propertyResolver,
                new TypedTransitions(structure, automaticChecks, symbolResolver,
                        new TypedTimeouts(annotations, diagnostics), annotations, diagnostics),
                pairs,
                new TypedWatchReader(vocabulary, pairs, propertyResolver, symbolResolver, signatures, diagnostics),
                new TypedSubmachineReader(vocabulary, pairs, propertyResolver, symbolResolver, signatures,
                        diagnostics),
                signatures, automaticChecks, diagnostics);
    }
}
