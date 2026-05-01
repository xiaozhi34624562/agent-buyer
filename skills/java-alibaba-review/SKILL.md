---
name: java-alibaba-review
description: "按《阿里巴巴 Java 开发手册（嵩山版）》和生产级 Java 工程经验审查代码。适用于用户要求 review Java、Spring Boot、MyBatis/MySQL、并发、日志、异常处理、单元测试、API 契约、安全、企业后端代码或工程设计时。"
---

# 阿里 Java 代码审查

使用这个 skill 时，把它当成一个代码审查视角，而不是格式化器。优先找生产风险，其次才是风格问题。

本 skill 来自用户提供的《阿里巴巴 Java 开发手册（嵩山版）》并结合生产级 Java 工程实践整理。不要大段引用原文；审查时引用规则类别即可，比如“异常日志规约”“MySQL 索引规约”“并发处理规约”。

## 审查姿态

结论先行，问题优先。每个 finding 必须说明：

- 问题是什么
- 为什么它在生产环境里重要
- 发生在哪里
- 如何具体修复

不要把 review 写成格式清单。只有当风格问题会造成理解成本、框架 bug、API 兼容性问题、排障困难或维护风险时，才把它写成 finding。

## 严重级别

- P0：数据破坏、安全漏洞、资金损失、不可逆误操作、公开 API 破坏。
- P1：运行时失败、并发 bug、事务一致性问题、权限边界错误、SQL 注入、严重可观测性缺口。
- P2：较可能演化成 bug 的正确性或可维护性风险，重要行为缺少测试，性能隐患。
- P3：低风险命名、结构、注释、规范一致性问题。

## 审查流程

1. 先读仓库说明和项目局部约定。
2. 检查变更范围：Java、SQL、MyBatis XML、YAML/properties、测试代码和文档。
3. 识别运行链路：Controller/API -> Service/Manager -> DAO/Client/Tool -> DB/外部系统。
4. 先看强风险，再看风格：
   - 安全和权限
   - 数据正确性和事务
   - 异常与日志
   - MySQL/schema/index/query 安全
   - 并发和资源生命周期
   - 变更行为的测试覆盖
5. 再检查阿里 Java 规约一致性。
6. 能运行测试就运行相关测试；不能运行时说明原因和残余风险。
7. 输出 review，不要顺手大改。修复建议要小而明确。

## 必查门禁

### 命名与 API 兼容性

检查不清晰缩写、拼音和英文混用、非标准类名/方法名/常量名、POJO 布尔字段 `is` 前缀、公开方法签名不兼容变更。

把 public API、RPC 契约、DTO、MyBatis XML、序列化字段、HTTP 响应字段、数据库字段、tool schema 都视为兼容性表面。

### 常量与魔法值

业务状态、错误码、超时、重试次数、cache key、SQL limit、权限标识中出现未命名的魔法值时要警惕。优先使用常量、enum、配置项或领域值对象。

### OOP 与 Java 正确性

检查缺少 `@Override`、空指针不安全的 `equals`、包装类数值用 `==` 比较、金额或精度敏感逻辑使用浮点数、误用 `BigDecimal(double)` 或 `BigDecimal.equals`、POJO 默认值覆盖持久化数据、构造器/getter/setter 中夹带业务逻辑、可见性过宽。

### 日期时间

检查 `YYYY/yyyy`、`MM/mm`、`HH/hh` 混淆，写死 365 天，遗留 `java.sql.Date/Time/Timestamp` 泄漏到业务代码，时区不明确，以及本应使用 `java.time` 却使用线程不安全日期工具。

### 集合处理

检查覆写 `equals` 但没有 `hashCode`、`Collectors.toMap` 没有处理重复 key 或 null value、误用 `subList`、修改不可变集合、foreach 中 add/remove、复杂集合没有初始容量、需要 key/value 却用 `keySet + get` 二次查询。

### 并发处理

检查直接创建线程、使用隐藏无界队列或无界线程的 `Executors` 工厂、线程无名称、`ThreadLocal` 未在 `finally` 清理、锁释放结构不安全、锁顺序不一致、静态 `SimpleDateFormat`、并发更新丢失、异步任务缺少幂等/重试/lease 语义。

### 控制语句

检查 `switch` 缺 default 或 fallthrough 不明确，缺少大括号，过深 `if/else`，复杂条件未提取命名变量，条件表达式里赋值，批量/分页接口缺少边界保护，循环内做昂贵操作。

### 异常与日志

检查用 catch 处理本可预检查的 RuntimeException、异常做流程控制、空 catch、catch 范围不合理、`finally` 中 return、事务 catch 后未回滚、`System.out/System.err/printStackTrace`、日志字符串拼接、error 日志缺少现场和堆栈、敏感数据进日志、直接 JSON 序列化整个对象、重复日志和高频无效日志。

### 测试

核心行为变更必须有自动化、独立、可重复测试。优先检查边界值、正确路径、设计约束、错误路径。数据库测试需要自己准备数据，并清理、回滚或使用明确测试数据前缀。

### 安全

检查用户资源权限、水平越权、输入校验、SQL 参数绑定、HTML 输出转义、CSRF、外部重定向白名单、短信/邮件/下单/支付等高成本操作的防重放和限流、PII 脱敏。

### MySQL 与 MyBatis

检查 `select *`、`${}` 参数拼接、字符串拼 SQL、更新不维护 `update_time`、业务唯一性没有唯一索引、普通索引上左模糊/全模糊查询、过多 join、多表列无别名、分布式高并发系统依赖外键级联、重要实体不用 `resultMap`、数据库 `is_xxx` 与 Java 布尔属性映射不清。

### 分层与设计

检查 Controller/Web 是否只做请求校验和编排，Service 是否承载业务逻辑，Manager 是否封装通用能力和第三方适配，DAO 是否只访问数据。复杂 Query 不要用无语义 `Map` 传输。

警惕重复逻辑、能组合却使用继承、对易变依赖缺少抽象、状态机/工作流缺少设计说明和测试。

## 详细清单

需要做深入审查时，读取 `references/alibaba-java-review-checklist.md`。按变更类型选择加载：

- Java 源码：命名、OOP、集合、并发、控制语句、异常、日志、测试。
- Spring Boot API：前后端契约、安全、异常映射、日志。
- MyBatis/MySQL：表结构、索引、SQL、ORM 映射。
- 大功能或设计文档：分层、状态机、设计规约。

## 输出格式

使用这个结构：

1. Findings
2. Open questions or assumptions
3. Tests run
4. Short summary

每个 finding 使用：

```text
[P1] 简短标题
Location: path/to/File.java:123
Problem: ...
Impact: ...
Fix: ...
```

如果没有发现问题，要明确说明“未发现需要阻断的 issue”，并列出残余风险或测试缺口。

如果运行环境支持 inline review comment，只对真实 finding 输出精确行号评论，不要对泛泛建议发评论。
