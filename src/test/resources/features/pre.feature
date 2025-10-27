@ignore
Feature: User authentication and access to protected resources
  As a user
  I want to register, login and logout
  So that I can access protected resources

  Background:
    Given the application is running

  @registration @happy
  Scenario: Register a new account successfully
    Given I am on the registration page
    When I register with:
      | email    | user@example.com |
      | password | S3cur3P@ssw0rd   |
    Then I should see a message "Your account has been created"
    And I should be able to log in with "user@example.com" and "S3cur3P@ssw0rd"

  @registration @validation
  Scenario Outline: Registration fails due to invalid input
    Given I am on the registration page
    When I register with:
      | email    | <email>    |
      | password | <password> |
    Then I should see a validation error "<message>"
    And my account should not be created

    Examples:
      | email       | password | message                                |
      |             | pass1234 | Email is required                      |
      | bademail    | pass1234 | Email must be a valid address          |
      | user@ex.com | short    | Password must be at least 8 characters |

  @login @happy
  Scenario: Login with valid credentials and access a protected resource
    Given I have an existing account:
      | email    | alice@example.com |
      | password | P@ssw0rd123       |
    When I log in with "alice@example.com" and "P@ssw0rd123"
    Then I should be logged in
    And I can access a protected resource

  @login @invalid
  Scenario: Login fails with wrong credentials
    Given I have an existing account:
      | email    | bob@example.com |
      | password | Correct#123     |
    When I log in with "bob@example.com" and "Wrong#123"
    Then I should see an authentication error "Invalid email or password"
    And I should not be logged in
    And trying to access a protected resource should require authentication

  @logout
  Scenario: Logout invalidates the session
    Given I am logged in as "alice@example.com" with password "P@ssw0rd123"
    When I log out
    Then I should be logged out
    And trying to access a protected resource should require authentication

  @session
  Scenario: Session persists across requests until logout
    Given I am logged in as "alice@example.com" with password "P@ssw0rd123"
    When I make multiple requests to a protected resource
    Then each request to the protected resource should succeed
    When I log out
    Then subsequent requests to the protected resource should require authentication

