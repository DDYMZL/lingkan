package com.lingkan.watchlist.dto;

import com.lingkan.calculation.dto.BoardRowDto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 首页自选看板响应（spec 010 §6）。
 *
 * @param rows      按自选顺序排列的看板行；无自选时为空列表
 * @param quoteTime 全部行中最新行情时间；无有效行情时为 null
 * @param count     看板行数
 */
public record BoardResponse(List<BoardRowDto> rows, LocalDateTime quoteTime, int count) {

    public static BoardResponse of(List<BoardRowDto> rows) {
        LocalDateTime latest = rows.stream()
                .map(BoardRowDto::quoteTime)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        return new BoardResponse(rows, latest, rows.size());
    }
}
