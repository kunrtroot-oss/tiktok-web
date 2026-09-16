# iOS 版打包说明（未签名 IPA）

这是 TikTok 网页壳的 iOS 版源码，界面与功能跟安卓版**一一对应**：

- 冷启动先显示「深底 + 音符 logo」启动图（与系统启动页同一张图，切过来看不出跳变），最长停留 8 秒兜底
- 启动后加载主页 `tiktok.com`
- **只在个人主页（Profile）** 时，网页上方出现一排原生入口条：
  **订单详情 / 商品橱窗 / 店铺中心**；显示时网页整体下压，不遮挡页面内容，离开个人主页自动收起
- 点任一入口 → 进入独立的网页页（带顶部进度条与返回箭头），不污染主页的浏览历史
- 所有网址加密存放，运行时还原（与安卓同一套算法）
- 传给网页的 `data=` 参数与 JS 桥（`window.android.closeWindow / tiktokusrinfo / goCustomerService`）
  与安卓完全同名同格式，网页端一套代码两端通用

## 目录结构

```
ios/
├── project.yml                   # XcodeGen 工程配置（新增 .swift 会被自动收录，无需改此文件）
├── TikTokWeb/
│   ├── AppDelegate.swift         # 入口：启动 UINavigationController(ViewController)
│   ├── ViewController.swift      # 主页（对应安卓 MainActivity）：网页 + 进度条 + 入口条 + 启动图
│   ├── MallViewController.swift  # 入口点开的独立网页页（对应安卓 MallActivity）：data 透传 + JS 桥
│   ├── EntranceBarView.swift     # 入口条控件（对应安卓 EntranceBarView）
│   ├── Entrance.swift            # 三个入口的数据定义（对应安卓 Entrance.java）
│   ├── EntranceIcon.swift        # 入口图标（代码绘制，对应安卓的矢量图）
│   ├── Endpoints.swift           # 加密地址表与解密（对应安卓 Endpoints.java）
│   ├── WebShell.swift            # 两个网页页共用的外壳能力（UA、data 编码、外部协议、进度条）
│   ├── Info.plist                # 权限、ATS 等配置
│   └── Assets.xcassets           # 启动图 + 应用图标
└── README.md
```

仓库根目录还有 `.github/workflows/build-ios.yml`，用于云端打包。

## 云端打包（GitHub Actions）

1. 把整个项目推送到 GitHub 仓库（包含 `ios/` 和 `.github/workflows/`）。
2. 在 GitHub 仓库页面点 **Actions** → 左侧 **Build iOS IPA** → **Run workflow**。
3. 等构建跑完，进入这次运行，在 **Artifacts** 里下载 `TikTokWeb-unsigned-ipa`。

> 也可直接推送代码到 `main`/`master` 分支，会自动触发构建。

## 重要限制（务必看）

- 你现在**没有 Apple 开发者账号**，所以这个 IPA 是**未签名**的。
- 未签名 IPA **无法安装到普通 iPhone**（只能用于越狱设备或后续签名）。
- 要让 IPA 能装到正常 iPhone，需要：
  1. 注册 Apple 开发者账号（付费，约 99 美元/年）；
  2. 在苹果后台创建 App ID 和证书（p12 + mobileprovision）；
  3. 把证书配置到 GitHub Actions 的 Secrets 里，并把 `build-ios.yml` 里的签名参数改为真实证书。

## 本地打包（有 Mac 时）

在 Mac 上执行：

```bash
brew install xcodegen
cd ios
xcodegen generate
open TikTokWeb.xcodeproj   # 用 Xcode 打开，选真机，直接 Run 即可
```

## 修改入口地址 / 入口名称

- 地址：`TikTokWeb/Endpoints.swift` 里以加密数组形式存放（`*_ENC` / `*_SEED`），
  换地址须用与安卓一致的加密算法重新生成后替换，保证两端地址一致。
- 入口名称与顺序：`TikTokWeb/Entrance.swift` 的 `all()`，一处修改，界面自动跟随。

## 与安卓的差异（有意为之，不是漏做）

| 点 | 安卓 | iOS | 原因 |
|---|---|---|---|
| 返回 | 系统返回键：能退网页就退网页，到底再退出 | 标题栏返回箭头（常驻） | iOS 没有硬件返回键；若照抄安卓「到底就隐藏返回键」，用户会退不回个人主页 |
| 页内历史 | `onPageFinished` + `doUpdateVisitedHistory` | KVO 监听 `url` | 两端都是「地址一变就刷新入口条可见性」 |
| JS 桥 | `addJavascriptInterface` 原生对象 | 注入同名 `window.android` 脚本对象 | iOS 无对应能力，需自己造对象再把调用转成消息 |
