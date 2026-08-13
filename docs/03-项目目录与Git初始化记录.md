# 项目目录与 Git 初始化记录

> 文档版本：V1.0  
> 执行日期：2026-08-13  
> 项目：MyTallyBook Project  
> GitHub：`git@github.com:Telencephalon/MyTallyBook_Project.git`

## 1. 初始化目标

本次初始化完成以下工作：

- 将 `MyTallyBook_Project` 作为唯一 Git 仓库根目录。
- 将 Spring Boot 后端放在 `account-book-server` 子目录。
- 创建微信小程序、部署配置和文档目录。
- 修正后端 Maven 坐标和 Java 包名。
- 将 Spring Boot 配置统一为 YAML。
- 创建根目录 README、`.gitignore` 和 `.gitattributes`。
- 绑定 GitHub SSH 远程仓库。
- 创建并推送首个 `main` 分支提交。

## 2. 最终目录结构

```text
MyTallyBook_Project/
├── .gitattributes
├── .gitignore
├── README.md
├── account-book-server/
│   ├── .mvn/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/mytallybook/accountbook/
│   │   │   │   └── AccountBookServerApplication.java
│   │   │   └── resources/
│   │   │       └── application.yml
│   │   └── test/
│   │       └── java/com/mytallybook/accountbook/
│   │           └── AccountBookServerApplicationTests.java
│   ├── mvnw
│   ├── mvnw.cmd
│   └── pom.xml
├── account-book-miniapp/
│   └── .gitkeep
├── deploy/
│   ├── nginx/.gitkeep
│   ├── systemd/.gitkeep
│   └── scripts/.gitkeep
└── docs/
    ├── 01-项目启动与第一阶段实施步骤.md
    ├── 02-IntelliJ-IDEA创建SpringBoot后端工程步骤.md
    ├── 03-项目目录与Git初始化记录.md
    └── 记账本微信小程序完整实现方案-单机原生部署.md
```

`.gitkeep` 只是为了让 Git 记录尚无正式文件的空目录；开始开发对应模块后可以删除。

## 3. 后端工程修正

### 3.1 Maven 坐标

修正前：

```xml
<groupId>com.api</groupId>
<artifactId>account-book-server</artifactId>
```

修正后：

```xml
<groupId>com.mytallybook</groupId>
<artifactId>account-book-server</artifactId>
```

### 3.2 Java 包名

修正前：

```text
com.api.accountbookserver
```

修正后：

```text
com.mytallybook.accountbook
```

启动类和测试类已经同步移动，文件中的 `package` 声明也已更新。

### 3.3 配置格式

已将：

```text
application.properties
```

转换为：

```text
application.yml
```

当前基础配置：

```yaml
spring:
  application:
    name: account-book-server
```

## 4. Git 仓库设计

本项目只使用一个 Git 仓库：

```text
MyTallyBook_Project/.git
```

以下子目录不创建独立 `.git`：

```text
account-book-server/
account-book-miniapp/
deploy/
docs/
```

这样前端、后端、数据库迁移、部署配置和对应文档可以在同一个功能提交中保持一致。

远程仓库：

```text
origin  git@github.com:Telencephalon/MyTallyBook_Project.git
```

初始化前已通过 `git ls-remote` 检查远端；远端没有分支或提交，因此可以安全创建首个 `main` 提交，不涉及覆盖远端历史。

## 5. Git 忽略规则

根目录 `.gitignore` 已覆盖：

- IDEA 配置和模块文件。
- Maven `target` 和 Java 编译产物。
- 小程序本地私有配置、npm 和构建目录。
- `.env` 和生产密钥配置。
- SSL 私钥和证书密钥文件。
- 日志、备份和运行数据。
- Windows、macOS 编辑器和系统文件。

必须持续遵守：

- 不提交微信 AppSecret。
- 不提交数据库真实密码。
- 不提交生产环境变量文件。
- 不提交登录令牌。
- 不提交 SSL 私钥。

## 6. Java 环境检查

IDEA 项目 SDK 已正确选择：

```text
Microsoft OpenJDK 21.0.11
```

实际安装目录：

```text
D:\Work\Config\JDK\JDK\jdk21
```

但 Windows PowerShell 当前继承的 `JAVA_HOME` 是：

```text
D:\Work\Config\JDK\jdk1.8.0_271
```

Spring Boot 4.1 不能使用 Java 8。当前会话执行 Maven 前，可临时切换：

```powershell
$env:JAVA_HOME = "D:\Work\Config\JDK\JDK\jdk21"
$env:Path = "$env:JAVA_HOME\bin;" + (($env:Path -split ';' | Where-Object {
  $_ -and ($_ -notmatch '(?i)jdk1\.8\.0_271\\bin')
}) -join ';')

java -version
.\mvnw.cmd -version
```

