package com.sixpack.product.domain;

import com.sixpack.shared.ApiException;
import java.util.ArrayList;
import java.util.List;

/**
 * A measurement table shown as the product's size guide, e.g. columns Size / Chest (in) / Length (in) and one
 * row per size. Stored on the product itself; {@link #EMPTY} means no size guide.
 */
public record SizeChart(List<String> columns, List<List<String>> rows, String note) {

    public static final SizeChart EMPTY = new SizeChart(List.of(), List.of(), null);

    public static final int MAX_COLUMNS = 8;
    public static final int MAX_ROWS = 30;
    private static final int MAX_CELL = 30;
    private static final int MAX_NOTE = 300;

    public SizeChart {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
    }

    public boolean isEmpty() {
        return columns.isEmpty();
    }

    /** Validates and trims admin input. No columns and no rows clears the chart. */
    public static SizeChart of(List<String> columns, List<List<String>> rows, String note) {
        List<String> cols = columns == null ? List.of() : columns;
        List<List<String>> body = rows == null ? List.of() : rows;
        if (cols.isEmpty() && body.isEmpty()) {
            return EMPTY;
        }
        if (cols.isEmpty() || cols.size() > MAX_COLUMNS) {
            throw bad("columns: between 1 and " + MAX_COLUMNS + " columns");
        }
        if (body.isEmpty() || body.size() > MAX_ROWS) {
            throw bad("rows: between 1 and " + MAX_ROWS + " rows");
        }
        List<String> header = new ArrayList<>();
        for (String c : cols) {
            if (c == null || c.isBlank()) {
                throw bad("columns: every column needs a heading");
            }
            header.add(cell(c));
        }
        List<List<String>> table = new ArrayList<>();
        for (List<String> row : body) {
            if (row == null || row.size() != header.size()) {
                throw bad("rows: every row needs exactly " + header.size() + " cells");
            }
            if (row.stream().allMatch(v -> v == null || v.isBlank())) {
                throw bad("rows: remove empty rows");
            }
            table.add(row.stream().map(v -> v == null ? "" : cell(v)).toList());
        }
        String trimmedNote = note == null || note.isBlank() ? null : note.trim();
        if (trimmedNote != null && trimmedNote.length() > MAX_NOTE) {
            throw bad("note: at most " + MAX_NOTE + " characters");
        }
        return new SizeChart(header, table, trimmedNote);
    }

    private static String cell(String value) {
        String v = value.trim();
        if (v.length() > MAX_CELL) {
            throw bad("cells: at most " + MAX_CELL + " characters");
        }
        return v;
    }

    private static ApiException bad(String message) {
        return ApiException.badRequest("sizeChart." + message);
    }
}
