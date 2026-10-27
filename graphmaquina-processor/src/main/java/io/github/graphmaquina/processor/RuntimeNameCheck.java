package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.output.CaseFolding;
import java.io.IOException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Reserves the companion names of each round's top-level declarations for the whole compilation, and refuses, at
 * the declaration, the companion that could not be generated under its name: a name that does not normalize, that
 * another declaration or an existing type takes, that differs only in case from another companion's on a volume
 * that does not tell the two apart, or under which a companion would take a name a companion of an earlier round
 * writes. An existing type that is this processor's own output of a previous compilation, which the build did not
 * retire, is reported as such.
 */
final class RuntimeNameCheck {

    private final ProcessingEnvironment environment;
    private final Optional<CompileManifest> manifest;
    private final Diagnostics diagnostics;
    private final Map<String, TypeElement> runtimeOrigins = new LinkedHashMap<>();
    private final Map<String, String> runtimeCases = new LinkedHashMap<>();
    private final Set<String> refused = new HashSet<>();
    private Set<String> previousInventory;

    /** {@code manifest} is the manifest of the compilation, empty when none could be trusted. */
    RuntimeNameCheck(ProcessingEnvironment environment, Optional<CompileManifest> manifest, Diagnostics diagnostics) {
        this.environment = environment;
        this.manifest = manifest;
        this.diagnostics = diagnostics;
    }

    /**
     * The qualified name of every companion reserved so far, mapped to the facade or the set it is for: what
     * {@link CompanionScope} counts among the types of a companion's package.
     */
    Map<String, TypeElement> companions() {
        return runtimeOrigins;
    }

    /** Whether the companion of the declaration of the qualified name {@code declaration} was refused. */
    boolean refused(String declaration) {
        return refused.contains(declaration);
    }

