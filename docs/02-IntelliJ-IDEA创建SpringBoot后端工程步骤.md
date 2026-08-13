# IntelliJ IDEA 创建 Spring Boot 后端工程步骤

> 文档版本：V1.0  
> 编制日期：2026-08-13  
> 适用项目：MyTallyBook 记账本微信小程序  
> 目标工程：`account-book-server`

## 1. 当前配置检查结论

当前 Spring Boot 工程可以编译，但有两处需要调整：

### 1.1 创建位置不正确

当前目录结构是：

```text
MyTallyBook_Project/
├── .mvn/
├── src/
├── mvnw
├── mvnw.cmd
└── pom.xml
```

这表示 Spring Boot 后端被直接创建到了整个项目的根目录。

正确结构应该是：

```text
MyTallyBook_Project/
├── account-book-server/
│   ├── .mvn/
│   ├── src/
│   ├── mvnw
│   ├── mvnw.cmd
│   └── pom.xml
├── account-book-miniapp/
├── deploy/
└── docs/
```

项目根目录用于统一存放后端、小程序、部署配置和文档，不能只作为 Spring Boot 工程目录。

### 1.2 Maven 坐标和 Java 包名不正确

当前 `pom.xml` 使用：

```xml
<groupId>com.api</groupId>
<artifactId>MyTallyBook_Project</artifactId>
<name>MyTallyBook_Project</name>
```

当前 Java 包名是：

```text
com.api.mytallybook_project
```

正确值应该是：

```xml
<groupId>com.mytallybook</groupId>
<artifactId>account-book-server</artifactId>
<name>account-book-server</name>
```

正确 Java 根包名：

```text
com.mytallybook.accountbook
```

### 1.3 依赖选择正确

当前工程已经包含所需依赖：

- Spring Boot Actuator。
- Spring Data JPA。
- Flyway Migration。
- Spring Security。
- Validation。
- Spring Web MVC。
- Flyway MySQL。
- MySQL Driver。

Spring Boot 4.1 将部分测试依赖拆分为独立 test starter，属于正常现象。

## 2. 推荐处理方法

由于当前只有 IDEA 自动生成的工程骨架，还没有业务代码，推荐采用以下方法：

1. 关闭当前 IDEA 工程。
2. 将当前错误创建的工程骨架移动到项目根目录之外作为临时备份。
3. 保留 `MyTallyBook_Project` 作为总项目目录。
4. 在 `MyTallyBook_Project\account-book-server` 中重新创建 Spring Boot 工程。
5. 验证新工程成功后，再删除临时备份。

这种方法最安全，不需要手工修改包目录、启动类、测试类和 IDEA 模块配置。

## 3. 第一步：关闭当前 IDEA 工程

在 IDEA 中选择：

```text
File → Close Project
```

等待返回 IDEA 欢迎页面。

关闭工程后再移动目录，避免 IDEA、Maven 或 Java 进程占用文件。

## 4. 第二步：备份当前错误创建的工程骨架

打开 PowerShell，执行以下命令。

### 4.1 定义目录

```powershell
$projectRoot = "D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project"
$skeletonBackup = "D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_BackendSkeleton_Backup"
```

### 4.2 检查路径

```powershell
Get-Item -LiteralPath $projectRoot

Get-ChildItem -LiteralPath $projectRoot -Force
```

确认输出中包含：

```text
.idea
.mvn
src
mvnw
mvnw.cmd
pom.xml
```

### 4.3 创建备份目录

```powershell
New-Item -ItemType Directory -Path $skeletonBackup
```

如果提示备份目录已存在，不要直接覆盖；给备份目录换一个新名称，例如：

```text
MyTallyBook_BackendSkeleton_Backup_02
```

### 4.4 移动旧的 Spring Boot 骨架

```powershell
$backendSkeletonItems = @(
  ".mvn",
  "src",
  ".gitattributes",
  "HELP.md",
  "mvnw",
  "mvnw.cmd",
  "pom.xml"
)

foreach ($item in $backendSkeletonItems) {
  $sourcePath = Join-Path $projectRoot $item
  if (Test-Path -LiteralPath $sourcePath) {
    Move-Item -LiteralPath $sourcePath -Destination $skeletonBackup
  }
}
```

