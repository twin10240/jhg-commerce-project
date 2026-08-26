package com.jhg.hgpage.repository;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CustomerReturnRepositoryTest {

    @Autowired CustomerReturnRepository customerReturnRepository;
    @Autowired TestEntityManager em;

    @Test
    void 상세조회는_반품과_주문상품을_함께_가져온다() {
        CustomerReturn customerReturn = saveCustomerReturn();
        em.clear();

        CustomerReturn found = customerReturnRepository.findDetailedById(customerReturn.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getOrder())).isTrue();
        assertThat(Hibernate.isInitialized(found.getItems())).isTrue();
        assertThat(Hibernate.isInitialized(found.getItems().get(0).getOrderItem())).isTrue();
        assertThat(Hibernate.isInitialized(found.getItems().get(0).getOrderItem().getProduct())).isTrue();
        assertThat(customerReturnRepository.findDetailedByRequestKey(found.getRequestKey())).contains(found);
        assertThat(customerReturnRepository.findDetailedByOrderId(found.getOrder().getId())).contains(found);
        assertThat(customerReturnRepository.findDetailedByStatusIn(List.of(CustomerReturnStatus.PENDING_SUBMISSION)))
                .contains(found);
    }

    @Test
    void 반품상태와처분은_문자열로_저장된다() {
        CustomerReturn customerReturn = saveCustomerReturn();
        customerReturn.markRequested(1L);
        customerReturn.complete(List.of(new CustomerReturn.ResultItem(
                customerReturn.getItems().get(0).getOrderItem().getId(), 1,
                com.jhg.hgpage.oms.domain.enums.ReturnDisposition.RESTOCKED)));
        em.flush();

        Object status = em.getEntityManager()
                .createNativeQuery("select status from customer_return where customer_return_id = :id")
                .setParameter("id", customerReturn.getId())
                .getSingleResult();
        Object disposition = em.getEntityManager()
                .createNativeQuery("select disposition from customer_return_item where customer_return_id = :id")
                .setParameter("id", customerReturn.getId())
                .getSingleResult();

        assertThat(status).isEqualTo("COMPLETED");
        assertThat(disposition).isEqualTo("RESTOCKED");
    }

    private CustomerReturn saveCustomerReturn() {
        Product product = new Product();
        product.setName("상품");
        product.setPrice(10000);
        em.persist(product);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        em.persist(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem orderItem = OrderItem.createOrderItem(product, product.getPrice(), 2);
        Order order = Order.createOrder(member, delivery, orderItem);
        order.ship();
        order.deliver();
        em.persist(order);
        em.flush();

        CustomerReturn customerReturn = CustomerReturn.create(order, UUID.randomUUID(), "불량",
                List.of(new CustomerReturn.RequestItem(orderItem, 2)));
        customerReturn.approve("admin@example.com");
        em.persistAndFlush(customerReturn);
        return customerReturn;
    }
}
