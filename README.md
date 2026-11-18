# RoomBook · 会议室预约服务

RoomBook 是基于 **Java 17、Spring Boot、MySQL 8 和 JDBC** 的会议室预约后端。员工可按人数、设备和时段查找空闲会议室，创建、改期或取消预约。管理员可管理房间状态、维护窗口并代办预约。

真实 MySQL 集成测试验证了 **6 个并发请求抢同一时段时仅 1 笔预约成功**，以及改期冲突后原预约和时段仍然保留。[查看集成测试](platform/src/test/java/rooms/MySqlBookingTest.java)

服务默认监听 `127.0.0.1:8080`，预约时间按 Asia/Shanghai 解释。项目也提供 Java 8 + Derby 入口，两个版本共用预约业务规则、HTTP 路径和响应格式。

## Spring Boot + MySQL

需要 JDK 17、Maven 3.6.3+ 和 MySQL 8。使用管理员账号执行以下 SQL，先替换示例密码。

```sql
CREATE DATABASE roombook CHARACTER SET utf8mb4;
CREATE USER 'roombook'@'localhost' IDENTIFIED BY '<your_password>';
GRANT ALL PRIVILEGES ON roombook.* TO 'roombook'@'localhost';
```

从项目根目录运行。PowerShell 会提示输入数据库密码。

```powershell
$dbCredential = Get-Credential -UserName roombook -Message '输入 RoomBook 的 MySQL 密码'
$env:ROOMBOOK_MYSQL_USER = $dbCredential.UserName
$env:ROOMBOOK_MYSQL_PASSWORD = $dbCredential.GetNetworkCredential().Password
$env:ROOMBOOK_MYSQL_URL = 'jdbc:mysql://127.0.0.1:3306/roombook'
mvn -f platform/pom.xml package
java -jar platform/target/roombook-platform-2.0.0.jar
```

首次启动时，Flyway 创建 InnoDB 表并初始化三间示例会议室。HikariCP 提供 JDBC 连接，现有事务代码负责提交和回滚。MySQL 使用 `READ COMMITTED`，预约和维护通过房间行锁协调写入，时段复合主键防止同一房间重复占用。

默认地址是 `http://127.0.0.1:8080`。可用 `ROOMBOOK_PORT` 修改端口，首次启动时可通过 `ROOMBOOK_ADMIN_PASSWORD` 初始化管理员。在另一个终端运行 `python demo-booking.py`，可验证员工注册、预约、查询日程和取消。MySQL 使用独立数据库，已有 Derby 数据可继续用下文的原版入口访问。

如果 Windows 上的 JDK 报 `Unable to establish loopback connection`，可用 `-Djava.io.tmpdir` 和 `-Djdk.net.unixdomain.tmpdir` 将 JVM 临时目录指向一个已存在的短路径目录。

### MySQL 集成测试

准备一个空的测试库（例如 `roombook_test`），将库权限授予上面配置的数据库账号。用独立的测试库运行以下命令。

```powershell
$env:ROOMBOOK_MYSQL_TEST_URL = 'jdbc:mysql://127.0.0.1:3306/roombook_test'
mvn -f platform/pom.xml verify
```

测试启动实际的 Spring MVC HTTP 服务与 MySQL 连接，验证多个请求抢同一时段只有一笔预约写入、改期冲突回滚、取消释放时段。未设置 `ROOMBOOK_MYSQL_TEST_URL` 时跳过这组集成测试。

## Derby 版快速开始

在项目根目录使用 JDK 8 或 11 和 Maven 3.6.3+（3.x）运行以下命令。生成的 JAR 已包含 Derby 驱动。

```sh
mvn verify
java -jar target/roombook-2.0.0.jar
```

服务启动后，在另一个终端进入项目目录，用 Python 3 运行 `python demo-booking.py`（部分系统命令为 `python3`）。脚本会注册演示员工，预约次日 10:00 的空闲会议室，从员工日程核对预约编号，然后取消预约并释放时段。按 Ctrl+C 停止服务。

## 构建与回归测试

