package com.jhg.hgpage.template;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentAdminTemplateContractTest {

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void paymentAdministrationShowsResponsiveFilteredReviewRows() throws Exception {
        String html = read("src/main/resources/templates/admin/payments.html");

        assertThat(html).contains("<meta name=\"viewport\"");
        assertThat(html).contains(".table-wrap{overflow-x:auto}");
        assertThat(html).contains("name=\"paymentStatus\"", "name=\"refundStatus\"");
        assertThat(html).contains(
                "counts.refundReviewCount",
                "counts.allocationReviewCount",
                "counts.cancellationPaymentReviewCount",
                "counts.cancellationAllocationReviewCount");
        assertThat(html).contains("${payments}", "${refunds}");
        assertThat(html).contains(
                "row.orderId",
                "row.returnId",
                "row.amount",
                "row.requestKey",
                "row.attemptCount",
                "row.failureReason",
                "row.nextRetryAt");
        assertThat(html).contains(
                "/admin/refunds/{refundId}/retry",
                "/admin/payment-attempts/{attemptId}/retry");
    }

    @Test
    void administratorNavigationLinksPaymentManagement() throws Exception {
        String layout = read("src/main/resources/templates/fragments/layout.html");

        assertThat(layout).contains("th:href=\"@{/admin/payments}\"");
        assertThat(layout).contains("active == 'payments'");
        assertThat(layout).contains("결제·환불 관리");
    }
}
