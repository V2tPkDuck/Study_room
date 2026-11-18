# 智慧自习室预约与管理系统（课程 Demo）

本仓库是软件工程课程的系统 Demo。需求依据为《需求分析报告 v1.2》与《A 需求分析质量验证报告 v1.1》；
实现范围是 FR-01 ~ FR-25、BR-01 ~ BR-12，并按照 A 报告 V-01 把“未签到”处理为
**待核查**，管理员确认后才生成违约记录。

小组编号：26 · 成员：崔乐天、排祖拉、赵晗琪、鄢凌霄

- 系统说明：[系统说明.md](系统说明.md)
- 代码核对清单：[核对清单.md](核对清单.md)

---

## 一、中文

### 1. 快速启动方式（不需要 Maven）
**确认电脑已安装 JDK 17 或以上**，然后直接双击项目根目录的：

```text
启动系统.cmd
```

它会自动找到 Java、启动 `app/study-room.jar`，并在约 8 秒后打开
`http://localhost:8080`。首次启动会自动建库，约需 5-10 秒。
如果 8080 被占用，脚本会自动尝试 8081，并打印实际端口；也可以手动指定：

```bat
启动系统.cmd 8081
```

然后访问 `http://localhost:8081`。也可以直接手动运行：

```bat
java -jar app\study-room.jar
```

> **运行只需要 JDK 17+，不需要 Maven、MySQL、Node.js 或 Docker。**
> `app/study-room.jar` 已经是打包好的可执行程序，不需要自己编译。
### 2. 环境清单

| 项目    | 要求                                              | 说明                                     |
| ----- | ----------------------------------------------- | -------------------------------------- |
| 操作系统  | Windows 10/11（已实测）；macOS / Linux 也可             | 项目路径可以包含中文和空格                          |
| JDK   | **17 或以上**（实测 JDK 21）                           | **运行必需**；Spring Boot 3.5.16 最低 Java 17 |
| Maven | 3.9.x（仅重新构建源码时需要）                               | 不修改代码就不需要安装                            |
| 开发工具  | IntelliJ IDEA Community 2024.1+ 或 VS Code 1.85+ | 只有改代码才需要                               |
| 浏览器   | Chrome / Edge 120+                              | 需要支持原生 `<dialog>` 和 Canvas             |
| 数据库   | H2 2.x（内嵌）                                      | 不需要安装 MySQL，首次启动自动建表                   |
| 端口    | 8080                                            | 可以用 `启动系统.cmd 8081` 换端口                |
| 编码    | UTF-8                                           | Java、SQL、前端文件均为 UTF-8                  |
| 其他    | 不需要 Node.js / npm / Docker                      | 前端是原生 HTML/CSS/JavaScript              |

### 3. 演示账号

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `Admin123!` |
| 学生 | `student` | `Student123!` |
| 学生 | `student2` | `Student123!` |

学生账号由管理员在“用户与黑名单”中创建，暂时不开放学生自助注册。

### 4. 目录结构

```text
study-room/
├── 启动系统.cmd                    # 双击启动（不需要 Maven）
├── app/
│   └── study-room.jar              # 打包好的可执行程序
├── server/                         # 源码与构建配置
│   ├── pom.xml
│   ├── src/studyroom/              # 14 个 Java 类（短包名 studyroom）
│   │   ├── StudyRoomApplication.java
│   │   ├── ApiController.java
│   │   ├── BookingService.java
│   │   └── ...
│   ├── resources/
│   │   ├── application.yml
│   │   ├── schema.sql
│   │   └── static/                 # index.html / app.js / styles.css
│   └── test/studyroom/             # 集成测试
├── docs/
│   ├── 系统说明.md
│   └── 核对清单.md
├── README.md
└── 修改清单.md
```

- 没有多层 `edu/campus/study` 空包路径；源码在 `server/src/studyroom/`。
- `server/` 是“源码工程”，`app/` 是“可直接运行的程序”，`docs/` 是交付文档，符合常见商业项目的分层。
### 5. 数据与重置

首次启动会在项目根目录创建 `data/studyroom-demo.mv.db`，并初始化 3 个自习室、72 个座位和演示账号。
停止服务后再次启动，数据会保留。如需重置：停止服务，删除 `data/` 目录，再启动。
### 6. 已实现范围与待确认项

- 已实现：登录、自习室/座位查询、预约/取消、签到/签退、我的预约/违约、投诉/评价、
  资源管理、实时座位、待核查、手动违约、黑名单、统计图表（10 秒刷新）、操作日志。
