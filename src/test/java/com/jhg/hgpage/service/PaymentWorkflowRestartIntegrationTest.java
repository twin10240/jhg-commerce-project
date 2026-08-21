package com.jhg.hgpage.service;

import com.jhg.hgpage.HgpageApplication;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.repository.MemberRepository;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.PaymentAttemptRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.service.CheckoutService;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.service.PaymentApprovalProcessor;
import com.jhg.hgpage.oms.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PaymentWorkflowRestartIntegrationTest {

    @Test
    void 종료한_writer와_새_reader_worker가_같은_DB에서_미완료_결제를_복구한다() {
        String databaseUrl = "jdbc:h2:mem:payment-restart-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Long orderId;
        Long attemptId;

        try (ConfigurableApplicationContext writer = context(databaseUrl)) {
            Long memberId = writer.getBean(MemberRepository.class)
                    .findMemberByEmail("twin10240@naver.com").getId();
            Long productId = writer.getBean(ProductRepository.class).findAll().get(0).getId();
            CheckoutService.CheckoutResult pending = writer.getBean(CheckoutService.class).createPending(
                    memberId, new Address("서울", "관악구", "500"),
                    List.of(new OrderService.OrderLine(productId, 1)), false);
            orderId = pending.orderId();
            attemptId = pending.attemptId();
            assertThat(writer.getBean(OrderRepository.class).findById(orderId).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.PAYMENT_PENDING);
        }

        try (ConfigurableApplicationContext reader = context(databaseUrl)) {
            PaymentService paymentService = reader.getBean(PaymentService.class);
            assertThat(paymentService.findDueApprovalAttemptIds(LocalDateTime.now())).contains(attemptId);

            reader.getBean(PaymentApprovalProcessor.class).process(attemptId);

            assertThat(reader.getBean(PaymentAttemptRepository.class).findById(attemptId).orElseThrow().getStatus())
                    .isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            assertThat(reader.getBean(PaymentRepository.class).findByOrderId(orderId).orElseThrow())
                    .extracting("status", "orderAmount", "paidAmount", "pendingRefundAmount", "refundedAmount")
                    .containsExactly(PaymentStatus.PAID, 10_000, 10_000, 0, 0);
            assertThat(reader.getBean(OrderRepository.class).findById(orderId).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.ALLOCATION_PENDING);
        }
    }

    private ConfigurableApplicationContext context(String databaseUrl) {
        return new SpringApplicationBuilder(HgpageApplication.class, NoSchedulingConfig.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + databaseUrl,
                        "--spring.jpa.hibernate.ddl-auto=update",
                        "--spring.jpa.defer-datasource-initialization=false",
                        "--spring.sql.init.mode=never",
                        "--spring.flyway.enabled=false");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NoSchedulingConfig {
        @Bean
        TaskScheduler taskScheduler() {
            return mock(TaskScheduler.class);
        }
    }
}
