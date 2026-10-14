package com.shop.user.infrastructure.persistence;

import com.shop.user.domain.model.UserProfile;
import com.shop.user.domain.repository.UserProfileRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data sinh implementation; đồng thời là implementation của port domain. */
public interface UserProfileJpaRepository extends JpaRepository<UserProfile, String>, UserProfileRepository {
}
