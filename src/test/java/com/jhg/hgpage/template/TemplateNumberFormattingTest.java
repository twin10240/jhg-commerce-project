package com.jhg.hgpage.template;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateNumberFormattingTest {

    private static final Pattern PADDED_INTEGER = Pattern.compile(
            "formatInteger\\([^,]+,\\s*[2-9][0-9]*\\s*,\\s*'COMMA'");

    @Test
    void 화면의_0원과_0개는_앞자리에_0을_채우지_않는다() throws Exception {
        try (var templates = Files.walk(Path.of("src/main/resources/templates"))) {
            assertThat(templates.filter(path -> path.toString().endsWith(".html")))
                    .allSatisfy(path -> assertThat(Files.readString(path))
                            .as(path.toString())
                            .doesNotMatch("(?s).*" + PADDED_INTEGER.pattern() + ".*"));
        }
    }
}
