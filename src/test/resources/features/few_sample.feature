@ignore
Feature: Auth API
  Scenario: Access protected API after logout
    Given I have a valid session by logging in username "bob" with password "pwd" and email "bob@example.com"
    When I logout with current session
    And I call GET "/api/greeting/protected" with Authorization header
    Then the response status should be 401
    And the response success should be false
    And the response message should be "请先登录"
