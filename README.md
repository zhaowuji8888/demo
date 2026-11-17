# RoomBook · 会议室预约服务

Java 8、JDBC 和 Derby 实现的单机会议室预约服务。员工登录后可按人数、设备和时段查房，创建、查看、修改或取消自己的预约。管理员管理房间状态、维护窗口和预约代办。服务监听本机 127.0.0.1，预约时间按 Asia/Shanghai 解释。

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

POST 使用 application/x-www-form-urlencoded。除注册、登录外均需 Authorization: Bearer 请求头，查询结果为 JSON。时间参数使用 yyyy-MM-dd 和 HH:mm，开放时间为 09:00–18:00，按半小时预约，区间结束点不占用。

| 方法和路径 | 参数或用途 |
|---|---|
| POST /register、POST /login | username、password，登录返回 data.token |
| POST /logout | 空表单，撤销当前 token |
| GET /rooms | 所有房间、座位、设备及 enabled（布尔值） |
| GET /availability | date、start、end，可选 people 和 equipment，按容量升序 |
| GET /bookings、GET /my-bookings | date，查看当天全部预约或当前员工的预约 |
| POST /bookings | room、date、start、end、attendees，可选 equipment，返回 data.id |
| POST /reschedule | id、room、date、start、end、attendees，保留原设备要求 |
| POST /cancel | id |
| GET /maintenance | date，查看当天维护安排 |
| POST /admin/room-status | room、enabled（true/false）、reason |
| POST /admin/maintenance | room、date、start、end、reason，返回维护 id |
| POST /admin/maintenance/remove | id、reason |
| GET /admin/audit | 管理员查看代办、维护和状态变更记录 |

401 表示需要重新登录或凭证错误，403 表示权限不足，400 表示参数不合法。管理员替他人改期或取消必须提交 reason，操作与审计记录同事务提交。409 表示时段冲突、设备不满足、房间停用等业务冲突，具体原因在 code 和 message 字段中。

## 数据与并发规则

- 升级前没有签到要求的预约免签到。会议结束后自动归档，仍计入预约总数，但不计入爽约率的分子或分母。新版创建的预约要求在开始后 15 分钟内签到，每累计三次爽约限制预约七天。
- 升级已使用签到功能的数据库时，系统按创建预约的审计记录识别当时需要签到的会议。对已被误记爽约的旧预约，迁移会记录纠正原因，并只用真实爽约记录重算受影响账号的处罚。
- 每次预约按半小时写入 slots，复合主键（房间、日期、起始分钟）阻止重复占用。改期时释放原时段和占用新时段在同一事务中完成，遇到冲突会回滚。
- 预约与维护通过房间版本号协调并发写入。
- 停用覆盖整个房间，若有尚未结束的预约则拒绝。维护仅针对指定日期的半开区间，已有预约或维护重叠时拒绝。结束时刻起可以重新预约。撤销维护或重新启用后，其他限制仍独立生效。

## 简历写法

实现支持员工和管理员角色的会议室预约服务，支持按容量与设备组合查房、预约改期、维护排期及操作审计。通过 JDBC 事务、锁和时段复合主键协调预约与维护的并发写入，并验证改期冲突时保留原预约。
