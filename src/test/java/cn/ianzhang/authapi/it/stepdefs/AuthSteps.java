package cn.ianzhang.authapi.it.stepdefs;

import cn.ianzhang.authapi.dto.LoginRequest;
import cn.ianzhang.authapi.dto.RegisterRequest;
import cn.ianzhang.authapi.service.UserService;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

public class AuthSteps {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestContext ctx;

    @Autowired
    private UserService userService;

    @When("I register with username {string}, password {string} and email {string}")
    public void iRegisterWith(String username, String password, String email) throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setPassword(password);
        req.setEmail(email);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> httpEntity = new HttpEntity<>(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(req), headers);
        ctx.lastResponse = restTemplate.exchange("/api/auth/register", HttpMethod.POST, httpEntity, String.class);
    }

    @Given("a registered user {string} with password {string} and email {string}")
    public void aRegisteredUser(String username, String password, String email) {
        userService.register(new cn.ianzhang.authapi.model.User(username, password, email));
    }

    @When("I login with username {string} and password {string}")
    public void iLoginWithUsernameAndPassword(String username, String password) throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> httpEntity = new HttpEntity<>(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(req), headers);
        ctx.lastResponse = restTemplate.exchange("/api/auth/login", HttpMethod.POST, httpEntity, String.class);
        ctx.currentSessionId = ctx.lastResponse.getHeaders().getFirst("Authorization");
    }

    @Given("I have a valid session by logging in username {string} with password {string} and email {string}")
    public void iHaveAValidSessionByLoggingIn(String username, String password, String email) throws Exception {
        aRegisteredUser(username, password, email);
        iLoginWithUsernameAndPassword(username, password);
    }

    @When("I logout with current session")
    public void iLogoutWithCurrentSession() {
        HttpHeaders headers = new HttpHeaders();
        if (ctx.currentSessionId != null) {
            headers.add("Authorization", ctx.currentSessionId);
        }
        HttpEntity<Void> httpEntity = new HttpEntity<>(headers);
        ctx.lastResponse = restTemplate.exchange("/api/auth/logout", HttpMethod.POST, httpEntity, String.class);
    }
}
