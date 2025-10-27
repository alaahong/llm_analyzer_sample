# language: en
Feature: User registration, login and logout
  As a user
  I want to register, login and logout
  So that I can access protected resources

  Scenario: Successful registration
    When I register with username "apple", password "p@ss" and email "apple@example.com"
    Then the response status should be 201
    And the response success should be true
    And the response message should be "注册成功"

  Scenario: Registration with existing username
    Given a registered user "bob" with password "secret" and email "bob@example.com"
    When I register with username "bob", password "other" and email "other@example.com"
    Then the response status should be 409
    And the response success should be false
    And the response message should be "用户名已存在"

  Scenario: Successful login returns Authorization header
    Given a registered user "carol" with password "pwd" and email "carol@example.com"
    When I login with username "carol" and password "pwd"
    Then the response status should be 200
    And the response has header "Authorization"
    And the response success should be true
    And the response message should be "登录成功"

  Scenario: Protected resource without auth is unauthorized
    When I call GET "/api/greeting/protected"
    Then the response status should be 401
    And the response success should be false
    And the response message should be "请先登录"

  Scenario: Access protected resource with valid session
    Given I have a valid session by logging in username "henry" with password "abc" and email "henry@example.com"
    When I call GET "/api/greeting/protected" with Authorization header
    Then the response status should be 200
    And the response success should be true
    And the response data should be "Hello, henry! Welcome back!"

  Scenario: Logout invalidates current session
    Given I have a valid session by logging in username "dave" with password "xyz" and email "dave@example.com"
    When I logout with current session
    Then the response status should be 200
    And the response success should be true

    # subsequent access with the same session should be unauthorized
    When I call GET "/api/greeting/protected" with Authorization header
    Then the response status should be 401
    And the response success should be false
    And the response message should be "请先登录"

