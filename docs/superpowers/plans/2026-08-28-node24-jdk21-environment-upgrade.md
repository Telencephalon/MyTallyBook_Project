# Node 24 LTS and JDK 21 Environment Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace old NVM-managed Node.js versions with Node.js 24.20.0 LTS, upgrade the mini-program TypeScript toolchain, and set the Windows system JDK to Microsoft OpenJDK 21.0.11 LTS.

**Architecture:** NVM for Windows remains the only Node.js version manager and keeps all existing Node.js installations for rollback. Java verification compares user and process environment variables, executable resolution, Java runtime output, and Maven Wrapper output.

**Tech Stack:** Windows PowerShell, NVM for Windows 1.2.2, Node.js 24.20.0 LTS, npm, Microsoft OpenJDK 21, Maven Wrapper 3.9.16.

**Spec:** `docs/04-项目版本基线与本地环境升级方案.md`

## Global Constraints

- Install and activate Node.js 24.20.0 64-bit through the existing NVM for Windows installation.
- Remove Node.js 13.14.0, 16.20.2, and 18.20.8 after Node.js 24.20.0 is active.
- Set machine-level `JAVA_HOME` to `D:\Work\Config\JDK\JDK\jdk21` and replace the old Java 8 machine `Path` entry with `%JAVA_HOME%\bin`.
- Do not delete the installed Java 8 files.
- Pin npm 11.19.0, TypeScript 5.9.3, and `miniprogram-api-typings` 5.2.3.
- Record actual verification results in the Markdown specification.

---

### Task 1: Capture and preserve the environment baseline

**Files:**
- Modify: `docs/04-项目版本基线与本地环境升级方案.md`

**Interfaces:**
- Consumes: Windows user and process environment, NVM installation list.
- Produces: A baseline against which the post-change verification is compared.

- [x] **Step 1: Capture Node and NVM state**

Run:

```powershell
nvm version
nvm list
nvm current
where.exe node
node --version
npm --version
```

Expected: NVM 1.2.2, Node.js 16.20.2 active before the change.

- [x] **Step 2: Capture Java state without modifying it**

Run:

```powershell
[Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
[Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
$env:JAVA_HOME
where.exe java
java -version
.\account-book-server\mvnw.cmd --version
```

Expected before manual correction: user, process, Java executable, and Maven Wrapper still resolve Java 8.

### Task 2: Install and activate Node.js 24 LTS

**Files:**
- Modify: NVM-managed runtime directory and NVM symlink only.

**Interfaces:**
- Consumes: NVM for Windows 1.2.2.
- Produces: Active Node.js 24.20.0 runtime with its bundled npm.

- [x] **Step 1: Install the exact 64-bit LTS release**

Run:

```powershell
nvm install 24.20.0 64
```

Expected: NVM reports Node.js 24.20.0 installed successfully.

- [x] **Step 2: Activate Node.js 24.20.0**

Run:

```powershell
nvm use 24.20.0
```

Expected: NVM reports that Node.js 24.20.0 64-bit is now in use.

- [x] **Step 3: Verify the active runtime**

Run:

```powershell
nvm current
nvm list
where.exe node
node --version
npm --version
```

Expected: current and active Node.js are 24.20.0; npm is executable from the same NVM-managed installation; old Node versions remain listed.

- [x] **Step 4: Remove old Node.js versions**

Run:

```powershell
nvm uninstall 13.14.0
nvm uninstall 16.20.2
nvm uninstall 18.20.8
nvm list
```

Expected: only 24.20.0 remains. If an old executable is locked by the currently running Codex process, record the exact lock owner and rerun the corresponding uninstall command after Codex restarts.

### Task 3: Upgrade and verify the mini-program Node toolchain

**Files:**
- Create: `.nvmrc`
- Modify: `account-book-miniapp/package.json`
- Modify: `account-book-miniapp/package-lock.json`

**Interfaces:**
- Consumes: Node.js 24.20.0 and npm 11.19.0.
- Produces: Reproducible TypeScript 5.9.3 and WeChat API typings 5.2.3 installation.

- [x] **Step 1: Record the Node.js version and pin toolchain versions**

Create `.nvmrc` containing `24.20.0`. Set `packageManager` to `npm@11.19.0`, require Node.js 24, set TypeScript to `5.9.3`, and set `miniprogram-api-typings` to `5.2.3`.

- [x] **Step 2: Rebuild npm dependency state**

Run:

```powershell
Set-Location -LiteralPath '.\account-book-miniapp'
npm install
```

Expected: npm completes successfully and creates executable shims under `node_modules\.bin`.

- [x] **Step 3: Run type checking and dependency verification**

Run:

```powershell
npm run typecheck
npm ls --depth=0
Test-Path -LiteralPath '.\node_modules\.bin\tsc.cmd'
```

Expected: typecheck succeeds, both pinned dependencies are installed without `UNMET DEPENDENCY`, and the TypeScript shim exists.

### Task 4: Verify JDK and document the actual outcome

**Files:**
- Create: `deploy/scripts/configure-windows-jdk21.ps1`
- Modify: `docs/04-项目版本基线与本地环境升级方案.md`

**Interfaces:**
- Consumes: Installed JDK at `D:\Work\Config\JDK\JDK\jdk21` and current Windows environment.
- Produces: A repeatable elevated configuration script and evidence that Java and Maven resolve JDK 21.

- [x] **Step 1: Verify the installed JDK 21 binary directly**

Run:

```powershell
& 'D:\Work\Config\JDK\JDK\jdk21\bin\java.exe' -version
```

Expected: Microsoft OpenJDK 21.0.11 LTS.

- [x] **Step 2: Configure the machine Java environment**

Run the repository script with administrator rights:

```powershell
Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','deploy\scripts\configure-windows-jdk21.ps1'
```

Expected: machine-level `JAVA_HOME` becomes `D:\Work\Config\JDK\JDK\jdk21`, the old Java 8 `Path` entry is removed, and `%JAVA_HOME%\bin` is the first machine `Path` entry.

- [x] **Step 3: Verify default Java resolution**

Run:

```powershell
[Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
$env:JAVA_HOME
where.exe java
java -version
.\account-book-server\mvnw.cmd --version
```

Expected for a successful manual switch: every command resolves JDK 21. If any command resolves Java 8, report that the manual Windows environment switch is incomplete and do not alter it.

- [x] **Step 4: Update the result section and status**

Record the exact Node.js, npm, JDK, Maven, executable paths, and timestamp. Mark the document status as completed for Node.js and either passed or action-required for JDK.

### Task 5: Final safety verification

**Files:**
- Verify only: repository working tree.

**Interfaces:**
- Consumes: Completed Tasks 1-4.
- Produces: Confirmation that the runtime switch did not overwrite user project files.

- [x] **Step 1: Confirm only the target Node.js version remains**

Run:

```powershell
nvm list
```

Expected: only 24.20.0 is listed, except for a precisely documented executable lock held by the current Codex process.

- [x] **Step 2: Inspect repository changes**

Run:

```powershell
git status --short
git diff -- docs/04-项目版本基线与本地环境升级方案.md docs/superpowers/plans/2026-08-28-node24-jdk21-environment-upgrade.md
```

Expected: the Node version file, mini-program package metadata and the two planned Markdown documents are added or updated; existing mini-program source files remain untouched.
