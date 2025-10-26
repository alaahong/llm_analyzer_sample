package cn.ianzhang.authapi.controller;

import cn.ianzhang.authapi.dto.Response;
import cn.ianzhang.authapi.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/greeting")
public class GreetingController {

    private final UserService userService;

    @Autowired
    public GreetingController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/public")
    public ResponseEntity<Response<String>> publicGreeting() {
        return ResponseEntity.ok(Response.success("Hello, welcome to our service!"));
    }

    @GetMapping("/protected")
    public ResponseEntity<Response<String>> protectedGreeting(AuthHeader authHeader) {
        // 验证会话是否有效
        if (authHeader.getSessionId() == null || !userService.isSessionValid(authHeader.getSessionId())) {
            return ResponseEntity.status(401)
                    .body(Response.fail("请先登录"));
        }

        // 获取用户名并返回个性化问候
        String username = userService.getUsernameBySessionId(authHeader.getSessionId());
        return ResponseEntity.ok(Response.success("Hello, " + username + "! Welcome back!"));
    }
}