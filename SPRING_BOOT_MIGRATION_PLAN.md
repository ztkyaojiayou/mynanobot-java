# Spring Boot 迁移规划文档

## 项目：nanobot-java - JDK HttpServer → Spring Boot

**版本**: v1.0.0 → v2.0.0  
**制定日期**: 2026-06-05  
**状态**: 规划阶段

---

## 一、迁移目标

### 1.1 主要目标

| 目标 | 描述 | 优先级 |
|------|------|--------|
| 标准化 WebSocket | 采用标准 `@ServerEndpoint` 实现 | P0 |
| 增强安全性 | 集成 Spring Security（未来） | P1 |
| 提升可维护性 | 符合 Spring Boot 标准架构 | P1 |
| 流式响应标准化 | 使用 SseEmitter | P0 |

### 1.2 迁移范围

```
┌─────────────────────────────────────────────────────────────┐
│                      迁移范围                               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ✓ ChannelServer.java      →  移除                         │
│  ✓ SimpleWebSocketConnection →  移除                        │
│  ✓ ChatHandler             →  ChatController               │
│  ✓ WebSocketHandler        →  NanobotWebSocketEndpoint     │
│  ✓ SessionsHandler         →  SessionController            │
│  ✓ StaticHandler          →  Spring 静态资源               │
│  ✓ SSE 流式响应            →  SseEmitter                   │
│                                                             │
│  ✓ pom.xml                 →  添加 Spring Boot 依赖        │
│  ✓ Nanobot.java            →  添加 @SpringBootApplication  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 不迁移范围

- AgentLoop 核心逻辑
- ToolRegistry 工具系统
- SessionManager 会话管理
- MemoryStore 记忆存储
- LLMProvider 提供商
- 所有业务逻辑组件

---

## 二、当前系统分析

### 2.1 当前架构

```
┌─────────────────────────────────────────────────────────────┐
│                    当前架构（JDK HttpServer）                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────────────────────────────────────────┐  │
│  │              Nanobot.main()                           │  │
│  │  - 创建组件                                           │  │
│  │  - 启动 AgentLoop                                     │  │
│  │  - 启动 ChannelServer                                 │  │
│  └─────────────────────────────────────────────────────┘  │
│                            │                               │
│                            ▼                               │
│  ┌─────────────────────────────────────────────────────┐  │
│  │         ChannelServer (JDK HttpServer)               │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌────────────┐ │  │
│  │  │ ChatHandler  │  │WebSocket    │  │ Static     │ │  │
│  │  │             │  │Handler      │  │ Handler    │ │  │
│  │  │  /api/chat  │  │  /ws        │  │ /static/   │ │  │
│  │  └─────────────┘  └─────────────┘  └────────────┘ │  │
│  │         │                 │                 │       │  │
│  │         └────────┬────────┴────────┬────────┘       │  │
│  │                  ▼                 ▼                 │  │
│  │           ┌─────────────────────────────┐            │  │
│  │           │  SimpleWebSocketConnection │            │  │
│  │           │  - 文本行协议               │            │  │
│  │           │  - 手动线程管理             │            │  │
│  │           └─────────────────────────────┘            │  │
│  └─────────────────────────────────────────────────────┘  │
│                            │                               │
│                            ▼                               │
│  ┌─────────────────────────────────────────────────────┐  │
│  │                    MessageBus                         │  │
│  └─────────────────────────────────────────────────────┘  │
│                            │                               │
│                            ▼                               │
│  ┌─────────────────────────────────────────────────────┐  │
│  │                     AgentLoop                         │  │
│  └─────────────────────────────────────────────────────┘  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 当前问题

| 问题 ID | 问题描述 | 影响 | 优先级 |
|---------|----------|------|--------|
| Q1 | WebSocket 使用非标准文本协议 | 客户端兼容性问题 | P1 |
| Q2 | 无 WebSocket 心跳/保活机制 | 连接可能假死 | P2 |
| Q3 | HttpServer 无法集成 Spring Security | 安全限制 | P2 |
| Q4 | 无标准 Servlet API 支持 | 生态受限 | P3 |

