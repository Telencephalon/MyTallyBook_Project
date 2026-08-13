# Maven install 测试失败排查与修复

> 文档版本：V1.0  
> 排查日期：2026-08-13  
> 适用工程：`account-book-server`

## 1. 报错现象

执行 IDEA Maven 面板的 `install` 或命令：

```powershell
.\mvnw.cmd install
```

末尾出现：

```text
See ...\target\surefire-reports for the individual test results.
See dump files (if any exist) ...
```

这两行只是 Maven Surefire 提示测试失败后去哪里查看报告，并不是实际根因。

## 2. 实际根因

测试报告：

```text
target/surefire-reports/
  com.mytallybook.accountbook.AccountBookServerApplicationTests.txt
```

实际错误：

```text
Failed to configure a DataSource: 'url' attribute is not specified
Reason: Failed to determine a suitable driver class
```

调用关系：

```text
contextLoads 测试
    ↓
启动完整 Spring Boot ApplicationContext
    ↓
加载 Spring Data JPA
    ↓
加载 Flyway
    ↓
尝试创建 DataSource
    ↓
未找到 JDBC URL、用户名和密码
    ↓
测试失败，Maven install 终止
```

当前 `application.yml` 只有应用名称，本地也没有以下环境变量：

```text
DB_URL
DB_USERNAME
DB_PASSWORD
```

因此该错误与目录、包名、Java 编译或 MySQL 驱动缺失无关。

## 3. 环境检查结果

### 3.1 Java

IDEA 项目 SDK 使用 JDK 21，但 Windows PowerShell 的系统环境仍指向：

```text
JAVA_HOME=D:\Work\Config\JDK\jdk1.8.0_271
```

Spring Boot 4.1 必须使用 Java 17 或以上，本项目使用 Java 21。IDEA Maven Runner 和命令行 Maven 都应选择：

```text
D:\Work\Config\JDK\JDK\jdk21
```

IDEA 检查位置：

```text
Settings
→ Build, Execution, Deployment
→ Build Tools
→ Maven
→ Runner
→ JRE
→ Project JDK (21)
```

以及：

```text
Settings
→ Build, Execution, Deployment
→ Build Tools
→ Maven
→ Importing
→ JDK for importer
→ Project JDK (21)
```

命令行当前会话临时切换：

```powershell
$env:JAVA_HOME = "D:\Work\Config\JDK\JDK\jdk21"
$env:Path = "$env:JAVA_HOME\bin;" + (($env:Path -split ';' | Where-Object {
  $_ -and ($_ -notmatch '(?i)jdk1\.8\.0_271\\bin')
}) -join ';')

java -version
.\mvnw.cmd -version
```

两处都必须显示 Java 21。

### 3.2 MySQL

本机检查结果：

- 能找到 MySQL 5.7 客户端。
- 没有发现正在运行的 MySQL Windows 服务。
- 3306 端口没有服务监听。
- 尚未配置 Spring Boot 数据库环境变量。

项目方案要求 MySQL 8.4 LTS，因此下一阶段应安装或准备 MySQL 8.4，而不是使用当前残留的 MySQL 5.7 客户端作为生产基线。

## 4. 当前阶段修复

项目刚完成骨架初始化，数据库尚未进入安装配置阶段。为了让普通单元测试和 `mvn install` 不依赖开发机数据库，生成的上下文冒烟测试使用独立 `test` Profile。

测试类增加：

```java
@SpringBootTest
@ActiveProfiles("test")
class AccountBookServerApplicationTests {
    // ...
}
```

测试配置：

```text
src/test/resources/application-test.yml
```

内容：

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
      - org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
      - org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
```

作用：

- 只在 `test` Profile 下排除数据库、JPA 和 Flyway自动配置。
- 不改变开发、测试数据库集成和生产环境配置。
- 允许当前最基础的 Spring Web/Security/Actuator 上下文测试正常执行。
- 不需要使用 `-DskipTests` 掩盖所有测试。

## 5. 重新执行验证

在 IDEA Terminal 或 PowerShell 中进入后端目录：

```powershell
Set-Location -LiteralPath "D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project\account-book-server"
```

确认 Maven 使用 Java 21：

```powershell
.\mvnw.cmd -version
```

然后执行：

```powershell
.\mvnw.cmd clean install
```

预期：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 6. 后续数据库测试方案

当前修复只适用于不需要数据库的应用骨架测试。安装 MySQL 8.4 并创建测试库后，应增加真正的数据库集成测试：

```text
account_book_dev
account_book_test
```

后续策略：

1. 普通单元测试不访问数据库。
2. `contextLoads` 继续验证不依赖数据库的基础上下文。
3. Repository、Flyway 和统计 SQL 使用 `account_book_test` 独立测试库。
4. 测试库账号与开发库账号分开或至少数据库分开。
5. 测试不得连接生产数据库。
6. CI 运行数据库集成测试时提供临时 MySQL 服务和受控环境变量。

不要采用以下处理方式：

- 删除 JPA、Flyway 或 MySQL 依赖。
- 把真实数据库密码写入 `application.yml`。
- 长期用 `-DskipTests` 代替修复。
- 让测试连接生产数据库。
- 为了通过测试改用与生产不一致的 MySQL 5.7。

## 7. 验收清单

- [x] 已从 Surefire 报告定位真实根因。
- [x] 没有 JVM dump，排除 JVM 崩溃。
- [x] 确认失败发生在 DataSource 自动配置阶段。
- [x] 创建独立的 `test` Profile。
- [x] 仅在基础上下文测试中排除数据库相关自动配置。
- [x] 已使用 JDK 21.0.11 执行 Maven Wrapper 验证。
- [ ] Windows `JAVA_HOME` 永久修正为 JDK 21。
- [x] `mvnw.cmd clean install` 验证通过。
- [ ] 安装 MySQL 8.4 LTS。
- [ ] 创建开发库和测试库。
- [ ] 增加真实数据库集成测试。

## 8. 本次验证结果

使用以下环境执行：

```text
Java 21.0.11
Maven Wrapper 3.9.16
Spring Boot 4.1.0
```

执行命令：

```powershell
.\mvnw.cmd clean install
```

测试结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

构建产物已成功安装到本地 Maven 仓库：

```text
C:\Users\CR\.m2\repository\com\mytallybook\account-book-server\0.0.1-SNAPSHOT\
```

构建过程中出现的 Mockito/Byte Buddy 动态 Java Agent 内容属于未来 JDK 兼容性警告，不是本次失败原因，也不影响当前构建结果。后续实际使用 Mockito 编写测试并升级 JDK 时，再按照届时的 Mockito 官方配置处理。
