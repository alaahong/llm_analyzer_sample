package cn.ianzhang.authapi.service;

import cn.ianzhang.authapi.model.User;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserService {
    private final Map<String, User> userStore = new ConcurrentHashMap<>();
    private final Map<String, String> sessionStore = new ConcurrentHashMap<>();

    // 注册新用户
    public boolean register(User user) {
        // 检查用户名是否已存在
        if (userStore.containsKey(user.getUsername())) {
            return false;
        }
        // 存储用户信息
        userStore.put(user.getUsername(), user);
        return true;
    }

    // 用户登录
    public String login(String username, String password) {
        User user = userStore.get(username);
        // 验证用户是否存在且密码正确
        if (user != null && user.getPassword().equals(password)) {
            // 生成简单的会话ID（实际应用中应使用更安全的方式）
            String sessionId = "session-" + System.currentTimeMillis() + "-" + username;
            sessionStore.put(sessionId, username);
            return sessionId;
        }
        return null;
    }

    // 验证会话是否有效
    public boolean isSessionValid(String sessionId) {
        return sessionStore.containsKey(sessionId);
    }

    // 根据会话ID获取用户名
    public String getUsernameBySessionId(String sessionId) {
        return sessionStore.get(sessionId);
    }

    // 用户登出
    public void logout(String sessionId) {
        sessionStore.remove(sessionId);
    }

    // 根据用户名获取用户信息
    public User getUserByUsername(String username) {
        return userStore.get(username);
    }
}