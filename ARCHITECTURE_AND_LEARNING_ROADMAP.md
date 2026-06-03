# Nanobot-Java 架构说明与新手学习路线

---

## 一、项目概述

Nanobot-Java 是基于香港大学开源的 Nanobot（mini 版 OpenClaw）项目进行的 Java 重写。这是一个轻量级的 AI Agent 框架，遵循 **"Core stays small; extend at the edges"**（核心保持精简，通过边缘扩展）的设计理念。

**项目位置**：`d:\IdeaProjects\个人项目\ai-vibe-coding\nanobot-java`

---

## 二、架构说明

### 2.1 整体架构分层

```
┌─────────────────────────────────────────────────────────────────────┐
│                      用户层 (Channels)                             │
│   Telegram | Discord | WeChat | WebSocket | CLI | ...              │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    消息总线层 (MessageBus)                          │
│           Inbound Queue              Outbound Queue                 │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     核心引擎层 (AgentLoop)                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐               │
│  │AgentLoop │ │AgentRunner│ │TurnContext│ │  Hooks   │               │
│  │ (状态机) │ │ (LLM循环) │ │ (上下文)  │ │ (扩展点) │               │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        ▼                      ▼                      ▼
┌───────────────┐    ┌───────────────┐    ┌───────────────┐
│   工具层      │    │   提供商层    │    │   内存层      │
│  ToolRegistry │    │  LLMProvider  │    │ MemoryStore   │
│  + Tools      │    │  + Providers  │    │ + Sessions    │
└───────────────┘    └───────────────┘    └───────────────┘
```

### 2.2 核心组件职责

| 组件 | 职责 | 文件位置 |
|------|------|----------|
| **Nanobot** | 应用入口，组件初始化和生命周期管理 | `Nanobot.java` |
| **AgentLoop** | 状态机引擎，管理消息处理流程 | `core/AgentLoop.java` |
| **AgentRunner** | LLM 调用循环，处理工具调用 | `core/AgentRunner.java` |
| **TurnContext** | 会话上下文，存储消息和状态 | `core/TurnContext.java` |
| **TurnState** | 状态枚举，定义状态机节点 | `core/TurnState.java` |
| **MessageBus** | 消息总线，异步队列通信 | `bus/MessageBus.java` |
| **ToolRegistry** | 工具注册中心 | `tools/ToolRegistry.java` |
| **Tool** | 工具接口，定义工具契约 | `tools/Tool.java` |
| **LLMProvider** | LLM 提供商接口 | `providers/LLMProvider.java` |
| **SessionManager** | 会话管理器 | `session/SessionManager.java` |
| **MemoryStore** | 内存持久化存储 | `memory/MemoryStore.java` |
| **Config / ConfigLoader** | 配置加载和管理 | `config/` |
| **AgentHook / CompositeHook** | 钩子系统，生命周期扩展 | `core/hook/` |
| **MCPManager** | MCP 服务器管理和工具注册 | `mcp/MCPManager.java` |
| **MCPClient / StdioMCPClient / HttpMCPClient** | MCP 客户端实现 | `mcp/` |
| **MCPToolWrapper** | MCP 工具包装器 | `mcp/MCPToolWrapper.java` |
| **CronScheduler** | 定时任务调度器 | `cron/CronScheduler.java` |
| **Consolidator** | 记忆压缩器 | `memory/Consolidator.java` |
| **Dream** | 长期记忆系统 | `memory/Dream.java` |
| **ChannelServer** | 多通道接入服务器 | `channels/ChannelServer.java` |

### 2.3 定时任务系统 (CronScheduler)

**功能说明**：基于 cron 表达式的定时任务调度器，支持在指定时间执行任务。

**支持的 cron 表达式格式**：
```
┌───────────── 分钟 (0 - 59)
│ ┌───────────── 小时 (0 - 23)
│ │ ┌───────────── 日期 (1 - 31)
│ │ │ ┌───────────── 月份 (1 - 12)
│ │ │ │ ┌───────────── 星期 (0 - 6) (周日=0)
│ │ │ │ │
* * * * *
```

**使用示例**：
```java
CronScheduler scheduler = new CronScheduler(messageBus);
scheduler.schedule("0 * * * *", () -> {
    messageBus.publish(OutboundMessage.builder()
        .channel("system")
        .content("每分钟执行一次")
        .build());
});
```

**支持的特殊字符**：
- `*` : 匹配任何值
- `?` : 不指定值（用于日期和星期）
- `,` : 列出多个值
- `-` : 指定范围
- `/` : 步长

