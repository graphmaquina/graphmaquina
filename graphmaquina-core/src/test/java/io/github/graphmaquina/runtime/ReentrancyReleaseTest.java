package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The holders of a scope release what they claimed with stores before their finally makes any call or allocation, on
 * both ways out of the try: the copy of the finally that follows the try and the handler of any throwable. A
 * StackOverflowError that strikes the release can then not leave the thread's cell claimed, nor a guard count changed.
 * The overflow sweeps of {@link ReentrancyOverflowTest} show it only when an overflow happens to strike there; the
 * bytecode that {@code javap -c -p} prints shows it every time. Each row names the method, the call its try makes,
 * and the fields its release stores, by the ends of their names.
 */
class ReentrancyReleaseTest {

    private static final Pattern INSTRUCTION = Pattern.compile("\\s+(\\d+): ([a-z][a-z0-9_]*)\\s*(.*)");
    private static final Pattern ANY_HANDLER = Pattern.compile("\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+any");

    /** An instruction: its offset, and its mnemonic followed by javap's comment on its operands, if any. */
    private record Instruction(int offset, String text) {

        /** Whether it calls, allocates or throws, where a StackOverflowError or an OutOfMemoryError can strike. */
        boolean callsAllocatesOrThrows() {
            String mnemonic = text.split(" ", 2)[0];
            return mnemonic.startsWith("invoke") || mnemonic.equals("new") || mnemonic.endsWith("newarray")
                    || mnemonic.equals("athrow");
        }
    }

    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource(delimiter = '|', value = {
            "Checks | admitted( | runtime/Steps.admit | Reentrancy$Cell.active Reentrancy$Cell.top",
            "RootCall | stepAndCommit( | java/util/function/Function.apply"
                    + " | Reentrancy$Cell.active Reentrancy$Cell.top",
            "Steps | syncVerdict( | api/TransitionGuard.test | UnitOfWork.guards",
            "Checks | check( | admitted | UnitOfWork.guards",
            "Reentrancy | requireCheckMayStart( | runtime/DefaultStateMachine.refOf | UnitOfWork.guards",
            "DefaultPeerView | stateOf( | runtime/DefaultStateMachine.refOf | UnitOfWork.guards",
            "DefaultPeerView$Committed | stateOf( | runtime/DefaultStateMachine.refOf | UnitOfWork.guards",
            "DefaultTransitionContext | cascade(io.github.graphmaquina.api.MachineKey | runtime/Cascades.cascade"
                    + " | UnitOfWork.guards",
            "DefaultTransitionContext | cascadeAll(io.github.graphmaquina.api.MachineKey | runtime/Cascades.cascadeAll"
                    + " | UnitOfWork.guards",
            "DefaultTransitionContext | cascade(java.lang.Class | runtime/Transaction.cascadeKeyFor"
                    + " | UnitOfWork.guards",
            "DefaultTransitionContext | cascadeAll(java.lang.Class | runtime/Cascades.cascadeAll | UnitOfWork.guards"})
    void theReleaseStoresBeforeItCallsOnBothWaysOut(String type, String method, String call, String fields)
            throws Exception {
        List<String> lines = javap(Class.forName("io.github.graphmaquina.runtime." + type));
        List<Instruction> code = new ArrayList<>();
        List<int[]> handlers = new ArrayList<>();
        parse(lines, method, code, handlers);
        int offset = code.stream().filter((Instruction each) -> each.text().contains(call)).findFirst()
                .orElseThrow(() -> new AssertionError(method + " no longer calls " + call)).offset();
        int[] release = handlers.stream().filter((int[] row) -> row[0] <= offset && offset < row[1]).findFirst()
                .orElseThrow(() -> new AssertionError("no finally covers " + call + " in " + method));
        List<String> expected = List.of(fields.split(" "));
        assertEquals(expected, storedBeforeTheFirstCall(code, release[1], expected), "after the try of " + method);
        assertEquals(expected, storedBeforeTheFirstCall(code, release[2], expected), "in the handler of " + method);
    }

    /** Which of {@code fields}, in their order, the instructions from {@code start} store before one calls. */
    private static List<String> storedBeforeTheFirstCall(List<Instruction> code, int start, List<String> fields) {
        List<String> stored = new ArrayList<>();
        for (Instruction instruction : code) {
            if (instruction.offset() < start) {
                continue;
            }
            if (instruction.callsAllocatesOrThrows()) {
                break;
            }
            fields.stream().filter((String field) -> instruction.text().startsWith("putfield ")
                    && instruction.text().endsWith("/" + field)).forEach(stored::add);
        }
        return fields.stream().filter(stored::contains).toList();
    }

    /**
     * The line that declares the only method whose declaration holds {@code method}, its name and an opening
     * parenthesis, followed by the start of its first parameter's type for an overload, as the listing indents a
     * member.
     */
    private static int declarationOf(List<String> lines, String method) {
        List<Integer> declarations = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("  ") && !line.startsWith("   ") && line.contains(" " + method)) {
                declarations.add(index);
            }
        }
        assertEquals(1, declarations.size(), "methods named " + method);
        return declarations.get(0);
    }

    /** Adds the instructions of the only method {@code method} to {@code code}, and its finally rows to {@code any}. */
    private static void parse(List<String> lines, String method, List<Instruction> code, List<int[]> any) {
        for (int index = declarationOf(lines, method) + 1; index < lines.size() && !lines.get(index).isEmpty();
                index++) {
            Matcher instruction = INSTRUCTION.matcher(lines.get(index));
            Matcher handler = ANY_HANDLER.matcher(lines.get(index));
            if (handler.matches()) {
                any.add(new int[] {Integer.parseInt(handler.group(1)), Integer.parseInt(handler.group(2)),
                    Integer.parseInt(handler.group(3))});
            } else if (instruction.matches()) {
                code.add(new Instruction(Integer.parseInt(instruction.group(1)),
                        instruction.group(2) + named(instruction.group(3))));
            }
        }
        assertTrue(!code.isEmpty(), "no code for " + method);
    }

    /** What javap's comment on an instruction's operands names, without its kind and descriptor, after a space. */
    private static String named(String operands) {
        int comment = operands.indexOf("// ");
        if (comment < 0) {
            return "";
        }
        String member = operands.substring(comment + 3);
        member = member.substring(member.indexOf(' ') + 1);
        int descriptor = member.indexOf(':');
        return " " + (descriptor < 0 ? member : member.substring(0, descriptor));
    }

    /** The lines {@code javap -c -p} prints for the compiled {@code type}. */
    private static List<String> javap(Class<?> type) throws URISyntaxException {
        Path classes = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = ToolProvider.findFirst("javap").orElseThrow().run(new PrintWriter(out), new PrintWriter(err),
                "-c", "-p", "-cp", classes.toString(), type.getName());
        assertEquals(0, code, "javap " + type.getName() + ": " + err);
        return out.toString().lines().toList();
    }
}
