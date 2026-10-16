# Moon PC

Moon 桌面代理客户端（sing-box 内核），对标 Surge Mac 设计。Kotlin 全新重写（未复用安卓代码，两端可互相验证）。

## 功能（M1–M6）

- **M1**：工程骨架、sing-box 进程管理（启动/停止/版本检测/日志）
- **M2**：节点导入（分享链接批量粘贴、订阅 URL 自动拉取更新）、节点列表（Surge 风格）、延迟显示
- **M3**：TUN 模式（gvisor 免驱动 / system 走 Wintun）、系统代理模式（写 Windows 注册表 + wininet 刷新）、手动模式
- **M4**：策略组（select 手动选出口 / url-test 自动选最优）、延迟测试
- **M5**：系统托盘（运行状态图标、右键菜单、关闭最小化到托盘）、开机自启（注册表 Run 键）、设置页
- **M6**：MSI 打包配置、应用图标

## 构建

需要 JDK 17+。

```bash
# 编译
./gradlew :composeApp:compileKotlinDesktop

# 本地运行
./gradlew :composeApp:run

# 打包（Windows 上执行，打 MSI 包）
./gradlew :composeApp:packageMsi -PtargetFormat=Msi

# 打包（Linux，app-image 便携目录）
./gradlew :composeApp:package -PtargetFormat=AppImage
```

## sing-box 二进制

Moon PC 通过外部 sing-box 进程工作，需要自行准备对应平台的 sing-box 二进制：

1. 从 sing-box 官方 Release 下载对应平台版本：
   https://github.com/SagerNet/sing-box/releases
   - Windows：`sing-box-<版本>-windows-amd64.zip` 内的 `sing-box.exe`
   - Linux：`sing-box-<版本>-linux-amd64.tar.gz` 内的 `sing-box`
2. 放置位置（二选一）：
   - `~/.moon-pc/bin/sing-box.exe`（Windows）/ `~/.moon-pc/bin/sing-box`（Linux）——优先查找
   - 系统 PATH 中（`where sing-box.exe` / `which sing-box` 能找到）

应用启动时会自动检测版本并显示在顶栏；找不到会明确报错。

## 数据目录

- `~/.moon-pc/nodes.json` — 节点
- `~/.moon-pc/policies.json` — 策略组
- `~/.moon-pc/settings.json` — 应用设置
- `~/.moon-pc/bin/` — sing-box 二进制（手动放置）
- `~/.moon-pc/config.json` — 本次生成的 sing-box 配置

## 注意

- TUN 模式需要管理员权限（右键"以管理员身份运行"）；system 栈需要 Wintun 驱动，默认用 gvisor 栈免驱动
- 托盘图标/注册表写入等 GUI 行为需在 Windows 真机验证
