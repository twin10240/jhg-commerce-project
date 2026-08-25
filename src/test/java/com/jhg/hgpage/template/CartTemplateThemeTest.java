package com.jhg.hgpage.template;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CartTemplateThemeTest {

    private final Path cartTemplate = Path.of("src/main/resources/templates/cart.html");

    @Test
    void cartTemplateUsesMainThemeTokens() throws Exception {
        String html = Files.readString(cartTemplate, StandardCharsets.UTF_8);

        assertThat(html).contains("--bg1:#fff7ed; --bg2:#fde7d8; --bg3:#fae8ff;");
        assertThat(html).contains("radial-gradient(1200px 700px at 80% 10%, var(--bg3) 0%, transparent 60%)");
        assertThat(html).contains("background:conic-gradient(from 210deg, #ffd7ba, #f7b267, #e76f51, #ffd7ba)");
        assertThat(html).contains("@media (prefers-color-scheme: dark)");
    }

    @Test
    void 수량변경_합계는_바깥의_원_표시와_중복되지_않는다() throws Exception {
        String html = Files.readString(cartTemplate, StandardCharsets.UTF_8);

        assertThat(html).contains("lineTotal.textContent = fmt.format(total);");
        assertThat(html).doesNotContain("lineTotal.textContent = fmt.format(total) + '원';");
    }
}
