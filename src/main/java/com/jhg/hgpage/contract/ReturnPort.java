package com.jhg.hgpage.contract;

import java.util.List;
import java.util.UUID;

public interface ReturnPort {

    ReturnResult create(CreateRequest request);

    ReturnResult find(Long rmaId);

    record CreateRequest(UUID requestKey, Long orderId, String reason, List<CreateItem> items) {}

    record CreateItem(Long orderItemId, Long productId, int quantity) {}

    record ReturnResult(Long rmaId, UUID requestKey, Long orderId, String status, List<ResultItem> items) {}

    record ResultItem(Long orderItemId, Long productId, int requestedQuantity,
                      int acceptedQuantity, String disposition) {}

    final class PermanentReturnRejection extends RuntimeException {
        private final String code;

        public PermanentReturnRejection(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    final class ReturnAuthenticationFailure extends RuntimeException {}

    final class TransientReturnFailure extends RuntimeException {
        public TransientReturnFailure(Throwable cause) {
            super(cause);
        }
    }

    final class RemoteReturnNotFound extends RuntimeException {
        public RemoteReturnNotFound(Long rmaId) {
            super("RMA not found: " + rmaId);
        }
    }
}
