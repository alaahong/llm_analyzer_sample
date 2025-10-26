Feature: Greeting API
  Access public and protected greetings

  Scenario: Public greeting returns welcome message
    When I call GET "/api/greeting/public"
    Then the response status should be 200
    And the response success should be true
    And the response data should be "Hello, welcome to our service!"

  Scenario: Protected greeting without auth is unauthorized
    When I call GET "/api/greeting/protected"
    Then the response status should be 401
    And the response success should be false
    And the response message should be "请先登录"

  Scenario: Protected greeting with valid session says hello username
    Given I have a valid session by logging in username "henry" with password "abc" and email "henry@example.com"
    When I call GET "/api/greeting/protected" with Authorization header
    Then the response status should be 200
    And the response success should be true
    And the response data should be "Hello, henry! Welcome back!"
