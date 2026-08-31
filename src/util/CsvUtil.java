package util;

import java.util.ArrayList;
import java.util.List;

/** Utilitário mínimo para CSV/DSV com aspas, sem biblioteca externa. */
public final class CsvUtil {
    private CsvUtil() {}

    public static List<String> separarLinha(String linha, char separador) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean entreAspas = false;

        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (c == '"') {
                if (entreAspas && i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                    atual.append('"');
                    i++;
                } else {
                    entreAspas = !entreAspas;
                }
            } else if (c == separador && !entreAspas) {
                campos.add(atual.toString().trim());
                atual.setLength(0);
            } else {
                atual.append(c);
            }
        }
        campos.add(atual.toString().trim());
        return campos;
    }
}
