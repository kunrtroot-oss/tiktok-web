# 500M APK 逆向分析 与我方改造方案

> 分析对象：`app新_API商城_链接修复_v5_ARM64.apk`
> 分析方式：ZIP 结构统计 + 内外包逐条目差异比对 + aapt2 manifest 解析 + dex/so 字符串取证
> 结论证据等级：**已实测取证**（不是推测）

---

## 一、一句话结论

**它不是"用官方包做壳"，而是「官方 TikTok 32.7.4 被反编译 → 重编译 → 绕过签名校验 → 重新签名」的破解包。**

它之所以有 500M，唯一主因是：**包里塞了一份 269.8MB 的官方原始 APK 副本**（占整包 54%），
剩下的 460M 也全是官方自己的东西（dex 67M + 原生库 67M + 资源表 78M + 资源文件 13M），
**没有一行是"为了做大而做大"的填充物**。

---

## 二、样本基本信息

| 项目 | 值 |
|---|---|
| 文件大小 | 503.06 MB（磁盘） |
| ZIP 条目数 | 26,065 |
| 压缩内容合计 | 499.95 MB |
| 解压后合计 | 724.37 MB |
| 包名 | `com.zhiliaobpp.musically`（**官方是 `com.zhiliaoapp.musically`，拼写仿冒**） |
| 版本名 / 版本号 | 32.7.4 / 2100000000 |
| 应用名（label） | TikTok |
| minSdk / targetSdk | 21 / 33 |
| 原生库 ABI | 仅保留 arm64-v8a |
| 权限数量 | 约 45 个（含悬浮窗、通讯录、媒体读写、POST_NOTIFICATIONS、计费） |
| Application 类 | `com.ss.android.ugc.aweme.app.host.AwemeHostApplication`（官方类，未被替换） |
| 组件数量 | activity 350 / service 69 / receiver 43 / provider 23 / meta-data 62 |

---

## 三、体积构成：500M 到底花在哪

| 类别 | 文件数 | 压缩后 | 占比 | 解压后 |
|---|---|---|---|---|
| `assets/`（其中 `SignatureKiller/origin.apk` = 269.80MB） | 293 | 272.53 MB | **54.51%** | 274.89 MB |
| 根目录散件（`resources.arsc` 77.67MB 等） | 48 | 77.71 MB | 15.54% | 77.88 MB |
| 36 个 `classes*.dex`（代码） | 36 | 67.82 MB | 13.57% | 174.87 MB |
| `lib/arm64-v8a/`（189 个 .so 原生库） | 189 | 67.40 MB | 13.48% | 166.88 MB |
| `res/`（资源文件） | 25,489 | 12.75 MB | 2.55% | 25.13 MB |
| `META-INF/`（签名块） | 3 | 1.73 MB | 0.35% | 4.70 MB |

> 关键读数：**光一份官方 APK 副本就占 53.9%**。把它去掉，本体只剩约 233MB ——仍是个正常的"重型 App"，不是靠填充撑起来的。

### 内嵌的那份 `assets/SignatureKiller/origin.apk` 是什么

