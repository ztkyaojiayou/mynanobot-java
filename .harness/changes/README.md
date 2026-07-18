# 变更追踪目录（.harness/changes/）

> **作用域**: 记录每一个需求/变更从需求分析到部署验证的全过程，做到 **仓库即记录系统**、全程可追溯、可断点续传。
> **SDD 约束**: 每个 `change.md` 不只是记录卡，而是该变更的规格真相源；编码前必须完成规格审批。
> **清单索引**: 全部变更的阶段分组、依赖 DAG 与状态总览见 [`ROADMAP.md`](ROADMAP.md)。

---

## 1. 目录约定

每个变更 = 一个子目录，命名 `C-NNN-<slug>`：

```
.harness/changes/
├── README.md            ← 本文件
├── _TEMPLATE/           ← 变更模板（复制后改名使用）
│   ├── change.md
│   ├── review.md
│   └── verify.md
├── ROADMAP.md          ← 清单索引 / 依赖地图 / 状态总览
├── C-001-project-bootstrapping/
│   ├── change.md        ← 变更卡（① 需求分析产出，全程更新 status）
│   ├── review.md        ← 专家评审记录（④ 产出）
│   └── verify.md        ← 部署验证记录（⑥ 产出，可选）
└── C-002-quality-gates/ …
```

- 编号 `C-NNN` 全局递增、唯一。
- slug 用 kebab-case，简述变更主题。

---

## 2. 变更状态机

```
draft → analyzing → coding → testing → reviewing → ci → verifying → done
                                                  └────(打回上游)────┘
```

状态写在 `change.md` frontmatter 的 `status:` 字段。每次阶段流转即更新 → Owner Agent 读 status 即可**定位当前阶段、断点续传**。

| 状态 | 对应阶段 | 责任技能 |
|------|---------|---------|
| analyzing | ① 需求分析 | request-analysis |
| coding | ② 编码实现 | coding-skill |
| testing | ③ 单测编写 | unit-test-write |
| reviewing | ④ 专家评审 | expert-reviewer |
| ci | ⑤ CI 门禁 | unit-test-ci |
| verifying | ⑥ 部署验证 | deploy-verify |
| done | 交付完成 | — |

### 2.1 审批与状态切换规则
- `analyzing → coding` 默认必须经过人类审批。
- 其余状态由 Owner Agent 在完成相应阶段后推进。
- 若评审、CI、验证失败，Owner Agent 负责回退状态，并在 `change.md` 或 `review.md` 写明回退原因。
- 未更新状态的变更，不得假定已进入下一阶段。

---

## 3. 与提交规范关联

每个 commit message 必须含变更 ID：

```
<type>(<scope>): C-NNN <描述>
例: feat(budget): C-009 实现超支预警 ≥15% 触发 HITL
```

→ 通过 `git log --grep "C-009"` 可回溯某变更的全部提交。

---

## 4. 使用流程

```bash
# 1. request-analysis 分配 ID 并建档（复制模板）
cp -r .harness/changes/_TEMPLATE .harness/changes/C-001-xhs-note-search

# 2. 沿流水线推进，每阶段更新 change.md 的 status 与勾选

# 3. 交付收口：status=done，同步相关 .harness/wiki/ 文档
```

### 4.1 任务粒度建议
- 一个 task 尽量 ≤ 1 天。
- 一个 task 默认聚焦一个主模块。
- 若一个变更同时影响 `agent / server / tests`，优先拆成多个 task。
- task 之间保持 DAG，无环依赖。

### 4.2 SDD / TDD 使用建议
- `change.md` 先写规格，再进入实现。
- 核心业务逻辑默认先定义测试策略，再开始编码。
- 对核心 Agent / Service / 规则判断，建议先有失败测试，再写最小实现。

---

## 5. 检索建议

| 想知道 | 怎么查 |
|--------|--------|
| 某变更进展到哪 | 看对应 `change.md` 的 `status` |
| 哪些变更未完成 | grep `status:` 非 `done` 的 change.md |
| 某变更改了什么 | `git log --grep "C-NNN"` |
| 某变更为何这么设计 | 看 `change.md` 的需求与影响面 + `review.md` |
