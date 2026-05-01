# 阿里 Java 代码审查清单

这个 reference 把《阿里巴巴 Java 开发手册（嵩山版）》整理成可执行的 code review 清单。它不是原文摘录，而是面向审查场景的工程化归纳。

按需读取本文件。只审查某一类变更时，不要把整份清单全部加载进上下文。

## 目录

1. finding 策略
2. 编程规约
3. 异常与日志
4. 单元测试
5. 安全
6. MySQL 与 ORM
7. 工程结构
8. 设计审查
9. Spring Boot 与 MyBatis 审查视角
10. 高频高价值问题

## 1. Finding 策略

优先报告生产影响大的问题：

- 运行时正确性优先于命名。
- 数据完整性优先于优雅。
- 安全优先于便利。
- 可观测性优先于静默失败。
- 测试应该证明行为，而不是只提高覆盖率数字。

不要用 IDE formatter 可以自动解决的格式问题淹没 review，除非它隐藏 bug、影响生成代码、破坏仓库约定或增加维护风险。

如果阿里规约和项目本地约定冲突，先说明冲突。默认尊重本地约定，除非阿里规约能避免明确 bug。

## 2. 编程规约

### 命名

检查：

- 名称以下划线或美元符号开始/结束。
- 拼音和英文混用、中文标识符、不明缩写。
- 类名不是 UpperCamelCase。
- 方法、参数、成员变量、局部变量不是 lowerCamelCase。
- 常量不是大写加下划线。
- 抽象类没有用 `Abstract` 或 `Base` 开头，且项目本地风格要求如此。
- 异常类没有以 `Exception` 结尾。
- 测试类没有以被测类名开头并以 `Test` 结尾。
- 包名不是小写单数语义单词。
- POJO 布尔字段以 `is` 开头，可能破坏框架属性解析。
- 父子类字段、不同代码块局部变量、非 setter 参数重名导致理解困难。

审查意图：

- 名称应该可搜索、可理解、跨 API 边界稳定。
- 命名通常是 P3；如果影响序列化、框架映射、公开 API 或排障，提升严重级别。

### 常量

检查：

- 业务逻辑、cache key、重试次数、状态值、超时、limit 中出现魔法值。
- `long` 数值后缀使用小写 `l`。
- 一个巨大的常量类混放所有领域常量。
- 重复字符串标志，比如 `"yes"`、`"success"`、`"cancelled"`，本应使用 enum 或类型化常量。
- 错误码、状态码在多个类里重复定义。

优先修复方式：

- 闭合值域或有附加属性时使用 enum。
- 运维可调值使用配置项。
- 只在本类内部使用的值放 private 常量。
- 只有真正跨组件共享时才放共享常量。

### 代码格式

检查：

- 二目/三目运算符左右缺空格。
- `if/for/while/switch/do` 和括号之间缺空格。
- 使用 Tab 而不是 4 空格。
- 单行超过 120 字符且没有合理换行。
- 方法体超过约 80 行且能抽取。
- 多个连续空行或为了对齐而制造噪声 diff。
- Windows 换行或非 UTF-8 文件。

格式问题一般按 P3 处理，除非它影响可维护性或隐藏真实逻辑问题。

### OOP 与 Java 正确性

检查：

- 通过对象引用访问静态成员。
- 覆写方法缺 `@Override`。
- 不同业务含义使用 `Object...` 或滥用可变参数。
- 已被外部调用或二方库依赖的接口签名被直接修改。
- 新增已过时 API。
- `object.equals("literal")`，应使用 `"literal".equals(object)` 或 `Objects.equals`。
- 包装类数值用 `==` 比较。
- 金额存储或计算使用浮点数。
- 浮点数用 `==` 或 `equals` 判断等值。
- `BigDecimal.equals` 用于数值等值判断，应使用 `compareTo`。
- `new BigDecimal(double)`，应使用 `BigDecimal.valueOf` 或字符串构造。
- DO/DTO/VO 属性使用基本类型，无法表达 null 的业务含义。
- POJO 属性设置默认值，可能覆盖数据库真实值。
- 构造器、getter、setter 中包含业务逻辑。
- POJO 在需要排障时没有有效 `toString`。
- 同一个 Boolean 属性同时暴露 `isXxx()` 和 `getXxx()`。
- public/protected 可见性过宽。
- 循环中用字符串 `+` 拼接。
- 误以为 `clone` 是深拷贝。