在项目根目录使用完整 JDK（Java 8 或 11）和 Maven 3.6.3 或更新的 Maven 3，执行 `mvn verify`。首次构建从 Maven Central 下载 Derby 和测试依赖，生成 `target/roombook-2.0.0.jar`。JUnit 按业务场景分别报告领域测试，并运行 HTTP 接口和报表测试。详细结果在 `target/surefire-reports/`。例如，要单独复查改期后被旧扫描结果误判爽约的场景，可执行 `mvn "-Dtest=DomainSuite#staleScanPreservesRescheduledBooking" test`。

## 启动（Windows PowerShell）

需要 JDK 8、PATH 中的 javac 和 Derby JDBC 驱动。脚本默认读取 JDK 的 db/lib/derby.jar，也可以用环境变量 DERBY_JAR 指向兼容 Java 8 的 derby.jar。

~~~powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Test

# 首次创建 admin 时输入 10–128 位密码。已有 admin 时以数据库中的密码为准。
$adminCredential = Get-Credential -UserName admin -Message '设置或输入 RoomBook 管理员密码'
$env:ROOMBOOK_ADMIN_PASSWORD = $adminCredential.GetNetworkCredential().Password
powershell -ExecutionPolicy Bypass -File .\run.ps1
~~~

Ctrl+C 停止服务。可用 -Port 8090 更换端口。管理员初始化是可选的，未初始化时员工仍可注册并使用预约接口。账号、房间、预约与审计记录保存在 data/meeting。密码采用随机盐和 PBKDF2-HMAC-SHA256（210000 次迭代）。登录令牌有效期 8 小时，注销或重启服务后失效。测试使用独立的内存数据库。

## 演示一次员工预约

在另一个 PowerShell 窗口执行。用户名为 3–40 位英文字母、数字或下划线，忽略大小写，admin 为保留名。注册只创建员工账号。

~~~powershell
$employee = Get-Credential -UserName alice -Message '输入员工用户名与密码（10–128 位）'
.\demo-booking.ps1 -Credential $employee -Register
~~~

首次运行加 `-Register`，已有账号直接运行脚本即可。脚本通过 `data.token` 登录，按 6 人查询并提交 `attendees=6`，验证改期后取消演示预约。

创建预约时由服务从登录会话确定预约人。将必需设备随创建表单提交后，服务会保存设备要求，换房时再次检查。搜索支持设备白板 whiteboard、投影仪 projector 和摄像头 camera。多个条件用逗号分隔，必须全部满足。人数 people 是搜索条件，默认为 1。

## 演示维护与停用

为房间设置维护窗口或停用前，管理员可以先查看当天日程。若存在受影响的预约，接口返回 HTTP 409，conflicts 数组包含预约编号、预约人、日期和起止分钟（从午夜起算）。管理员协调改期后可重试。

~~~powershell
$base = 'http://127.0.0.1:8080'
$day = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')
$admin = Get-Credential -UserName admin -Message '输入初始化时设置的密码'
$auth = Invoke-RestMethod "$base/login" -Method Post -Body @{username='admin'; password=$admin.GetNetworkCredential().Password}
$ah = @{ Authorization="Bearer $($auth.data.token)" }
$block = Invoke-RestMethod "$base/admin/maintenance" -Method Post -Headers $ah -Body @{room=3; date=$day; start='10:00'; end='11:00'; reason='Camera repair'}
Invoke-RestMethod "$base/maintenance?date=$day" -Headers $ah
Invoke-RestMethod "$base/admin/maintenance/remove" -Method Post -Headers $ah -Body @{id=$block.data.id; reason='Repair complete'}
Invoke-RestMethod "$base/admin/room-status" -Method Post -Headers $ah -Body @{room=3; enabled='false'; reason='Floor closed'}
Invoke-RestMethod "$base/admin/room-status" -Method Post -Headers $ah -Body @{room=3; enabled='true'; reason='Floor reopened'}
Invoke-RestMethod "$base/admin/audit" -Headers $ah
~~~

## HTTP 接口

POST 使用 application/x-www-form-urlencoded。除注册、登录外均需 Authorization: Bearer 请求头，查询结果为 JSON。时间参数使用 yyyy-MM-dd 和 HH:mm，默认开放时间为 09:00–18:00，管理员可按星期或指定日期覆盖，按半小时预约，区间结束点不占用。