---

## 三、目标架构设计

### 3.1 目标架构

```
┌─────────────────────────────────────────────────────────────┐
│                 目标架构（Spring Boot）                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────────────────────────────────────────┐  │
│  │          @SpringBootApplication                      │  │
│  │           NanobotApplication                          │  │
│  │  ┌─────────────────────────────────────────────────┐ │  │
│  │  │           CommandLineRunner                      │ │  │
│  │  │  - 初始化组件                                   │ │  │
│  │  │  - 启动 AgentLoop                              │ │  │
│  │  └─────────────────────────────────────────────────┘ │  │
│  └─────────────────────────────────────────────────────┘  │
│                            │                               │
│                            ▼                               │
│  ┌─────────────────────────────────────────────────────┐  │
│  │         Spring MVC + WebSocket                       │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌────────────┐ │  │
│  │  │ ChatController│ │ WebSocket  │  │  静态资源   │ │  │
│  │  │             │  │ Endpoint   │  │ /static/** │ │  │
│  │  │  /api/chat  │  │  /ws       │  │            │ │  │
│  │  │  /api/chat/ │  │            │  │            │ │  │
│  │  │  stream     │  │  标准帧协议 │  │            │ │  │
│  │  └─────────────┘  └─────────────┘  └────────────┘ │  │
│  └─────────────────────────────────────────────────────┘  │
│                            │                               │
│                            ▼                               │
│  ┌─────────────────────────────────────────────────────┐  │
│  │              WebSocketSession 管理                    │  │
│  │  ┌─────────────────────────────────────────────┐    │  │
│  │  │ Map<String, WebSocketSession>               │    │  │
│  │  │ - 自动心跳                                   │    │  │
│  │  │ - 标准帧解析                                 │    │  │
│  │  │ - 压缩支持                                   │    │  │
│  │  └─────────────────────────────────────────────┘    │  │
│  └─────────────────────────────────────────────────────┘  │
│                            │                               │
│                            ▼                               │
│  ┌─────────────────────────────────────────────────────┐  │
│  │                    MessageBus                         │  │
│  └─────────────────────────────────────────────────────┘  │
│                            │                               │
│                            ▼                               │
│  ┌─────────────────────────────────────────────────────┐  │
│  │                     AgentLoop                         │  │
│  └─────────────────────────────────────────────────────┘  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 组件对应关系

| 当前组件 | 目标组件 | 变化说明 |
|---------|---------|---------|
| ChannelServer | SpringApplication | 启动方式改变 |
| ChatHandler | ChatController | REST API |
| ChatHandler.streamMode | SseEmitter | SSE 流式 |
| WebSocketHandler | NanobotWebSocketEndpoint | 标准 WebSocket |
| SimpleWebSocketConnection | WebSocketSession | Spring 管理 |
| StaticHandler | Spring 静态资源配置 | 配置化 |

### 3.3 包结构设计

```
com.nanobot
├── NanobotApplication.java          # Spring Boot 启动类
├── config/
│   └── WebSocketConfig.java        # WebSocket 配置
├── controller/
│   ├── ChatController.java         # REST API
│   └── SessionController.java      # Session API
├── websocket/
│   └── NanobotWebSocketEndpoint.java # WebSocket Endpoint
└── runner/
    └── NanobotRunner.java          # 初始化 Runner
```

---

## 四、详细实施步骤

### Phase 1: 基础设施准备

#### 1.1 添加依赖

**文件**: `pom.xml`

```xml
<!-- Spring Boot Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>3.2.5</version>
</dependency>

<!-- Spring Boot WebSocket -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
    <version>3.2.5</version>
</dependency>

<!-- Spring Boot Actuator (可选，用于监控) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
    <version>3.2.5</version>
</dependency>
```

#### 1.2 创建启动类

**文件**: `NanobotApplication.java`

```java
package com.nanobot;

@SpringBootApplication
public class NanobotApplication {

