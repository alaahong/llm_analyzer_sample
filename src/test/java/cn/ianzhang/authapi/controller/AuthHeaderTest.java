package cn.ianzhang.authapi.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Disabled
public class AuthHeaderTest {

    @Test
    void constructorSetsSessionId() {
        AuthHeader authHeader = new AuthHeader("session123");
        assertEquals("session123", authHeader.getSessionId());
    }

    @Test
    void constructorSetsNullSessionId() {
        AuthHeader authHeader = new AuthHeader(null);
        assertNull(authHeader.getSessionId());
    }

    @Test
    void constructorSetsEmptySessionId() {
        AuthHeader authHeader = new AuthHeader("");
        assertEquals("", authHeader.getSessionId());
    }

    @Test
    void authHeaderResolverSupportsAuthHeaderParameter() throws Exception {
        AuthHeader.AuthHeaderResolver resolver = new AuthHeader.AuthHeaderResolver();
        MethodParameter parameter = mock(MethodParameter.class);
        when(parameter.getParameterType()).thenReturn((Class) AuthHeader.class);
        assertTrue(resolver.supportsParameter(parameter));
    }

    @Test
    void authHeaderResolverDoesNotSupportOtherParameterTypes() throws Exception {
        AuthHeader.AuthHeaderResolver resolver = new AuthHeader.AuthHeaderResolver();
        MethodParameter parameter = mock(MethodParameter.class);
        when(parameter.getParameterType()).thenReturn((Class) String.class);
        assertFalse(resolver.supportsParameter(parameter));
    }

    @Test
    void resolveArgumentExtractsAuthorizationHeader() throws Exception {
        AuthHeader.AuthHeaderResolver resolver = new AuthHeader.AuthHeaderResolver();
        NativeWebRequest webRequest = mock(NativeWebRequest.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(request);
        when(request.getHeader("Authorization")).thenReturn("session123");

        AuthHeader result = (AuthHeader) resolver.resolveArgument(null, null, webRequest, null);
        assertEquals("session123", result.getSessionId());
    }

    @Test
    void resolveArgumentReturnsNullWhenRequestIsNull() throws Exception {
        AuthHeader.AuthHeaderResolver resolver = new AuthHeader.AuthHeaderResolver();
        NativeWebRequest webRequest = mock(NativeWebRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(null);

        AuthHeader result = (AuthHeader) resolver.resolveArgument(null, null, webRequest, null);
        assertNull(result.getSessionId());
    }

    @Test
    void resolveArgumentReturnsNullWhenHeaderIsNull() throws Exception {
        AuthHeader.AuthHeaderResolver resolver = new AuthHeader.AuthHeaderResolver();
        NativeWebRequest webRequest = mock(NativeWebRequest.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(request);
        when(request.getHeader("Authorization")).thenReturn(null);

        AuthHeader result = (AuthHeader) resolver.resolveArgument(null, null, webRequest, null);
        assertNull(result.getSessionId());
    }

    @Test
    void resolveArgumentReturnsEmptyStringWhenHeaderIsEmpty() throws Exception {
        AuthHeader.AuthHeaderResolver resolver = new AuthHeader.AuthHeaderResolver();
        NativeWebRequest webRequest = mock(NativeWebRequest.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(request);
        when(request.getHeader("Authorization")).thenReturn("");

        AuthHeader result = (AuthHeader) resolver.resolveArgument(null, null, webRequest, null);
        assertEquals("", result.getSessionId());
    }
}
