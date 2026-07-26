package com.jhg.hgpage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.web.ServerProperties;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HgpageApplicationTests {

	@Autowired ServerProperties serverProperties;

	@Test
	void contextLoads() {
	}

	@Test
	void 세션_쿠키는_OMS_전용_이름을_사용한다() {
		assertThat(serverProperties.getServlet().getSession().getCookie().getName())
				.isEqualTo("OMSSESSION");
	}

}