    public static void main(String[] args) {
        SpringApplication.run(NanobotApplication.class, args);
    }
    
    @Bean
    public CommandLineRunner nanobotRunner(...) {
        return args -> {
            // 原 Nanobot.initialize() 和 start() 的逻辑
        };
    }
}
```

#### 1.3 创建 WebSocket 配置

**文件**: `WebSocketConfig.java`

```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    
    @Autowired
    private NanobotWebSocketEndpoint nanobotEndpoint;
    
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(nanobotEndpoint, "/ws")
                .setAllowedOrigins("*");
    }
}
```

**验证点**:
- [ ] 项目能正常启动
- [ ] `/ws` 端点可访问
- [ ] Spring Boot Banner 显示正常

---

### Phase 2: HTTP API 迁移

#### 2.1 创建 ChatController

**文件**: `ChatController.java`

```java
@RestController
@RequestMapping("/api")
public class ChatController {
    
    private final MessageBus messageBus;
    private final AgentLoop agentLoop;
    
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestBody ChatRequest request) {
        
        InboundMessage message = convertToMessage(request);
        messageBus.publishInbound(message);
        
        String response = waitForResponse(
            request.getSessionId(), 
            request.getRequestId(), 
            300
        );
        
        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "sessionId", request.getSessionId(),
            "content", response
        ));
    }
    
    @RequestMapping(value = "/chat/stream", 
                   produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        
        // 设置流式回调
        StreamResponseCallback callback = new StreamResponseCallback() {
            @Override
            public void onStreamData(String sessionId, String requestId, String content) {
                try {
                    emitter.send(content);
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }
            
            @Override
            public void onStreamComplete(String sessionId, String requestId) {
                emitter.complete();
            }
        };
        
        agentLoop.setStreamResponseCallback(callback);
        
        // 发布消息
        messageBus.publishInbound(convertToMessage(request));
        
        return emitter;
    }
}
```

#### 2.2 创建请求/响应 DTO

**文件**: `ChatRequest.java`

```java
public class ChatRequest {
    private String sessionId;
    private String content;
    private String channel;
    private boolean useSearch;
    private boolean streamMode;
    private String requestId;
    
    // getters and setters
}
```

**验证点**:
- [ ] `POST /api/chat` 返回正常 JSON
- [ ] `POST /api/chat/stream` 返回 SSE 流
- [ ] Session 管理正常工作

---

### Phase 3: WebSocket 迁移

#### 3.1 创建 WebSocket Endpoint

**文件**: `NanobotWebSocketEndpoint.java`

```java
@ServerEndpoint("/ws")
@Component
public class NanobotWebSocketEndpoint {
    
    private static MessageBus messageBus;
    private static AgentLoop agentLoop;
    private static ObjectMapper objectMapper;
    
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    
    @Autowired
    public void setMessageBus(MessageBus messageBus) {
        NanobotWebSocketEndpoint.messageBus = messageBus;
    }
    
    @Autowired
    public void setAgentLoop(AgentLoop agentLoop) {
        NanobotWebSocketEndpoint.agentLoop = agentLoop;
    }
    
    @PostConstruct
    public void init() {
        objectMapper = new ObjectMapper();
    }
    
