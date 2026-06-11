package com.jhg.hgpage.template;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CartTemplateThemeTest {

    @Test
    void cartTemplateUsesMainThemeTokens() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/templates/cart.html"), StandardCharsets.UTF_8);

        assertThat(html).contains("--bg1:#fff7ed; --bg2:#fde7d8; --bg3:#fae8ff;");
        assertThat(html).contains("radial-gradient(1200px 700px at 80% 10%, var(--bg3) 0%, transparent 60%)");
        assertThat(html).contains("background:conic-gradient(from 210deg, #ffd7ba, #f7b267, #e76f51, #ffd7ba)");
        assertThat(html).contains("@media (prefers-color-scheme: dark)");
    }
}
