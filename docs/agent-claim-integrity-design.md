# Agent 完整性检测设计 —— 防止"声称完成但实际未执行"

> 状态：设计提案（待评审） · 日期：2026-08-07
> 背景：用户实测发现 nanobot 出现"声称推送成功但实际未执行"的真实性缺陷。

---

## 一、问题定义

LLM Agent 存在一个系统性真实性缺陷：**当用户请求执行类动作（提交/推送/部署/删除/写入等）时，
模型可能直接生成"已推送成功"这类文本而完全不调用工具**，Agent 框架把这段文本当最终答案发给用户。

### 1.1 问题链条（nanobot 现状）

```
用户: 帮我推送
  │
  ▼
LLM 生成: "好的，已推送成功！"        ← 无 tool_calls，纯文本
  │
  ▼
AgentRunner.handleToolCallResponse 路径2:
  if (toolCalls.isEmpty()) return handleFinalResponse(content)   ← 直接当答案
  │
  ▼
用户看到: "好的，已推送成功！"          ← 假的，根本没执行
```

### 1.2 关键确认（已调研）

- **工具层不造假**：`ExecTool` 真实启动进程、真实捕获输出、真实返回 exit code。一旦 LLM 发射 `tool_calls`，执行是真的。
- **但有两个机制缺口**：
  1. LLM 可绕过工具（`toolCalls.isEmpty()` 即当最终答案）——核心缺口
  2. 无事后验证（`git push` 后不复核 `git status`/`git log`）

### 1.3 这是共性问题，不是 nanobot 特有

