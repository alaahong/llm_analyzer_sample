package cn.ianzhang.authapi.dto;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
@Disabled

class LoginRequestTest {

    @Test
    void defaultConstructorCreatesLoginRequestWithNullFields() {
        LoginRequest request = new LoginRequest();
        assertNull(request.getUsername());
        assertNull(request.getPassword());
    }

    @Test
    void setUsernameUpdatesUsername() {
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        assertEquals("testuser", request.getUsername());
    }

    @Test
    void setPasswordUpdatesPassword() {
        LoginRequest request = new LoginRequest();
        request.setPassword("password123");
        assertEquals("password123", request.getPassword());
    }

    @Test
    void setUsernameToNull() {
        LoginRequest request = new LoginRequest();
        request.setUsername(null);
        assertNull(request.getUsername());
    }

    @Test
    void setPasswordToNull() {
        LoginRequest request = new LoginRequest();
        request.setPassword(null);
        assertNull(request.getPassword());
    }

    @Test
    void setUsernameToEmptyString() {
        LoginRequest request = new LoginRequest();
        request.setUsername("");
        assertEquals("", request.getUsername());
    }

    @Test
    void setPasswordToEmptyString() {
        LoginRequest request = new LoginRequest();
        request.setPassword("");
        assertEquals("", request.getPassword());
    }
}
