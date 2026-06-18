package com.jhg.hgpage.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jhg.hgpage.config.QueryDslConfig;
import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Delivery;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.OrderItem;
import com.jhg.hgpage.catalog.Product;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findOrders는 컬렉션을 메모리에서 페이징하지 않는다(#9 ②).
 * 1:N 컬렉션 fetch join + limit 조합은 Hibernate가 전체를 메모리에 적재한 뒤 잘라내며
 * "HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory" 경고를 남긴다.
 * batch fetch 방식으로 재작성하면 limit이 SQL에 적용되고 이 경고가 사라진다.
 */
@DataJpaTest
@Import({QueryDslConfig.class, OrderRepositoryQuery.class})
class OrderRepositoryInMemoryPagingTest {

    @Autowired OrderRepositoryQuery orderRepositoryQuery;
    @Autowired TestEntityManager em;

    private static final Address ADDRESS = new Address("서울", "관악구", "500");

    private Logger hibernateLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        hibernateLogger = (Logger) LoggerFactory.getLogger("org.hibernate");
        hibernateLogger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.start();
        hibernateLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        hibernateLogger.detachAppender(appender);
    }

    private Product newProduct(String name, int price) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(price);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(100);
        product.setInventory(inventory);
        em.persist(product);
        return product;
    }

    private Member newMember() {
        Member member = Member.createUser("테스터", "010-0000-0000", ADDRESS);
        em.persist(member);
        return member;
    }

    private void saveOrder(Member member, OrderItem... orderItems) {
        Delivery delivery = new Delivery();
        delivery.setAddress(ADDRESS);
        em.persist(Order.createOrder(member, delivery, orderItems));
    }

    @Test
    void findOrders는_컬렉션을_메모리에서_페이징하지_않는다() {
        Member member = newMember();
        Product a = newProduct("상품A", 10000);
        Product b = newProduct("상품B", 20000);
        saveOrder(member,
                OrderItem.createOrderItem(a, a.getPrice(), 2),
                OrderItem.createOrderItem(b, b.getPrice(), 1));
        em.flush();
        em.clear();

        List<Order> orders = orderRepositoryQuery.findOrders(member.getId());

        // 동작 보존: 주문과 모든 라인이 정상 조회된다
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getOrderItems()).hasSize(2);
        assertThat(orders.get(0).getTotalPrice()).isEqualTo(40000);

        // 메모리 페이징 경고가 없어야 한다
        List<String> warnings = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings).noneMatch(m -> m.contains("HHH90003004"));
    }
}
