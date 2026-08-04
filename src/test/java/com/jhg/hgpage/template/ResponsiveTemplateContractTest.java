package com.jhg.hgpage.template;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResponsiveTemplateContractTest {

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void sharedLayoutHasMobileOverflowAndTouchRules() throws Exception {
        String css = read("src/main/resources/static/css/app.css");

        assertThat(css).contains("@media(max-width:720px)");
        assertThat(css).contains("body{padding:16px}");
        assertThat(css).contains(".site-nav{max-width:100%;overflow-x:auto");
        assertThat(css).contains(".app-card{padding:18px}");
        assertThat(css).contains(".app-btn{min-height:44px}");
    }

    @Test
    void cartRowsBecomeMobileCards() throws Exception {
        String html = read("src/main/resources/templates/cart.html");

        assertThat(html).contains("@media (max-width: 720px)");
        assertThat(html).contains(".grid.head{display:none}");
        assertThat(html).contains(".grid.row{grid-template-columns:28px minmax(0,1fr)");
        assertThat(html).contains(".footer{align-items:stretch;flex-direction:column}");
    }

    @Test
    void orderDetailStacksMetadataAndActions() throws Exception {
        String html = read("src/main/resources/templates/orderview.html");

        assertThat(html).contains("@media (max-width: 720px)");
        assertThat(html).contains(".meta{grid-template-columns:1fr}");
        assertThat(html).contains(".actions{align-items:stretch;flex-direction:column}");
    }

    @Test
    void administratorTablesUseLocalOverflow() throws Exception {
        String inventory = read("src/main/resources/templates/admin/inventory.html");
        String replenishment = read("src/main/resources/templates/admin/replenishment-requests.html");
        String shipping = read("src/main/resources/templates/admin/orders.html");

        assertThat(inventory).contains(".table-wrap{overflow-x:auto}");
        assertThat(inventory).contains(".inventory-table{min-width:620px}");
        assertThat(replenishment).contains(".table-wrap{overflow-x:auto}");
        assertThat(shipping).contains(".table-wrap{overflow-x:auto}");
        assertThat(shipping).contains(".bulk-actions{align-items:stretch;width:100%}");
    }

    @Test
    void catalogActionsStackAtTabletWidth() throws Exception {
        String html = read("src/main/resources/templates/main.html");

        assertThat(html).contains(".product-actions{grid-template-columns:1fr}");
        assertThat(html).contains(".product-actions .btn{width:100%;min-width:0}");
    }

    @Test
    void loginCardStaysWithinBodyPadding() throws Exception {
        String html = read("src/main/resources/templates/home.html");

        assertThat(html).contains("width:min(100%,480px)");
        assertThat(html).doesNotContain("width:min(94vw,480px)");
    }

    @Test
    void signupCardStaysWithinBodyPadding() throws Exception {
        String html = read("src/main/resources/templates/signup.html");

        assertThat(html).contains("width:min(100%,680px)");
        assertThat(html).doesNotContain("width:min(94vw,680px)");
    }

    @Test
    void cartMobileRowsKeepQuantityAndPricesLabeled() throws Exception {
        String html = read("src/main/resources/templates/cart.html");

        assertThat(html).contains("<span class=\"mobile-label\">수량</span>");
        assertThat(html).contains("<span class=\"mobile-label\">단가</span>");
        assertThat(html).contains("<div class=\"line-total\"><span class=\"mobile-label\">합계</span><span data-line-total");
        assertThat(html).doesNotContain("<div class=\"line-total\" data-line-total>");
        assertThat(html).contains(".mobile-label{display:none}");
        assertThat(html).contains(".mobile-label{display:block}");
    }
}
