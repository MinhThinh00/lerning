package com.shop.user.domain.repository;

import com.shop.user.domain.model.UserProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Port: domain chỉ biết interface này, không biết JPA. Implement ở infrastructure.persistence. */
public interface UserProfileRepository {

    Optional<UserProfile> findById(String id);

    List<UserProfile> findByIdIn(Collection<String> ids);

    UserProfile save(UserProfile profile);

    /** Ghi xuống DB ngay để bắt vi phạm khóa chính tại chỗ (race khi tạo profile lần đầu). */
    UserProfile saveAndFlush(UserProfile profile);

    void deleteById(String id);
}
