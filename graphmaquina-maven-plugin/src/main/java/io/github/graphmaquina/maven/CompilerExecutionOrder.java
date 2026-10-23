package io.github.graphmaquina.maven;

import org.apache.maven.lifecycle.DefaultLifecycles;
import org.apache.maven.lifecycle.Lifecycle;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Where the executions of {@code maven-compiler-plugin} run in the lifecycles of a build, against the StateGraph goals
 * that surround one compilation: which executions of the compiler goal the default lifecycle runs, why it runs none,
 * and whether javac runs between the preparation and the completion of its compilation, in the order Maven runs the
 * executions of a lifecycle.
 */
final class CompilerExecutionOrder {

    /** The key the project model files {@code maven-compiler-plugin} under. */
    private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";

    /** The key the project model files this plugin under. */
    private static final String STATEGRAPH = "io.github.graphmaquina:graphmaquina-maven-plugin";

    /** The id of the lifecycle that holds the phases of the StateGraph goals and of the compilations they surround. */
    private static final String DEFAULT_LIFECYCLE = "default";

    private CompilerExecutionOrder() {
    }

    /**
     * Why the default lifecycle of {@code project} runs no execution of the compiler goal of {@code execution}, which
     * makes this compilation one the build switches off.
     *
     * @param project the project being built
     * @param execution the compilation the calling goal belongs to
     * @param lifecycles the lifecycles of this build
     * @return the reason, naming every execution of the goal and the phase it is bound to; empty when one runs
     */
    static Optional<String> idle(MavenProject project, CompilerExecution execution, DefaultLifecycles lifecycles) {
        if (!running(project, execution, lifecycles).isEmpty()) {
            return Optional.empty();
        }
        List<String> bindings = new ArrayList<>();
        Plugin compiler = project.getPlugin(COMPILER);
        if (compiler != null) {
            for (PluginExecution candidate : compiler.getExecutions()) {
                if (candidate.getGoals().contains(execution.goal())) {
                    String phase = phase(candidate, execution);
                    Lifecycle lifecycle = lifecycles.get(phase);
                    bindings.add(candidate.getId() + " is bound to " + phase + (lifecycle == null
                        ? ", which is no phase of a lifecycle"
                        : ", a phase of the " + lifecycle.getId() + " lifecycle"));
                }
            }
        }
        return Optional.of("maven-compiler-plugin runs no execution of its " + execution.goal() + " goal in the"
            + " default lifecycle of this project"
            + (bindings.isEmpty() ? "" : ", as " + String.join(", and ", bindings)));
    }

    /**
     * The executions of the compiler goal of {@code execution} that the default lifecycle of {@code project} runs, in
     * the order of the project model: those bound to a phase of that lifecycle, which holds the phases of the
     * StateGraph goals. Maven runs no execution bound to anything else, {@code none} included, binds one that names
     * no phase to the default phase of its goal, and runs one bound to a phase of another lifecycle, such as
     * {@code pre-site} or {@code clean}, only when the build invokes that lifecycle, before or after the default one,
     * never between the preparation and the completion of a compilation.
     */
    static List<PluginExecution> running(MavenProject project, CompilerExecution execution,
            DefaultLifecycles lifecycles) {
        Plugin compiler = project.getPlugin(COMPILER);
        List<PluginExecution> running = new ArrayList<>();
        if (compiler == null) {
            return running;
        }
        for (PluginExecution candidate : compiler.getExecutions()) {
            Lifecycle lifecycle = lifecycles.get(phase(candidate, execution));
            if (candidate.getGoals().contains(execution.goal()) && lifecycle != null
                    && lifecycle.getId().equals(DEFAULT_LIFECYCLE)) {
                running.add(candidate);
            }
        }
        return running;
    }

    /** The phase {@code candidate} is bound to: the one it declares, else the default phase of the goal. */
    private static String phase(PluginExecution candidate, CompilerExecution execution) {
        return candidate.getPhase() == null ? execution.defaultPhase() : candidate.getPhase();
    }

