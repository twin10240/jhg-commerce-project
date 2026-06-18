package com.jhg.hgpage.domain;

import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery.status가 ORDINAL(숫자)이 아닌 STRING으로 저장되는지 검증.
 * 숫자 저장은 enum 순서 변경 시 기존 데이터의 의미가 바뀌는 위험이 있다.
 */
@DataJpaTest
class DeliveryStatusMappingTest {

    @Autowired TestEntityManager em;

    @Test
    void 배송상태는_문자열로_저장된다() {
        Delivery delivery = new Delivery();
        delivery.setStatus(DeliveryStatus.READY);
        Long id = em.persistAndFlush(delivery).getId();

        Object raw = em.getEntityManager()
                .createNativeQuery("select status from delivery where delivery_id = :id")
                .setParameter("id", id)
                .getSingleResult();

        assertThat(raw).isEqualTo("READY");
    }
}