| 方法和路径 | 参数或用途 |
|---|---|
| POST /register、POST /login | username、password，登录返回 data.token |
| POST /logout | 空表单，撤销当前 token |
| GET /rooms | 所有房间、座位、设备及 enabled（布尔值） |
| GET /availability | date、start、end，可选 people 和 equipment，按容量升序 |
| GET /bookings、GET /my-bookings | date；公共日程对员工只显示匿名忙闲，管理员可看完整日程。/my-bookings 仅返回本人记录，可加 includeCancelled=true |
| POST /bookings | room、date、start、end、attendees，可选 equipment，返回 data.id |
| POST /reschedule | id、room、date、start、end、attendees，保留原设备要求 |
| POST /cancel | id |
| GET /maintenance | date，查看当天维护安排 |
| POST /admin/room-status | room、enabled（true/false）、reason |
| POST /admin/maintenance | room、date、start、end、reason，返回维护 id |
| POST /admin/maintenance/remove | id、reason |
| GET /admin/audit | 可选 from、to、page、size，按日期筛选并分页 |
| POST /bookings/recurring | 创建预约的参数加 rrule，例如 WEEKLY;COUNT=4；整批事务提交 |
| PUT /bookings/{id} | 改期，scope 默认 THIS，可选 THIS_AND_FUTURE |
| POST /bookings/{id}/cancel、POST /bookings/{id}/checkin | 取消或签到，使用空表单。签到也支持 POST /checkin 传 id |
| POST /admin/rooms | 创建或编辑房间，必填 name、capacity、reason；编辑传 id，可设 equipment、location、description、image、approval |
| POST /admin/rooms/delete | id、reason，有预约历史时返回 ROOM_IN_USE |
| POST /admin/calendar | kind、reason；weekly 传 room、weekday、start、end；override 传 room、date、start、end；holiday/workday 传 date |
| POST /admin/decision | id、approve（true/false）、reason，处理待审批预约 |
| POST /profile/email、GET /notifications | 设置本人邮箱（email），或查看最近 100 条站内通知 |
| GET /admin/dead-letters、POST /admin/dead-letters/replay | 查看失败邮件，按 id 重新投递 |
| GET /admin/reports/usage | from、to、granularity（week/month），最多 366 天 |

所有路径也支持 `/api` 前缀。创建或改期到需要审批的房间，须在会议开始前提交。免审批房间允许开始后 5 分钟内提交。预约须在 30 天内，每次最多 4 小时，每人每天最多 5 笔有效预约。日历规则 `weekly` 和 `override` 必须同时传入 `start` 和 `end`，全天关闭可显式传入两个 `00:00`。登录连续失败 5 次后锁定 15 分钟，同一 IP 每分钟最多发起 20 次登录请求。

报表按请求范围裁剪首尾周或月。可用分钟按当前房间状态和开放日历计算，并扣除维护时段。已订分钟只累计可用时段中已确认、已签到或已完成的预约。使用率等于已订分钟除以可用分钟；可用分钟为零时返回 `null`。

爽约率以要求签到且签到结果已确定的预约为分母，包括 `CHECKED_IN`、`COMPLETED` 和 `NO_SHOW`。分子为其中的 `NO_SHOW`。分母为零时，`noShowRate` 返回 `null`。预约总数 `bookings` 包含统计区间内的所有状态。

邮件投递需要 Python 3 以及 `ROOMBOOK_SMTP_HOST`、`ROOMBOOK_SMTP_USER`、`ROOMBOOK_SMTP_PASSWORD`、`ROOMBOOK_SMTP_FROM`。可选 `ROOMBOOK_SMTP_PORT` 默认 465，使用 SMTP over TLS。未设置收件邮箱的账号只收站内信。

401 表示需要重新登录或凭证错误，403 表示权限不足，400 表示参数不合法。管理员替他人改期或取消必须提交 reason，操作与审计记录同事务提交。409 表示时段冲突、设备不满足、房间停用等业务冲突，具体原因在 code 和 message 字段中。

