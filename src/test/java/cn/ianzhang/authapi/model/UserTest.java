package cn.ianzhang.authapi.model;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
@Disabled
class UserTest {

    @Test
    void defaultConstructorCreatesUserWithNullFields() {
        User user = new User();
        assertNull(user.getUsername());
        assertNull(user.getPassword());
        assertNull(user.getEmail());
    }

    @Test
    void constructorWithParametersSetsFieldsCorrectly() {
        User user = new User("testuser", "password123", "test@example.com");
        assertEquals("testuser", user.getUsername());
        assertEquals("password123", user.getPassword());
        assertEquals("test@example.com", user.getEmail());
    }

    @Test
    void setUsernameUpdatesUsername() {
        User user = new User();
        user.setUsername("newuser");
        assertEquals("newuser", user.getUsername());
    }

    @Test
    void setPasswordUpdatesPassword() {
        User user = new User();
        user.setPassword("newpass");
        assertEquals("newpass", user.getPassword());
    }

    @Test
    void setEmailUpdatesEmail() {
        User user = new User();
        user.setEmail("new@example.com");
        assertEquals("new@example.com", user.getEmail());
    }

    @Test
    void equalsReturnsTrueForSameObject() {
        User user = new User("testuser", "password123", "test@example.com");
        assertTrue(user.equals(user));
    }

    @Test
    void equalsReturnsFalseForNull() {
        User user = new User("testuser", "password123", "test@example.com");
        assertFalse(user.equals(null));
    }

    @Test
    void equalsReturnsFalseForDifferentClass() {
        User user = new User("testuser", "password123", "test@example.com");
        assertFalse(user.equals("string"));
    }

    @Test
    void equalsReturnsTrueForUsersWithSameUsernameAndEmail() {
        User user1 = new User("testuser", "password123", "test@example.com");
        User user2 = new User("testuser", "differentpass", "test@example.com");
        assertTrue(user1.equals(user2));
    }

    @Test
    void equalsReturnsFalseForUsersWithDifferentUsername() {
        User user1 = new User("testuser", "password123", "test@example.com");
        User user2 = new User("otheruser", "password123", "test@example.com");
        assertFalse(user1.equals(user2));
    }

    @Test
    void equalsReturnsFalseForUsersWithDifferentEmail() {
        User user1 = new User("testuser", "password123", "test@example.com");
        User user2 = new User("testuser", "password123", "other@example.com");
        assertFalse(user1.equals(user2));
    }

    @Test
    void hashCodeIsConsistentForSameUsernameAndEmail() {
        User user1 = new User("testuser", "password123", "test@example.com");
        User user2 = new User("testuser", "differentpass", "test@example.com");
        assertEquals(user1.hashCode(), user2.hashCode());
    }

    @Test
    void hashCodeDiffersForDifferentUsername() {
        User user1 = new User("testuser", "password123", "test@example.com");
        User user2 = new User("otheruser", "password123", "test@example.com");
        assertNotEquals(user1.hashCode(), user2.hashCode());
    }

    @Test
    void hashCodeDiffersForDifferentEmail() {
        User user1 = new User("testuser", "password123", "test@example.com");
        User user2 = new User("testuser", "password123", "other@example.com");
        assertNotEquals(user1.hashCode(), user2.hashCode());
    }

    @Test
    void toStringIncludesUsernameAndEmail() {
        User user = new User("testuser", "password123", "test@example.com");
        String expected = "User{username='testuser', email='test@example.com'}";
        assertEquals(expected, user.toString());
    }

    @Test
    void toStringHandlesNullUsername() {
        User user = new User(null, "password123", "test@example.com");
        String expected = "User{username='null', email='test@example.com'}";
        assertEquals(expected, user.toString());
    }

    @Test
    void toStringHandlesNullEmail() {
        User user = new User("testuser", "password123", null);
        String expected = "User{username='testuser', email='null'}";
        assertEquals(expected, user.toString());
    }
}
