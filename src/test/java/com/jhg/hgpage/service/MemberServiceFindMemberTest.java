package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.MemberService;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.repository.MemberRepository;
import com.jhg.hgpage.oms.repository.MemberRepositoryQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceFindMemberTest {

    @Mock MemberRepository memberRepository;
    @Mock MemberRepositoryQuery memberRepositoryQuery;
    @InjectMocks MemberService memberService;

    @Test
    void 없는_회원을_조회하면_EntityNotFoundException을_던진다() {
        when(memberRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.findMember(99L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }
}
