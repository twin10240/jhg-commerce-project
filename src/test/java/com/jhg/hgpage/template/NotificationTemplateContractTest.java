package com.jhg.hgpage.template;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTemplateContractTest {

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void userNavigationContainsOneAccessibleNotificationPanel() throws Exception {
        String layout = read("src/main/resources/templates/fragments/layout.html");

        assertThat(layout).containsOnlyOnce("data-notification-trigger");
        assertThat(layout).contains(
                "sec:authorize=\"hasRole('USER')\"",
                "aria-expanded=\"false\"",
                "aria-controls=\"notification-panel\"",
                "data-notification-badge hidden",
                "id=\"notification-panel\"",
                "role=\"region\"",
                "data-notification-items",
                "th:href=\"@{/notifications}\"",
                ">알림<");
        assertThat(layout).doesNotContain("app-card");
    }

    @Test
    void notificationAssetsAndBootstrapStayUserOnlyAndSecretFree() throws Exception {
        String layout = read("src/main/resources/templates/fragments/layout.html");
        String client = read("src/main/resources/static/js/notification-client.js");

        assertThat(layout).contains(
                "th:href=\"@{/css/notifications.css}\"",
                "th:src=\"|${realtimePublicUrl}/socket.io/socket.io.js|\"",
                "th:src=\"@{/js/notification-client.js}\"");
        assertThat(layout).doesNotContain("data-jwt", "data-member", "data-room");
        assertThat(client).doesNotContain("localStorage", "sessionStorage", "memberId", "roomName");
    }

    @Test
    void notificationStylesKeepPanelCompactAndMobileSafe() throws Exception {
        String css = read("src/main/resources/static/css/notifications.css");

        assertThat(css).contains(
                "max-width:360px",
                "border-radius:8px",
                "min-width:2.25em",
                "@media(max-width:720px)",
                "width:calc(100vw - 32px)");
    }
}
