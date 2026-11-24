package io.github.graphmaquina.processor.gen;

import io.github.graphmaquina.processor.model.CheckModel;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.OperationModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the selector every companion implements for {@code Machine}: {@code execute(entity, transition)} and
 * {@code eligibility(entity, transition)}, which find the operation, or the check, without input that the facade
 * declares for an identifier and call it, and refuse any other identifier.
 */
final class SelectorGenerator {

    private SelectorGenerator() { }

    /**
     * The selector of {@code Machine}: {@code execute(entity, transition)}, a switch over the identifiers of the
     * operations without input, each calling its operation, and {@code eligibility(entity, transition)}, one over the
     * identifiers of the checks without input, each calling its check. Both refuse a null argument through
     * {@code objects}, naming the parameter, and refuse any other identifier with an {@code IllegalArgumentException}
     * that names it and the facade, before anything runs.
     */
    static void write(MachineModel model, Names names, String objects, SourceWriter out) {
        String e = names.of(model.types().entityElement().getQualifiedName().toString());
        String t = names.of(model.types().transitionsElement().getQualifiedName().toString());
        String facade = model.types().facade().getSimpleName().toString();
        String refusal = names.of(MachineGenerator.ILLEGAL_ARGUMENT);
        List<String> operations = new ArrayList<>();
        for (OperationModel operation : model.operations()) {
            if (operation.inputType().isEmpty()) {
                operations.add("case " + operation.transitionId() + " -> " + operation.name() + "(entity);");
            }
        }
        List<String> checks = new ArrayList<>();
        for (CheckModel check : model.checks()) {
            if (check.inputType().isEmpty()) {
                checks.add("case " + check.transitionId() + " -> " + check.name() + "(entity);");
            }
        }
        List<String> tags = List.of("@param entity the entity", "@param transition the identifier");
        out.blank();
        out.javadoc("Runs the operation without input that {@link " + facade + "} declares for {@code transition}, as"
                + " calling it does. It refuses a null argument with a {@code NullPointerException} that names the"
                + " parameter, and any other identifier with an {@code IllegalArgumentException}, before anything"
                + " runs.", tagsWith(tags, "@return the outcome of the operation"));
        out.line("@" + names.of(MachineGenerator.OVERRIDE));
        String arguments = MachineGenerator.typeArguments(model.types(), names);
        out.open("public " + names.of(ResultTypes.OUTCOME) + arguments + " execute(" + e + " entity, " + t
                + " transition)");
        switchOver(out, objects, refusal, operations, "\" has no operation without input on " + facade + "\"");
        out.blank();
        out.javadoc("Runs the check without input that {@link " + facade + "} declares for {@code transition}, as"
                + " calling it does: it writes no state, runs no action and tells no observer. It refuses a null"
                + " argument with a {@code NullPointerException} that names the parameter, and any other identifier"
                + " with an {@code IllegalArgumentException}, before anything runs.",
                tagsWith(tags, "@return the answer of the check"));
        out.line("@" + names.of(MachineGenerator.OVERRIDE));
        out.open("public " + names.of(MachineGenerator.ELIGIBILITY) + arguments + " eligibility(" + e + " entity, "
                + t + " transition)");
        switchOver(out, objects, refusal, checks, "\" has no check without input on " + facade + "\"");
    }

    /** {@code tags} followed by {@code last}. */
    private static List<String> tagsWith(List<String> tags, String last) {
        List<String> all = new ArrayList<>(tags);
        all.add(last);
        return all;
    }

    /**
     * The body of a selector method, closed: the null checks of its two parameters, then a switch that returns what
     * {@code arms} call and refuses any other identifier with {@code refusal}, whose message is the identifier's name
     * followed by {@code reason}, a string literal's text. Without arms, the refusal alone, since a switch expression
     * needs one result.
     */
    private static void switchOver(SourceWriter out, String objects, String refusal, List<String> arms,
                                   String reason) {
        out.line(objects + ".requireNonNull(entity, \"entity\");");
        out.line(objects + ".requireNonNull(transition, \"transition\");");
        String message = "        transition.name() + " + reason + ");";
        if (arms.isEmpty()) {
            out.line("throw new " + refusal + "(");
            out.line(message);
            out.close();
            return;
        }
        out.line("return switch (transition) {");
        out.indent();
        for (String arm : arms) {
            out.line(arm);
        }
        out.line("default -> throw new " + refusal + "(");
        out.line(message);
        out.outdent();
        out.line("};");
        out.close();
    }
}