## 数据与并发规则

- 管理员可在会议开始前批准待审批预约。从开始时刻起，批准请求返回 HTTP 409 和 `APPROVAL_CLOSED`，待审批预约由扫描任务标记为 `EXPIRED`。管理员拒绝预约时，状态为 `REJECTED`。升级旧库时，有拒绝审计记录的过期预约会更正为 `REJECTED`。
- 按周开放时间、指定日期开放时间或节假日规则变更时，若新规则会影响尚未结束的有效预约，接口返回 HTTP 409 和 `data.conflicts` 预约清单。指定日期的开放时间优先于节假日和按周规则。
- 每次爽约都会给预约人发一条 `NO_SHOW` 站内通知。触发七天限制的那条通知会写明恢复预约的具体时间（上海时区）。在 `GET /notifications` 可查看本人通知。
- 缺少必填字段时，接口返回 HTTP 400 和 `INVALID_REQUEST`。例如管理员审批时漏填 `reason`，响应的 `message` 为 `Missing field: reason`。
- 升级前没有签到要求的预约免签到。会议结束后自动归档，仍计入预约总数，但不计入爽约率的分子或分母。新版创建的预约要求在开始后 15 分钟内签到，每累计三次爽约限制预约七天。
- 升级已使用签到功能的数据库时，系统按创建预约的审计记录识别当时需要签到的会议。对已被误记爽约的旧预约，迁移会记录纠正原因，并只用真实爽约记录重算受影响账号的处罚。
- 每次预约按半小时写入 slots，复合主键（房间、日期、起始分钟）阻止重复占用。改期时释放原时段和占用新时段在同一事务中完成，遇到冲突会回滚。
- 预约与维护通过房间版本号协调并发写入。
- 停用覆盖整个房间，若有尚未结束的预约则拒绝。维护仅针对指定日期的半开区间，已有预约或维护重叠时拒绝。结束时刻起可以重新预约。撤销维护或重新启用后，其他限制仍独立生效。

## 使用率报表性能验证

在同一台机器上，用 JDK 8 和 Derby 内存库生成 3 间会议室、732 条预约记录。按周生成报表，各预热 3 次，再取 7 次耗时的中位数。基线提交为 `044b20c`。两个日期范围分别对比，优化前后报表 JSON 的 SHA-256 均一致。

| 查询范围 | 优化前 SELECT 次数 | 优化后 SELECT 次数 | 优化前耗时 | 优化后耗时 |
| --- | ---: | ---: | ---: | ---: |
| 7 天 | 25 | 6 | 4.14 ms | 1.81 ms |
| 365 天 | 1,201 | 6 | 31.55 ms | 12.75 ms |

在 Windows 上运行 `.\run.ps1 -Test` 编译后，用以下命令复测当前版本。耗时随硬件和数据量变化。

```powershell
$jdk = Split-Path (Split-Path (Get-Command javac).Source)
$derby = if ($env:DERBY_JAR) { $env:DERBY_JAR } else { Join-Path $jdk 'db\lib\derby.jar' }
& (Join-Path $jdk 'bin\java.exe') -cp "out;$derby" rooms.UsageBenchmark
```

## 简历写法

**RoomBook 会议室预约系统**（Java 17、Spring Boot、MySQL 8、JDBC、Flyway、JUnit）

- 使用 InnoDB 房间行锁和半小时时段复合主键处理并发预约，通过 JDBC 事务保证改期失败可回滚。MySQL 集成测试验证 6 个并发请求争抢同一时段仅 1 笔成功，改期冲突时保留原预约与时段。
- 将使用率报表改为按日期范围批量读取，以 BitSet 计算已订与可用时段的交集。在 732 条预约的 Derby 内存库基准中，365 天报表的 SELECT 次数从 1,201 降至 6，7 天和 365 天报表的 JSON 校验值均与优化前一致。
- 编写数据库升级逻辑，依据审计记录纠正历史爽约处罚，区分审批拒绝与自动过期。通过升级和重启测试核对预约状态、账户处罚及审计记录，并在 GitHub Actions 运行领域、HTTP 接口和报表回归测试。
