package cn.ianzhang.authapi.controller;

import cn.ianzhang.authapi.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GreetingController.class)
class GreetingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    void testPublicGreeting() throws Exception {
        mockMvc.perform(get("/api/greeting/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").value("Hello, welcome to our service!"));
    }

    @Test
    void testProtectedGreeting_authenticated() throws Exception {
        String sessionId = "session-test-123";
        String username = "testuser";

        when(userService.isSessionValid(sessionId)).thenReturn(true);
        when(userService.getUsernameBySessionId(sessionId)).thenReturn(username);

        mockMvc.perform(get("/api/greeting/protected")
                .header("Authorization", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("Hello, " + username + "! Welcome back!"));
    }

    @Test
    void testProtectedGreeting_unauthorized() throws Exception {
        // 无Authorization头
        mockMvc.perform(get("/api/greeting/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("请先登录"));

        // 无效的sessionId
        String invalidSessionId = "invalid-session";
        when(userService.isSessionValid(invalidSessionId)).thenReturn(false);

        mockMvc.perform(get("/api/greeting/protected")
                .header("Authorization", invalidSessionId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("请先登录"));
    }
}