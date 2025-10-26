package cn.ianzhang.authapi;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Disabled
@SpringBootTest
class AuthApiApplicationTest {

    @Test
    void contextLoads() {
        // 测试应用程序上下文能否正常加载
        assertDoesNotThrow(() -> AuthApiApplication.main(new String[]{}));
    }
}