package com.jhg.hgpage.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReplenishmentCallbackHttpTest {

    @LocalServerPort int port;

    @Test
    void 잘못된_Basic_인증은_로그인_리다이렉트가_아닌_401을_반환한다() throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString("wrong:wrong".getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/replenishments"))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"productIds\":[1]}"))
                .build();

        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }
}
