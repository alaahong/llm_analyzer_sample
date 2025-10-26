package cn.ianzhang.authapi.dto;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Disabled
class RegisterRequestTest {

    @Test
    void defaultConstructorCreatesRegisterRequestWithNullFields() {
        RegisterRequest request = new RegisterRequest();
        assertNull(request.getUsername());
        assertNull(request.getPassword());
        assertNull(request.getEmail());
    }

    @Test
    void setUsernameUpdatesUsername() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("testuser");
        assertEquals("testuser", request.getUsername());
    }

    @Test
    void setPasswordUpdatesPassword() {
        RegisterRequest request = new RegisterRequest();
        request.setPassword("password123");
        assertEquals("password123", request.getPassword());
    }

    @Test
    void setEmailUpdatesEmail() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@example.com");
        assertEquals("test@example.com", request.getEmail());
    }

    @Test
    void setUsernameToNull() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(null);
        assertNull(request.getUsername());
    }

    @Test
    void setPasswordToNull() {
        RegisterRequest request = new RegisterRequest();
        request.setPassword(null);
        assertNull(request.getPassword());
    }

    @Test
    void setEmailToNull() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(null);
        assertNull(request.getEmail());
    }

    @Test
    void setUsernameToEmptyString() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("");
        assertEquals("", request.getUsername());
    }

    @Test
    void setPasswordToEmptyString() {
        RegisterRequest request = new RegisterRequest();
        request.setPassword("");
        assertEquals("", request.getPassword());
    }

    @Test
    void setEmailToEmptyString() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("");
        assertEquals("", request.getEmail());
    }
}