    /**
     * Refuses a compilation whose javac would not run between the preparation and the completion of this compilation.
     *
     * <p>Maven runs the phases of a lifecycle in their order, and within one phase the executions bound to it by their
     * priority, then in the order of their plugins in the project model, then in the order their plugin declares them:
     * maven-core 3.9.12 gives the executions the lifecycle of the packaging binds, such as {@code default-compile}, a
     * priority below zero, and the others zero. An execution of the compiler a POM adds itself and binds to the phase
     * of {@code complete} therefore runs after it when graphmaquina-maven-plugin is declared first, and one bound to an
     * earlier phase than {@code prepare}, or to its phase with maven-compiler-plugin declared first, runs before it.
     * Either way the completion finds nothing javac compiled, after the preparation retired the outputs of the
     * previous compilation. Executions bound to no phase of a lifecycle never run and are not compared, nor are two
     * bound to phases of different lifecycles.</p>
     *
     * @param execution the compilation the calling goal belongs to
     * @param id the id of the execution of the compiler this compilation is read from
     * @param project the project being built, whose model orders the executions
     * @param lifecycles the lifecycles of this build, whose phases order the executions bound to them
     * @throws MojoExecutionException naming both executions, their phases and the order that fixes it
     */
    static void requireCompilerBetweenPreparationAndCompletion(CompilerExecution execution, String id,
            MavenProject project, DefaultLifecycles lifecycles) throws MojoExecutionException {
        List<Step> preparations = steps(project, lifecycles, STATEGRAPH, execution.preparationGoal(),
            execution.preparationPhase());
        List<Step> completions = steps(project, lifecycles, STATEGRAPH, execution.completionGoal(),
            execution.completionPhase());
        for (Step javac : steps(project, lifecycles, COMPILER, execution.goal(), execution.defaultPhase())) {
            if (!javac.id().equals(id)) {
                continue;
            }
            String remedy = completions.isEmpty() || javac.phase().equals(completions.get(0).phase())
                ? "Declare maven-compiler-plugin before graphmaquina-maven-plugin in the <plugins> of the build."
                : "Bind " + id + " to the phase " + completions.get(0).phase() + ", which "
                    + completions.get(0).id() + " runs in, and declare maven-compiler-plugin before"
                    + " graphmaquina-maven-plugin in the <plugins> of the build.";
            for (Step preparation : preparations) {
                if (javac.comparable(preparation) && javac.runsBefore(preparation)) {
                    throw outOfOrder(javac, "before", preparation, "prepares", ", so the preparation would retire"
                        + " what javac compiled, and the completion would find nothing to publish. " + remedy);
                }
            }
            for (Step completion : completions) {
                if (javac.comparable(completion) && completion.runsBefore(javac)) {
                    throw outOfOrder(javac, "after", completion, "completes", ", so the completion would find nothing"
                        + " javac compiled, after the preparation retired the outputs of the previous compilation. "
                        + remedy);
                }
            }
        }
    }

    /** The refusal of a javac that runs {@code relation} {@code step}, which {@code role} this compilation. */
    private static MojoExecutionException outOfOrder(Step javac, String relation, Step step, String role,
            String consequence) {
        String reason = javac.phase().equals(step.phase())
            ? ": within one phase, Maven runs the executions a POM adds in the order their plugins are declared, after"
                + " those the lifecycle of the packaging binds"
            : ": Maven runs the phase " + (javac.runsBefore(step) ? javac.phase() + " before " + step.phase()
                : step.phase() + " before " + javac.phase());
        return new MojoExecutionException("the " + javac.id() + " execution of maven-compiler-plugin, bound to "
            + javac.phase() + ", runs " + relation + " the " + step.id() + " execution of graphmaquina-maven-plugin,"
            + " bound to " + step.phase() + ", which " + role + " this compilation" + reason + consequence);
    }

    /**
     * Where in the build every running execution of {@code goal} of the plugin {@code pluginKey} runs, in the order of
     * the project model, an execution that declares no phase being bound to {@code defaultPhase}.
     */
    private static List<Step> steps(MavenProject project, DefaultLifecycles lifecycles, String pluginKey, String goal,
            String defaultPhase) {
        List<Step> steps = new ArrayList<>();
        List<Plugin> plugins = project.getBuild().getPlugins();
        for (int plugin = 0; plugin < plugins.size(); plugin++) {
            if (!plugins.get(plugin).getKey().equals(pluginKey)) {
                continue;
            }
            List<PluginExecution> executions = plugins.get(plugin).getExecutions();
            for (int position = 0; position < executions.size(); position++) {
                PluginExecution candidate = executions.get(position);
                String phase = candidate.getPhase() == null ? defaultPhase : candidate.getPhase();
                Lifecycle lifecycle = lifecycles.get(phase);
                int goalPosition = candidate.getGoals().indexOf(goal);
                if (lifecycle != null && goalPosition >= 0) {
                    steps.add(new Step(candidate.getId(), phase, lifecycle.getId(),
                        lifecycle.getPhases().indexOf(phase), candidate.getPriority(), plugin, position, goalPosition));
                }
            }
        }
        return steps;
    }

    /**
     * Where one goal of one execution runs in the build, in the terms Maven orders the executions of a lifecycle by.
     *
     * @param id the id of the execution
     * @param phase the phase it is bound to
     * @param lifecycle the id of the lifecycle that phase belongs to
     * @param phaseIndex the position of that phase in its lifecycle
     * @param priority the priority of the execution, below zero for the executions the packaging binds
     * @param plugin the position of its plugin among the plugins of the project model
     * @param execution the position of the execution among those of its plugin
     * @param goal the position of the goal among those of the execution
     */
    private record Step(String id, String phase, String lifecycle, int phaseIndex, int priority, int plugin,
            int execution, int goal) {

        /** Whether this step and {@code other} belong to one lifecycle, the only steps Maven orders. */
        boolean comparable(Step other) {
            return lifecycle.equals(other.lifecycle);
        }

        /** Whether Maven runs this step before {@code other}, which belongs to the same lifecycle. */
        boolean runsBefore(Step other) {
            return Comparator.comparingInt(Step::phaseIndex).thenComparingInt(Step::priority)
                .thenComparingInt(Step::plugin).thenComparingInt(Step::execution).thenComparingInt(Step::goal)
                .compare(this, other) < 0;
        }
    }
}
