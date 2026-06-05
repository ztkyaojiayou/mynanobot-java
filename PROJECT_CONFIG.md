# Nanobot-Java 项目配置文档

## 项目概述
本文档记录 Nanobot-Java 项目的关键配置信息，确保每次操作都使用正确的环境和路径。

---

## 1. 环境配置

### 1.1 Java 环境
- **推荐 JDK 版本**: Java 21 (LTS)
- **JDK 21 路径**: `D:\devSoftWare\jdk21`
- **环境变量设置**:
  ```powershell
  $env:JAVA_HOME="D:\devSoftWare\jdk21"
  $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
  ```

### 1.2 Maven 配置
- **Maven 路径**: `D:\devSoftWare\maven`
- **Maven 版本**: 3.9.x (随 IDE 自带)

---

## 2. 项目路径

### 2.1 工作目录
- **主项目目录**: `D:\IdeaProjects\个人项目\ai-vibe-coding\nanobot-java`
- **注意**: 不要混淆 `nanobot-java` 和 `nanobot` (Python项目)

### 2.2 关键文件位置
| 文件/目录 | 路径 |
|----------|------|
| 主类 | `src/main/java/com/nanobot/Nanobot.java` |
| 配置文件 | `src/main/resources/config/config.yaml` |
| 前端页面 | `src/main/resources/static/index.html` |
| 编译输出 | `target/classes/` |

---

## 3. Git 配置

### 3.1 用户信息
- **用户名**: 需要确认
- **邮箱**: 需要确认
- **默认分支**: `main`

### 3.2 Git 操作命令
```bash
# 查看当前配置
git config user.name
git config user.email

# 设置用户信息（首次使用）
git config user.name "Your Name"
git config user.email "your.email@example.com"

# 添加所有文件
git add .

# 提交
git commit -m "commit message"

# 推送到远程
git push origin main
```

---

## 4. 常用命令

### 4.1 编译项目
```powershell
$env:JAVA_HOME="D:\devSoftWare\jdk21"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn compile
```

### 4.2 打包项目
```powershell
$env:JAVA_HOME="D:\devSoftWare\jdk21"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn package -DskipTests
```

### 4.3 运行项目
```powershell
$env:JAVA_HOME="D:\devSoftWare\jdk21"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn "exec:java" "-Dexec.mainClass=com.nanobot.Nanobot"
```

### 4.4 启动服务后访问
- **HTTP 地址**: `http://localhost:8080`
- **WebSocket 地址**: `ws://localhost:8080/ws`

---

## 5. 项目特性

### 5.1 已实现功能
- ✅ WebSocket 流式对话
- ✅ HTTP REST API
- ✅ 联网搜索功能（baidu_web）
- ✅ hooks 系统
- ✅ subagent 编排协调
- ✅ 消息总线

### 5.2 配置要点
- **搜索提供商**: `baidu_web`（国内可访问，无需 API Key）
- **默认端口**: `8080`
- **模型**: `deepseek-chat`
- **API Base**: `https://api.deepseek.com`

---

## 6. 常见问题

### 6.1 编译错误 - 无效的目标发行版
**问题**: `无效的目标发行版: 21`

**原因**: JAVA_HOME 指向了旧版本 JDK（如 JDK 1.8）

**解决方案**:
```powershell
$env:JAVA_HOME="D:\devSoftWare\jdk21"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

### 6.2 前端无响应
**检查项**:
1. 服务是否正常启动在 `http://localhost:8080`
2. WebSocket 连接状态（浏览器控制台查看）
3. 后端日志是否有错误

---

## 7. 更新记录

| 日期 | 内容 | 作者 |
|------|------|------|
| 2026-06-05 | 初始文档创建 | System |

---

**备注**: 执行任何操作前，请先确认当前工作目录和环境配置正确！
