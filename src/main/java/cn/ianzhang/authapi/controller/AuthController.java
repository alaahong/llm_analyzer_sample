package cn.ianzhang.authapi.controller;

import cn.ianzhang.authapi.dto.LoginRequest;
import cn.ianzhang.authapi.dto.RegisterRequest;
import cn.ianzhang.authapi.dto.Response;
import cn.ianzhang.authapi.model.User;
import cn.ianzhang.authapi.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    @Autowired
    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<Response<String>> register(@RequestBody RegisterRequest request) {
        // 验证请求参数
        if (request.getUsername() == null || request.getPassword() == null || request.getEmail() == null) {
            return ResponseEntity.badRequest()
                    .body(Response.fail("用户名、密码和邮箱不能为空"));
        }

        // 创建用户对象
        User user = new User(request.getUsername(), request.getPassword(), request.getEmail());

        // 注册用户
        boolean registered = userService.register(user);
        if (registered) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Response.success("注册成功", "注册成功"));
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Response.fail("用户名已存在"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Response<String>> login(@RequestBody LoginRequest request) {
        // 验证请求参数
        if (request.getUsername() == null || request.getPassword() == null) {
            return ResponseEntity.badRequest()
                    .body(Response.fail("用户名和密码不能为空"));
        }

        // 用户登录
        String sessionId = userService.login(request.getUsername(), request.getPassword());
        if (sessionId != null) {
            return ResponseEntity.ok()
                    .header("Authorization", sessionId)
                    .body(Response.success("登录成功", sessionId));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Response.fail("用户名或密码错误"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Response<String>> logout(AuthHeader authHeader) {
        if (authHeader.getSessionId() != null) {
            userService.logout(authHeader.getSessionId());
        }
        return ResponseEntity.ok(Response.success("登出成功", "登出成功"));
    }
}
