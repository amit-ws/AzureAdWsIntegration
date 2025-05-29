package com.ws.azureKuberntesJIT.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;


@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PageResponse<T> {
    List<T> content;
    int page;
    int size;
    int totalPages;
    long totalElements;
    boolean hasNext;
}
