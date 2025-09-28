package com.jhg.hgpage.domain.dto.form;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Data
public class OrderCreateForm {
    @Valid
    private List<Line> items = new ArrayList<>();

    @Getter @Setter
    @NoArgsConstructor
    public static class Line {
        @NotNull
        private Long productId;

        @NotNull @Min(1)
        private Integer qty;

        private Boolean selected;
    }
}