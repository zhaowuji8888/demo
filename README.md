# RoomBook · 会议室预约服务

一个可以离线演示的 Java 后端项目。三间会议室按半小时预约，开放时间为 09:00–18:00。支持查看会议室、按日查预约、创建和凭密钥取消预约。数据保存在本机；服务默认只监听本机回环地址。

## 启动与测试（Windows PowerShell）

在本目录运行下列命令。需要 JDK 8、PATH 中的 javac 和 Derby JDBC 驱动。本机 JDK 自带 Java DB，脚本会自动找到驱动；其他 JDK 8 环境可将环境变量 DERBY_JAR 指向 Derby 10.14 的 derby.jar。

~~~powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Test
powershell -ExecutionPolicy Bypass -File .\run.ps1
~~~

第二条命令会持续运行；Ctrl+C 停止。端口被占用时可追加 -Port 8090。测试使用独立内存数据库；服务把数据写入项目的 data/meeting 目录，重启后仍可查询。

## 三分钟演示

保持服务运行，在另一个 PowerShell 窗口中逐段执行：

~~~powershell
$base = 'http://127.0.0.1:8080'
$day = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')
Invoke-RestMethod "$base/rooms"
$form = @{ room = '1'; date = $day; start = '09:30'; end = '10:30'; holder = 'Lin' }
$ticket = Invoke-RestMethod "$base/bookings" -Method Post -Body $form
Invoke-RestMethod "$base/bookings?date=$day"

# 相同请求应返回 HTTP 409。故意制造冲突，捕获并显示状态码。
try { Invoke-RestMethod "$base/bookings" -Method Post -Body $form } catch { [int]$_.Exception.Response.StatusCode }

# 取消后再订，证明时段已释放；最后清理演示记录。
Invoke-RestMethod "$base/cancel" -Method Post -Body @{id=$ticket.id; cancelKey=$ticket.cancelKey}
$again = Invoke-RestMethod "$base/bookings" -Method Post -Body $form
Invoke-RestMethod "$base/cancel" -Method Post -Body @{id=$again.id; cancelKey=$again.cancelKey}
~~~

POST 接口接收 URL 编码表单。创建成功返回 201 和取消密钥，按日查询不会返回密钥；日期、时间或会议室参数不合法返回 400，预约冲突返回 409。取消时记录不存在或密钥错误都返回 404。

## 为什么同时预约不会双订？

核心逻辑在 src/rooms/BookingStore.java，HTTP 层在 src/rooms/Server.java。rooms 存会议室；bookings 存完整预约；slots 存实际占用。比如 09:30–10:30 占用 09:30 和 10:00 两个时段，10:30 可以交给下一位。

1. 在同一 JDBC 事务内先写预约，再按开始时间依次写入时段。slots 的复合主键是（会议室、日期、时段），两笔事务不能同时提交相同的占用。
2. 假设 10:00 已被占用。09:30 写入后，10:00 的插入会因重复键失败。回滚整个事务，09:30 和预约主记录一起撤销。只在写时段遇到重复键时返回业务冲突。
3. 取消时根据预约 ID 和密钥删除预约，外键级联删除该预约的全部时段。一条 SQL 完成释放。

若只做“先查询空闲，再插入预约”，两个线程可能同时查到空闲。数据库约束让最终写入具有明确的冲突判定。代价是每个半小时多写一条记录，也只支持固定粒度；如果业务要求任意分钟起止，需要重新设计区间冲突检查。

## 测试覆盖与边界

test/rooms/StoreTest.java 用 CountDownLatch 让 12 个线程同时开始，通过独立 JDBC 连接争抢同一时段，断言只有 1 个成功、11 个冲突。另测部分重叠回滚、首尾相接、不同房间和日期隔离、错误取消密钥、取消后重订、非法时间。这个结果验证正确性，不代表吞吐量。

这是单机演示服务，没有用户登录或管理员角色；holder 是展示名称，持有取消密钥即可取消。为方便复现，允许使用过去日期。当前不会保留取消历史，不提供生产部署配置。

## 简历写法

**RoomBook 会议室预约服务｜Java 8、JDBC、Apache Derby、HTTP**

- 实现会议室查询、按日查看预约、创建与取消接口，使用 JDBC 和嵌入式关系数据库持久化预约数据。
- 将预约区间拆分为半小时时段，使用复合主键和事务回滚处理并发争抢与部分重叠；编写 12 线程并发测试，验证同一时段仅有一笔预约成功。