### 2.4 记忆压缩系统 (Consolidator)

**功能说明**：当对话历史超过 token 预算时，自动压缩历史消息，保留关键信息。

**压缩策略**：
1. 计算当前对话的 token 使用量
2. 如果超过预算，选择最不重要的消息进行压缩
3. 使用 LLM 对选中的消息进行总结
4. 用总结替换原始消息

**优先级规则**：
- 系统消息：最高优先级，不压缩
- 工具调用结果：高优先级，保留关键结果
- 用户消息：中等优先级，可适当压缩
- 助手消息：低优先级，优先压缩

**核心方法**：
```java
// 压缩消息列表
CompletableFuture<List<Map<String, Object>>> consolidate(List<Map<String, Object>> messages)

// 检查是否需要压缩
boolean needsConsolidation(List<Map<String, Object>> messages)

// 获取当前 token 使用量
int getCurrentUsage(List<Map<String, Object>> messages)
```

### 2.5 长期记忆系统 (Dream)

**功能说明**：实现 AI Agent 的长期记忆功能，允许 Agent 存储和检索长期信息。

**核心功能**：
1. **记忆存储** - 将对话中的关键信息保存到长期记忆
2. **记忆检索** - 根据当前上下文检索相关记忆
3. **记忆整合** - 将新信息与现有记忆融合
4. **记忆清理** - 移除过时或重复的记忆

**记忆结构**：
| 字段 | 说明 |
|------|------|
| id | 唯一标识 |
| content | 记忆内容 |
| keywords | 关键词标签 |
| timestamp | 创建时间 |
| importance | 重要性分数 (0-1) |
| source | 来源会话 ID |

**核心方法**：
```java
// 从对话中提取并存储记忆
CompletableFuture<List<MemoryEntry>> extractAndStore(String sessionId, List<Map<String, Object>> messages)

// 检索相关记忆
CompletableFuture<List<MemoryEntry>> retrieve(String query, int limit)

// 整合新信息到现有记忆
CompletableFuture<MemoryEntry> consolidate(MemoryEntry newMemory)

// 清理过时记忆
void cleanup()
```

### 2.6 多通道接入系统 (ChannelServer)

**功能说明**：提供 HTTP 和 WebSocket 通道的统一管理，允许客户端通过多种方式与 Agent 交互。

**支持的通道类型**：

| 通道类型 | 说明 | 端点 |
|---------|------|------|
| HTTP REST | 同步请求/响应模式 | `/api/chat` |
| WebSocket | 异步双向通信 | `/ws` |

**HTTP API 端点**：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/chat` | POST | 发送消息 |
| `/api/sessions` | GET | 获取会话列表 |
| `/api/sessions/{id}` | DELETE | 删除会话 |

**使用示例**：
```java
ChannelServer server = new ChannelServer(messageBus, 8080);
server.start();
```

### 2.7 状态机流程

`AgentLoop` 采用状态机模式管理消息处理：

```
RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE
    │          │         │        │       │       │         │
    ▼          ▼         ▼        ▼       ▼       ▼         ▼
 恢复会话   压缩历史   命令分发  构建上下文 LLM调用 保存状态 发送响应
