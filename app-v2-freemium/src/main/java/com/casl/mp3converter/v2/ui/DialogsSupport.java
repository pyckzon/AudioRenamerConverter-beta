package com.casl.mp3converter.v2.ui;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.function.Consumer;

/**
 * Utilidades compartidas de los diálogos de compra/activación (fase 5): formato
 * dinámico de la tarjeta (espacios visuales), labels de error inline, combo de
 * prefijos de país, etc.
 */
final class DialogsSupport {

    private DialogsSupport() {
    }

    /** Formato dinámico de tarjeta: grupos de 4 dígitos con espacios ("4111 1111 1111 1111"). */
    static String formatCardVisual(String digits) {
        var clean = digits == null ? "" : digits.replaceAll("\\D", "");
        if (clean.length() > 16) {
            clean = clean.substring(0, 16);
        }
        var builder = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                builder.append(' ');
            }
            builder.append(clean.charAt(i));
        }
        return builder.toString();
    }

    /** Formato dinámico del vencimiento: mm/AAAA (inserta el slash al llegar a 2). */
    static String formatExpiryVisual(String typed) {
        var clean = typed == null ? "" : typed.replaceAll("\\D", "");
        if (clean.length() > 6) {
            clean = clean.substring(0, 6);
        }
        if (clean.length() >= 3) {
            return clean.substring(0, 2) + "/" + clean.substring(2);
        }
        return clean;
    }

    /** Solo dígitos del texto (teléfono, CVV). */
    static String digitsOnly(String text) {
        return text == null ? "" : text.replaceAll("\\D", "");
    }

    /**
     * Formateador de dígitos con CURSOR DETERMINISTA (regresión del bug del
     * vencimiento): cuando el formateador reescribe el texto completo (p. ej.
     * inserta la "/" de mm/AAAA o el espacio de la tarjeta), el caret de JavaFX
     * queda MAPEADO a mitad del texto y los dígitos siguientes se insertan en
     * medio, desordenando la secuencia ("08/0322"). Aquí fijamos el caret
     * EXPLÍCITAMENTE al índice del texto formateado donde sigue la misma
     * cantidad de dígitos que el usuario llevaba hasta su cursor.
     */
    static javafx.scene.control.TextFormatter<String> digitFormatter(
            java.util.function.Function<String, String> formatter) {
        return new javafx.scene.control.TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            String formatted = formatter.apply(newText);
            if (formatted.equals(newText)) {
                return change;
            }
            // ¿cuántos dígitos quedan ANTES del caret ya mapeado al texto nuevo?
            // (getCaretPosition: el caret recalculado por JavaFX, en coordenadas del
            // texto nuevo; controlCaretPosition está en el texto VIEJO y se queda
            // un carácter atrás en el tecleo seguido)
            int mappedCaret = Math.min(change.getCaretPosition(), newText.length());
            int digitsBefore = digitsOnly(newText.substring(0, mappedCaret)).length();
            change.setRange(0, change.getControlText().length());
            change.setText(formatted);
            int target = caretAfterDigits(formatted, digitsBefore);
            change.setCaretPosition(target);
            change.setAnchor(target);
            return change;
        });
    }

    /** Índice del texto formateado donde terminan los primeros {@code n} dígitos. */
    static int caretAfterDigits(String formatted, int n) {
        int seen = 0;
        for (int i = 0; i < formatted.length(); i++) {
            if (Character.isDigit(formatted.charAt(i)) && ++seen > n) {
                return i;
            }
        }
        return formatted.length();
    }

    /** Prefijos de país del dropdown (requisito 2.5: default +52 México). */
    static ComboBox<String> buildCountryCombo() {
        var combo = new ComboBox<String>();
        combo.getItems().addAll(
                "+52 (México)", "+1 (EE.UU./Canadá)", "+34 (España)",
                "+54 (Argentina)", "+57 (Colombia)", "+51 (Perú)", "+56 (Chile)");
        combo.setValue("+52 (México)");
        return combo;
    }

    /** Código de país como "+52" desde la combo. */
    static String countryCodeOf(ComboBox<String> combo) {
        var selected = combo.getValue();
        if (selected == null || selected.isBlank()) {
            return "+52";
        }
        int space = selected.indexOf(' ');
        return space > 0 ? selected.substring(0, space) : "+52";
    }
}