### 4.5 备份旧的 IDEA 配置

不要直接删除 `.idea`，先把它移入备份目录：

```powershell
$ideaPath = Join-Path $projectRoot ".idea"

if (Test-Path -LiteralPath $ideaPath) {
  Move-Item -LiteralPath $ideaPath -Destination $skeletonBackup
}
```

### 4.6 检查结果

```powershell
Get-ChildItem -LiteralPath $projectRoot -Force
Get-ChildItem -LiteralPath $skeletonBackup -Force
```

备份目录中应该包含旧的 `pom.xml`、`src`、`.mvn` 和 `.idea`。

## 5. 第三步：创建总项目目录结构

在 PowerShell 中执行：

```powershell
New-Item -ItemType Directory -Force -Path `
  (Join-Path $projectRoot "account-book-miniapp"), `
  (Join-Path $projectRoot "deploy"), `
  (Join-Path $projectRoot "deploy\nginx"), `
  (Join-Path $projectRoot "deploy\systemd"), `
  (Join-Path $projectRoot "deploy\scripts"), `
  (Join-Path $projectRoot "docs")
```

此时暂时不要手工创建 `account-book-server`，让 IDEA 创建它，避免 IDEA 提示目标目录已经存在且非空。

## 6. 第四步：在 IDEA 中重新创建后端工程

### 6.1 打开创建向导

在 IDEA 欢迎页选择：

```text
New Project → Spring Boot
```

### 6.2 填写基础信息

| IDEA 选项 | 正确值 |
|---|---|
| Name | `account-book-server` |
| Language | Java |
| Type | Maven |
| Group | `com.mytallybook` |
| Artifact | `account-book-server` |
| Package name | `com.mytallybook.accountbook` |
| JDK | 21 |
| Java | 21 |
| Packaging | Jar |

### 6.3 设置 Location

最终创建目录必须是：

```text
D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project\account-book-server
```

不同 IDEA 版本对 `Location` 的显示方式可能略有差异，因此以向导中显示的“最终完整路径”为准。

如果 `Location` 表示项目父目录，则设置：

```text
D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project
```

同时保持：

```text
Name = account-book-server
```

如果 `Location` 表示项目完整目录，则直接设置：

```text
D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project\account-book-server
```

点击下一步前，必须确认最终路径末尾只有一层 `account-book-server`。

正确：

```text
MyTallyBook_Project\account-book-server\pom.xml
```

错误一：直接创建到根目录：

```text
MyTallyBook_Project\pom.xml
```

错误二：多嵌套一层：

```text
MyTallyBook_Project\account-book-server\account-book-server\pom.xml
```

### 6.4 选择 Spring Boot 版本

选择：

```text
Spring Boot 4.1.0
```

不选择带以下后缀的版本：

```text
SNAPSHOT
M1
M2
RC
```

### 6.5 选择依赖

使用依赖页面左上角的搜索框逐个搜索并勾选：

| 搜索词 | 选择项 | 分类 |
|---|---|---|
| `Spring Web` | Spring Web | Web |
| `Spring Security` | Spring Security | Security |
| `Validation` | Validation | I/O |
| `Spring Data JPA` | Spring Data JPA | SQL |
| `MySQL Driver` | MySQL Driver | SQL |
| `Flyway Migration` | Flyway Migration | SQL |
| `Actuator` | Spring Boot Actuator | Ops |

右侧 `Added dependencies` 最终应包含且只需要包含：

```text
Spring Web
Spring Security
Validation
Spring Data JPA
MySQL Driver
Flyway Migration
Spring Boot Actuator
```

### 6.6 不选择的依赖

以下依赖不要勾选：

- GraalVM Native Support。
- Docker Compose Support。
- Spring Boot DevTools。
- Lombok。
- Spring WebFlux。
- Spring Data Redis。
- Spring Session。
- Thymeleaf。
- OAuth2 相关依赖。
- GraphQL。
- WebSocket。
- Spring Cloud 相关依赖。
- Spring Modulith。
- Testcontainers。

