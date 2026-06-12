package com.anatomist.query;

import java.util.List;

public record PagedResult<T>(List<T> items, int total, boolean truncated, int offset) {
}
