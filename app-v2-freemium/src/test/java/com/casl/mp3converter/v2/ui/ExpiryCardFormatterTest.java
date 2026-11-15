package com.casl.mp3converter.v2.ui;

import javafx.application.Platform;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REGRESIÓN del reporte del usuario: teclear "082032" producía "08/0322"
 * porque, al insertar la "/", el caret quedaba mapeado a mitad del texto y
 * los dígitos siguientes se insertaban EN MEDIO en vez de al final.
 * El formateador de DialogsSupport.fijar el caret EXPLÍCITAMENTE tras la
 * misma cantidad de dígitos que el usuario llevaba tecleados.
 */
@DisplayName("TextFormatter mm/AAAA y tarjeta: texto Y cursor correctos dígito a dígito")
class ExpiryCardFormatterTest {

    @BeforeAll
    static void startToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException yaIniciado) {
            // ya estaba: no importa
        }
    }

    private static TextField fieldExpiry() {
        var field = new TextField();
        field.setTextFormatter(DialogsSupport.digitFormatter(DialogsSupport::formatExpiryVisual));
        return field;
    }

    private static TextField fieldCard() {
        var field = new TextField();
        field.setTextFormatter(DialogsSupport.digitFormatter(DialogsSupport::formatCardVisual));
        return field;
    }

    /** Simula teclear al final, dígito por dígito (como el usuario real). */
    private static void type(TextField field, String digits) {
        for (char c : digits.toCharArray()) {
            int end = field.getText().length();
            field.replaceText(end, end, String.valueOf(c));
        }
    }

    private static void backspace(TextField field) {
        int end = field.getText().length();
        field.replaceText(end - 1, end, "");
    }

    @Test
    @DisplayName("vencimiento: teclear '082023' da EXACTAMENTE '08/2023' con caret al final")
    void vecimientoFeliz() {
        var field = fieldExpiry();
        String[] expected = {"", "0", "08", "08/2", "08/20", "08/202", "08/2023"};
        int i = 0;
        for (char c : "082023".toCharArray()) {
            type(field, String.valueOf(c));
            final String finalText = field.getText();
            final int finalCaret = field.getCaretPosition();
            final String stepText = expected[++i];
            assertAll("paso " + i,
                    () -> assertEquals(stepText, finalText),
                    () -> assertEquals(finalText.length(), finalCaret, "caret SIEMPRE al final"));
        }
    }

    @Test
    @DisplayName("regresión EXACTA del reporte: '082032' ya NO produce '08/0322'")
    void regression082032() {
        var field = fieldExpiry();
        type(field, "082032");
        assertAll(
                () -> assertEquals("08/2032", field.getText(),
                        "los dígitos se APPEND en orden: nunca reordenados a 08/0322"),
                () -> assertEquals(field.getText().length(), field.getCaretPosition())
        );
    }

    @Test
    @DisplayName("vencimiento: máximo 6 dígitos, el excedente se ignora sin romper el texto")
    void maximoSeisDigitos() {
        var field = fieldExpiry();
        type(field, "0820239");
        assertEquals("08/2023", field.getText(), "solo se admiten 6 dígitos (mm/AAAA)");
    }

    @Test
    @DisplayName("vencimiento: backspace al final borra el último dígito ('08/2023' -> '08/202')")
    void backspace() {
        var field = fieldExpiry();
        type(field, "082023");
        backspace(field);
        assertEquals("08/202", field.getText());
        assertEquals(6, field.getCaretPosition());
        // corregir y continuar tecleando (el caso 'corregir' del usuario): 3 -> 9
        type(field, "9");
        assertEquals("08/2029", field.getText());
        assertEquals(7, field.getCaretPosition());
    }

    @Test
    @DisplayName("backspace SOBRE la '/': no se borra (el formateador la re-añade)")
    void backspaceSobreBarra() {
        var field = fieldExpiry();
        type(field, "082023");
        // posición del cursor sobre la '/' (índice 2) y backspace:
        field.positionCaret(3);
        field.deletePreviousChar();
        // el resultado no puede perder la '/', y quedan los 6 dígitos originales:
        assertEquals("08/2023", field.getText().replaceAll("\\s", ""));
    }

    @Test
    @DisplayName("tarjeta: '4111111111111111' da grupos de 4 con espacios, caret SIEMPRE al final")
    void tarjetaFeliz() {
        var field = fieldCard();
        for (char c : "4111111111111111".toCharArray()) {
            type(field, String.valueOf(c));
            assertAll("tras teclear '" + c + "'",
                    () -> assertEquals(field.getText().length(), field.getCaretPosition(),
                            "caret al final con texto=[" + field.getText() + "]"),
                    () -> assertTrue(field.getText().matches("(\\d{0,4})( \\d{0,4})*"),
                            "formato de grupos: [" + field.getText() + "]")
            );
        }
        assertEquals("4111 1111 1111 1111", field.getText());
    }

    @Test
    @DisplayName("tarjeta: pastear letras se limpia y agrupa sin excepciones")
    void tarjetaPasteSUCIO() {
        var field = fieldCard();
        field.setTextFormatter(DialogsSupport.digitFormatter(DialogsSupport::formatCardVisual));
        field.replaceText(0, 0, "4111abc1111111111111");
        assertEquals("4111 1111 1111 1111", field.getText());
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
