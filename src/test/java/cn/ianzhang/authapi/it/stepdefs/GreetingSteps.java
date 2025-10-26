package cn.ianzhang.authapi.it.stepdefs;

import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

public class GreetingSteps {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestContext ctx;

    @When("I call GET {string}")
    public void iCallGET(String path) {
        ctx.lastResponse = restTemplate.exchange(path, HttpMethod.GET, null, String.class);
    }

    @When("I call GET {string} with Authorization header")
    public void iCallGETWithAuthorizationHeader(String path) {
        HttpHeaders headers = new HttpHeaders();
        if (ctx.currentSessionId != null) {
            headers.add("Authorization", ctx.currentSessionId);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ctx.lastResponse = restTemplate.exchange(path, HttpMethod.GET, entity, String.class);
    }
}
