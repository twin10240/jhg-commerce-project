package com.jhg.hgpage.controller.order;

import com.jhg.hgpage.controller.form.CheckOutForm;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.oms.service.MemberService;
import com.jhg.hgpage.oms.service.OrderService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock MemberService memberService;
    @Mock ProductRepository productRepository;
    @Mock OrderService orderService;

    private ValidatorFactory validatorFactory;
    private Validator validator;
    private OrderController orderController;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
        orderController = new OrderController(memberService, productRepository, orderService);
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    void checkout_주문상품이_없으면_주문하지않고_주문상세로_돌아간다() {
        CheckOutForm form = new CheckOutForm();
        form.getDelivery().setCity("서울");
        form.getDelivery().setStreet("관악구");
        form.getDelivery().setZipcode("500");
        BindingResult bindingResult = validate(form);

        String viewName = orderController.checkout(userPrincipal(), form, bindingResult);

        assertThat(viewName).isEqualTo("orderdetail");
        assertThat(bindingResult.hasFieldErrors("product")).isTrue();
        verifyNoInteractions(orderService);
    }

    @Test
    void checkout_정상주문이면_주문하고_메인으로_리다이렉트한다() {
        CheckOutForm form = new CheckOutForm();
        form.getDelivery().setCity("서울");
        form.getDelivery().setStreet("관악구");
        form.getDelivery().setZipcode("500");
        form.getProduct().add(new CheckOutForm.ProductDto(1L, "상품1", 10000, 2));
        BindingResult bindingResult = validate(form);

        String viewName = orderController.checkout(userPrincipal(), form, bindingResult);

        assertThat(bindingResult.hasErrors()).isFalse();
        assertThat(viewName).isEqualTo("redirect:/main");

        ArgumentCaptor<Address> addressCaptor = ArgumentCaptor.forClass(Address.class);
        ArgumentCaptor<List<OrderService.OrderLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderService).order(eq(1L), addressCaptor.capture(), linesCaptor.capture());

        assertThat(addressCaptor.getValue().getCity()).isEqualTo("서울");
        assertThat(addressCaptor.getValue().getStreet()).isEqualTo("관악구");
        assertThat(addressCaptor.getValue().getZipcode()).isEqualTo("500");

        List<OrderService.OrderLine> lines = linesCaptor.getValue();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).productId()).isEqualTo(1L);
        assertThat(lines.get(0).quantity()).isEqualTo(2);
    }

    private BindingResult validate(CheckOutForm form) {
        BindingResult bindingResult = new BeanPropertyBindingResult(form, "checkout");
        Set<ConstraintViolation<CheckOutForm>> violations = validator.validate(form);

        for (ConstraintViolation<CheckOutForm> violation : violations) {
            bindingResult.addError(new FieldError(
                    "checkout",
                    violation.getPropertyPath().toString(),
                    violation.getMessage()
            ));
        }

        return bindingResult;
    }

    private UserPrincipal userPrincipal() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }
}
