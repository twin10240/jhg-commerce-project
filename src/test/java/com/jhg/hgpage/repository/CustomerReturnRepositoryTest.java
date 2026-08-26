package com.jhg.hgpage.repository;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.dto.AdminCustomerReturnDto;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(CustomerReturnService.class)
class CustomerReturnRepositoryTest {

    @Autowired CustomerReturnRepository customerReturnRepository;
    @Autowired CustomerReturnService customerReturnService;
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

    @Test
    void 관리자_상세조회는_승인대기를_우선하고_상태필터와_연관엔티티를_적용한다() {
        CustomerReturn olderPending = saveCustomerReturn(false);
        CustomerReturn newerPending = saveCustomerReturn(false);
        CustomerReturn submitted = saveCustomerReturn(true);
        em.getEntityManager().createNativeQuery("update customer_return set requested_at = '2026-01-01 00:00:00' where customer_return_id = :id")
                .setParameter("id", olderPending.getId()).executeUpdate();
        em.getEntityManager().createNativeQuery("update customer_return set requested_at = '2026-01-02 00:00:00' where customer_return_id = :id")
                .setParameter("id", newerPending.getId()).executeUpdate();
        em.flush();
        em.clear();

        List<CustomerReturn> all = customerReturnRepository.findAllDetailedForAdmin(null);
        List<CustomerReturn> pending = customerReturnRepository
                .findAllDetailedForAdmin(CustomerReturnStatus.PENDING_APPROVAL);

        assertThat(all).extracting(CustomerReturn::getId)
                .containsExactly(olderPending.getId(), newerPending.getId(), submitted.getId());
        assertThat(pending).extracting(CustomerReturn::getId)
                .containsExactly(olderPending.getId(), newerPending.getId());
        CustomerReturn found = pending.get(0);
        assertThat(Hibernate.isInitialized(found.getOrder())).isTrue();
        assertThat(Hibernate.isInitialized(found.getOrder().getMember())).isTrue();
        assertThat(Hibernate.isInitialized(found.getItems())).isTrue();
        assertThat(Hibernate.isInitialized(found.getItems().get(0).getOrderItem())).isTrue();
        assertThat(Hibernate.isInitialized(found.getItems().get(0).getOrderItem().getProduct())).isTrue();
    }

    @Test
    void 관리자_상세조회는_여러_요청품목도_반품_한건으로_반환한다() {
        CustomerReturn customerReturn = saveMultiItemCustomerReturn();
        em.clear();

        List<AdminCustomerReturnDto> found = customerReturnService
                .findAllForAdmin(CustomerReturnStatus.PENDING_APPROVAL);

        assertThat(found).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo(customerReturn.getId());
            assertThat(value.items()).hasSize(2);
        });
    }

    private CustomerReturn saveCustomerReturn() {
        return saveCustomerReturn(true);
    }

    private CustomerReturn saveCustomerReturn(boolean approved) {
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
        if (approved) {
            customerReturn.approve("admin@example.com");
        }
        em.persistAndFlush(customerReturn);
        return customerReturn;
    }

    private CustomerReturn saveMultiItemCustomerReturn() {
        Product product = new Product();
        product.setName("다품목 상품");
        product.setPrice(10000);
        em.persist(product);
        Member member = Member.createUser("다품목 테스터", "010-0000-0001", new Address("서울", "관악구", "500"));
        em.persist(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem first = OrderItem.createOrderItem(product, product.getPrice(), 1);
        OrderItem second = OrderItem.createOrderItem(product, product.getPrice(), 1);
        Order order = Order.createOrder(member, delivery, first, second);
        order.ship();
        order.deliver();
        em.persist(order);
        em.flush();

        CustomerReturn customerReturn = CustomerReturn.create(order, UUID.randomUUID(), "불량",
                List.of(new CustomerReturn.RequestItem(first, 1), new CustomerReturn.RequestItem(second, 1)));
        em.persistAndFlush(customerReturn);
        return customerReturn;
    }
}
