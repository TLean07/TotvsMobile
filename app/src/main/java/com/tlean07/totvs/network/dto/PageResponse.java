package com.tlean07.totvs.network.dto;

import java.util.List;

public class PageResponse<T> {
    public List<T> content;
    public int page;
    public int size;
    public long totalElements;
    public int totalPages;
    public boolean last;
}
