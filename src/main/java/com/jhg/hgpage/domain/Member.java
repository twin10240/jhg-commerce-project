package com.jhg.hgpage.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {
    @Id @GeneratedValue
    @Column(name = "member_id")
    private Long id;
    @Column(nullable=false, length=50)
    private String name;
    @Column(length=20)
    private String phone;
    @Embedded
    private Address address;

    @OneToOne(mappedBy = "member", fetch = FetchType.LAZY)
    private Account account;

    @JsonIgnore
    @OneToOne(mappedBy = "member", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private Cart cart;

    @OneToMany(mappedBy = "member")
    private List<Order> orders = new ArrayList<Order>();

    public Member(String name, String phone, Address address) {
        this.name = name;
        this.phone = phone;
        this.address = address;
    }

    public void setCart(Cart cart) {
        this.cart = cart;
    }
}