需要提升严重级别的场景：

- 涉及金额、库存、订单状态、权限状态、持久化或序列化。
- Jackson、MyBatis、Spring BeanUtils、Lombok 等依赖 JavaBean 属性命名。

### 日期时间

检查：

- 用 `YYYY` 表示自然年，应使用 `yyyy`。
- 混淆 `MM/mm`、`HH/hh`。
- 使用 `new Date().getTime()`，可读性上应使用 `System.currentTimeMillis()`。
- `java.sql.Date`、`java.sql.Time`、`java.sql.Timestamp` 泄漏到业务代码。
- 写死一年 365 天。
- 持久化、API、日志未明确时区。
- 前后端时间格式不一致。
- 静态 `SimpleDateFormat`。

优先修复方式：

- 使用 `java.time`。
- 在边界层明确时区和格式。
- 使用线程安全的 `DateTimeFormatter`。

### 集合处理

检查：

- 覆写 `equals` 但没有覆写 `hashCode`。
- 自定义对象作为 `Map` key 或 `Set` 元素，却没有正确相等语义。
- 普通判空写成 `collection.size() == 0`，应使用 `isEmpty()`。
- `Collectors.toMap` 没有 merge function。
- `Collectors.toMap` 的 value 可能为 null。
- 把 `subList` 强转为 `ArrayList`。
- 修改 `keySet`、`values`、`entrySet`、`Collections.emptyList`、`singletonList`、`Arrays.asList` 返回集合。
- 持有 `subList` 视图时修改父集合。
- raw collection 赋值给泛型 collection。
- foreach 中 remove/add。
- Comparator 不满足反对称、传递性、一致性。
- 大 `HashMap`/`ArrayList` 没有初始容量。
- 同时需要 key/value 时用 `keySet` 再 `map.get`。
- 误以为 `ConcurrentHashMap` 支持 null key/value。
- 大集合去重或包含判断用 `List.contains`，应考虑 `Set`。

### 并发处理

检查：

- 单例状态不是线程安全的。
- 应用代码中直接 `new Thread`。
- 使用 `Executors.newFixedThreadPool`、`newSingleThreadExecutor`、`newCachedThreadPool` 隐藏无界队列/线程风险。
- 线程没有有意义名称。
- 静态 `SimpleDateFormat`。
- `ThreadLocal` 没有在 `finally` 中 remove，尤其在线程池中。
- 锁范围过大或锁内调用远程服务。
- 多把锁获取顺序不一致。
- `lock()` 放在 `try` 内部。
- `tryLock()` 结果没有判断就 unlock。
- 并发更新同一记录时没有悲观锁、乐观 version、cache lock 或应用层锁。
- 乐观锁重试缺失或次数太低。
- 用 `Timer` 执行多个任务而不是 `ScheduledExecutorService`。
- `CountDownLatch` 子线程异常路径没有保证 `countDown`。
- 高并发共享 `Random`，应考虑 `ThreadLocalRandom`。
- 双重检查锁没有 `volatile`。
- 把 `volatile` 当成复合写原子性保障。
- 并发修改 `HashMap`。

需要提升严重级别的场景：

- 代码调度 tool call、job、支付、订单状态变更或重试。
- 存在重复副作用风险。

### 控制语句

检查：

- `switch` 没有 default。
- `switch` case fallthrough 没有明确注释。
- 对外部 String 做 `switch` 前没有 null 判断。
- `if/else/for/while/do` 没有大括号。
- 三目表达式可能因自动拆箱产生 NPE。
- 高并发退出条件使用等值判断。
- `if/else` 超过三层嵌套。
- 复杂条件表达式没有提取为有意义的 boolean 变量。
- 条件或算术表达式中夹带赋值。
- 循环体内创建对象、连 DB、远程调用或做不必要 try/catch。
- 大量取反逻辑降低可读性。
- 公开接口缺少参数边界，尤其批量和分页接口。

### 注释

检查：

- 项目要求 Javadoc 时，公开类、抽象方法、接口方法缺少有效 Javadoc。
- 行为变更后注释未同步。
- 注释掉的死代码没有解释。
- `TODO`/`FIXME` 没有责任人、时间、预计处理方式。
- 注释解释显而易见的代码，而不是业务意图。
- enum 常量没有解释领域含义。

