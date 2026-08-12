package com.jhg.hgpage.oms.web.form;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class CustomerReturnForm {

    @NotBlank(message = "반품 사유를 입력해주세요.")
    @Size(max = 500, message = "반품 사유는 500자 이하여야 합니다.")
    private String reason;

    @Valid
    private List<Line> lines = new ArrayList<>();

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long orderItemId;

        @Min(value = 0, message = "반품 수량은 0 이상이어야 합니다.")
        private int quantity;
    }
}