Claude Code 同样被此困扰（已调研）：
- 幻影工具调用 [#83713]、伪造工具结果 [#66986]、语义绕过 [#46991]
- Anthropic 内部数据：无 post-edit 验证时**虚假声称率高达 29-30%**（该验证门控在内部员工条件下，社区不可用）
- Claude Code 的 system prompt **没有**"不得声称未执行操作"的约束
- 社区方案（verification-before-completion / Iron Law）只做成提示词注入，**依赖模型自觉**，而模型会无视这类指令

---

## 二、设计目标

- **不逐工具写验证分支**（工具有几十个、会持续增加，逐工具不可维护）
- **不依赖模型自觉**（提示词约束已知无效）
- 检测的是"声称有没有**执行支撑**"，而不是"结果对不对"——后者与具体工具绑定，前者是二元的、通用的

---

## 三、核心设计：三信号完整性检测

### 3.1 信号定义（三个通用信号）

| 信号 | 来源 | 说明 |
|------|------|------|
| **动作意图** | 用户消息 | 命中动作动词表（push/commit/部署/删除/写入/创建/修改/发布/构建/重启/安装/升级...） |
| **完成声称** | 最终回答 | 命中完成态标记表（已推送/已提交/已完成/已部署/搞定/successfully/done/completed...） |
| **执行证据** | 本轮工具调用记录 | `toolCalls.isEmpty()` 或本轮无写工具 |

### 3.2 检测规则（一条通用 if，覆盖所有工具）

```java
boolean actionIntent    = matchActionVerbs(userMessage);        // 信号1：用户要执行动作
boolean completionClaim = matchCompletionMarkers(finalReply);   // 信号2：模型声称完成
boolean noToolExecuted  = currentTurnToolCalls.isEmpty();       // 信号3：本轮没调工具

if (actionIntent && completionClaim && noToolExecuted) {
    return redoWithToolGuidance();  // 拒绝这段文本，引导模型实际执行
}
```

### 3.3 触发后的处置（也通用）

不指定"去跑 git status"这种特定指令，而是**拒绝接受无证据的完成声称**，把控制权交还模型：

> 生成给模型的补充指令：
> "你声称已完成动作 X，但本轮没有任何工具执行。执行类请求必须通过工具实际执行，
> 并基于工具返回的真实输出汇报结果。请调用合适的工具完成或验证后，再汇报。"

模型自然知道自己该调 `exec git push` 还是别的——无需替它指定。

**处置策略（已定稿）**：
- 拦截后引导模型重走一轮带工具执行；**上限 1 次**——若这次仍是纯文本声称，降级为普通文本放行，防死循环
- **用户可见提示**：CLI 打印 `[检测到未执行的完成声称，正在引导模型实际执行]`，让用户知道为什么多等了一轮

---

## 四、实现位置（无逐工具代码）

- **动词/完成态表**：两个小的字符串集合（几十个词），集中维护
- **检测点**：`AgentRunner.handleToolCallResponse` 的**路径 2**（`toolCalls.isEmpty()` → `handleFinalResponse`）
  此处是"模型想直接以文本收尾"的唯一入口，拦截成本最低
- **处置点**：拦截后追加引导指令 → 递归 `runInternal`（带工具）重走一轮

伪代码：

```java
// 路径2: 无工具调用 → 原: return handleFinalResponse(content, workingMessages);
if (toolCalls.isEmpty()) {
    if (checkClaimIntegrity(context, content, workingMessages)) {
        workingMessages.add(Map.of("role", "user", "content", INTEGRITY_GUIDANCE));
        return runInternal(context, workingMessages, onDelta, iteration + 1, 0);
    }
    return handleFinalResponse(content, workingMessages);
}
```

---

### 3.4 检测范围（已定稿：先只做执行类动作）

初版**只覆盖执行类写操作**：动词表先收录 git（提交/推送/部署/删除/创建/修改/写入/发布/构建/重启/安装/升级...）+ 文件操作 + 部署类高频动作。对话类请求（"解释一下 git push"）天然不触发信号1，误报率最低。词表后续按真实对话校准增量扩充。

---

## 五、误报边界（可控）

| 场景 | 是否触发 | 原因 |
|------|---------|------|
| "解释一下 git push" → 纯文本回答 | ✗ | 用户是解释请求，非动作意图 |
| "帮我推送" → 调 exec 失败 → "推送失败：网络错误" | ✗ | 有工具调用，且如实汇报 |
| "帮我推送" → 调 exec 成功 → "已推送" | ✗ | 有工具调用，合法 |
| "帮我推送" → 纯文本"好的已推送" | ✓ | 三信号全中——正是要抓的 |

误报点集中在动词/完成态词表的质量——需要一段时间的真实对话校准。

---

## 六、后续增强（可选，不在本期）

1. **事后验证回路**：对 git/文件类动作，Agent 在汇报前自动跑一次只读复核（`git status`/重读文件）——这是 Claude Code 也没有的能力（门控在内部）
2. **动作完成语义**：区分"读"（只读工具，不算动作执行）与"写"（exec/write_file 等）——用工具 `isReadOnly()` 判断，无需逐工具
3. **Hook 集成**：把检测做成 ECA Hook，用户可配置开关

---

## 七、与 Claude Code 的对比结论

| 维度 | Claude Code | nanobot（本期设计） |
|------|------------|---------------------|
| 工具执行真实性 | 管道强制（tool_use 发射即执行） | 已有（同基线） |
| 防"文本声称完成" | 无工程检测，依赖模型自觉 | **三信号硬检测，拦截重试** |
| 事后验证 | 门控在内部员工，社区不可用 | 可选增强（见六） |

**核心差异化**：把社区只做成提示词（Iron Law）的规则，落地为**框架硬检测**——不依赖模型自觉，这是 nanobot 相对 Claude Code 的真实机会。

---

## 八、已定稿决策

| 决策点 | 结论 |
|--------|------|
| 检测范围 | 先只做执行类动作（git/文件/部署写操作），对话类不触发 |
| 重试次数 | 上限 1 次，超限降级为普通文本放行（防死循环） |
| 用户可见性 | 触发时 CLI 打印 `[检测到未执行的完成声称，正在引导模型实际执行]` |
| 实现位置 | `AgentRunner.handleToolCallResponse` 路径2，不逐工具写分支 |

---

## 九、实现要点速查（定稿后）

- 两个词表集合：`ACTION_VERBS`、`COMPLETION_MARKERS`（集中在 AgentRunner 常量区维护）
- 检测点：路径2 `if (toolCalls.isEmpty())` 处加完整性检查
- 处置：追加 `INTEGRITY_GUIDANCE` 指令 → 递归 runInternal 重走一轮（iteration + 1）→ 计数器超 1 次直接降级
- CLI 提示：检测触发时 CliChannel 打印可见提示
- 待评估项：是否有历史消息（workingMessages 已含工具调用记录）可复用为信号3，而非单独计数器
