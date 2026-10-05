package io.github.graphmaquina.codegen.output;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.source.SourceDeclarations;
import io.github.graphmaquina.codegen.source.SourceUnit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Finds the original sources that declare a generated type themselves.
 *
 * <p>javac accepts a top-level type in a source file of any name, so a generated type can collide with an original
 * declaration that no file path reveals. The original sources are walked with symbolic links followed, as the
 * build that compiles them reads them. A declaration spells the name it declares, so only the files whose text
 * holds one of the generated simple names, or that javac could read differently from their text, are parsed
 * again. javac parses each of them whole, bodies included, but only its package and its top-level declarations
 * are looked at.</p>
 */
final class OriginalTypes {

    private OriginalTypes() {
    }

    /**
     * The original source declaring each of {@code qualifiedNames} as a top-level type, as the path diagnostics
     * name it; a name no original source declares is absent.
     *
     * @throws IOException if an original source cannot be read
     * @throws UncheckedIOException naming the link, if a symbolic link under a source directory leads back to a
     *     directory that contains it
     */
    static Map<String, String> declaring(GenerationRequest request, Set<String> qualifiedNames) throws IOException {
        Set<String> simpleNames = new HashSet<>();
        for (String name : qualifiedNames) {
            simpleNames.add(name.substring(name.lastIndexOf('.') + 1));
        }
        List<Path> candidates = new ArrayList<>();
        for (Path file : javaFiles(request.sources())) {
            if (mayDeclare(file, simpleNames)) {
                candidates.add(file);
            }
        }
        Map<String, String> declaring = new TreeMap<>();
        for (SourceUnit unit : SourceDeclarations.parse(request.projectDirectory(), candidates)) {
            ExpressionTree packageName = unit.tree().getPackageName();
            String prefix = packageName == null ? "" : spell(packageName) + '.';
            for (Tree declaration : unit.tree().getTypeDecls()) {
                if (declaration instanceof ClassTree type
                        && qualifiedNames.contains(prefix + type.getSimpleName())) {
                    declaring.putIfAbsent(prefix + type.getSimpleName(), unit.origin().path());
                }
            }
        }
        return declaring;
    }

    /**
     * The {@code .java} files the generator reads from {@code sources}, in sorted order: a file as it is given, a
     * directory walked recursively, following symbolic links as the build that compiles them does, an entry that
     * does not exist skipped.
     */
    private static List<Path> javaFiles(List<Path> sources) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path source : sources) {
            if (Files.isDirectory(source)) {
                files.addAll(javaFiles(source));
            } else if (Files.isRegularFile(source)) {
                files.add(source);
            }
        }
        files.sort(Path::compareTo);
        return files;
    }

    /**
     * The {@code .java} files under {@code directory}, symbolic links followed. A link back to a directory that
     * contains it is reported, naming the link, since no walk through it could end.
     */
    private static List<Path> javaFiles(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory, FileVisitOption.FOLLOW_LINKS)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .toList();
        } catch (UncheckedIOException failure) {
            if (failure.getCause() instanceof FileSystemLoopException loop) {
                throw new UncheckedIOException("cannot read the original sources under " + directory
                        + ": the symbolic link " + loop.getFile() + " leads back to a directory that contains it",
                        loop);
            }
            throw failure;
        }
    }

    /**
     * Whether {@code file} can declare a type named one of {@code simpleNames}. Its text holds that name, unless
     * javac reads the name through a Unicode escape or across characters identifiers ignore, so a file carrying
     * either is a candidate too, and so is one whose text cannot be decoded here.
     */
    private static boolean mayDeclare(Path file, Set<String> simpleNames) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException undecodable) {
            return true;
        }
        if (text.contains("\\u") || text.codePoints().anyMatch(Character::isIdentifierIgnorable)) {
            return true;
        }
        for (String simpleName : simpleNames) {
            if (text.contains(simpleName)) {
                return true;
            }
        }
        return false;
    }

    /** A package name as its declaration spells it, for example {@code fx.orders}. */
    private static String spell(ExpressionTree name) {
        if (name instanceof MemberSelectTree select) {
            return spell(select.getExpression()) + '.' + select.getIdentifier();
        }
        return name.toString();
    }
}