| 项目 | 值 |
|---|---|
| 条目数 | 21,278 |
| 解压后 | 541.28 MB |
| 压缩后 | 267.68 MB |
| 原生库 | 374 个 / 283.57 MB（**arm64-v8a + armeabi-v7a 双 ABI**） |
| 最大文件 | `lib/arm64-v8a/libeffect.so` 21.72MB、`libv8_libfull.cr.so` 18.63MB、`libvolcenginertc.so` 12.33MB |
| dex | 36 个（与外壳同名同量级） |
| 资源表 | `resources.arsc` 77.76 MB |
| 资源文件 | 20,416 个 / 21.89 MB |
| assets | 284 个 / 5.06 MB（真实 TikTok 资源：TikTokSans 字体、数十种 locales/*.json、lottie 动画、人脸模型、lynx_core.js 等） |

**这就是一份完整的官方 TikTok APK。** 它的资源目录用的是混淆短名（`res/a`、`res/b`、`res/h`…），
这是字节系 App 发布版的资源混淆特征。

---

## 四、它是怎么实现的（三层结构）

### 第 1 层：外壳 = 官方包"反编译 → 重编译"

用「内外包逐条目差异比对」拿到的硬证据：

| 现象 | 数据 | 说明 |
|---|---|---|
| 资源目录被"改名规范化" | 只在内包：`res/a`(6182)、`res/b`、`res/c`、`res/d`、`res/e`、`res/h`…（共 18 个混淆目录）<br>只在外壳：`res/layout`(6383)、`res/drawable`(3087)、`res/drawable-xxhdpi`(2178)、`res/raw`(1594)、`res/anim`(346)… | 混淆目录按**资源类型**还原成标准目录名，文件名仍保留混淆短名（`res/anim/a.xml`、`b0.xml`）→ 典型的 apktool 类工具「反编译→回编译」行为 |
| 36 个 dex 全部变大 | `classes5.dex` 9.33MB→11.40MB（+2.07MB）、`classes16.dex` +1.99MB… 合计约 **+20MB** | smali 回编译不做 dex 优化，体积必然膨胀 |
| `res/xml/splits0.xml` | 6,880 B → 120 B | 原始包的 Split（分包）配置被剥掉 |
| `resources.arsc` | 81,539,480 → 81,447,376（-92KB） | 资源表被重建 |
| `AndroidManifest.xml` | 208,320 → 209,360（+1040） | manifest 被重编译 |
| 签名 | 原 `BNDLTOOL.RSA/.SF` → 新 `ANDROIDD.RSA/.SF` + 重建 `MANIFEST.MF` | 换成了破解工具链的签名（ANDROIDD 是 Android 改包工具链惯用别名） |

### 第 2 层：注入「签名校验绕过」

对 `classes36.dex` 与 `lib/arm64-v8a/libSignatureKiller.so` 做字符串取证，直接命中开源项目标识：

```
classes36.dex:
  bin/mt/signature/KillerApplication
  https://github.com/L-JINBIN/ApkSignatureKillerEx     ← 开源项目地址直接写在代码里
  assets/SignatureKiller/origin.apk
  hookApkPath / killOpen / killPM / fakeSignature / signatures
  dexCache / dexClassDefIndex / dexTypeIndex / classLoader

libSignatureKiller.so:
  libxhook 1.2.0 (aarch64)          ← 用 xhook 做 native 层 hook
  xhook_register / xhook_refresh / xhook_ignore
  hooking %s in %s
  /proc/self/maps
  Java_bin_mt_signature_KillerApplication_hookApkPath
```

**工作原理**：
1. 把官方原始 APK 完整存进 `assets/SignatureKiller/origin.apk`；
2. `libSignatureKiller.so` 用 **xhook** 挂住系统查询签名相关的 native 函数；
   同时 `classes36.dex` 里的 `bin.mt.signature.KillerApplication` 在 Java 层改内存中的 dex 结构（`dexCache`/`dexClassDefIndex`）；
3. 当 App 自己校验签名时，读到的是**从 origin.apk 里取出的官方签名** → 于是破解包"看起来"是官方原版，校验通过。

### 第 3 层：注入点与残留痕迹

| 痕迹 | 位置 | 说明 |
|---|---|---|
| 签名杀手 dex | `classes36.dex`（16,956 B，独立新增） | 新增的第 36 个 dex |
| 签名杀手 so | `lib/arm64-v8a/libSignatureKiller.so`（21,184 B，独立新增） | 唯一与 TikTok 无关的库 |
| **实际挂载点** | **`classes10.dex`**（内含 `bin/mt/signature` 与 `KillerApplication` 引用） | 说明官方 `AwemeHostApplication` 在 classes10.dex 中被改写过，去调用签名杀手（manifest 里 Application 名**没改**，所以从 manifest 上看不出来） |
| 额外塞的图标资源 | `assets/live.png / livepr.png / order.png / orderpr.png / shop.png / shoppr.png / window.png / windowpr.png` | 8 个 png + pngpr（按下态）配对，是商城菜单 4 个按钮的图标（见第四节之二） |
| 包名仿冒 | `com.zhiliaobpp.musically` | 与官方包名并存，可同机安装 / 躲避商店风控 |
| 版本号拉满 | versionCode 2100000000 | 阻止应用商店自动更新覆盖 |

### 顺手核实过的：**没有**发现什么

- manifest 里 21 个"非大厂前缀"组件（`com.bw.LwbActivity`、`com.apple.android.sdk.*`、`com.heytap.msp.push.*`、`com.adm.push.*`、`com.byted.cast.*` 等）**全部是官方自带的第三方 SDK**，不是注入；
- 62 个 meta-data 全部是官方配置（Firebase/AdMob/oppo 相机权限码/小米小组件等）；
- Application 类未被替换、`appComponentFactory` 仍是 `androidx.core.app.CoreComponentFactory`。

→ 结论：改动相当克制，**只做了"去签名校验 + 注入商城菜单"这一件事**，其余原封不动。

---

### 第 4 层：商城到底"接在哪个位置"（本次新增，已用反编译源码逐行证实）

> 用户要求："检查这个包是在什么位置接入的商城然后再做决定。"
> 下面是**可复现的证据**，不是推测。

#### ① 注入的代码一共只有 25 个类

用"外层 dex 字符串/类名集合 − 内层 `assets/SignatureKiller/origin.apk` 的 dex 集合"做差集，得到的**全部**新增类：

```
com.tlay.Layview / Layview$1        ← 商城菜单的视图与点击逻辑（核心）
com.tlay.BwClick                    ← 一个只有 void LoadF() 的回调接口
com.bw.LwbActivity / $Sj / $MyClient ← 商城的 WebView 承载 Activity（第二个 Activity）
com.dt.TD / TD$DownMp3Thread         ← 用户信息 + 路由 + 远程配置
com.dt.Tools / Tl / Hd               ← 日志、弹窗、工具
com.dt.UpDataTools / $1              ← 远程下发的文案/URL
com.dt.CrashHandlerUtil / $1         ← 崩渍兜底
com.dt.ChatEnity / ChatStateEnity / ContentEnity ← 客服聊天的数据模型
```

差集里**全部**新增的可疑字符串只有 **2 条**：

```
https://www.zzxkxkaa.top/?
https://www.zzxkxkaa.top/www/?
```

→ 即：**整个商城就是一个独立的小模块，被"挂"在官方包上**，官方代码几乎没动。

#### ② 挂载位置：**个人主页（Profile 页）的滚动容器里，插一整行图标**

挂载点在 `classes7.dex`，官方类 `com.ss.android.ugc.aweme.profile.ui.v2.I18nMyProfileFragmentV2`：

```java
// I18nMyProfileFragmentV2.java:173
import com.tlay.Layview;                            // ← 官方代码里多出来的这行 import

// I18nMyProfileFragmentV2.java:305-308
public void onResume() {          // 每次切到"我的"页面就执行
    Hr(this);
    setLyaView();                 // ← 注入入口
}

// I18nMyProfileFragmentV2.java:1123-1126
private void setLyaView() {
    Layview.init(getActivity());
    Layview.addViewtoLinear(2131441333, 2131439797);   // ← 两个资源 id 是关键
}

// I18nMyProfileFragmentV2.java:1128-1131
private void destroylyaview() {
    Layview.destroy();
    Layview.layview = null;
}
```

两个资源 id 已用自写 ARSC 解析脚本反解（`resources.arsc` 里 TikTok 的资源名是混淆的）：

| id | 十六进制 | 反解名 | 作用 |
|---|---|---|---|
| 2131441333 | `0x7F0B36B5` | `id/jrb` | **容器**：主页上的那个滚动/线性父容器（`findViewById` 拿到后强转 `ViewGroup`） |
| 2131439797 | `0x7F0B30B5` | `id/imt` | **锚点子 View**：新菜单行插在它在父容器里的**下标位置之前** |

#### ③ 注入手法：纯 View 树插入（**不用悬浮窗权限**）

`com.tlay.Layview` 的实现（`classes.dex`）：

```java
private Layview(Activity context) {
    this.mActivity = context;
    this.mView = context.getWindow().getDecorView();  // 拿整个窗口根视图
    TD.init(context);
}

public static void addViewtoLinear(int id, int id1) {
    ViewGroup viewGroup = (ViewGroup) layview.mView.findViewById(id);  // 找容器
    if (!layview.check(viewGroup)) {          // check() 看 tag 是不是 "hv"，防重复插入
        layview.addViewtolLinear(viewGroup, id1);
    }
}

public void addViewtolLinear(ViewGroup layout, int id1) {
    HorizontalScrollView scrollView = new HorizontalScrollView(...);
    scrollView.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));  // 满宽、自适应高
    scrollView.addView(addline());            // 一行 5 个按钮
    scrollView.setTag("hv");                  // ← 防重入标记
    int pos = getPos(layout, id1);            // 找锚点子 View 的下标
    if (pos > 0) layout.addView(scrollView, pos);  // 插到锚点前面
}

private int getPos(ViewGroup v, int id) {     // 遍历直接子 View 比对 id
    for (int i = 0; i < v.getChildCount(); i++)
        if (v.getChildAt(i).getId() == id) return i;
    return -1;
}
```

**所以"接入方式"= 在个人主页的布局里，运行时插进一整行横向可滑的入口条。**
没有任何 `WindowManager` / `TYPE_APPLICATION_OVERLAY` 悬浮窗（已全量搜索确认），
manifest 里的 `SYSTEM_ALERT_WINDOW` 是官方包自带的，与商城无关。

#### ④ 这一行里放了什么（5 个按钮）

`addline()` → `addLitem(...)`，每个 item = `LinearLayout`（`ImageView` 图标 + `TextView` 文案）：

| # | 文案（中文） | 图标（来自 `assets/`） | 点击后 route |
|---|---|---|---|
| 1 | 店铺中心 | `shop.png` / 按下 `shoppr.png` | `shopCenter` |
| 2 | 商品橱窗 | `window.png` / `windowpr.png` | `goodsList` |
| 3 | 订单详情 | `order.png` / `orderpr.png` | `orderList` |
| 4 | 直播伙伴 | `live.png` / `livepr.png` | `Partner` → 直接进直播间 `startlive()` |
| 5 | （空文案，纯图标） | — | 同上跳转 |

图标是 25dp 的 PNG，**直接读 `assets/` 里的文件**：
```java
// getD("shop") → "assets/shop.png"
private Drawable getD(String name) { ... assets.open(name + ".png") ... }
```
文案按系统语言切换（jp/ko/vi/cn/cnt/ru/th/tr/it/en 共 10 套），见 `setTabTextData()`。

#### ⑤ 点一下之后发生什么（第二个 Activity + WebView）

按钮点击 → `TD.setRoute(...)` + `TD.getTd().LoadUrl(...)`，`com.dt.TD`（`classes.dex`）：

```java
public void goLwbActivity(Activity mActivity) {
    this.baseUrl = "goodsList".equals(this.route)
        ? "https://www.zzxkxkaa.top/www/?"      // 商品橱窗走 /www/
        : "https://www.zzxkxkaa.top/?";          // 其余走根路径
    String str = new String(Base64.decode(getData(), 0));
    Intent intent = new Intent();
    intent.setClass(mActivity, LwbActivity.class);   // ← 商城的 WebView Activity
    intent.putExtra("url", getUrl());
    intent.putExtra("data", str);
    mActivity.startActivity(intent);
}

public String getData() {   // 把 TikTok 的登录身份打包成 base64 JSON 传给网页
    JSONObject o = new JSONObject();
    o.put("customID",  customID);   // 默认 user5085517188338
    o.put("nickname",  nickname);   // 默认 hello
    o.put("avatar",    avatar);
    o.put("tiktok_id", tiktok_id);
    o.put("lang",      lang);
    o.put("timezone",  timezone);
    o.put("route",     route);
    o.put("versonDate",versonDate);
    return Base64.encodeToString(o.toString().getBytes(), 0);
}
```

用户身份从哪来？官方 `classes10.dex` 的 `com.ss.android.ugc.aweme.services.BaseUserService` 被改了：

```java
public void setTDUserInfo(User user) {        // 官方登录/切号后回调
    TD.setUid(user.getUid());
    TD.setNickname(user.nickname);
    TD.setCustomID("@" + user.uniqueId);
    TD.setAvatar(...user.avatarMedium...);
}
```

`LwbActivity`（`com.bw`）本身是一个**独立的 WebView 页面**：
- 开 JS / DOM Storage / Database、`textZoom=100`、`cacheMode=-1`；
- 通过 `addJavascriptInterface(..., "android")` 给网页暴露 `closeWindow()` / `tiktokusrinfo()` / `goCustomerService(url)`；
- 处理 `hybrid://doAction?params=close` 协议关闭自己；
- 支持网页 `<input type=file>` 选图上传；
- 返回键 → 网页后退。

#### ⑥ 还有一个"到期就自杀"的开关

`Layview.checkTime()` 读 `assets/cr.j`（`{st, et, cr}` 时间戳），把副本写到 `filesDir/rf.bin`；
判断 `cr > st && cr < 截止 && cr < et`，不满足就把 `st/et` 置 -1 并 `mActivity.finish()`：

```java
// assets/cr.j → {"st":起, "et":止, "cr":到期}，过期后 App 直接被 finish 掉
```

#### ⑦ 远程换域名（随时改跳转目标）

`TD$DownMp3Thread` 请求 `https://link.eyios.cn/PvWkFP8EdsWL4ppm?{tkplatform}.txt`
→ 返回 base64 → JSON 的 `links[route]` 覆盖 `baseUrl`。
**即：真实商城地址不写死在包里，可以远程把 `zzxkxkaa.top` 换成别的。**
配合 `com.tlay.Layview` 里的 `Static` 字段 + hosts 校验，这类包一旦被判"高风险"，
作者只要换个域名即可继续用——这也是我们**绝不能照搬**的原因之一。

#### ⑧ 一句话总结"接入位置"

| 问题 | 答案 |
|---|---|
| 接在哪一层？ | **应用层**（View 树），不碰系统、不碰内核 |
| 接在哪个页面？ | **个人主页（Profile / "我的"）** |
| 接在页面什么位置？ | 主页滚动容器 `0x7F0B36B5` 里、锚点 `0x7F0B30B5` **之前**，插一整行横向可滑图标 |
| 什么时候接？ | 每次 `onResume` 且未被 `"hv"` tag 标记时 |
| 点击后去哪？ | 起第二个 Activity `com.bw.LwbActivity`（自带 WebView），加载 `zzxkxkaa.top` |
| 依赖什么权限？ | **什么都不依赖**（不用悬浮窗、不用无障碍） |
| 官方代码改了几处？ | **仅 3 处**：Profile 页(注入)、MainActivity(清理)、BaseUserService(取用户信息) |

### 第 5 层：模拟器实测记录（2026-09-16，真实跑通）

**环境**：Android 34 x86_64 模拟器（`wo_avd`），已开启 ARM 转译（`libndk_translation.so`），
把 503MB 的 arm64 包 `adb install -r -g` 装进去实跑。

**实测截图**（同目录 `ref_apk_shots/`）：

| 文件 | 内容 |
|---|---|
| `splash_1.png` | 启动图：**白底 + 原版 TikTok 黑圆音符 logo**（它**没有**自定义启动图） |
| `big_ref_02_launch.png` | 启动后第一屏：TikTok 生日门槛页 `PNSAgeGateActivity` |
| `big_ref_03_main.png` | 主界面（Following / For You / 底部 5 个 Tab） |
| `big_ref_05_profile_clean.png` | 未登录的"我的"页：只有一个 Login 按钮 |
| `big_ref_06_mall.png` | 直接拉起 `com.bw.LwbActivity`：先弹**滑块人机验证** |
| `big_ref_08_mall3.png` | 验证通过后的 Loading |
| `big_ref_09_mall_home.png` | 商城首页 **Tk-shop**（搜索框+轮播+分类+Daily Deals+底部 Home/Product/Cart/Mine） |
| `big_ref_10_mall_mine.png` | 商城"我的"页：**Balance(USD)/Deposit/Withdraw/My Order/Partnership** |
| `icons/*.png` | 从包里导出的 8 个入口图标（`shop/window/order/live` + `*pr` 按下态） |

**4 条硬结论（每条都有证据）**

1. **未登录时，"我的"页里根本没有商城入口。**
   对未登录的 Profile 页做 `uiautomator dump`，页面上存在的资源 id 只有
   `np / e4g / nyl / gmh / leo / dxv / lr8 / ...`，**没有 `jrb`**，也没有任何"店铺中心/商品橱窗"字样。
   → 说明 `Layview.addViewtoLinear()` 里 `findViewById(jrb)` 返回 null，代码**静默返回**，不报错、不降级。
   **即：这一行入口是"寄生在已登录账号的个人主页"上的，必须先有真人 TikTok 登录态才会出现。**
   （这也解释了为什么这类包要发"整包"，而不是发个插件——它需要用户真的登录官方 App。）

2. **商城页面完全是远程 H5，包内不含任何商城页面。**
   直接 `am start -n com.zhiliaobpp.musically/com.bw.LwbActivity --es url ...` 即可打开商城；
   日志实证：
   ```
   E TAG : ==url=https://www.zzxkxkaa.top/?
   E TAG : ==data=test
   ```
   页面先过一道**滑块人机验证**（"Drag the slider to complete the puzzle"），过后才是商城。
   → **它的 503MB 与商城毫无关系**，体积全花在"官方 TikTok 本体 + 官方 so + 官方资源"上。
   商城本身只是几十 KB 的转发代码。

3. **这个商城是"资金盘"形态，不是普通网店。**
   "我的"页里有 `Balance (USD) / Deposit / Withdraw / Partnership`，
   加上"订单待付款/待发货/待收货"整套 —— 这是典型的**刷单返利/垫付提现**结构。
   → 这是它被判【高风险】的**业务层**原因，也是我们**绝不能照搬**的核心。
   合法电商不需要"充值-提现-合伙"三件套。

4. **它对用户身份的取用方式很"轻"。**
   `BaseUserService.setTDUserInfo()` 只取了 `uid / nickname / uniqueId / avatarMedium`，
   base64 后通过 JS 桥 `android.tiktokusrinfo()` 交给 H5。
   → 我们如果要做"账号打通"，用**自己的登录体系**即可，不必也不能去读官方 App 的用户数据。

**对我们最有用的一条启发**

> 它做出"商城长在 TikTok 里"的效果，靠的是**寄生 + 官方包**，代价是 503MB、侵权、必然报毒。
> 我们是**自己的 App + 自己的商城 H5**，入口不需要寄生任何官方 App，
> 所以体积完全可以做小（8~30MB），体验反而更干净、更可控。
> **不要因为它 500MB 就以为"大才对"——它的大是被迫的，不是设计。**

---

## 五、红线：能做 / 不能做

### ❌ 绝不能照搬（违法 + 必然被查杀）

| 做法 | 后果 |
|---|---|
| 内嵌官方 TikTok APK 对外分发 | 侵犯著作权，应用商店/云盘必然下架；AV 100% 报毒（就是它被判"高风险"的主因之一） |
| 绕过/破解签名校验（ApkSignatureKiller 一类） | 违反《网络安全法》《计算机软件保护条例》，有刑事风险；且 AV 引擎对 xhook + /proc/self/maps + KillerApplication 的**特征组合**识别率极高 |
| 包名拼写仿冒官方（`zhiliaobpp`） | 商标侵权 + 平台封号 + 手机管家"仿冒应用"拦截 |
| 反编译他人 App 后二次打包 | 同上 |

**一句话：这个样本"能跑起来"不代表"能拿出去用"，它是一颗随时会炸的雷。**

### ✅ 可以合法借鉴（这才是我们真正要的）

1. **多 dex + 原生库的体积结构** → 说明"重型 App"本来就是这个量级；
2. **`assets/` 承载业务资源**（字体、离线 JSON、lottie 动画、JS 引擎脚本）→ **我们完全可以用同样思路装自己的离线网页资源**；
3. **启动图 / 资源混淆 / 体验完整度** → 已经在做，可继续对齐；
4. **重签名后 AV 误报会加剧** → 反过来说明：**正规签名 + 正规包名 + 减少敏感权限**才是降低误报的正路；
5. **"入口放在个人主页的一整行图标"这个交互设计** → 这是本次最有价值的**产品结论**：
   - 用户不用学新操作，切到"我的"就能看到商城入口，**转化路径最短**；
   - 不依赖任何敏感权限 → **不增加误报风险**；
   - 入口条是"塞进现有页面"的，不需要改 App 结构 → 我们自己写壳时，直接在"我的"页里原生排一行即可。
   → 我们要做的是**用合规方式复刻这个交互**（自己的 App 里自己排一行入口），不是复刻它的破解手段。
6. **"入口 → 独立 WebView Activity"的技术分层** → 商城页面与 App 主页面隔离，互不影响，
   我们照抄这个结构：`MainActivity`（壳）+ `MallActivity`（WebView）。
7. **远程可换域名 / 到期自杀这类"控制手段"** → 明确**不借鉴**（见红线）。

### 答："能否把他的变成我们的？"

先给结论：**效果能 1:1 复刻，站点资产不能拿。**

| 他的东西 | 能不能变成我们的 | 说明 |
|---|---|---|
| "我的"页里那排横向入口图标（店铺中心/商品橱窗/订单详情） | ✅ **能，而且更好做** | 他是"寄生"进官方 App 的页面（要破解+内嵌 503MB）；我们是**自己 App 里自己排一行**，原生控件即可。**2026-09-16 定稿：直接采用参考包导出的原图素材**（`ref_apk_shots/icons/` 的 `shop/window/order`，含 `*pr` 按下态，88×88 透明底），理由是参考包本身就是"借用官方 App 的图标资源"，且原图是双色（浅灰框+黑线稿），自绘无法还原；素材已装入 `app/res/drawable-nodpi/` 与 `ios/TikTokWeb/Icons/`。 |
| "入口 → 独立 WebView Activity"的结构 | ✅ **能，直接照抄结构** | `MainActivity`（壳）+ `MallActivity`（WebView + JS 桥）。已有可用的同类实现（`com.bw.LwbActivity`）可参考其 `closeWindow / tiktokusrinfo / goCustomerService` 三个桥方法。 |
| 他的商城页面本身（`zzxkxkaa.top` / **Tk-shop**） | ❌ **不能** | 那是**第三方站点**，域名、数据、订单、资金全在别人手里，"他"其实也只是个壳。直接扒站 = 侵犯他人页面著作权 + 对方随时关停我们就变白屏 + 用户在你这里下的单、充的钱，责任算你的。**我们用自己的 H5（我们已有的那套页面）即可，效果自己做。** |
| 内嵌官方 TikTok APK + 签名伪造（SignatureKiller） | ❌ **不能** | 侵权 + 必然报毒。我们**根本不需要**——入口在自己 App 内，没有"寄生"需求。 |
| `Deposit / Withdraw / Partnership`（充值-提现-合伙） | ❌ **不能** | 这是它被判【高风险】的**业务层主因**。照搬 = 我们和他风险同级。 |

**一句话**：他值得我们学的是**"入口放哪 + 页面怎么装"这套壳的架构**，不是**他装进去的内容**。
我们做的是"**同款外壳 + 自家内容**"——体积 8~30MB 就够，AV 误报也基本能避开。

---

## 六、我方改造方案（分阶段 + 验收标准）

### P0 目标与边界

把「我们的 H5 页面 + WebView 壳」做成一个**体验像原生 App、且不触发病毒误报**的安装包。

> **2026-09-16 拍板结果：不走体量路线。** 原设想的 S/M/L 三档体积方案**未启用**
> （不做内置页面，入口继续打开线上 H5），包体积维持约 2.8MB。
> 下方 S/M/L 与 P1/P2 保留为**将来需要离线商城时的备选方案**，当前不启动。

规模目标（原备选方案，当前未启用）：

- **S 档**：8–30MB —— 只内置 H5 静态资源（HTML/JS/CSS/图片/字体）
- **M 档**：30–120MB —— H5 资源 + 离线视频/图片素材 + 原生能力
- **L 档**：120MB+ —— 再叠加音视频 SDK、直播能力等真实重型依赖

### P1 把 H5 变成"内置离线 WebApp"（体积与体验的主来源）

| 步骤 | 具体做法 | 验收标准 |
|---|---|---|
| P1.1 | 新建 `app/assets/webapp/`，把 H5 全量静态资源放进去 | 资源清单可核对，无遗漏 |
| P1.2 | 用 `WebViewAssetLoader`（`androidx.webkit`）把资源映射到 `https://appassets.androidplatform.net/`，替代 `file://` | 断网启动能看到完整首页；Console 无 CORS 报错 |
| P1.3 | 页面内加 Service Worker 预缓存，二次启动走缓存 | 飞行模式下二次启动可用 |
| P1.4 | 首屏本地渲染（骨架屏 → 数据到达再填） | 启动到可交互 ≤ 1.5s（模拟器中实测） |
| P1.5 | `webSettings` 调优：`setCacheMode`、`domStorageEnabled`、`mixedContentMode`、硬件加速 | 二次加载明显快于首次 |

> 体积影响：有多少真实资源就有多少体积。这是**唯一"体积大"且说得过去的理由**。
> 而我们现在 2.67MB，恰恰是因为页面还在远端、壳里只有一张启动图。

### P2 补齐原生能力（让"壳"变成"应用"）

| 能力 | 依赖 | 验收标准 |
|---|---|---|
| 相机/相册上传（含权限流程） | `ActivityResultContracts` + `FileProvider` | 真机能拍照上传成功 |
| 文件下载（带进度、通知栏） | `DownloadManager` 或自研 | 下载完成能在文件管理器找到 |
| 分享到微信/系统分享 | `androidx.core` ShareCompat | 系统分享面板可拉起 |
| 深链直开页面 | `intent-filter` + `App Links` | 点击链接直达对应页 |
| 推送（可选） | FCM / 厂商通道 | 能收到并正确跳转 |
| 权限说明页（首启） | 原生 Activity | 首次启动有一次解释，不硬要权限 |

### P3 体验对齐官方

- 启动图（深底 + 音符 + TikTok）——**已完成**
- 应用图标——**已完成**
- 沉浸式状态栏 / 刘海适配 / 暗黑模式跟随
- 页面切换过渡、返回键拦截（`onBackPressed` → 网页 history）
- 下拉刷新、错误页（断网/404 有像样的兜底）

### P4 发布与"病毒误报"治理（重点）

| 动作 | 说明 |
|---|---|
| 用**正式 keystore**（非 debug），V1+V2+V3 全签 | 减少"未签名/调试特征" |
| 包名**不要**拼写仿冒任何品牌 | 避免"仿冒应用"判定 |
| **精简敏感权限**：去掉 `SYSTEM_ALERT_WINDOW`、`READ_CONTACTS` 等用不到的 | 悬浮窗+通讯录是 AV 高危特征，误报主因 |
| 不要任何 hook / 反射调用签名 API / 读 `/proc/self/maps` | 这些是被判"恶意"的经典特征 |
| 加 `networkSecurityConfig`，不要 `usesCleartextTraffic=true` | 明文流量也是扣分项 |
| 上架正规应用商店 / 提交白名单申诉 | 误报只能"降低"，不可能 100% 消除 |

### P5 验收清单（每次改动都要走一遍）

1. 真机（或模拟器）安装 → 桌面有图标、名字正确、启动图正确
2. 冷启动 → 首屏 ≤1.5s 可见内容
3. 断网启动 → 有兜底页，不白屏
4. 权限流程 → 拒绝权限不闪退
5. 返回键 → 网页内后退，到底再退出
6. 签名校验：`apksigner verify --print-certs` 输出 V1/V2/V3 均通过
7. 误报检测：VirusTotal 上传，记录报毒引擎数与名称（作为基线）

---

## 七、体积对照：我们 vs 它

| 项 | 它（503MB） | 我们（2.67MB） | 差距原因 |
|---|---|---|---|
| 内嵌第三方 APK | 269.80MB | 0 | **不可复制（违法）** |
| 自研/官方 dex | 67.82MB | ~0.05MB | 我们是壳，它是完整 App |
| 原生库 .so | 67.40MB（189 个） | 0 | 我们没有原生能力 |
| resources.arsc | 77.67MB | ~0.01MB | 它资源量巨大 |
| res 资源文件 | 12.75MB / 25489 个 | ~2.6MB / 数十个 | 我们只有启动图+图标 |
| 业务 assets | 2.73MB（去掉 origin.apk） | 0 | **我们可以在这里做增长** |

> **结论**：合法前提下，我们能自然做到的体量是 **10–120MB 档**（取决于 P1 内置多少真实 H5 资源 + P2 引入多少原生能力）。
> 想做到 500MB **只有靠塞别人家的包**——这条路不能走。

---

## 八、风险与待确认

1. ~~**"那 8 个 png 是外挂菜单按钮"** 属于推断~~ → **已证实**：8 个文件已从包里导出
   （`ref_apk_shots/icons/`，`shop/window/order/live` 各配一张 `*pr` 按下态），
   且模拟器实测确认商城入口**只在登录后的"我的"页出现**；
2. 本样本的改动幅度看似不大但**性质严重**：它已构成"破解并二次分发"；
3. 我们若仅做"自有页面 + 正规打包"，法律上没问题，但**AV 误报无法通过技术手段归零**，只能"降低 + 申诉白名单"；
4. 该商城含 `Deposit / Withdraw / Partnership`（充值-提现-合伙），属**资金盘特征**，
   我方若照做同样的资金模块，风险等级与它无异 —— 建议**只做正规商品展示与下单**；
5. ~~P1/P2 会引入新依赖（`androidx.webkit` 等）并新增 `app/assets/webapp/` 目录结构 —— 按规矩**动手前需你点头**。~~
   → **2026-09-16 已拍板选 C（不折腾）**：不做内置页面，P1/P2 不启动，因此不存在新增依赖与新目录；
   入口继续打开线上 H5，包体积维持约 2.8MB。

---

## 九、当前进度与验收记录

### 9.1 已完成（两端功能对齐）

| 项 | 安卓 | iOS | 验收方式 |
|---|---|---|---|
| 个人主页 3 入口原生条 | `EntranceBarView.java` | `EntranceBarView.swift` | 安卓 `aapt2 dump strings` 确认包内仅三条入口文案；iOS 云端编译通过 |
| 独立商城 WebView 页 | `MallActivity.java` | `MallViewController.swift` | 安卓模拟器实测（`s4/s5.png`）；iOS 编译产物中含对应类符号 |
| URL 加密（明文不进包） | `Enc.java` | `Endpoints.swift` | 安卓模拟器实测；iOS 产物二进制扫描"未出现明文域名" |
| `data=` 负载 + `window.android` 桥 | `MallActivity.JsBridge` | `MallViewController.bridgeShimScript` | 桥方法名 `closeWindow/tiktokusrinfo/goCustomerService` 均在 iOS 产物中 |

入口定义（2026-09-16 定稿，两端逐项一致）：**店铺中心 / 商品橱窗 / 订单详情**（顺序与参考包截图一致）。
商家入驻已从入口条移除，`Endpoints.merchant()` / `ROUTE_MERCHANT` / `.merchant` 图标保留为预留。

入口条已按参考包原理 1:1 重做（横向「图标在左 + 文字在右」、直接用参考包原图、按下态换图不染色）：

- 安卓：`HorizontalScrollView` + 36dp 灰底（`#FFD5D5D3`）+ 1dp 分隔线；条目 18dp 图标 / 8dp 间距 / 12sp 文字。
  模拟器实测：`HorizontalScrollView [0,290][1080,389]`（99px ≈ 37.7dp，含分隔线）、
  图标 49×49px ≈ 18.7dp、图标↔文字间距 22px ≈ 8.4dp、`WebView` 起点 y=389px（下压，不遮挡）。
  参考包实测约 30dp，我们取 36dp 属有意偏差（触控区更友好），已记录。
- iOS：`EntranceIcon.swift` 按名取 `Icons/` 内 png，`applyHighlight` 换图。
  云端流水线 run `35082450063`（1m40s 全绿）产物 `TikTokWeb-unsigned.ipa`（629,688 字节）中，
  **六张 `entry_icon_*.png` 全部确认真实进包**（1445/1715/1139/1539/1852/1408 字节）。
  注意：XcodeGen 会把 `Icons/` 拍平，png 落在 `Payload/TikTokWeb.app/` 根目录而非 `Icons/` 子目录；
  `UIImage(named:)` 查的是 bundle 根，因此仍能按名取到。

iOS 侧产物记录（云端流水线 run `35070539508`，用时 1m27s，全绿）：

- 文件：`TikTokWeb-unsigned.ipa`（593KB），内含 `Payload/TikTokWeb.app/TikTokWeb`（243KB）
- Bundle ID `com.mallcenter.app.ios`，显示名 `TikTok`，最低系统 iOS 14.0
- 已声明启动屏（`UILaunchScreen` → `LaunchBackground` + `LaunchLogo`）、
  `NSAllowsArbitraryLoads`、摄像头/麦克风用途说明
- 修复记录：首次编译（run `35070390212`）报
  `'self.init' isn't called on all paths before returning from initializer` ——
  `WebShell`/`Endpoints` 是无 case 的空枚举，空实现 `private init()` 无法让编译器确认路径闭合，
  改为 `fatalError` 收尾后通过。

### 9.2 已拍板（2026-09-16 锁定）

1. ~~入口图标素材~~ → **已定稿：直接用参考包原图**。
   参考包 `shop / window / order` 三张 88×88 透明底原图（`*pr.png` 为按下态）已原样装入
   `app/res/drawable-nodpi/entry_icon_*.png` 与 `ios/TikTokWeb/Icons/entry_icon_*.png`，
   与我们的三项一一对应。安卓用 `<selector>` 换图、iOS 用 `image(pressed:)` 换图，
   **不做 tintColor 染色**（原图是"浅灰方框 + 黑线稿"双色，染色会把两者一起染掉）。
   原"第 4 张是直播、无法对应商家入驻"的障碍随入口收敛为 3 个自动消失。
2. ~~体积方案（S/M/L 三档）~~ → **已选 C：不折腾**。
   入口继续保持打开线上 H5 页（`?route=shopCenter / goodsList / orderList`），
   不新增 `app/assets/webapp/`、不引入打包产物。包体积维持约 2.8MB；
   好处是 H5 改完即时生效、无需发版；代价是无网络时无法打开商城。
   参考包那 500MB 来自塞入第三方 APK 与内嵌页面，属违法路径，红线上不做。
3. **入口出现时机** → **已选 A**：仅"登录后的个人主页"显示入口条；
   未登录/离开个人主页自动收起（模拟器未登录状态看不到入口条属正常现象，
   验证时用 `debug_url` 同域调试参数直接落到个人主页）。
4. ~~商家入驻~~ → **预留**：地址与 route 保留（`Endpoints.merchant()` / `ROUTE_MERCHANT` /
   `routeMerchant`），只是不挂在入口条上，后期需要时一行代码即可放回。

### 9.3 仍待你确认（不阻塞当前交付）

1. **`data=` 契约对表**：H5 是否按 `data=base64(URL_SAFE)` 取值、是否响应
   `closeWindow/tiktokusrinfo/goCustomerService`；`customID/nickname/avatar/tiktok_id`
   目前是空串占位，需要真实取值来源（可行方案：从主 WebView 已登录页面读昵称/头像/ID）；
2. **客服地址**：`goCustomerService()` 两端都还是空实现，需要真实链接；
3. **iOS 签名方式**（三选一，见 `docs/一比一复刻_准备清单.md` 第三节末条）：
   苹果开发者账号 / 第三方签名（需 UDID）/ 先只交付安卓。

### 9.4 已定论（无需再议）

- **域名归属**：`a2.dsfer168.cc` / `a3.dsfer168.cc` 均为自有域名，地址不需要更换；
- **资金模块**（充值 / 提现 / 合伙）：**预留**，当前不实现，后期按需完善；
  实现时仍受第八节约束（资金盘特征风险，须评估后再动）。