注释问题一般是 P3；如果隐藏 API 契约、运维行为或数据迁移语义，则提升严重级别。

### 前后端 API

检查：

- API 没有明确 method、path、request、status code、response。
- 资源路径使用动词或大写，且违反项目 REST 风格。
- 空列表返回 null。
- 错误响应缺 HTTP 状态码、`errorCode`、`errorMessage` 和用户提示。
- JSON key 不是 lowerCamelCase。
- Long ID 作为数字返回给 JavaScript，可能精度丢失。
- URL query 中携带敏感信息。
- URL 参数过长。
- request body 无大小限制。
- 分页边界没有规范化。
- 外部重定向没有白名单。
- API 版本策略和项目约定冲突。

## 3. 异常与日志

### 错误码

检查：

- 错误码不是稳定字符串。
- 错误码暴露版本、严重等级、组织结构或实现细节。
- 必须返回 code 时，成功态没有统一表达。
- 有共享错误码却随意新增。
- 直接把错误码作为用户提示。
- 第三方错误码丢失，没有转义或保留诊断信息。

企业 Java 系统里要区分：

- 用户端错误。
- 当前系统错误。
- 第三方依赖错误。

### 异常处理

检查：

- catch 本可通过预检查规避的 `NullPointerException`、`IndexOutOfBoundsException` 等。
- 用异常做正常分支控制。
- 巨大的 try 块导致无法定位失败操作。
- 空 catch。
- catch 后既不处理、不重抛，也不在正确层级记录日志。
- 事务中 catch 后忘记手动回滚或重抛。
- 资源关闭没有 try-with-resources 或 finally。
- `finally` 中 return。
- catch 类型和实际异常不匹配。
- 动态生成类、RPC、二方包边界需要更宽异常捕获时没有隔离。
- 直接 `new RuntimeException()` 或 `throws Exception`，缺少业务含义。
- API 边界把堆栈或敏感内部信息返回给用户。
- 返回 null 但没有说明。
- 级联调用容易 NPE。

分层期望：

- DAO 包装底层持久化异常，不重复打日志。
- Manager 适配第三方错误并转换诊断信息。
- Service 记录业务失败现场。
- Web/API 把异常转换成稳定错误响应。

### 日志

检查：

- 直接依赖 Logback/Log4j API，而不是 SLF4J 门面。
- 生产日志缺少保留策略或 app/error/access/monitor 分类。
- 日志字符串拼接而不是占位符。
- debug/trace/info 参数构造昂贵且没有 level 判断。
- logger additivity 或 catch/log/rethrow 导致重复日志。
- `System.out`、`System.err`、`printStackTrace`。
- error 日志没有现场信息和异常堆栈。
- 直接把完整对象 JSON 序列化进日志。
- 敏感字段进日志，例如手机号、邮箱、身份证、token、password、API key、支付数据。
- 缺少 requestId/runId/userId/orderId 等关联字段，分布式排障困难。
- 高频路径输出大量 info/warn。
- 用户输入校验失败打 error，导致无效报警，除非它代表攻击或系统异常。

推荐 finding 表述：

- “这个问题会导致排障困难，因为...”
- “这里可能泄露敏感数据，因为...”
- “这里可能打爆生产日志，因为...”

## 4. 单元测试

检查：

- 核心行为变更没有测试。
- 测试依赖人工查看或 `System.out`。
- 测试之间互相调用或依赖执行顺序。
- 本可 mock/local 的依赖却依赖外部服务。
- 测试粒度太大，失败后难定位。
- 核心模块缺少边界和错误路径测试。
- 测试不在 `src/test/java`。
- DB 测试假设已有数据。
- DB 测试不回滚，也没有唯一测试数据前缀。
- 业务代码因重构不足而不可测，例如构造器过重、全局状态、静态调用、外部依赖、条件嵌套过深。

使用 AIR：

- Automatic，自动化。
- Independent，独立。
- Repeatable，可重复。

使用 BCDE：

- Boundary，边界值。
- Correct，正确输入。
- Design，设计约束。
- Error，错误路径。

review 输出要具体：

- 缺哪个场景。
- 应断言什么。
- 应该是 unit、slice、integration 还是 contract test。

## 5. 安全

检查：

