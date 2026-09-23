# AgentPing 开发流程

新功能按下面顺序走。**某一步失败或用户不满意，退回上一步改，不要跳步往前冲。**

仓库没有 `gradlew`，用本机 Gradle 8.9：`gradle …`（本机路径 `/Users/dilfish/opt/gradle-8.9/bin/gradle`，若已在 PATH 则可直接 `gradle`）。签名在仓库根 `agentping-release.keystore` + `keystore.properties`（均不入库）；本地 `assembleRelease` 和已装包同一把钥匙，才能覆盖安装。


## 1. 讨论设计

先把问题和方案说清楚，再动代码。

- 协议 / 架构：对照 [`DESIGN.md`](DESIGN.md)。
- 设计定了再改 [`DESIGN.md`](DESIGN.md)（实现细节、协议坑、产品决策）。不要只改代码不留记录。

这一步没达成一致，不要改 App，更不要打包。


## 2. 修改代码

把定稿方案落到 Kotlin / Compose
测试或行为不对：停在这一层修，不要带着红测试去打包。

## 4. 本地打 release 包

真机验证用 **signed release**（R8 minify + shrink），debug 包过不了 ProGuard / 覆盖安装。`versionCode` 和 `versionName` 仍按用户的 `0.0.x` 约定，未正式发版不要改。

```bash
gradle :app:testDebugUnitTest --offline
gradle assembleRelease
```

产物：`app/build/outputs/apk/release/agentping-release.apk`。


打包失败（R8、资源、签名）回到第 3 步改代码或 ProGuard，不要让用户装坏包。

## 5. 用户真机测试

把 APK 交给用户在已装 AgentPing 的手机上覆盖安装。Agent 不要假设本机有 `adb`。

请用户确认：新功能、回归（时间线、通知、重连、会话 Gap）。有问题记下复现，**退回对应步骤**（观感 → 第 2 步；逻辑 / 崩溃 → 第 3 步；装不上 → 第 4 步），再重新打包，不要直接推。

## 6. 推送代码

真机通过后再提交、推远程。

- 不要提交：`keystore.properties`、`*.keystore`、本地 APK。
- 提交信息写清做了什么，不要空的 `update`。
- **任何情况下不要自行打 tag 或者修改版本号** 只有用户明确说「打 tag」时才打。日常真机包用第 4 步的本地 APK。中间验证包若用户要求走 CI，才用临时 `v0.0.x-verifyN` tag（见 DESIGN.md §8）。

推送被拒或 CI 挂了：按报错回到第 3 / 4 步，不要强推。
