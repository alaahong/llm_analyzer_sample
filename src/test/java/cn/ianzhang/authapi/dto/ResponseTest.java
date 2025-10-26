package cn.ianzhang.authapi.dto;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Disabled
class ResponseTest {

    @Test
    void constructorSetsFieldsCorrectly() {
        Response<String> response = new Response<>(true, "message", "data");
        assertTrue(response.isSuccess());
        assertEquals("message", response.getMessage());
        assertEquals("data", response.getData());
    }

    @Test
    void setSuccessUpdatesSuccess() {
        Response<String> response = new Response<>(false, "message", "data");
        response.setSuccess(true);
        assertTrue(response.isSuccess());
    }

    @Test
    void setMessageUpdatesMessage() {
        Response<String> response = new Response<>(true, "old", "data");
        response.setMessage("new");
        assertEquals("new", response.getMessage());
    }

    @Test
    void setDataUpdatesData() {
        Response<String> response = new Response<>(true, "message", "old");
        response.setData("new");
        assertEquals("new", response.getData());
    }

    @Test
    void successWithDataCreatesResponseWithSuccessTrueAndMessageSuccess() {
        Response<String> response = Response.success("data");
        assertTrue(response.isSuccess());
        assertEquals("success", response.getMessage());
        assertEquals("data", response.getData());
    }

    @Test
    void successWithMessageAndDataCreatesResponseWithSuccessTrueAndSpecifiedMessage() {
        Response<String> response = Response.success("custom", "data");
        assertTrue(response.isSuccess());
        assertEquals("custom", response.getMessage());
        assertEquals("data", response.getData());
    }

    @Test
    void failCreatesResponseWithSuccessFalseAndSpecifiedMessageAndNullData() {
        Response<String> response = Response.fail("error");
        assertFalse(response.isSuccess());
        assertEquals("error", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void successWithNullData() {
        Response<String> response = Response.success(null);
        assertTrue(response.isSuccess());
        assertEquals("success", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void successWithMessageAndNullData() {
        Response<String> response = Response.success("message", null);
        assertTrue(response.isSuccess());
        assertEquals("message", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void failWithNullMessage() {
        Response<String> response = Response.fail(null);
        assertFalse(response.isSuccess());
        assertNull(response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void successWithEmptyStringData() {
        Response<String> response = Response.success("");
        assertTrue(response.isSuccess());
        assertEquals("success", response.getMessage());
        assertEquals("", response.getData());
    }

    @Test
    void successWithMessageAndEmptyStringData() {
        Response<String> response = Response.success("message", "");
        assertTrue(response.isSuccess());
        assertEquals("message", response.getMessage());
        assertEquals("", response.getData());
    }

    @Test
    void failWithEmptyStringMessage() {
        Response<String> response = Response.fail("");
        assertFalse(response.isSuccess());
        assertEquals("", response.getMessage());
        assertNull(response.getData());
    }
}