    @OnOpen
    public void onOpen(Session session) {
        SESSIONS.put(session.getId(), session);
        session.setMaxTextMessageBufferSize(1024 * 1024);
        
        // 设置流式回调
        if (agentLoop != null) {
            agentLoop.setStreamResponseCallback(new StreamResponseCallback() {
                @Override
                public void onStreamData(String sessionId, String requestId, String content) {
                    send(sessionId, content);
                }
                
                @Override
                public void onStreamComplete(String sessionId, String requestId) {
                    send(sessionId, "[DONE]");
                }
            });
        }
    }
    
    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            JsonNode json = objectMapper.readTree(message);
            InboundMessage inbound = convertToMessage(json);
            messageBus.publishInbound(inbound);
        } catch (Exception e) {
            send(session.getId(), "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    @OnClose
    public void onClose(Session session) {
        SESSIONS.remove(session.getId());
    }
    
    @OnError
    public void onError(Session session, Throwable e) {
        SESSIONS.remove(session.getId());
    }
    
    public static void send(String sessionId, String message) {
        Session session = SESSIONS.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
            } catch (IOException e) {
                // handle error
            }
        }
    }
    
    public static void broadcast(String message) {
        SESSIONS.values().forEach(session -> {
            if (session.isOpen()) {
                try {
                    session.getBasicRemote().sendText(message);
                } catch (IOException e) {
                    // handle error
                }
            }
        });
    }
}
```

**验证点**:
- [ ] WebSocket 连接成功
- [ ] 消息收发正常
- [ ] 断线重连正常
- [ ] Ping/Pong 心跳正常

---

### Phase 4: 静态资源迁移

#### 4.1 配置静态资源

**文件**: `application.properties` 或 `application.yml`

```properties
spring.web.resources.static-locations=classpath:/static/
spring.web.resources.add-mappings=true
server.servlet.context-path=
```

**验证点**:
- [ ] `/static/index.html` 可访问
- [ ] 其他静态资源正常

---

### Phase 5: 清理旧代码

#### 5.1 移除的文件

```
待删除:
├── src/main/java/com/nanobot/channels/
│   ├── ChannelServer.java
│   ├── SimpleWebSocketConnection.java
│   └── WebSocketFrame.java
└── Nanobot.java 中的 ChannelServer 启动代码
```

#### 5.2 修改的文件

| 文件 | 修改内容 |
|------|---------|
| Nanobot.java | 移除 ChannelServer 启动代码，改为 Spring Bean |
| NanobotApplication.java | 新增 Spring Boot 启动类 |
| pom.xml | 添加 Spring Boot 依赖 |

#### 5.3 验证清理完成

```bash
# 检查是否还有 HttpServer 引用
grep -r "HttpServer" src/

# 检查是否还有 SimpleWebSocketConnection 引用
grep -r "SimpleWebSocketConnection" src/

# 检查是否还有 ChannelServer 引用
grep -r "ChannelServer" src/
```

---

## 五、测试计划

### 5.1 测试矩阵

| 测试项 | 测试方法 | 预期结果 | 优先级 |
|--------|---------|---------|--------|
| HTTP POST /api/chat | 发送测试请求 | 返回 JSON | P0 |
| SSE 流式响应 | 启用 streamMode | 分块接收 | P0 |
| WebSocket 连接 | ws://localhost:8080/ws | 连接成功 | P0 |
| WebSocket 消息发送 | 发送 JSON | 收到响应 | P0 |
| 多会话隔离 | 并发请求不同 session | 互不干扰 | P1 |
| 流式 + WebSocket | 流式消息通过 WS 推送 | 实时推送 | P1 |
| 静态资源访问 | GET /static/index.html | 返回 HTML | P2 |
| 错误处理 | 发送非法 JSON | 返回错误信息 | P2 |
| 断线重连 | 断开后重连 | 恢复正常 | P2 |

### 5.2 测试用例

#### TC-01: 基本聊天

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "test-001",
    "content": "你好",
    "streamMode": false
  }'
```

**预期**: 返回 JSON 格式的聊天响应

#### TC-02: 流式聊天

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "test-002",
    "content": "写一首诗",
    "streamMode": true
  }'
```

**预期**: SSE 格式分块返回

#### TC-03: WebSocket 连接

```javascript
const ws = new WebSocket("ws://localhost:8080/ws");

ws.onopen = () => {
    ws.send(JSON.stringify({
        type: "chat",
        sessionId: "test-003",
        content: "Hello via WebSocket"
    }));
};

ws.onmessage = (event) => {
    console.log("Received:", event.data);
};
```

**预期**: 收到聊天响应

### 5.3 性能测试

```bash
# 10 并发连接
ab -n 100 -c 10 http://localhost:8080/api/chat

