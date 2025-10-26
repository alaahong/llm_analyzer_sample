package cn.ianzhang.authapi.it.stepdefs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.spring.ScenarioScope;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@ScenarioScope
public class TestContext {
    @Autowired(required = false)
    public ObjectMapper objectMapper;

    public ResponseEntity<String> lastResponse;

    public String currentSessionId;

    @PostConstruct
    public void init() {
        if (this.objectMapper == null) {
            this.objectMapper = new ObjectMapper();
        }
    }
}
