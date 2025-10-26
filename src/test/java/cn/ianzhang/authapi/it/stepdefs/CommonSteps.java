package cn.ianzhang.authapi.it.stepdefs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;

public class CommonSteps {

    @Autowired
    private TestContext ctx;

    private JsonNode body() throws Exception {
        ObjectMapper mapper = ctx.objectMapper != null ? ctx.objectMapper : new ObjectMapper();
        return mapper.readTree(ctx.lastResponse.getBody());
    }

    @Then("the response status should be {int}")
    public void theResponseStatusShouldBe(int status) {
        Assertions.assertThat(ctx.lastResponse.getStatusCode().value()).isEqualTo(status);
    }

    @And("the response success should be {word}")
    public void theResponseSuccessShouldBe(String expected) throws Exception {
        Assertions.assertThat(body().path("success").asBoolean()).isEqualTo(Boolean.parseBoolean(expected));
    }

    @And("the response message should be {string}")
    public void theResponseMessageShouldBe(String message) throws Exception {
        Assertions.assertThat(body().path("message").asText()).isEqualTo(message);
    }

    @And("the response data should be {string}")
    public void theResponseDataShouldBe(String data) throws Exception {
        Assertions.assertThat(body().path("data").asText()).isEqualTo(data);
    }

    @And("the response has header {string}")
    public void theResponseHasHeader(String name) {
        String value = ctx.lastResponse.getHeaders().getFirst(name);
        Assertions.assertThat(value).isNotBlank();
    }
}