```

**状态转换表**：

| 当前状态 | 事件 | 下一状态 | 说明 |
|---------|------|---------|------|
| RESTORE | ok | COMPACT | 加载会话历史 |
| COMPACT | ok | COMMAND | 压缩历史消息 |
| COMMAND | dispatch | BUILD | 分发普通消息 |
| COMMAND | shortcut | DONE | 处理快捷命令（如 `/stop`） |
| BUILD | ok | RUN | 构建 LLM 上下文 |
| RUN | ok | SAVE | 执行 LLM 调用和工具 |
| SAVE | ok | RESPOND | 保存会话状态 |
| RESPOND | ok | DONE | 发送响应 |

### 2.4 模块结构

```
nanobot-java/
├── src/main/java/com/nanobot/
│   ├── Nanobot.java           # 主入口
│   ├── bus/                   # 消息总线
│   │   ├── MessageBus.java
│   │   ├── InboundMessage.java
│   │   └── OutboundMessage.java
│   ├── config/                # 配置系统
│   │   ├── Config.java
│   │   └── ConfigLoader.java
│   ├── core/                  # 核心引擎
│   │   ├── AgentLoop.java
│   │   ├── AgentRunner.java
│   │   ├── TurnContext.java
│   │   ├── TurnState.java
│   │   └── hook/              # 钩子系统
│   │       ├── AgentHook.java
│   │       ├── AgentHookContext.java
│   │       └── CompositeHook.java
│   ├── channels/              # 多通道接入
│   │   └── ChannelServer.java
│   ├── cron/                   # 定时任务系统
│   │   └── CronScheduler.java
│   ├── memory/                # 内存存储
│   │   ├── MemoryStore.java
│   │   ├── Consolidator.java   # 记忆压缩器
│   │   └── Dream.java          # 长期记忆系统
│   ├── mcp/                   # MCP (Model Context Protocol)
│   │   ├── MCPClient.java
│   │   ├── StdioMCPClient.java
│   │   ├── HttpMCPClient.java
│   │   ├── MCPManager.java
│   │   ├── MCPToolWrapper.java
│   │   ├── MCPToolInfo.java
│   │   ├── MCPResult.java
│   │   └── MCPMessage.java
│   ├── providers/             # LLM提供商
│   │   ├── LLMProvider.java
│   │   ├── LLMResponse.java
│   │   └── impl/
│   │       └── OpenAIProvider.java
│   ├── session/               # 会话管理
│   │   └── SessionManager.java
│   └── tools/                 # 工具系统
│       ├── Tool.java
│       ├── ToolRegistry.java
│       ├── Schema.java
│       └── impl/
│           ├── ReadFileTool.java
│           ├── WriteFileTool.java
│           ├── EditFileTool.java
│           ├── ListDirTool.java
│       │   │   ├── GlobTool.java
│   │   ├── GrepTool.java
│   │   ├── ExecTool.java
│   │   ├── WebSearchTool.java    # 网页搜索工具
│   │   └── WebFetchTool.java     # 网页内容抓取工具
├── src/main/resources/
│   ├── config/
│   │   └── config.yaml
│   └── logback.xml
└── pom.xml
```

### 2.5 核心接口设计

#### Tool 接口

工具是 Agent 与外部世界交互的核心方式：

```java
public interface Tool {
    String getName();                    // 工具名称
    String getDescription();             // 工具描述
    JsonNode getParameters();            // 参数 JSON Schema
    boolean isReadOnly();                // 是否只读
    boolean isExclusive();               // 是否独占执行
    CompletableFuture<Object> execute(Map<String, Object> params);  // 执行方法
}
```

#### LLMProvider 接口

统一的 LLM API 调用接口：

```java
public interface LLMProvider {
    String getName();
    CompletableFuture<LLMResponse> chat(List<Message> messages, List<JsonNode> tools);
    CompletableFuture<LLMResponse> chatStream(List<Message> messages, 
                                               List<JsonNode> tools, 
                                               Consumer<String> onDelta);
}
```

#### AgentHook 接口

生命周期钩子扩展点：

```java
public interface AgentHook {
    void onMessageReceived(TurnContext context);
    void onMessageProcessed(TurnContext context);
    void onToolCalled(TurnContext context, String toolName, Map<String, Object> params);
    void onError(TurnContext context, Throwable error);
}
```

#### MCP 相关接口

**MCPClient 接口** - MCP 客户端接口：

```java
public interface MCPClient {
    CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> arguments);
    CompletableFuture<List<MCPToolInfo>> listTools();
    CompletableFuture<MCPResult> readResource(String uri);
    CompletableFuture<MCPResult> getPrompt(String promptName, Map<String, Object> arguments);
    void close();
    boolean isConnected();
    String getServerName();
}
```

**MCPToolWrapper 类** - 将 MCP 工具包装为 Nanobot Tool：

```java
public class MCPToolWrapper implements Tool {
    private final MCPClient client;
    private final MCPToolInfo toolInfo;
    private final String qualifiedName;  // mcp_<server>_<tool>
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return client.callTool(toolInfo.getName(), params)
            .thenApply(result -> result.toString());
    }
}
```

---

## 二、架构说明（续）

### 2.6 MCP (Model Context Protocol) 系统

**MCP**（Model Context Protocol）是由 Cursor 编辑器提出的标准化协议，用于连接 AI Agent 与外部工具/服务。Nanobot 通过 MCP 支持，可以动态加载和使用第三方工具，而无需修改核心代码。

#### MCP 架构

```
┌─────────────────────────────────────────────────────────────┐
│                      MCP 服务器层                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐      │
│  │ Git MCP  │  │ 天气 MCP │  │  其他 MCP 服务...     │      │
│  └────┬─────┘  └────┬─────┘  └──────────┬───────────┘      │
│       │             │                    │                  │
└───────┼─────────────┼────────────────────┼─────────────────┘
        │             │                    │
        ▼             ▼                    ▼
