# Cucumber 步骤定义说明（Auth/Greeting 控制器）

本项目的集成测试使用 Cucumber（JUnit Platform）+ Spring Boot，Glue 包位于：

- `cn.ianzhang.authapi.it.stepdefs`

上下文（场景范围）对象：

- `TestContext`（ScenarioScope）：在步骤间共享的测试状态
  - `lastResponse`：最近一次 HTTP 调用的响应（`ResponseEntity<String>`）
  - `currentSessionId`：最近一次登录得到的 `Authorization` 头值
  - `objectMapper`：用于解析 `lastResponse` 的 JSON

以下按文件对可用 Step 进行说明，并给出其格式与用法。

---

## CommonSteps（响应断言）

适用于任何 HTTP 调用后的响应断言。

1) `Then the response status should be {int}`
- 作用：断言 HTTP 状态码
- 参数：`{int}`（例如 200、201、401、409 等）
- 示例：
  ```gherkin
  Then the response status should be 200
  ```

2) `And the response success should be {word}`
- 作用：断言响应 JSON 的 `success` 字段是否与期望布尔值一致
- 参数：`{word}` 仅支持 `true`/`false`（大小写按小写书写）
- 示例：
  ```gherkin
  And the response success should be true
  And the response success should be false
  ```

3) `And the response message should be "{string}"`
- 作用：断言响应 JSON 的 `message` 字段文本
- 参数：`{string}` 任意字符串（需要使用双引号包裹）
- 示例：
  ```gherkin
  And the response message should be "登录成功"
  ```

4) `And the response data should be "{string}"`
- 作用：断言响应 JSON 的 `data` 字段文本
- 参数：`{string}` 任意字符串（需要使用双引号包裹）
- 示例：
  ```gherkin
  And the response data should be "Hello, welcome to our service!"
  ```

5) `And the response has header "{string}"`
- 作用：断言响应中存在指定名称的 Header 且非空
- 参数：`{string}` Header 名称（如 `Authorization`）
- 示例：
  ```gherkin
  And the response has header "Authorization"
  ```

注意：`CommonSteps` 内部会从 `TestContext.lastResponse` 读取响应，并用 `objectMapper` 解析 JSON；若非 2xx 响应，断言仍可使用（例如 401/409）。

---

## AuthSteps（认证接口）

围绕 `/api/auth` 系列接口的步骤。

1) `When I register with username "{string}", password "{string}" and email "{string}"`
- 作用：调用注册接口 `POST /api/auth/register`
- 请求体：`{ username, password, email }`
- 结果：将响应保存到 `lastResponse`
- 示例：
  ```gherkin
  When I register with username "alice", password "p@ss" and email "alice@example.com"
  ```

2) `Given a registered user "{string}" with password "{string}" and email "{string}"`
- 作用：通过服务层直接预置一个已注册用户（绕过 HTTP 注册流程）
- 使用时机：作为前置条件，便于测试重复注册/登录等场景
- 示例：
  ```gherkin
  Given a registered user "bob" with password "secret" and email "bob@example.com"
  ```

3) `When I login with username "{string}" and password "{string}"`
- 作用：调用登录接口 `POST /api/auth/login`
- 请求体：`{ username, password }`
- 结果：将响应保存到 `lastResponse`，并把响应头 `Authorization` 记入 `currentSessionId`
- 示例：
  ```gherkin
  When I login with username "carol" and password "pwd"
  ```

4) `Given I have a valid session by logging in username "{string}" with password "{string}" and email "{string}"`
- 作用：便捷步骤，先预置用户，再执行登录，最终获得有效会话（`currentSessionId`）
- 示例：
  ```gherkin
  Given I have a valid session by logging in username "erin" with password "xyz" and email "erin@example.com"
  ```

5) `When I logout with current session`
- 作用：调用登出接口 `POST /api/auth/logout`
- 行为：若 `currentSessionId` 存在，则以 `Authorization` 头发送
- 示例：
  ```gherkin
  When I logout with current session
  ```

---

## GreetingSteps（问候接口）

围绕 `/api/greeting` 系列接口的步骤。

1) `When I call GET "{string}"`
- 作用：对给定路径执行 GET 请求
- 参数：`{string}` 为路径，如 `/api/greeting/public`
- 结果：将响应保存到 `lastResponse`
- 示例：
  ```gherkin
  When I call GET "/api/greeting/public"
  ```

2) `When I call GET "{string}" with Authorization header`
- 作用：带上当前会话的 `Authorization` 头执行 GET 请求
- 依赖：`currentSessionId` 已在此前登录步骤中写入
- 示例：
  ```gherkin
  When I call GET "/api/greeting/protected" with Authorization header
  ```

---

## 变量占位符与书写规范

- `{int}`：整数；用于状态码等
- `{word}`：仅建议使用 `true` / `false`（小写）以匹配布尔断言
- `{string}`：使用双引号包裹的任意字符串；字符串中若包含引号请进行转义

---

## 组合示例

1) 公开接口成功访问：
```gherkin
Scenario: Public greeting returns welcome message
  When I call GET "/api/greeting/public"
  Then the response status should be 200
  And the response success should be true
  And the response data should be "Hello, welcome to our service!"
```

2) 受保护接口未授权：
```gherkin
Scenario: Protected greeting without auth is unauthorized
  When I call GET "/api/greeting/protected"
  Then the response status should be 401
  And the response success should be false
  And the response message should be "请先登录"
```

3) 登录后访问受保护接口：
```gherkin
Scenario: Protected greeting with valid session says hello username
  Given I have a valid session by logging in username "henry" with password "abc" and email "henry@example.com"
  When I call GET "/api/greeting/protected" with Authorization header
  Then the response status should be 200
  And the response success should be true
  And the response data should be "Hello, henry! Welcome back!"
```

4) 注册与重复注册：
```gherkin
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
```

---

## 报告与执行

- 已在 `src/test/resources/cucumber.properties` 中配置：
  - `cucumber.glue=cn.ianzhang.authapi.it.stepdefs,cn.ianzhang.authapi.it`
  - `cucumber.features=classpath:features`
  - `cucumber.plugin=summary, html:target/cucumber-report.html`
- 执行方式（Maven）：
  ```bash
  mvn -DskipTests=false verify
  ```
- 报告位置：`target/cucumber-report.html`

---

## 小贴士

- 步骤中的路径参数采用相对路径（例如：`/api/...`），由 `TestRestTemplate` 基于随机端口发起请求
- 当预期非 2xx 响应时，请照常使用 `CommonSteps` 进行状态码与消息断言
- 需要会话时，请先使用 AuthSteps 的登录或便捷登录步骤以写入 `currentSessionId`