验收结果必须显示：

```text
Java version: 21.0.11
```

建议后续在 Windows“系统属性 → 环境变量”中，将用户级或系统级 `JAVA_HOME` 永久改为 JDK 21，并把 `%JAVA_HOME%\bin` 放到旧 Java 8 路径之前。修改后需要重新打开 PowerShell 和 IDEA。永久修改是开发机系统级操作，应由使用者确认其他旧项目是否仍依赖 Java 8 后执行。

## 7. Maven 验证结果

### 7.1 已验证内容

Maven Wrapper 已正确使用：

```text
Apache Maven 3.9.16
Java 21.0.11
```

编译阶段已经确认：

- Maven 坐标为 `com.mytallybook:account-book-server`。
- 主代码编译成功。
- 测试代码编译成功。
- Spring Boot 能找到修正后的启动类。

### 7.2 初始测试失败与处理结果

执行：

```powershell
.\mvnw.cmd test
```

初次执行时，`contextLoads` 测试在加载数据库自动配置时失败，错误核心是：

```text
Failed to configure a DataSource: 'url' attribute is not specified
```

原因是本地 MySQL 开发库和 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 尚未配置。这不是包名、目录结构或 Java 编译错误。

已创建独立 `test` Profile，仅为当前不依赖数据库的应用骨架测试排除 DataSource、JPA 和 Flyway 自动配置。修复后执行：

```powershell
.\mvnw.cmd clean install
```

实际结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

处理原则仍然是：

- 不删除 JPA、Flyway 或 MySQL 依赖来规避错误。
- 不在 Git 中硬编码数据库密码。
- 下一阶段创建 `account_book_dev` 与 `account_book_test` 后增加真实数据库集成测试。

在数据库准备完成前，可使用跳过测试的打包命令验证编译和 JAR 生成：

```powershell
.\mvnw.cmd clean package -DskipTests
```

本次已使用 JDK 21 执行该命令，结果为：

```text
BUILD SUCCESS
```

已生成可执行 JAR：

```text
account-book-server/target/account-book-server-0.0.1-SNAPSHOT.jar
```

`target/` 已被根目录 `.gitignore` 忽略，不会提交到 GitHub。

## 8. 常用 Git 命令

查看状态：

```powershell
git status
```

查看远程：

```powershell
git remote -v
```

查看分支：

```powershell
git branch -vv
```

后续提交：

```powershell
git add --all
git commit -m "描述本次变更"
git push
```

提交前必须先检查 `git status` 和 `git diff --cached`，防止密钥或本地配置进入提交。

## 9. 初始化验收清单

- [x] 总项目目录与后端子目录职责分离。
- [x] 后端 Maven 坐标修正完成。
- [x] Java 包名修正完成。
- [x] Spring Boot 配置转换为 YAML。
- [x] 小程序、部署和文档目录已创建。
- [x] 根目录 README 已创建。
- [x] 根目录 Git 忽略与换行规则已创建。
- [x] 未创建嵌套 Git 仓库。
- [x] GitHub SSH 远程仓库可访问。
- [x] 远端初始状态已检查。
- [ ] Windows 全局 `JAVA_HOME` 永久切换为 JDK 21。
- [ ] 本地 MySQL 开发库和测试库创建完成。
- [x] 基础 `mvnw.cmd clean install` 已通过。
- [ ] 配置测试数据库后，数据库集成测试通过。

## 10. 下一步

下一阶段按照以下顺序执行：

1. 永久修正或在终端会话中切换 `JAVA_HOME` 到 JDK 21。
2. 安装或检查 MySQL 8.4 LTS。
3. 创建 `account_book_dev` 和 `account_book_test`。
4. 创建开发环境配置和环境变量模板。
5. 添加第一版 Flyway 数据库迁移。
6. 重新运行 Maven 测试。
7. 再创建微信小程序 TypeScript 工程。

## 11. GitHub 初始化执行结果

2026-08-13 已完成 Git 初始化并推送到 GitHub。

远程仓库：

```text
git@github.com:Telencephalon/MyTallyBook_Project.git
```

默认分支及跟踪关系：

```text
main → origin/main
```

首个提交：

```text
f568103 chore: initialize MyTallyBook project structure
```

已核对本地和远端完整提交哈希一致：

```text
f5681035206ad547b9fde0112e95f9d3dbce0607
```

推送后的工作区状态：

```text
## main...origin/main
```

没有未提交文件，根目录之外也没有嵌套 Git 仓库。
