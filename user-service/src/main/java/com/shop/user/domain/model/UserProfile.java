package com.shop.user.domain.model;

import com.shop.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.Objects;
import org.springframework.data.domain.Persistable;

/**
 * Hồ sơ mở rộng của user. Khóa = sub của Keycloak (do Keycloak sinh, không phải DB).
 *
 * Implement Persistable vì id được gán sẵn: nếu không, Spring Data thấy id != null sẽ coi là
 * entity cũ và gọi merge() (SELECT trước rồi mới INSERT) thay vì persist().
 */
@Entity
@Table(name = "user_profile")
public class UserProfile extends AuditableEntity implements Persistable<String> {

    public static final String DEFAULT_LANGUAGE = "vi";

    @Id
    @Column(name = "sub", length = 36)
    private String id;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "language_key", nullable = false, length = 10)
    private String languageKey = DEFAULT_LANGUAGE;

    @Transient
    private boolean isNew = true;

    protected UserProfile() {
        // JPA
    }

    public static UserProfile create(String id, Identity identity) {
        UserProfile p = new UserProfile();
        p.id = Objects.requireNonNull(id, "id");
        p.applyIdentity(identity);
        return p;
    }

    /**
     * Chép lại các trường danh tính từ Keycloak (nguồn sự thật).
     * @return true nếu có thay đổi (để biết có cần lưu hay không)
     */
    public boolean syncIdentity(Identity identity) {
        Identity current = new Identity(username, email, firstName, lastName);
        if (current.equals(identity)) {
            return false;
        }
        applyIdentity(identity);
        return true;
    }

    public void updateContact(String phoneNumber, String avatarUrl, String languageKey) {
        this.phoneNumber = phoneNumber;
        this.avatarUrl = avatarUrl;
        this.languageKey = languageKey != null ? languageKey : DEFAULT_LANGUAGE;
    }

    private void applyIdentity(Identity identity) {
        this.username = Objects.requireNonNull(identity.username(), "username");
        this.email = identity.email();
        this.firstName = identity.firstName();
        this.lastName = identity.lastName();
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getLanguageKey() {
        return languageKey;
    }

    /** Các trường danh tính có bản gốc ở Keycloak. */
    public record Identity(String username, String email, String firstName, String lastName) {
    }
}
