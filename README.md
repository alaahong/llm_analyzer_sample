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