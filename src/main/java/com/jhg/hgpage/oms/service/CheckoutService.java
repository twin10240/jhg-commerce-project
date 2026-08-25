package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final MemberService memberService;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CartService cartService;

    @Transactional
    public CheckoutResult createPending(Long memberId, Address address,
                                        List<OrderService.OrderLine> lines, boolean fromCart) {
        var member = memberService.findMember(memberId);
        Delivery delivery = new Delivery();
        delivery.setAddress(address);

        var products = productRepository.findAllById(lines.stream().map(OrderService.OrderLine::productId).toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        OrderItem[] items = lines.stream().map(line -> {
            Product product = products.get(line.productId());
            if (product == null) {
                throw new EntityNotFoundException("Product", line.productId());
            }
            return OrderItem.createOrderItem(product, product.getPrice(), line.quantity());
        }).toArray(OrderItem[]::new);

        Order order = Order.createOrder(member, delivery, items);
        order.markPaymentPending();
        orderRepository.save(order);
        paymentRepository.save(Payment.create(order, order.getTotalPrice()));

        if (fromCart) {
            cartService.removeCartItems(memberId, lines.stream().map(OrderService.OrderLine::productId).toList());
        }
        return new CheckoutResult(order.getId());
    }

    public record CheckoutResult(Long orderId) {
    }
}