- 按 A 报告 V-01 调整：未签到由自动任务转为“待核查”，不直接记违约；违约达到 2 次才进入黑名单。
- 教学模拟值：预约提前 7 天、单次最长 4 小时、签到窗口 15 分钟、每人待使用预约 3 条、
  黑名单 7 天。报告 6.5 将这些数值标为“待确认”，代码中集中在 `reservation_policy` 表和
  “规则说明”页，答辩时应说明。
- 已知偏差：报告 5.3.2 的“每日 22:00”自动任务被替换为 30 秒巡检（便于课堂观察），
  已在“规则说明”页和系统说明中登记。

---

## English

### 1. Quick start (no Maven required)

Make sure **JDK 17 or newer** is installed, then double-click:

```text
启动系统.cmd
```

The script finds Java, starts `app/study-room.jar`, and opens `http://localhost:8080`
after about 8 seconds. The first run creates the database and takes 5–10 seconds.

If port 8080 is busy, the launcher automatically tries 8081 and prints the chosen port.
You can also specify it manually:

```bat
启动系统.cmd 8081
```

Then open `http://localhost:8081`. Manual alternative:

```bat
java -jar app\study-room.jar
```

> **Running only needs JDK 17+. No Maven, MySQL, Node.js or Docker is required.**
> `app/study-room.jar` is already packaged; no build step is needed.

### 2. Environment checklist

| Item | Requirement | Notes |
| --- | --- | --- |
| OS | Windows 10/11 (tested); macOS / Linux also works | Chinese characters and spaces in paths are fine |
| JDK | **17 or newer** (tested 21) | **Required to run**; Spring Boot 3.5.16 needs Java 17+ |
| Maven | 3.9.x (only for rebuilding from source) | Not needed if you do not change code |
| IDE | IntelliJ IDEA Community 2024.1+ or VS Code 1.85+ | Only needed for editing |
| Browser | Chrome / Edge 120+ | Native `<dialog>` and Canvas are required |
| Database | H2 2.x (embedded) | No MySQL installation; tables are created on first run |
| Port | 8080 | Use `启动系统.cmd 8081` to change it |
| Encoding | UTF-8 | All Java, SQL and frontend files are UTF-8 |
| Others | No Node.js / npm / Docker | Plain HTML/CSS/JavaScript frontend |

### 3. Demo accounts

| Role | Username | Password |
| --- | --- | --- |
| Administrator | `admin` | `Admin123!` |
| Student | `student` | `Student123!` |
| Student | `student2` | `Student123!` |

Student accounts are created by the administrator; self-registration is intentionally not exposed
because it is not an FR in the requirements report.

### 4. Project layout

```text
study-room/
├── 启动系统.cmd                    # Double-click launcher (no Maven)
├── app/study-room.jar              # Prebuilt runnable jar
├── server/
│   ├── pom.xml
│   ├── src/studyroom/              # 14 Java classes, short package name
│   ├── resources/                  # application.yml, schema.sql, static/
│   └── test/studyroom/             # integration tests
├── docs/
│   ├── 系统说明.md
│   └── 核对清单.md
├── README.md
└── 修改清单.md
```

### 5. Rebuild from source (optional, needs Maven)

```bat
cd server
mvn -B -ntp test
mvn -B -ntp -DskipTests package
copy target\study-room.jar ..\app\study-room.jar
```

Then double-click `启动系统.cmd` again.

### 6. Scope and assumptions

- Implemented: login, room/seat search, booking/cancel, check-in/check-out, personal bookings and
  violations, complaints/evaluations, resource management, live seat map, pending review,
  manual violations, blacklist, charts with 10-second refresh, and audit log.
- Per A-report V-01: a missed check-in becomes **PENDING** first; an administrator must confirm it
  before a violation is recorded. Two active violations trigger the blacklist.
- Teaching values: 7 booking days, 4 hours maximum, 15-minute check-in window, 3 active bookings per
  student, 7-day blacklist. The report marks these values as "to be confirmed".
- Known deviation: the report's "daily 22:00" task is replaced by a 30-second sweep for classroom
  demonstration. This is recorded on the rules page and in the system description.

### 7. Why code changes do not show up

`启动系统.cmd` runs the prebuilt `app/study-room.jar`; it does **not** read the source code in
`server/`. To develop, open the project in IntelliJ IDEA and run `StudyRoomApplication`.
Spring Boot DevTools is included: Java changes restart automatically, and frontend changes appear
after `Ctrl+F5`. After you finish, run `cd server` → `mvn package`, copy
`target/study-room.jar` to `app/study-room.jar`, then use the launcher again.
