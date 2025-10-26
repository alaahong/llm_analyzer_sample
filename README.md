# Authentication API

一个基于Java 21、Spring Boot 3.5.6和Maven构建的简单认证API服务。

## 功能特性

- 用户注册功能
- 用户登录（基于内存的会话认证）
- 用户登出
- 公开和受保护的问候语API端点
- 基于内存的会话管理
- 完整的单元测试
- 基于GitHub Actions的CI集成

## 环境要求

- Java 21或更高版本
- Maven 3.6或更高版本
- Git

## 快速开始


### 构建项目

```bash
mvn clean package -DskipTests
```

### 运行应用

```bash
mvn spring-boot:run
```

应用将在`http://localhost:8080`上启动。

## 项目结构

```
/
├── .github/workflows/       # GitHub Actions配置
│   └── ci.yml              # CI工作流配置
├── src/
│   ├── main/java/com/example/authapi/  # 主源码目录
│   │   ├── controller/    # REST控制器
│   │   ├── service/       # 业务服务层
│   │   ├── model/         # 数据模型
│   │   ├── dto/           # 数据传输对象
│   │   └── config/        # 应用配置
│   └── test/              # 测试代码目录
├── target/                # 构建输出目录
└── pom.xml               # Maven项目配置文件

## API端点

### 认证相关

#### 用户注册

- **URL**: `/api/auth/register`
- **方法**: `POST`
- **请求体**:
  ```json
  {
    "username": "your_username",
    "password": "your_password",
    "email": "your_email@example.com"
  }
  ```

#### 用户登录

- **URL**: `/api/auth/login`
- **方法**: `POST`
- **请求体**:
  ```json
  {
    "username": "your_username",
    "password": "your_password"
  }
  ```
- **响应头**: 包含带会话ID的`Authorization`头

#### 用户登出

- **URL**: `/api/auth/logout`
- **方法**: `POST`
- **请求头**: `Authorization: <session-id>`

### 问候语相关

#### 公开问候语

- **URL**: `/api/greeting/public`
- **方法**: `GET`
- **无需认证**

#### 受保护问候语

- **URL**: `/api/greeting/protected`
- **方法**: `GET`
- **请求头**: `Authorization: <session-id>`

## 运行测试

```bash
mvn test
```

> **注意**：如果遇到测试问题，可以使用`mvn clean package -DskipTests`命令跳过测试阶段进行构建。

## 构建说明

1. 清理并编译项目：
   ```bash
   mvn clean compile
   ```

2. 构建打包（包含跳过测试选项）：
   ```bash
   mvn clean package -DskipTests
   ```

## 许可证

本项目基于APL 2.0许可证开源。


# 内容生成策略
## 一、完全本地生成/检测的内容 这些都不调用大模型，依赖仓库代码、git/gh CLI、构建工具、正则/启发式规则。
```
PR 与仓库上下文

PR 链接、构建工具类型（Maven/Gradle/未知）、测试命令、测试退出码、是否启用 FAIL_ON_TEST_FAILURE。
来源：环境变量 + gh CLI 查询 + 本地字符串拼装。
Changed files 变更清单

列出本次 PR 变更的文件名与状态（added/modified/removed）。
来源：gh api repos/{repo}/pulls/{number}/files，本地解析。
选中执行的测试类清单（Selected tests）与候选明细（Candidate test files）

依据 Spring Boot 增强映射，本地静态分析与检索：
名称启发式映射：Foo.java → FooTest/TestFoo/FooTests。
同包/子包测试：src/test/java/<同包>/**/*.java 中的 *Test.java、*Tests.java。
测试内引用扫描：grep import FQN、FQN.class、new <Class>(…)、@WebMvcTest(<Class>.class)、@MockBean(<Class>.class)、@Autowired <Class>。
去重顺序保持（LinkedHashSet），不依赖模型。
Affected API endpoints (detected)

针对受影响的控制器枚举接口：
直接修改了 Controller：解析类级 @RequestMapping 和方法级 @Get/Post/Put/Delete/PatchMapping、@RequestMapping(method=…) 得出 [METHOD] 路径与 Controller#method。
非 Controller 变更：扫描引用这些类的 Controllers（import FQN、FQN.class、new <Class>、@Autowired <Class>），解析得到其端点。
路径合并与规范化（class-level base + method-level path，去重斜杠、去尾部/）。
全部为本地正则解析/拼装，不用模型。
测试执行与控制

Maven：只运行选中测试类，命令为
mvn -B -DskipITs=true -DfailIfNoTests=false -Dtest=<类名,逗号分隔> -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition test
说明：显式禁用 @Disabled 条件，避免被跳过。
Gradle：./gradlew test --tests SimpleClassName …（若存在 gradlew）。
结果捕获：stdout、退出码、最终命令字符串。本地执行，不用模型。
Test summary（测试摘要）