完成后点击：

```text
Create
```

## 7. 第五步：验证新工程目录

创建完成后，在 IDEA 项目窗口或 PowerShell 中确认：

```powershell
Get-ChildItem -LiteralPath $projectRoot -Force
Get-ChildItem -LiteralPath (Join-Path $projectRoot "account-book-server") -Force
```

正确结构：

```text
MyTallyBook_Project/
├── account-book-server/
│   ├── .mvn/
│   ├── src/
│   ├── .gitattributes
│   ├── .gitignore
│   ├── HELP.md
│   ├── mvnw
│   ├── mvnw.cmd
│   └── pom.xml
├── account-book-miniapp/
├── deploy/
│   ├── nginx/
│   ├── systemd/
│   └── scripts/
└── docs/
```

## 8. 第六步：检查 `pom.xml`

打开：

```text
account-book-server/pom.xml
```

确认包含：

```xml
<groupId>com.mytallybook</groupId>
<artifactId>account-book-server</artifactId>
<version>0.0.1-SNAPSHOT</version>
<name>account-book-server</name>
```

确认 Java 版本：

```xml
<properties>
    <java.version>21</java.version>
</properties>
```

Spring Boot 4.1 生成以下依赖名称属于正常情况：

```xml
<artifactId>spring-boot-starter-webmvc</artifactId>
<artifactId>spring-boot-starter-flyway</artifactId>
```

不要因为它们和旧版教程中的 `spring-boot-starter-web` 写法不同而手工改回去。

## 9. 第七步：检查 Java 包名

正确启动类路径：

```text
account-book-server/src/main/java/com/mytallybook/accountbook/AccountBookServerApplication.java
```

文件开头应为：

```java
package com.mytallybook.accountbook;
```

测试类也应位于：

```text
account-book-server/src/test/java/com/mytallybook/accountbook/
```

不应再出现：

```text
com.api.mytallybook_project
```

## 10. 第八步：执行 Maven 验证

在 IDEA Terminal 中执行：

```powershell
Set-Location -LiteralPath "D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project\account-book-server"

.\mvnw.cmd test
```

第一次运行会下载 Maven 和项目依赖，需要等待。

预期结果：

```text
BUILD SUCCESS
```

如果首次测试因为数据库连接失败，不要删除 JPA、MySQL 或 Flyway 依赖。下一步应创建本地 MySQL 开发库并配置环境变量。

## 11. 第九步：使用 IDEA 打开总项目目录

后端验证通过后，建议关闭当前窗口，再选择：

```text
File → Open
```

打开总目录：

```text
D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project
```

如果 IDEA 询问是否信任项目，选择：

```text
Trust Project
```

如果 IDEA 没有自动识别 Maven：

1. 找到 `account-book-server/pom.xml`。
2. 右键 `pom.xml`。
3. 选择 `Add as Maven Project`。

之后 IDEA 项目窗口能够同时看到：

- 后端工程。
- 微信小程序工程目录。
- Linux 部署配置。
- Markdown 文档。

## 12. 第十步：处理旧备份

只有满足以下条件后，才处理旧备份：

- [ ] 新工程位于正确的 `account-book-server` 子目录。
- [ ] Maven 坐标正确。
- [ ] Java 包名正确。
- [ ] 7 项依赖正确。
- [ ] `mvnw.cmd test` 显示 `BUILD SUCCESS`，或仅因尚未配置 MySQL 而失败。
- [ ] IDEA 能正常识别 Maven 工程。

确认新工程可用后，可以删除项目根目录之外的备份：

```text
D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_BackendSkeleton_Backup
```

删除前再次确认它不是当前正在使用的新工程。

## 13. 本步骤最终验收清单