    /**
     * Reserves the normalized runtime names of this round's top-level declarations before any source of the round is
     * written. A declaration is refused, at the declaration itself, when its simple name does not normalize to a
     * runtime name, when another declaration or an existing type takes that name already, when the companion name of
     * another declaration differs from it only in case and the volume of the companions' sources or of the class
     * output does not tell names apart, which would hold the two companions, or their class files, as one file, or
     * when its companion would take a name that a companion written in an earlier round writes, as {@code scope}
     * decides. A refused declaration is still checked like every other declaration of the round, so all their
     * problems are reported together, but its companion is never written, nor is the companion of a set that holds
     * it, which would name that companion.
     *
     * <p>The existing type is looked up by the companion's qualified name as javac's {@code Filer} looks up a type
     * it is asked to create, so a member type whose canonical name that is takes the name too, as one nested in a
     * class of the unnamed package named like the first segment of the package does. The {@code Filer} refuses to
     * create a type over one declared in a source the compilation was given, over one a processor created in it
     * already, or over a class it was given by name; it warns of any other existing type under
     * {@code -Xlint:processing}.</p>
     */
    void validate(RoundEnvironment round, CompanionScope scope) {
        Set<Element> declarations = new LinkedHashSet<>(round.getElementsAnnotatedWith(GenerateGraphApi.class));
        declarations.addAll(round.getElementsAnnotatedWith(StateGraphs.class));
        for (Element declaration : declarations) {
            TypeElement type = (TypeElement) declaration;
            if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
                // The model builder reports unsupported nested declarations as SG502.
                continue;
            }
            Optional<String> name = runtimeName(type);
            if (name.isPresent() && reserve(type, name.get())) {
                checkTaken(type, name.get(), round, scope);
            }
        }
    }

    /**
     * The qualified name of the companion of {@code type}, or empty after {@code SG603}, which refuses it, when its
     * simple name does not normalize to a runtime name.
     */
    private Optional<String> runtimeName(TypeElement type) {
        try {
            return Optional.of(SymbolNames.qualified(environment.getElementUtils().getPackageOf(type)
                    .getQualifiedName().toString(), SymbolNames.runtimeName(type.getSimpleName().toString())));
        } catch (IllegalArgumentException failure) {
            diagnostics.error(DiagnosticCode.SG603, failure.getMessage(), type);
            refused.add(type.getQualifiedName().toString());
            return Optional.empty();
        }
    }

    /**
     * Reserves {@code name} for {@code type}, and answers whether it did. It does not when {@code name} was reserved
     * already, which refuses {@code type} as {@code SG603} unless {@code type} reserved it, nor when a name reserved
     * already differs from it only in case on a volume that does not tell them apart, which refuses {@code type} as
     * {@code SG603}.
     */
    private boolean reserve(TypeElement type, String name) {
        String declared = type.getQualifiedName().toString();
        TypeElement previous = runtimeOrigins.putIfAbsent(name, type);
        if (previous != null) {
            if (!previous.getQualifiedName().contentEquals(type.getQualifiedName())) {
                diagnostics.error(DiagnosticCode.SG603, "the generated runtime " + name + " is claimed by both "
                        + previous.getQualifiedName() + " and " + type.getQualifiedName(), type);
                refused.add(declared);
            }
            return false;
        }
        String twin = runtimeCases.putIfAbsent(CaseFolding.key(name), name);
        Optional<String> volume = twin == null ? Optional.empty() : caseBlindVolume();
        if (volume.isPresent()) {
            diagnostics.error(DiagnosticCode.SG603, "the generated runtimes " + twin + " of "
                    + runtimeOrigins.get(twin).getQualifiedName() + " and " + name + " of "
                    + type.getQualifiedName() + " differ only in case, and the volume of " + volume.get()
                    + " does not tell their files apart", type);
            refused.add(declared);
            return false;
        }
        return true;
    }

    /**
     * Refuses {@code type} when the name {@code name} it reserved is taken: by an existing type, reported as
     * {@code SG602} when that type is a stale output of this processor and as {@code SG603} otherwise, or, when no
     * type exists, by a name a companion written in an earlier round writes, which {@code scope} reports.
     */
    private void checkTaken(TypeElement type, String name, RoundEnvironment round, CompanionScope scope) {
        String declared = type.getQualifiedName().toString();
        TypeElement existing = environment.getElementUtils().getTypeElement(name);
        if (existing == null) {
            if (!scope.admitsLater(type, name)) {
                refused.add(declared);
            }
            return;
        }
        refused.add(declared);
        if (isStaleOutput(existing, round)) {
            diagnostics.error(DiagnosticCode.SG602, "the generated runtime " + name + " of " + type.getQualifiedName()
                    + " is still compiled from a previous compilation that nothing retired: run the generation"
                    + " lifecycle, whose prepare-compile step retires it, or clean the build, then compile again",
                    type);
        } else {
            diagnostics.error(DiagnosticCode.SG603, "the generated runtime " + name + " for " + type.getQualifiedName()
                    + " conflicts with existing type " + existing.getQualifiedName(), type);
        }
    }

    /**
     * The first output of the companions whose volume does not tell names apart by case, named as a diagnostic names
     * it: the output of their sources, then the class output, each the directory the {@code Filer} resolves a file of
     * the unnamed package in. Empty when each tells names apart, and for an output that is no directory of the
     * default file system or that cannot be examined, as one a file manager keeps in memory.
     */
    private Optional<String> caseBlindVolume() {
        Map<String, StandardLocation> outputs = new LinkedHashMap<>();
        outputs.put("the generated sources ", StandardLocation.SOURCE_OUTPUT);
        outputs.put("the class output ", StandardLocation.CLASS_OUTPUT);
        for (Map.Entry<String, StandardLocation> output : outputs.entrySet()) {
            try {
                FileObject probe = environment.getFiler().getResource(output.getValue(), "", "graphmaquina-case");
                Path directory = Path.of(probe.toUri()).getParent();
                if (directory != null && CaseFolding.folds(directory)) {
                    return Optional.of(output.getKey() + directory);
                }
            } catch (IOException | IllegalArgumentException | FileSystemNotFoundException | SecurityException unknown) {
                // An output that is no directory, or that cannot be examined, is not known to hold two names as one.
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code existing} is what a previous compilation of this processor generated, still on the classpath
     * because nothing retired it: a type this round does not compile from source, which the inventory the
     * previous run wrote lists. A source of this round that takes the generated name is a collision instead.
     */
    private boolean isStaleOutput(TypeElement existing, RoundEnvironment round) {
        Element topLevel = existing;
        while (topLevel.getEnclosingElement() instanceof TypeElement enclosing) {
            topLevel = enclosing;
        }
        return !round.getRootElements().contains(topLevel)
                && previousInventory().contains(existing.getQualifiedName().toString());
    }

    /**
     * The qualified names the previous run of this processor generated, read once from the inventory the manifest
     * names; none when there is no manifest, no inventory yet, or one that cannot be read, so that nothing which
     * is not certainly this processor's own output is ever taken for it.
     */
    private Set<String> previousInventory() {
        if (previousInventory == null) {
            previousInventory = manifest.map((CompileManifest compiled) -> {
                try {
                    Path inventory = Path.of(compiled.processorInventory());
                    return Files.isRegularFile(inventory)
                            ? Set.copyOf(ManifestCodec.readProcessorInventory(inventory))
                            : Set.<String>of();
                } catch (IOException | CodegenException | InvalidPathException unreadable) {
                    return Set.<String>of();
                }
            }).orElse(Set.of());
        }
        return previousInventory;
    }
}
