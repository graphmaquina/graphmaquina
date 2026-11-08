package io.github.graphmaquina.codegen.source;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ModuleElement;

/**
 * The packages a single-module-import declaration, Java 25 syntax such as {@code import module java.base;}, imports on
 * demand into code of the unnamed module (JLS 7.5.5): every package the module exports to all modules, and the same of
 * each module it requires transitively, followed through their own transitive requirements. A package exported only to
 * named modules is not among them, and neither are the packages of a module required without {@code transitive}, such
 * as the {@code java.base} every module requires.
 */
final class ModuleExports {

    private ModuleExports() {
    }

    /** The packages an import of {@code module} brings, in the order the modules declare them; none without one. */
    static List<String> packages(Optional<ModuleElement> module) {
        Set<String> packages = new LinkedHashSet<>();
        Set<ModuleElement> visited = new HashSet<>();
        Deque<ModuleElement> pending = new ArrayDeque<>();
        module.ifPresent(pending::add);
        while (!pending.isEmpty()) {
            ModuleElement current = pending.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (ModuleElement.Directive directive : current.getDirectives()) {
                if (directive instanceof ModuleElement.ExportsDirective exports && exports.getTargetModules() == null) {
                    packages.add(exports.getPackage().getQualifiedName().toString());
                } else if (directive instanceof ModuleElement.RequiresDirective requires && requires.isTransitive()) {
                    pending.add(requires.getDependency());
                }
            }
        }
        return List.copyOf(packages);
    }
}