从控制台输出提取 surefire 风格关键信息（Tests run/Failures/Errors/Skipped/Results），若不足，从 surefire/failsafe/Gradle 报告尾部补全。
本地正则抽取。
Failure diagnostics（仅失败时显示）

优先解析 JUnit XML 中 <failure>/<error> 的 message 与堆栈，并附 TestClass#method。
若无 XML，再从 surefire/failsafe .txt 报告截取 “<<< FAILURE!”/“<<< ERROR!” 附近片段。
若还不够，最后退回关键字高亮（error/exception/failed 等）。
全部本地解析与剪裁。
规则引擎兜底建议（Rule-based suggestions）

当大模型不可用/调用失败/未配置时，用内置规则输出简短建议（classpath 缺失、断言失败、依赖解析失败等）。
完全本地。
```

## 二、依赖远程大模型（OpenRouter）生成的内容

```
Analysis and suggestions（“分析与建议”段）
仅此段落使用大模型生成。调用 OpenRouter Chat Completions：
默认模型：meta-llama/llama-3.3-8b-instruct:free（可通过 OPENROUTER_MODEL 覆盖）。
输入 Prompt 由本地汇总的上下文构成，包括：
仓库/PR 基本信息（repo、PR 编号、base/head SHA）。
Changed files 列表（文件名与状态）。
已选测试类（列表）。
Test summary（摘要）。
Failure diagnostics（若失败则附聚焦堆栈片段；通过则为“(none)”）。
Truncated diffs：对每个变更文件截取 git diff 的部分片段（限长，统一裁剪）。
输出用于给出：
“还应运行哪些测试/用例”的建议；
失败根因与最小修复建议；
精确的 Maven/Gradle 运行命令建议。

在LLM_TEST_SELECTOR开启的时候，还会额外请求模型输出“候选测试文件中还应选哪些测试类”的建议列表。
```


# 本地模型启动与配置方案
## Ollama（推荐入门；OpenAI 兼容端口 http://127.0.0.1:11434/v1）
本机/自托管 Runner 安装并启动
macOS/Linux: curl -fsSL https://ollama.com/install.sh | sh
后台启动: ollama serve
拉取模型: ollama pull llama3.1:8b-instruct（或更小：qwen2.5:3b-instruct、phi3:mini）
工作流变量（仓库 Settings → Variables）
LLM_PROVIDER = openai
LLM_BASE_URL = http://127.0.0.1:11434/v1
LLM_MODEL = llama3.1:8b-instruct
LLM_API_KEY 可留空（某些版本忽略校验）
注意：GitHub 托管 runner 无法访问你本地端口，请用自托管 Runner，或在工作流中临时安装+拉取模型（首次耗时较长）。
##  vLLM（OpenAI 兼容服务）
启动
pip install vllm
python -m vllm.entrypoints.openai.api_server --model meta-llama/Llama-3.1-8B-Instruct --host 0.0.0.0 --port 8000 --max-model-len 8192
变量
LLM_PROVIDER = openai
LLM_BASE_URL = http://<runner-host>:8000/v1
LLM_MODEL = meta-llama/Llama-3.1-8B-Instruct
LLM_API_KEY = 任意字符串或按你服务配置
说明：需要有可用显卡或选择小模型跑 CPU。
## LM Studio（OpenAI 兼容）
UI 中启用本地服务器（默认 http://127.0.0.1:1234/v1）
变量
LLM_PROVIDER = openai
LLM_BASE_URL = http://127.0.0.1:1234/v1
LLM_MODEL = 按 LM Studio 显示的模型名称
LLM_API_KEY = 可留空
## llama.cpp server（OpenAI 兼容）
启动（示例）
./server -m models/llama-3.2-3b-instruct.gguf -c 4096 --host 0.0.0.0 --port 8080 --api-key sk-local
变量
LLM_PROVIDER = openai
LLM_BASE_URL = http://<runner-host>:8080/v1
LLM_MODEL = 任意字符串（有些实现会忽略 model 并使用加载的 gguf）
LLM_API_KEY = sk-local（或对应值）
五、常见问题

- 路由 404：确认 LLM_BASE_URL 包含 /v1 并指向 /chat/completions 路由（脚本会拼接）；Ollama新版已提供 /v1/chat/completions。
- 模型名不匹配：OpenAI 兼容服务要求的 model 名通常与下载名称一致（如 ollama 用 llama3.1:8b-instruct），与 OpenRouter 的标识不同。
- CI 连不上本地：GitHub 托管 Runner 无法访问你电脑。需使用自托管 Runner，或在 Job 中拉起模型服务（CPU 会较慢、首次下载耗时）。
- 安全：LLM_API_KEY 会作为 Authorization: Bearer 发送到 LLM_BASE_URL；不要把它指向不信任的 URL。