@ignore
Feature: Logout and Access Control
  Scenario: Accessing protected endpoint after logout
    Given the user is logged in
    When the user logs out
    And the user tries to access a protected endpoint
    Then the user should receive a 401 Unauthorized response
