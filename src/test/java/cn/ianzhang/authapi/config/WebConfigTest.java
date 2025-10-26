package cn.ianzhang.authapi.config;

import cn.ianzhang.authapi.controller.AuthHeader;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class WebConfigTest {

    @Test
    void addArgumentResolversAddsAuthHeaderResolver() {
        WebConfig webConfig = new WebConfig();
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
        webConfig.addArgumentResolvers(resolvers);
        assertEquals(1, resolvers.size());
        assertTrue(resolvers.get(0) instanceof AuthHeader.AuthHeaderResolver);
    }

    @Test
    void addArgumentResolversAddsToExistingResolvers() {
        WebConfig webConfig = new WebConfig();
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
        resolvers.add(mock(HandlerMethodArgumentResolver.class));
        webConfig.addArgumentResolvers(resolvers);
        assertEquals(2, resolvers.size());
        assertTrue(resolvers.get(1) instanceof AuthHeader.AuthHeaderResolver);
    }
}