┌─────────────────────────────────────────────────────────────┐
│                    MCP 客户端层                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │   StdioMCPClient    │    HttpMCPClient               │   │
│  │   (进程间通信)       │    (HTTP/SSE 通信)            │   │
│  └──────────────────────────────────────────────────────┘   │
│                          │                                  │
│                          ▼                                  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                  MCPManager                           │   │
│  │  - 管理多个 MCP 服务器连接                            │   │
│  │  - 自动发现和注册工具                                 │   │
│  │  - 统一生命周期管理                                   │   │
│  └──────────────────────────────────────────────────────┘   │
│                          │                                  │
└──────────────────────────┼──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    ToolRegistry                            │
│        MCP 工具被包装为标准 Tool 注册到此处                  │
└─────────────────────────────────────────────────────────────┘
```

#### 支持的传输方式

| 传输类型 | 说明 | 适用场景 |
|---------|------|---------|
| **stdio** | 通过标准输入输出与进程通信 | 本地 MCP 服务器（如 git-mcp） |
| **sse** | Server-Sent Events | 实时推送场景 |
| **streamableHttp** | HTTP 流式传输 | 远程 MCP 服务 |

#### MCP 工具命名规则

注册后的工具名称格式：`mcp_<server_name>_<tool_name>`

例如：
- 服务器名 `git` + 工具名 `status` → `mcp_git_status`
- 服务器名 `weather` + 工具名 `get` → `mcp_weather_get`

---

## 三、新手学习路线

### 3.1 学习阶段规划

| 阶段 | 目标 | 时间 | 关键知识点 |
|------|------|------|-----------|
| **Phase 1** | 环境搭建与入门 | 1-2 天 | Java 21, Maven, 项目结构 |
| **Phase 2** | 核心组件理解 | 3-5 天 | 状态机、消息总线、工具系统 |
| **Phase 3** | 深入核心机制 | 3-5 天 | LLM 调用、会话管理、钩子系统 |
| **Phase 4** | 扩展开发 | 3-5 天 | 自定义工具、新提供商、通道扩展 |

### 3.2 Phase 1：环境搭建与入门

**目标**：搭建开发环境，了解项目结构，成功运行项目

#### 步骤 1：环境准备

- 安装 JDK 21+
- 安装 Maven 3.9+
- 配置 IDE（IntelliJ IDEA 推荐）

#### 步骤 2：项目结构探索

```bash
cd d:\IdeaProjects\个人项目\ai-vibe-coding\nanobot-java
```

查看目录结构，理解各模块职责：
- `src/main/java/com/nanobot/` - 源代码
- `src/main/resources/` - 配置文件
- `pom.xml` - Maven 依赖管理

#### 步骤 3：配置运行

1. 配置 OpenAI API Key：
   - 创建 `~/.nanobot/config.yaml`
   - 或设置环境变量 `OPENAI_API_KEY`

2. 编译项目：
```bash
mvn clean compile
```

3. 运行项目：
```bash
mvn exec:java -Dexec.mainClass="com.nanobot.Nanobot"
```

### 3.3 Phase 2：核心组件理解

**目标**：理解状态机、消息总线、工具系统的设计

#### 学习路径：

1. **Nanobot.java** - 主入口
   - 组件初始化流程
   - 生命周期管理

2. **AgentLoop.java** - 状态机引擎
   - 状态转换逻辑
   - 消息处理流程

3. **MessageBus.java** - 消息总线
   - 异步队列机制
   - 入站/出站消息模型

4. **Tool.java + ToolRegistry.java** - 工具系统
   - 工具接口设计
   - 工具注册与执行

#### 实践练习：

1. 跟踪一条消息从进入到响应的完整流程
2. 添加一个简单的自定义工具
3. 调试状态机转换过程

### 3.4 Phase 3：深入核心机制

**目标**：理解 LLM 调用、会话管理、钩子系统

#### 学习路径：

1. **AgentRunner.java** - LLM 调用循环
   - 多轮工具调用
   - 上下文治理

2. **LLMProvider.java** - 提供商接口
   - 流式与非流式调用
   - 工具调用处理

3. **SessionManager.java** - 会话管理
   - 历史存储与加载
   - 会话隔离

4. **AgentHook.java** - 钩子系统
   - 生命周期扩展点
   - CompositeHook 组合模式

#### 实践练习：

1. 实现一个自定义 LLMProvider
2. 添加会话压缩逻辑
3. 实现一个监控钩子

### 3.5 Phase 4：扩展开发

**目标**：能够扩展新功能

#### 学习路径：

1. **添加新工具**
   - 实现 Tool 接口
   - 注册到 ToolRegistry

2. **添加新提供商**
   - 实现 LLMProvider 接口
   - 处理不同 API 格式

3. **添加新通道**（未来扩展）
   - 实现通道接口
   - 集成到消息总线

#### 实践练习：

1. 创建一个天气查询工具
2. 实现 Anthropic 提供商
3. 添加 WebSocket 通道

---

## 四、关键技术选型

| 功能 | 技术方案 | 理由 |
|------|---------|------|
| **异步编程** | Virtual Threads + CompletableFuture | Java 21 新特性，高性能并发 |
| **JSON 处理** | Jackson | 成熟稳定，支持 Schema 验证 |
| **HTTP 客户端** | HttpClient (Java 11+) | 内置，支持异步 |
| **配置管理** | Jackson + YAML | 支持复杂嵌套配置 |
| **日志** | SLF4J + Logback | Java 标准日志框架 |
| **定时任务** | ScheduledExecutorService | 内置定时能力 |
| **文件 I/O** | NIO.2 (Files/Paths) | 现代文件 API |

---

## 五、配置说明

### 5.1 配置文件位置

```
~/.nanobot/config.yaml
```

或通过命令行指定：
```bash
java -jar nanobot-java.jar --config /path/to/config.yaml
```

### 5.2 配置结构

```yaml
agents:
  defaults:
    model: gpt-4o-mini
    workspace: ~/.nanobot/workspace
    max_tokens: 4096
    temperature: 0.7
    max_tool_iterations: 10

