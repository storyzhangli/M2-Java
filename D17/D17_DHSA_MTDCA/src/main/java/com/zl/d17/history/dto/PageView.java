package com.zl.d17.history.dto;

import java.util.List;

/**
 * 分页对外视图：记录列表 + 总数与当前分页信息。
 */
public record PageView<T>(List<T> records, long total, long page, long size) {
}
