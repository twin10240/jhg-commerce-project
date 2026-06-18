package com.jhg.hgpage.controller.auth;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.oms.domain.Account;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.exception.DuplicateEmailException;
import com.jhg.hgpage.oms.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 회원가입 컨트롤러 슬라이스 테스트.
 * 가입은 단일 서비스 호출(signUp(member, account))로 위임되어야 하고(원자성),
 * 이메일 중복은 에러 페이지가 아닌 signup 폼의 필드 에러로 안내되어야 한다.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean AccountService accountService;
    @MockBean PasswordEncoder passwordEncoder;

    private MockHttpServletRequestBuilder signUpRequest() {
        return post("/signup")
                .with(anonymous())
                .with(csrf())
                .param("email", "user@example.com")
                .param("password", "1111")
                .param("passwordConfirm", "1111")
                .param("name", "테스터")
                .param("phone", "010-0000-0000")
                .param("city", "서울")
                .param("street", "관악구")
                .param("zipcode", "500");
    }

    @Test
    void 회원가입에_성공하면_홈으로_리다이렉트한다() throws Exception {
        when(passwordEncoder.encode("1111")).thenReturn("encoded-password");

        mockMvc.perform(signUpRequest())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        verify(accountService).signUp(any(Member.class), any(Account.class));
    }

    @Test
    void 이메일이_중복이면_signup_폼으로_돌아가고_email_필드에러를_보여준다() throws Exception {
        when(passwordEncoder.encode("1111")).thenReturn("encoded-password");
        when(accountService.signUp(any(Member.class), any(Account.class)))
                .thenThrow(new DuplicateEmailException("user@example.com"));

        mockMvc.perform(signUpRequest())
                .andExpect(status().isOk())
                .andExpect(view().name("signup"))
                .andExpect(model().attributeHasFieldErrors("signUpForm", "email"));
    }

    @Test
    void 비밀번호와_확인이_다르면_passwordConfirm_필드에러를_보여주고_가입하지_않는다() throws Exception {
        mockMvc.perform(post("/signup")
                        .with(anonymous())
                        .with(csrf())
                        .param("email", "user@example.com")
                        .param("password", "1111")
                        .param("passwordConfirm", "2222")
                        .param("name", "테스터")
                        .param("phone", "010-0000-0000")
                        .param("city", "서울")
                        .param("street", "관악구")
                        .param("zipcode", "500"))
                .andExpect(status().isOk())
                .andExpect(view().name("signup"))
                .andExpect(model().attributeHasFieldErrors("signUpForm", "passwordConfirm"));

        verify(accountService, never()).signUp(any(Member.class), any(Account.class));
    }

    @Test
    void 이름_연락처_주소가_비어있으면_필드에러를_보여주고_가입하지_않는다() throws Exception {
        mockMvc.perform(post("/signup")
                        .with(anonymous())
                        .with(csrf())
                        .param("email", "user@example.com")
                        .param("password", "1111")
                        .param("passwordConfirm", "1111")
                        .param("name", "")
                        .param("phone", "")
                        .param("city", "")
                        .param("street", "")
                        .param("zipcode", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("signup"))
                .andExpect(model().attributeHasFieldErrors("signUpForm",
                        "name", "phone", "city", "street", "zipcode"));

        verify(accountService, never()).signUp(any(Member.class), any(Account.class));
    }

    @Test
    void 필수값이_비어있으면_가입을_시도하지_않는다() throws Exception {
        mockMvc.perform(post("/signup")
                        .with(anonymous())
                        .with(csrf())
                        .param("email", "")
                        .param("password", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("signup"))
                .andExpect(model().attributeHasFieldErrors("signUpForm", "email", "password"));

        verify(accountService, never()).signUp(any(Member.class), any(Account.class));
        verify(passwordEncoder, never()).encode(anyString());
    }
}