providers:
  openai:
    api_key: ${OPENAI_API_KEY}
    api_base: https://api.openai.com/v1

tools:
  exec:
    enable: false

# MCP 服务器配置
mcp_servers:
  # stdio 模式 - 通过命令启动 MCP 服务器
  git:
    command: "npx"
    args: ["-y", "git-mcp@latest"]
    tool_timeout: 30
    enabled_tools: ["*"]
  
  # HTTP 模式 - 连接远程 MCP 服务器
  weather:
    type: "streamableHttp"
    url: "https://api.example.com/mcp"
    headers:
      Authorization: "Bearer token"

channels:
  send_progress: true
```

### 5.3 环境变量

| 变量名 | 说明 |
|--------|------|
| `OPENAI_API_KEY` | OpenAI API 密钥 |
| `NANOBOT_MODEL` | 默认模型 |
| `NANOBOT_WORKSPACE` | 工作目录 |

---

## 六、编译、启动与部署

### 6.1 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| Java | 21+ | 核心运行环境 |
| Maven | 3.9+ | 构建工具 |
| Git | 2.x | 版本控制（可选） |

### 6.2 编译项目

```bash
# 编译项目
mvn clean compile

# 运行测试
mvn test

# 打包项目（包含依赖）
mvn clean package

# 打包跳过测试
mvn clean package -DskipTests
```

### 6.3 启动运行

#### 开发模式运行

```bash
# 使用 exec 插件运行
mvn exec:java -Dexec.mainClass="com.nanobot.Nanobot"

# 指定配置文件
mvn exec:java -Dexec.mainClass="com.nanobot.Nanobot" -Dexec.args="--config path/to/config.yaml"
```

#### 生产模式运行

```bash
# 打包后运行
java -jar target/nanobot-1.0.0.jar

# 指定配置文件
java -jar target/nanobot-1.0.0.jar --config path/to/config.yaml

# 设置 JVM 参数
java -Xmx2g -Xms512m -jar target/nanobot-1.0.0.jar
```

### 6.4 配置环境变量

```bash
# Linux/Mac
export OPENAI_API_KEY=your-api-key
export NANOBOT_MODEL=gpt-4o-mini
export NANOBOT_WORKSPACE=~/.nanobot/workspace

# Windows PowerShell
$env:OPENAI_API_KEY="your-api-key"
$env:NANOBOT_MODEL="gpt-4o-mini"
$env:NANOBOT_WORKSPACE="~/.nanobot/workspace"
```

### 6.5 Docker 部署

**Dockerfile 示例**：

```dockerfile
FROM openjdk:21-jdk-slim

