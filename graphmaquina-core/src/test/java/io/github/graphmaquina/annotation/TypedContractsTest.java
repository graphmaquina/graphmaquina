package io.github.graphmaquina.annotation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.annotation.internal.GeneratedCompanion;
import io.github.graphmaquina.annotation.internal.GeneratedSymbol;
import io.github.graphmaquina.annotation.internal.GeneratedVocabulary;
import io.github.graphmaquina.annotation.internal.GraphCompilation;
import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.annotation.internal.VocabularyKind;
import io.github.graphmaquina.symbol.ActionSymbol;
import io.github.graphmaquina.symbol.ConditionSymbol;
import io.github.graphmaquina.symbol.GuardSymbol;
import io.github.graphmaquina.symbol.HookSymbol;
import io.github.graphmaquina.symbol.PropertySymbol;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shape of the annotation and symbol types: retention, targets and members of the markers and of the generated
 * metadata annotations, the two fields of {@code Timeout}, the closed and ordered lists of {@code SymbolKind} and
 * {@code VocabularyKind}, and symbol types that declare no method and extend nothing.
 */
class TypedContractsTest {

    @Test
    void stateAndMemberReferencesHaveNoStringEscapeHatch() throws NoSuchMethodException {
        assertEquals(RetentionPolicy.CLASS, GraphGuard.class.getAnnotation(Retention.class).value());
        assertEquals(0, GraphGuard.class.getDeclaredMethods().length);
        assertEquals(long.class, Timeout.class.getDeclaredMethod("seconds").getReturnType());
        assertEquals(int.class, Timeout.class.getDeclaredMethod("nanos").getReturnType());
        assertEquals(0L, Timeout.class.getDeclaredMethod("seconds").getDefaultValue());
        assertEquals(0, Timeout.class.getDeclaredMethod("nanos").getDefaultValue());
    }

    @Test
    void stableMarkersExposeOnlyTheirDeclaredRoles() {
        List<Class<? extends Annotation>> emptyMarkers = List.of(
                GenerateGraphApi.class,
                GraphAction.class,
                GraphGuard.class,
                GraphHook.class,
                GraphCondition.class,
                GraphProperty.class,
                GraphBindings.class);

        for (Class<? extends Annotation> marker : emptyMarkers) {
            assertEquals(RetentionPolicy.CLASS, marker.getAnnotation(Retention.class).value(), marker.getSimpleName());
            assertEquals(0, marker.getDeclaredMethods().length, marker.getSimpleName());
        }

        assertArrayEquals(new ElementType[] {ElementType.TYPE},
                GenerateGraphApi.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[] {ElementType.TYPE},
                GraphPeers.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[] {ElementType.METHOD},
                GraphAction.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[] {ElementType.METHOD},
                GraphGuard.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[] {ElementType.METHOD},
                GraphHook.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[] {ElementType.METHOD},
                GraphCondition.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[] {ElementType.FIELD, ElementType.METHOD},
                GraphProperty.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[] {ElementType.TYPE},
                GraphBindings.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[] {}, Timeout.class.getAnnotation(Target.class).value());
    }

    @Test
    void generatedMetadataCarriesTypedMachineAndCategoryContracts() throws NoSuchMethodException {
        assertArrayEquals(new ElementType[] {ElementType.ANNOTATION_TYPE},
                GeneratedVocabulary.class.getAnnotation(Target.class).value());
        assertEquals(Class.class, GeneratedVocabulary.class.getDeclaredMethod("machine").getReturnType());
        assertEquals(Class[].class, GeneratedVocabulary.class.getDeclaredMethod("peer").getReturnType());
        assertEquals(VocabularyKind.class, GeneratedVocabulary.class.getDeclaredMethod("value").getReturnType());

        assertArrayEquals(new ElementType[] {ElementType.TYPE},
                GeneratedSymbol.class.getAnnotation(Target.class).value());
        assertEquals(Class.class, GeneratedSymbol.class.getDeclaredMethod("host").getReturnType());
        assertEquals(String.class, GeneratedSymbol.class.getDeclaredMethod("member").getReturnType());
        assertEquals(SymbolKind[].class, GeneratedSymbol.class.getDeclaredMethod("kinds").getReturnType());

        assertArrayEquals(new ElementType[] {ElementType.TYPE},
                GraphCompilation.class.getAnnotation(Target.class).value());
        assertEquals(String.class, GraphCompilation.class.getDeclaredMethod("value").getReturnType());
    }

    /** The marker of a processor-generated companion: a bare type marker kept in the class file only. */
    @Test
    void generatedCompanionIsABareClassFileMarkerOfTypes() {
        assertArrayEquals(new ElementType[] {ElementType.TYPE},
                GeneratedCompanion.class.getAnnotation(Target.class).value());
        assertEquals(RetentionPolicy.CLASS, GeneratedCompanion.class.getAnnotation(Retention.class).value());
        assertEquals(0, GeneratedCompanion.class.getDeclaredMethods().length);
    }

    @Test
    void categorySetsAreClosedAndStable() {
        assertArrayEquals(new SymbolKind[] {
                SymbolKind.ACTION, SymbolKind.GUARD, SymbolKind.HOOK, SymbolKind.CONDITION, SymbolKind.PROPERTY
        }, SymbolKind.values());
        assertArrayEquals(new VocabularyKind[] {
                VocabularyKind.DEFINITION, VocabularyKind.STATE_POLICY, VocabularyKind.STATE_POLICIES,
                VocabularyKind.TRANSITION, VocabularyKind.TRANSITIONS, VocabularyKind.REJECT, VocabularyKind.REJECTS,
                VocabularyKind.WATCH, VocabularyKind.WATCHES, VocabularyKind.WHEN
        }, VocabularyKind.values());
    }

    @Test
    void symbolContractsRemainPureCategoryTypes() {
        List<Class<?>> symbols = List.of(
                ActionSymbol.class, GuardSymbol.class, HookSymbol.class, ConditionSymbol.class, PropertySymbol.class);

        for (Class<?> symbol : symbols) {
            assertEquals(0, symbol.getDeclaredMethods().length, symbol.getSimpleName());
            assertEquals(0, symbol.getInterfaces().length, symbol.getSimpleName());
        }
    }
}
