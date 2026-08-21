package com.jhg.hgpage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HgpageApplicationTests {

	@Autowired ServerProperties serverProperties;
	@Autowired Environment environment;

	@Test
	void contextLoads() {
	}

	@Test
	void 세션_쿠키는_OMS_전용_이름을_사용한다() {
		assertThat(serverProperties.getServlet().getSession().getCookie().getName())
				.isEqualTo("OMSSESSION");
	}

	@Test
	void 결제_할당_환불_취소_작업자의_지연과_처리_제한시간은_명시적으로_설정된다() {
		assertThat(environment.getProperty("payments.sweep-delay")).isEqualTo("5s");
		assertThat(environment.getProperty("payments.processing-timeout")).isEqualTo("5m");
		assertThat(environment.getProperty("allocation.sweep-delay")).isEqualTo("5s");
		assertThat(environment.getProperty("allocation.processing-timeout")).isEqualTo("5m");
		assertThat(environment.getProperty("refunds.sweep-delay")).isEqualTo("5s");
		assertThat(environment.getProperty("refunds.processing-timeout")).isEqualTo("5m");
		assertThat(environment.getProperty("cancellations.sweep-delay")).isEqualTo("5s");
		assertThat(environment.getProperty("cancellations.processing-timeout")).isEqualTo("5m");
	}

}
