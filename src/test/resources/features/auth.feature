Feature: Auth API
  As a user
  I want to register, login and logout
  So that I can access protected resources

  Scenario: Successful registration
    When I register with username "alice", password "p@ss" and email "alice@example.com"
    Then the response status should be 201
    And the response success should be true
    And the response message should be "注册成功"

  Scenario: Registration with existing username
    Given a registered user "bob" with password "secret" and email "bob@example.com"
    When I register with username "bob", password "other" and email "other@example.com"
    Then the response status should be 409
    And the response success should be false
    And the response message should be "用户名已存在"

  Scenario: Login success returns session and header
    Given a registered user "carol" with password "pwd" and email "carol@example.com"
    When I login with username "carol" and password "pwd"
    Then the response status should be 200
    And the response has header "Authorization"
    And the response success should be true
    And the response message should be "登录成功"

  Scenario: Login with wrong password
    Given a registered user "dave" with password "123" and email "dave@example.com"
    When I login with username "dave" and password "bad"
    Then the response status should be 401
    And the response success should be false
    And the response message should be "用户名或密码错误"

  Scenario: Logout is idempotent
    Given I have a valid session by logging in username "erin" with password "xyz" and email "erin@example.com"
    When I logout with current session
    Then the response status should be 200
    And the response success should be true
    And the response message should be "登出成功"
