package com.sixpack.dashboard.domain;

import java.util.List;

/** One analytics tab's answer. Its main table can also be exported as CSV. */
public interface Section {

    Window window();

    List<String> csvHeader();

    /** Cells are numbers, strings, dates/instants or null; {@code CsvWriter} formats them. */
    List<List<Object>> csvRows();
}
