package com.jhg.hgpage.domain;

import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order 낙관적 락 검증(리뷰 B5 — 취소 vs 백오더 승격 경합으로 잔존 예약 방어).
 * 임베디드 H2로 실제 JPA 버전 관리 동작을 확인한다. (InventoryOptimisticLockTest와 동일 패턴)
 */
@DataJpaTest
class OrderOptimisticLockTest {

    @Autowired TestEntityManager em;

    private static final Address ADDRESS = new Address("서울", "관악구", "500");

    private Long persistOrder() {
        Member member = Member.createUser("테스터", "010-0000-0000", ADDRESS);
        em.persist(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(ADDRESS);
        Order order = Order.createOrder(member, delivery);
        Long id = em.persistAndFlush(order).getId();
        em.clear();
        return id;
    }

    @Test
    void 주문을_수정하면_version이_증가한다() {
        Long id = persistOrder();

        Order order = em.find(Order.class, id);
        long before = order.getVersion();

        order.cancel();
        em.flush();

        assertThat(order.getVersion()).isEqualTo(before + 1);
    }

    @Test
    void 다른_수정이_먼저_커밋되면_늦은_수정은_충돌예외가_발생한다() {
        Long id = persistOrder();

        // 사용자 A(취소 요청): 주문을 읽어둔 채 대기 (detached, stale version)
        // cancel()이 delivery(LAZY)를 참조하므로, detach 전에 미리 초기화해 둔다.
        Order stale = em.find(Order.class, id);
        stale.getDelivery().getStatus();
        em.clear();

        // 사용자 B(백오더 승격 등): 먼저 상태를 바꾸고 커밋 → version 증가
        Order winner = em.find(Order.class, id);
        winner.markBackordered();
        em.flush();
        em.clear();

        // 사용자 A: 낡은 version으로 취소 시도 → 충돌
        stale.cancel();
        assertThatThrownBy(() -> {
            em.getEntityManager().merge(stale);
            em.flush();
        }).isInstanceOf(OptimisticLockException.class);
    }
}
