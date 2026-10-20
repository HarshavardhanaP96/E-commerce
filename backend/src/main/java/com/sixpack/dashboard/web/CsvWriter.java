package com.sixpack.dashboard.web;

import java.math.BigDecimal;
import java.util.List;

/** RFC 4180 CSV for section exports. Text that a spreadsheet would run as a formula is prefixed with a quote. */
final class CsvWriter {

    private CsvWriter() {
    }

    static String write(List<String> header, List<List<Object>> rows) {
        StringBuilder out = new StringBuilder();
        line(out, List.copyOf(header));
        for (List<Object> row : rows) {
            line(out, row);
        }
        return out.toString();
    }

    private static void line(StringBuilder out, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double || value instanceof Float) {
            return BigDecimal.valueOf(((Number) value).doubleValue()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