- 用户个人资源或操作缺少权限校验。
- 水平越权：用户 A 可以读写用户 B 的数据。
- 敏感用户数据展示未脱敏。
- SQL 参数没有绑定。
- 动态 SQL 标识符没有白名单。
- 用户数据输出到 HTML 前没有转义。
- 修改型表单/AJAX 缺少 CSRF 防护。
- 开放重定向没有白名单。
- 短信、邮件、下单、支付等高成本操作缺少防重放、限流、疲劳度控制或验证码。
- 请求参数缺少有效性校验。
- page size 或 batch size 无上限。
- 用户传入 order-by/sort-field 没有白名单。
- SSRF、Shell 注入、反序列化注入、ReDoS、缓存击穿风险。

权限设计优先 fail-closed：

- 请求可以收窄权限，不能放大权限。
- 服务端用户身份必须来自可信上下文，不能来自模型或用户 body 字段。

## 6. MySQL 与 ORM

### 建表

审查 DDL 或 migration：

- 表达是/否的字段没有按项目约定使用 `is_xxx` 与 unsigned tinyint。
- 表名/字段名包含大写、数字开头或奇怪下划线模式。
- 表名使用复数名词。
- 使用保留字，例如 `desc`、`range`、`match`、`delayed`。
- 索引命名不符合 `pk_`、`uk_`、`idx_`。
- 小数使用 `float`/`double`。
- 过长 `varchar` 没有拆分 text 表设计。
- 缺少 `id`、`create_time`、`update_time`。
- 字段含义或状态扩展后没有更新注释。
- 没有数据量依据就提前分库分表。
- 冗余字段频繁变化、唯一或过大。

### 索引

检查：

- 业务唯一字段没有唯一索引。
- 超过三表 join。
- join 字段类型不一致。
- join 字段没有索引。
- `varchar` 索引没有考虑前缀长度。
- 普通索引字段上做左模糊或全模糊查询。
- `order by` 没有利用组合索引有序性。
- 大 offset 分页没有延迟关联或 keyset 优化。
- 关键路径 explain 低于合理的 range/ref。
- 组合索引没有考虑等值、范围、区分度顺序。
- 隐式类型转换导致索引失效。
- 为每个查询都建索引，索引膨胀。

### SQL

检查：

- 本意统计行数却用 `count(column)` 或 `count(constant)` 替代 `count(*)`。
- `sum(column)` 结果没有处理 null。
- 用 `= null` 或 `<> null` 判断 null。
- count 为 0 时仍执行分页查询。
- 分布式高并发设计依赖外键和级联。
- 应用功能代码使用存储过程。
- 数据订正 update/delete 前没有 select-first 保护。
- 多表查询/更新/删除没有用别名限定列。
- `in` 集合过大。
- 应用代码中使用 `truncate`。

### MyBatis/ORM

检查：

- `select *`。
- 应使用 `#{}` 却使用 `${}`。
- 动态 column/table/order 没有白名单。
- 重要实体缺少 `resultMap`。
- 数据库 `is_xxx` 没有映射到 Java `xxx` 字段。
- 稳定业务实体查询结果直接用 `HashMap`/`Hashtable`。
- 更新 SQL 没有维护 `update_time`。
- 大而全 update 接口把 POJO 所有字段都更新掉。
- 滥用 `@Transactional`，没有考虑回滚、缓存、消息、搜索、幂等。
- Entity 字段类型和 DB 字段类型不匹配。

## 7. 工程结构

审查分层：

- Open API 层暴露 HTTP/RPC 契约并转换错误。
- Web/Controller 做请求形状校验和编排，不承载复杂业务规则。
- Service 承载业务逻辑。
- Manager 处理通用业务能力、第三方适配、缓存/中间件、DAO 组合。
- DAO 只访问数据源。
- External client 隔离第三方协议。

审查模型：

- DO 对应数据库表。
- DTO 跨 Service/RPC 边界。
- BO 表达业务对象或业务聚合。
- Query 封装复杂查询参数，超过两个关键参数时避免裸 `Map`。
- VO 是视图/API 响应模型。

审查依赖：

- GAV 和模块命名有业务语义。
- 生产依赖不使用 SNAPSHOT，除非有明确安全修复理由。
- 依赖升级没有改变无关仲裁结果。
- 共享依赖版本集中管理。
- 同一 group/artifact 没有多个 version。
- 公共库 API 和依赖保持最小化。
- Utils 包稳定，不是垃圾桶。

