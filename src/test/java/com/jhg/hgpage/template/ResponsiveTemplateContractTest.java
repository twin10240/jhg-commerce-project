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
}
