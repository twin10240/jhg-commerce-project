package com.jhg.hgpage;

import com.jhg.hgpage.domain.*;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class initDb {
    private final initService initService;

    @PostConstruct
    public void init() {
        initService.initAccount();
        initService.initProduct();
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    static class initService {
        private final EntityManager em;

        public void initAccount() {
            Member admin = new Member("관리자", "010-1111-2222", new Address("서울", "관악구", "500"));
            em.persist(admin);

            Account adminAccount = Account.createAdminAccount("admin@admin.com",  new BCryptPasswordEncoder(12).encode("1111"), admin);
            em.persist(adminAccount);

            Member member = new Member("조형근", "010-6797-5587", new Address("서울", "관악구", "500"));
            em.persist(member);

            Account account = new Account("twin10240@naver.com", new BCryptPasswordEncoder(12).encode("1111"), member);
            em.persist(account);
        }

        public void initProduct() {
            for (int i = 0; i < 5; i++) {
                Inventory inventory = new Inventory();
                inventory.setOnHandQty(15 * (i + 1));
                inventory.setReservedQty(0);

                Product product = new Product();
                product.setName("상품" + (i + 1));
                product.setPrice(10000 + (i * 1000));
                product.setInventory(inventory);

                em.persist(product);
            }
        }
    }
}
