# AgentPing 开发流程

新功能按下面顺序走。**某一步失败或用户不满意，退回上一步改，不要跳步往前冲。**

仓库没有 `gradlew`；Gradle 8.9 装在 instabox 盒子里（见 [`INSTABOX.md`](INSTABOX.md)）。签名在仓库根 `agentping-release.keystore` + `keystore.properties`（均不入库）；盒子上的 `assembleRelease` 和已装包同一把钥匙，才能覆盖安装。

**单测和打包只在 instabox 的盒子里跑，不在本机跑 gradle。** 本机只用来改代码、装 APK。


## 1. 讨论设计

先把问题和方案说清楚，再动代码。

- 协议 / 架构：对照 [`DESIGN.md`](DESIGN.md)。
- 设计定了再改 [`DESIGN.md`](DESIGN.md)（实现细节、协议坑、产品决策）。不要只改代码不留记录。

这一步没达成一致，不要改 App，更不要打包。


## 2. 修改代码

把定稿方案落到 Kotlin / Compose。

测试或行为不对：停在这一层修，不要带着红测试去打包。单测也只在盒子上跑（第 3 步），本机不跑 gradle。

## 3. 单测 + 打 release 包

单测和打包都在 instabox 的盒子上执行，一条命令同步代码、跑单测、打包并拉回 APK。本机不跑 gradle，也不需要装 Android SDK：

```bash
tools/instabox-build.sh
```

- 真机验证用 **signed release**（R8 minify + shrink），debug 包过不了 ProGuard / 覆盖安装。`versionCode` 和 `versionName` 仍按用户的 `0.0.x` 约定，未正式发版不要改。
- 只允许用现有的盒子 `ib`。**不要自行 `instabox create` 新机器**：新增 / 重建机器必须先得到用户明确同意。
- 产物：`app/build/outputs/apk/release/app-release.apk`（脚本自动拉回本地；和已装包同一把钥匙，可覆盖安装）。
- 细节（盒子规格、上传代码 / 下载 APK、手动命令、重建盒子）见 [`INSTABOX.md`](INSTABOX.md)。编译完不用就 `instabox suspend ib`。

**单测或打包失败（脚本非 0 退出）就停下来询问用户**：把失败的任务名和报错原文贴给用户，等用户决定下一步。不要改到本机再跑一遍、不要换机器 / 重建盒子重试、不要跳过失败去拿旧 APK。

## 4. 用户真机测试

把 APK 交给用户在已装 AgentPing 的手机上覆盖安装。Agent 不要假设本机有 `adb`。

请用户确认：新功能、回归（时间线、通知、重连、会话 Gap）。有问题记下复现，**退回对应步骤**（观感 → 第 2 步；逻辑 / 崩溃 → 第 2 步；装不上 → 第 3 步），再重新打包，不要直接推。

## 5. 推送代码

真机通过后再提交、推远程。

- 不要提交：`keystore.properties`、`*.keystore`、本地 APK。
- 提交信息写清做了什么，不要空的 `update`。
- **任何情况下不要自行打 tag 或者修改版本号** 只有用户明确说「打 tag」时才打。日常真机包用第 4 步的本地 APK。中间验证包若用户要求走 CI，才用临时 `v0.0.x-verifyN` tag（见 DESIGN.md §8）。

推送被拒或 CI 挂了：按报错回到第 3 / 4 步，不要强推。