# 100 并发 WebSocket
# 使用 ws replay 或自定义脚本
```

**基准指标**:
- 响应时间 < 500ms (不含 LLM 调用)
- WebSocket 吞吐量 > 1000 msg/s
- 内存占用 < 200MB (空闲)

---

## 六、回滚策略

### 6.1 回滚触发条件

| 条件 | 描述 | 阈值 |
|------|------|------|
| 功能测试失败 | 核心功能不工作 | 任意 P0 测试 |
| 性能下降 | 响应时间增加 | > 50% |
| 内存泄漏 | 内存持续增长 | > 20%/小时 |
| 连接异常 | WebSocket 频繁断开 | > 5% |

### 6.2 回滚步骤

```bash
# 1. 停止服务
pkill -f nanobot-java

# 2. 切换回旧版本
git checkout v1.0.0
git tag -d v2.0.0
git checkout -b rollback-v1

# 3. 使用旧版本启动
java -jar target/nanobot-java.jar

# 4. 验证旧版本正常
# - 检查日志无报错
# - 测试核心功能
```

### 6.3 版本标签

| 标签 | 描述 | 状态 |
|------|------|------|
| v1.0.0 | 当前版本 (JDK HttpServer) | 已存在 |
| v2.0.0-snapshot | Spring Boot 迁移中 | 待创建 |
| v2.0.0 | Spring Boot 稳定版 | 待发布 |
| v1.0.1-rollback | 回滚版本 | 待创建 |

---

## 七、风险评估与缓解

### 7.1 风险矩阵

| 风险 ID | 风险描述 | 概率 | 影响 | 风险等级 | 缓解措施 |
|---------|----------|------|------|---------|---------|
| R1 | 流式响应性能下降 | 中 | 中 | 中 | 性能测试，对比基准 |
| R2 | WebSocket 连接不稳定 | 低 | 高 | 中 | 增加心跳机制 |
| R3 | 依赖冲突 | 低 | 高 | 中 | 隔离测试 |
| R4 | Session 丢失 | 中 | 高 | 高 | 双轨运行验证 |
| R5 | 破坏现有功能 | 低 | 高 | 中 | 完整测试覆盖 |

### 7.2 缓解措施详情

#### R1: 流式响应性能下降

**措施**:
- 使用异步非阻塞 I/O
- 调整 Tomcat 连接池配置
- 监控 SSE 推送延迟

#### R4: Session 丢失

**措施**:
- Phase 1 采用双轨运行
- 新旧版本并行测试
- 确认 Session ID 格式兼容

---

## 八、部署步骤

### 8.1 部署前检查清单

```
部署前检查:
☐ 所有单元测试通过
☐ 所有集成测试通过
☐ 性能测试达标
☐ 代码审查完成
☐ 文档更新完成
☐ 回滚计划已验证
☐ 监控告警已配置
☐ 备份已创建
```

### 8.2 部署步骤

```bash
# 1. 创建备份标签
git tag v1.0.0-backup

# 2. 构建新版本
mvn clean package -DskipTests

# 3. 停止服务
systemctl stop nanobot

# 4. 部署新 JAR
cp target/nanobot-java.jar /opt/nanobot/

# 5. 启动服务
systemctl start nanobot

# 6. 验证
curl http://localhost:8080/api/health

# 7. 监控日志
tail -f logs/nanobot.log
```

### 8.3 健康检查

```bash
# HTTP 健康检查
curl http://localhost:8080/api/health

# WebSocket 健康检查
wscat -c ws://localhost:8080/ws -x '{"type":"ping"}'
```

---

## 九、监控和日志

### 9.1 关键指标

| 指标 | 阈值 | 告警 |
|------|------|------|
| CPU 使用率 | > 80% | 警告 |
| 内存使用率 | > 85% | 警告 |
| WebSocket 连接数 | > 1000 | 警告 |
| API 响应时间 | > 1000ms | 警告 |
| 错误率 | > 1% | 错误 |

### 9.2 日志配置

```xml
<!-- logback.xml -->
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>

