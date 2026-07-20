package com.jhg.hgpage.wms.web.form;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ReplenishmentRequestForm {
    private UUID requestKey;
    private String reason;
    private List<Item> items = new ArrayList<>(List.of(new Item()));

    @Getter
    @Setter
    public static class Item {
        private Long productId;
        private int requestedQty;
    }
}