WORKDIR /app

COPY target/nanobot-1.0.0.jar app.jar

ENV OPENAI_API_KEY=your-api-key
ENV NANOBOT_MODEL=gpt-4o-mini

CMD ["java", "-jar", "app.jar"]
```

**构建和运行**：

```bash
# 构建镜像
docker build -t nanobot .

# 运行容器
docker run -d \
  --name nanobot \
  -e OPENAI_API_KEY=your-api-key \
  -v ~/.nanobot:/root/.nanobot \
  nanobot
```

### 6.6 常见启动问题

| 问题 | 解决方案 |
|------|----------|
| API Key 未设置 | 检查环境变量 `OPENAI_API_KEY` |
| 端口被占用 | 修改配置中的端口或停止占用进程 |
| 依赖下载失败 | 使用 `mvn clean compile -U` 强制更新 |
| 内存不足 | 增加 JVM 堆内存 `-Xmx2g` |

---

## 七、调试与日志

### 7.1 日志配置

日志配置文件：`src/main/resources/logback.xml`

日志级别：
- `DEBUG` - 详细调试信息
- `INFO` - 一般信息
- `WARN` - 警告
- `ERROR` - 错误

### 7.2 调试技巧

1. 设置断点跟踪状态机转换
2. 查看消息队列的入队出队
3. 监控 LLM 调用的请求/响应
4. 使用钩子记录执行时间

---

## 七、扩展建议

### 7.1 添加新工具

```java
public class WeatherTool implements Tool {
    @Override
    public String getName() { return "get_weather"; }
    
    @Override
    public String getDescription() { 
        return "获取指定城市的天气信息"; 
    }
    
    @Override
    public JsonNode getParameters() { /* JSON Schema */ }
    
    @Override
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        String city = (String) params.get("city");
        // 调用天气 API
        return CompletableFuture.completedFuture("天气结果...");
    }
}

// 注册
toolRegistry.register(new WeatherTool());
```

### 7.2 添加新提供商

```java
public class AnthropicProvider implements LLMProvider {
    @Override
    public String getName() { return "anthropic"; }
    
    @Override
    public CompletableFuture<LLMResponse> chat(List<Message> messages, List<JsonNode> tools) {
        // 调用 Anthropic API
    }
}
```

### 7.3 配置 MCP 服务器

MCP 允许动态加载第三方工具，无需修改代码：

```yaml
mcp_servers:
  git:
    command: "npx"
    args: ["-y", "git-mcp@latest"]
    tool_timeout: 30
```

### 7.4 添加自定义 MCP 客户端

```java
public class CustomMCPClient implements MCPClient {
    @Override
    public CompletableFuture<MCPResult> callTool(String toolName, Map<String, Object> arguments) {
        // 实现自定义通信逻辑
    }
}
```

---

## 八、常见问题

### Q1：如何配置 API Key？

A：可以通过配置文件或环境变量：
```yaml
providers:
  openai:
    api_key: your-api-key
```
或
```bash
export OPENAI_API_KEY=your-api-key
```

### Q2：如何添加自定义工具？

A：实现 `Tool` 接口，然后在 `Nanobot.initialize()` 中注册。

### Q3：如何扩展支持其他 LLM？

A：实现 `LLMProvider` 接口，处理特定 API 的格式差异。

### Q4：项目依赖哪些库？

A：主要依赖：
- Jackson（JSON 处理）
- SLF4J + Logback（日志）
- OkHttp（HTTP 客户端）

---

## 九、学习资源

1. **官方文档**：`docs/` 目录下的文档
2. **架构分析**：`nanobot_architecture_analysis.md`
3. **测试代码**：参考现有工具和提供商实现
4. **原始项目**：`../nanobot/` 目录下的 Python 源码

---

## 十、总结

Nanobot-Java 的核心价值在于：

1. **简洁的架构设计**：状态机驱动的消息处理
2. **灵活的扩展能力**：插件化的工具和提供商机制
3. **异步优先**：使用 Virtual Threads 实现高性能并发
4. **新手友好**：清晰的模块划分和详细的注释

**建议学习顺序**：
1. 从 `Nanobot.java` 入口开始，理解组件初始化
2. 深入 `AgentLoop.java`，理解状态机流程
3. 学习 `AgentRunner.java`，理解 LLM 调用循环
4. 研究 `Tool.java` 和 `LLMProvider.java`，了解扩展机制

祝你学习愉快！🚀