<!-- WebSocket 日志 -->
<logger name="com.nanobot.websocket" level="DEBUG"/>
<logger name="org.springframework.web.socket" level="DEBUG"/>
```

---

## 十、时间线和里程碑

### 10.1 详细时间线

| 阶段 | 任务 | 工期 | 开始 | 结束 |
|------|------|------|------|------|
| **Phase 1** | **基础设施准备** | **2天** | | |
| | 添加 Spring Boot 依赖 | 0.5天 | 第1天 | 第1天上午 |
| | 创建启动类和配置 | 0.5天 | 第1天 | 第1天下午 |
| | 验证项目启动 | 0.5天 | 第1天 | 第1天傍晚 |
| | 创建 WebSocket 配置 | 0.5天 | 第2天 | 第2天 |
| **Phase 2** | **HTTP API 迁移** | **3天** | | |
| | 创建 ChatController | 1天 | 第3天 | 第3天 |
| | 实现 SSE 流式 | 1天 | 第4天 | 第4天 |
| | 创建 Session API | 0.5天 | 第5天 | 第5天上午 |
| | 单元测试 | 0.5天 | 第5天 | 第5天下午 |
| **Phase 3** | **WebSocket 迁移** | **3天** | | |
| | 实现 WebSocket Endpoint | 1.5天 | 第6天 | 第7天上午 |
| | 集成 MessageBus | 0.5天 | 第7天 | 第7天下午 |
| | WebSocket 测试 | 1天 | 第8天 | 第8天 |
| **Phase 4** | **静态资源和清理** | **1天** | | |
| | 配置静态资源 | 0.5天 | 第9天 | 第9天上午 |
| | 清理旧代码 | 0.5天 | 第9天 | 第9天下午 |
| **Phase 5** | **集成测试和部署** | **2天** | | |
| | 完整功能测试 | 1天 | 第10天 | 第10天 |
| | 性能测试 | 0.5天 | 第11天 | 第11天上午 |
| | 部署和验证 | 0.5天 | 第11天 | 第11天下午 |
| **总计** | | **11天** | | |

### 10.2 里程碑

| 里程碑 | 日期 | 交付物 |
|--------|------|--------|
| M1: 基础设施就绪 | 第2天 | Spring Boot 应用可启动 |
| M2: HTTP API 完成 | 第5天 | REST API 正常工作 |
| M3: WebSocket 完成 | 第8天 | 标准 WebSocket 可用 |
| M4: 迁移完成 | 第11天 | 完整功能测试通过 |

---

## 十一、附录

### A. 依赖版本清单

```xml
Spring Boot: 3.2.5
Java: 21+
Maven: 3.8+
```

### B. 关键文件清单

```
新增文件:
├── src/main/java/com/nanobot/
│   ├── NanobotApplication.java
│   ├── config/
│   │   └── WebSocketConfig.java
│   ├── controller/
│   │   ├── ChatController.java
│   │   ├── SessionController.java
│   │   └── HealthController.java
│   └── websocket/
│       └── NanobotWebSocketEndpoint.java

修改文件:
├── pom.xml
└── Nanobot.java

删除文件:
├── src/main/java/com/nanobot/channels/
│   ├── ChannelServer.java
│   ├── SimpleWebSocketConnection.java
│   └── WebSocketFrame.java
```

### C. 配置示例

```yaml
# application.yml
spring:
  application:
    name: nanobot-java
  web:
    resources:
      static-locations: classpath:/static/

server:
  port: 8080
  servlet:
    context-path:

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics

nanobot:
  agent:
    model: gpt-3.5-turbo
    max-tokens: 4096
```

---

## 十二、审批

| 角色 | 姓名 | 日期 | 签名 |
|------|------|------|------|
| 制定人 | AI Assistant | 2026-06-05 | - |
| 审批人 | 用户 | - | 待签字 |
| 实施人 | AI Assistant | - | - |

---

**文档版本**: v1.0  
**下次审查日期**: 迁移完成后
