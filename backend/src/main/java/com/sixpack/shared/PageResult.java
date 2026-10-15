package com.sixpack.shared;

import java.util.List;

public record PageResult<T>(List<T> items, long total) {
}
