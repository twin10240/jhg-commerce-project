package com.jhg.hgpage.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@AllArgsConstructor
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
    private Cart cart;

    @OneToMany(mappedBy = "member")
    private List<Order> orders = new ArrayList<Order>();

    public Member() {}

    public Member(String name, String phone, Address address) {
        this.name = name;
        this.phone = phone;
        this.address = address;
    }
}