- [ ] `MyTallyBook_Project` 是总项目目录。
- [ ] 后端位于 `MyTallyBook_Project/account-book-server`。
- [ ] `pom.xml` 不在总项目根目录。
- [ ] Maven Group 为 `com.mytallybook`。
- [ ] Maven Artifact 为 `account-book-server`。
- [ ] Java 根包为 `com.mytallybook.accountbook`。
- [ ] 使用 JDK 21。
- [ ] Packaging 为 Jar。
- [ ] Spring Boot 为 4.1.0 正式版。
- [ ] 仅选择约定的 7 项业务依赖。
- [ ] 未选择 Docker、Redis、WebFlux 和 Spring Cloud。
- [ ] Maven 已被 IDEA 正确识别。
- [ ] Maven 测试完成基础验证。

完成本步骤后，下一步是安装或检查本地 MySQL 8.4，创建 `account_book_dev` 和 `account_book_test` 数据库，并建立 Spring Boot 的开发环境配置。

## 14. 2026-08-13 实际创建结果复核

已根据 IDEA 截图和磁盘文件进行复核。

### 14.1 已正确的配置

- [x] 后端创建在 `MyTallyBook_Project/account-book-server` 子目录。
- [x] Name 为 `account-book-server`。
- [x] Artifact 为 `account-book-server`。
- [x] Language 为 Java。
- [x] Type 为 Maven。
- [x] JDK 为 Microsoft OpenJDK 21.0.11。
- [x] Java 版本为 21。
- [x] Packaging 为 Jar。
- [x] Spring Boot 为 4.1.0 正式版。
- [x] 未在子工程中单独创建 Git 仓库。
- [x] 已选择 Spring Web。
- [x] 已选择 Spring Security。
- [x] 已选择 Spring Data JPA。
- [x] 已选择 Flyway Migration。
- [x] 已选择 MySQL Driver。
- [x] 已选择 Validation。
- [x] 已选择 Spring Boot Actuator。
- [x] 未选择 Docker Compose、Redis、WebFlux 和 Spring Cloud。

### 14.2 必须纠正的配置

当前 Maven Group：

```text
com.api
```

应改为：

```text
com.mytallybook
```

当前 Java 包名：

```text
com.api.accountbookserver
```

应改为：

```text
com.mytallybook.accountbook
```

如果尚未点击 `Create`，直接返回第一页修改这两项，然后再创建。

如果已经点击 `Create`，由于当前还没有业务代码，推荐删除并重新创建 `account-book-server` 子工程。也可以使用 IDEA 的 `Refactor → Move` 和 `Refactor → Rename` 修改包名，并同步修改 `pom.xml` 的 Group，但重新创建更简单、不易残留错误目录。

### 14.3 建议调整的配置

当前 Configuration 选择的是：

```text
Properties
```

建议选择：

```text
YAML
```

Properties 本身可以正常运行，不属于功能错误；选择 YAML 是为了和本项目后续的多环境、数据库、微信、Actuator 配置文档保持一致。

创建后应得到：

```text
src/main/resources/application.yml
```

而不是：

```text
src/main/resources/application.properties
```

### 14.4 总项目目录仍需补齐

当前根目录已有：

```text
MyTallyBook_Project/
├── account-book-server/
└── docs/
```

还应创建：

```text
account-book-miniapp/
deploy/
├── nginx/
├── systemd/
└── scripts/
```

此外，总项目根目录尚未初始化 Git。后端子目录不单独创建 Git 仓库是正确的；应在总项目根目录执行一次：

```powershell
Set-Location -LiteralPath "D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project"
git init -b main
```

### 14.5 修正完成状态

2026-08-13 已完成以下修正：

- [x] Maven Group 已改为 `com.mytallybook`。
- [x] Java 根包已改为 `com.mytallybook.accountbook`。
- [x] 启动类和测试类已移动到正确包目录。
- [x] 配置文件已由 `application.properties` 转换为 `application.yml`。
- [x] 后端工程仍位于正确的 `account-book-server` 子目录。
- [x] 总项目目录已补齐小程序与部署目录。

当前命令行 `JAVA_HOME` 仍指向旧的 Java 8；执行 Maven 前需要先切换到 JDK 21。具体记录见《03-项目目录与Git初始化记录.md》中的环境检查部分。
