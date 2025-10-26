package cn.ianzhang.authapi.service;

import cn.ianzhang.authapi.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Disabled
class UserServiceTest {

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService();
    }

    @Test
    void testRegister_success() {
        User user = new User("testuser", "password123", "test@example.com");
        boolean result = userService.register(user);
        assertTrue(result);
    }

    @Test
    void testRegister_usernameExists() {
        User user1 = new User("testuser", "password123", "test1@example.com");
        User user2 = new User("testuser", "password456", "test2@example.com");
        
        assertTrue(userService.register(user1));
        assertFalse(userService.register(user2));
    }

    @Test
    void testLogin_success() {
        User user = new User("testuser", "password123", "test@example.com");
        userService.register(user);
        
        String sessionId = userService.login("testuser", "password123");
        assertNotNull(sessionId);
        assertTrue(sessionId.startsWith("session-"));
    }

    @Test
    void testLogin_invalidCredentials() {
        User user = new User("testuser", "password123", "test@example.com");
        userService.register(user);
        
        // 密码错误
        assertNull(userService.login("testuser", "wrongpassword"));
        // 用户不存在
        assertNull(userService.login("nonexistent", "password123"));
    }

    @Test
    void testSessionManagement() {
        User user = new User("testuser", "password123", "test@example.com");
        userService.register(user);
        
        String sessionId = userService.login("testuser", "password123");
        assertNotNull(sessionId);
        
        // 验证会话有效
        assertTrue(userService.isSessionValid(sessionId));
        assertEquals("testuser", userService.getUsernameBySessionId(sessionId));
        
        // 登出
        userService.logout(sessionId);
        assertFalse(userService.isSessionValid(sessionId));
    }

    @Test
    void testGetUserByUsername() {
        User user = new User("testuser", "password123", "test@example.com");
        userService.register(user);
        
        User retrievedUser = userService.getUserByUsername("testuser");
        assertNotNull(retrievedUser);
        assertEquals("testuser", retrievedUser.getUsername());
        assertEquals("test@example.com", retrievedUser.getEmail());
        assertEquals("password123", retrievedUser.getPassword());
    }
}