## 8. 设计审查

大功能变更时，检查设计是否被文档、测试或清晰结构表达：

- 存储方案和数据结构考虑性能、容量、迁移、回滚。
- 多类用户、多用例场景有结构化表达。
- 超过三个状态的业务对象有明确状态和转换条件。
- 多对象调用链有时序或流程说明。
- 复杂类关系有类图或清晰文档。
- 多对象协作工作流有活动流程说明。
- 系统边界明确。
- 非功能需求明确：安全、可用性、扩展性、可观测性。
- 覆盖异常流程和业务边界，不只覆盖 happy path。
- 类职责单一。
- 能组合就不要优先继承，除非符合替换原则。
- 对易变依赖使用稳定抽象隔离。
- 扩展点符合开闭原则。
- 重复业务逻辑被抽取。
- 设计文档表达系统难点，代码不是全部文档。

## 9. Spring Boot 与 MyBatis 审查视角

### Controller

检查：

- `userId` 来自可信 header/security principal，不来自 request body。
- Request DTO 有 validation annotation，业务规则在 service 层继续校验。
- batch/page 输入有最大上限。
- 错误响应稳定，不泄露内部细节。
- 修改型 endpoint 检查权限和幂等性。
- Controller 中没有数据库或第三方复杂逻辑。
- SSE/streaming endpoint 能处理断连和超时。

### Service

检查：

- 事务边界放在正确层级。
- 事务中的 catch 要么重抛，要么显式标记 rollback。
- 领域状态转换明确并有测试。
- 写操作幂等，或有唯一键/version/token 保护。
- 避免在事务内调用外部服务，除非有明确理由。
- 日志包含 requestId/userId/business id，但不包含敏感数据。

### MyBatis Mapper

检查：

- SQL 使用 `#{}`，动态标识符走白名单。
- Mapper result mapping 和 entity 类型一致。
- 没有 `select *`。
- update 语句更新 `update_time`。
- 查询方法不传 raw `Map`，除非项目局部约定允许且场景很简单。
- 大 list 查询做分片或限制输入大小。

### Async / Executor

检查：

- `ThreadPoolTaskExecutor` 或 `ThreadPoolExecutor` 有有界队列和拒绝策略。
- 线程名有意义。
- MDC 或 trace context 被有意识传播。
- reject 路径写终态或返回明确错误。
- shutdown 行为明确。
- 可重试 job 幂等。

### Logging

检查：

- Logback 配置有 rolling file 和环境匹配的 retention。
- 必要时分离 app/error/access/monitor 日志。
- MDC 包含关联 key。
- error 日志最后一个参数传异常对象。
- 不记录 API key、密码、token 或完整 PII。

## 10. 高频高价值问题

快速扫描这些模式：

- SQL 注入：mapper 中 `${}` 或从请求值拼接 SQL。
- 水平越权：endpoint 收到 `userId/orderId` 后没有校验归属。
- 丢失更新：订单/状态更新没有 version、唯一 guard 或锁。
- 重复副作用：可重试 async/job/tool call 执行非幂等写。
- 金额类型错误：price/payment/refund 使用 `double`、`float` 或浮点比较。
- BigDecimal 构造错误：`new BigDecimal(0.1)`。
- Mapper 属性错误：DB `is_deleted` 映射到 Java `isDeleted` Boolean，框架属性解析不一致。
- 无界 executor：`Executors.newCachedThreadPool` 或隐藏无界队列。
- ThreadLocal 泄漏：set 后没有 finally remove。
- 静默失败：scheduler、async task、event writer、transaction 中 catch 后吞异常。
- 事务回滚错误：transactional 方法 catch 异常后返回 success。
- 日志泄露：完整 request/response body、token 或 PII 进日志。
- 缺少测试：新增取消、退款、权限、状态转换路径没有错误路径测试。
- 分页风险：page size 无上限，大 offset 查询，list endpoint 返回 null。
- `Collectors.toMap`：重复 key 或 null value 导致运行时崩溃。
- 修改不可变集合：修改 `Collections.emptyList()` 或 `Arrays.asList()`。
- 缺少更新时间：update SQL 没有维护 `update_time`。
