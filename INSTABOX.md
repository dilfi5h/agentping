# INSTABOX

agentping 的单测和打包都跑在 instabox 的 Linux 盒子上，本地只负责改代码、装 APK。
盒子里有完整的 gradle / Android SDK / 签名，本机不需要装 Android SDK 也能出 release 包。

**单测和打包只在盒子上跑，本机不跑 gradle。** 不要为了「先本地验一下」在本机执行 `testDebugUnitTest` / `assembleRelease`。

**只用现有的 `ib`，不要自行开新机器。** Agent 不许自己 `instabox create` / `instabox fork`：
新增机器（含换名重建）必须先向用户说明原因并拿到明确同意。free plan 机器数有上限，
多出来的机器会占配额、容易和别的项目混在一起。

**盒子上的单测或打包失败就停下来问用户。** `tools/instabox-build.sh` 非 0 退出时，把失败的任务名和报错原文
贴给用户、等用户决定；不要改到本机重跑、不要换机器或重建盒子重试、不要拿上一次的旧 APK 顶替。

## 盒子

| 项 | 值 |
| --- | --- |
| 盒子 | `ib`（instabox free plan，4 vCPU / 8 GB，Debian 13；通用盒子，agentping 的编译机） |
| 仓库 | `/data/workspace/agentping`（`/data` 是持久盘，HOME 是 `/data/home`） |
| 用户 | `user`，免密 sudo（老模板的用户名是 `user`，不是本机用户名） |
| Gradle | 8.9，装在 `/opt/gradle-8.9`，软链到 `/usr/local/bin/gradle` |
| Android SDK | `/data/android-sdk`：`platforms;android-35`、`build-tools;35.0.0`、`build-tools;34.0.0`（AGP 8.7.3 默认要 34）、`platform-tools` |
| JDK | OpenJDK 21（当前 `21.0.12.1`；换机器时缺就 `sudo apt-get install -y openjdk-21-jdk-headless`。Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 都支持） |
| Gradle init | `~/.gradle/init.d/prefer-official-repos.gradle`：盒子上移除 aliyun 镜像，只用官方 `google()` / `mavenCentral()` |
| 签名 | `agentping-release.keystore` + `keystore.properties` 已放在仓库根，被 `.gitignore` 忽略，不走同步 |
| `local.properties` | 内容 `sdk.dir=/data/android-sdk`，同样被忽略，不走同步 |

盒子闲置 30 分钟自动挂起，`instabox exec` / `instabox ssh` 会自动唤醒。
单次运行上限看 `instabox get ib` 的 `maxRunMinutes`（当前是 **120 分钟**），到点强制挂起并冷却 10 分钟（`cooldownUntil`）。编译完不用就 `instabox suspend ib`，挂起的时间不计入运行时长。

## 日常：一条命令

```sh
tools/instabox-build.sh
```

它做四件事：

1. `git bundle` 把当前分支同步过去，并在盒子上 `git reset --hard`，所以本地删除 / 改名的文件在盒子上也会消失（第一次跑时盒子上没有仓库，会自动 `git init`）；
2. `instabox push --all` 推未提交改动（含未跟踪但不被 ignore 的文件）；
3. 盒子上跑 `gradle :app:testDebugUnitTest assembleRelease`；
4. 把 `app/build/outputs/apk/release/app-release.apk` 拉回来覆盖本地同名文件。

额外 gradle 参数直接跟在后面，例如 `tools/instabox-build.sh --offline`。
盒子名可以用环境变量覆盖：`INSTABOX_BOX=other-box tools/instabox-build.sh`。

单测失败时脚本以非 0 退出，不会拉 APK。

耗时参考：空缓存首次（下依赖）约 2–7 分钟，看网络；有缓存后无改动（`--offline`）约 30 秒。

## 上传代码

日常不用手动上传，`tools/instabox-build.sh` 已经做了同步。要单独把「当前工作区」推上去（不构建）：

```sh
# 已提交的历史 + 删除/改名（git bundle 走一遍，保证盒子上和本地分支一致）
BRANCH=$(git rev-parse --abbrev-ref HEAD)
git bundle create /tmp/agentping-sync.bundle "refs/heads/$BRANCH"
instabox cp /tmp/agentping-sync.bundle ib:/tmp/agentping-sync.bundle
instabox ssh ib -- "cd /data/workspace/agentping && { [ -d .git ] || git init -q; } && git fetch -q -f /tmp/agentping-sync.bundle 'refs/heads/$BRANCH:refs/instabox/sync' && git reset -q --hard refs/instabox/sync"

# 未提交改动（含未跟踪但不被 ignore 的文件）；--all 不能省，否则未跟踪文件不上去
instabox push ib . /data/workspace --all
```

`instabox push` 从 git 仓库推送目录时只发 tracked 文件，`--all` 再加未跟踪但不被 ignore 的文件；
`.git`、`.gradle`、`build/`、`local.properties`、`*.keystore` 一律不上传（签名和 `local.properties` 只在搭建盒子时拷过一次）。

## 下载 APK

```sh
instabox cp ib:/data/workspace/agentping/app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/app-release.apk

# 单测报告这类目录
instabox pull ib /data/workspace/agentping/app/build/reports/tests/testDebugUnitTest /tmp/agentping-test-report
```

`instabox pull <box> <远端路径> [本地路径]` 会把文件（任意大小）或目录（压缩后 ≤ 32 MiB）拉下来；
单个 APK 用 `cp` 更直接，目录报告一般用 `pull`。

APK 是用仓库同一把钥匙签名的 release 包，可以直接覆盖手机上已装的 AgentPing。

## 手动操作

```sh
instabox ssh ib                                                          # 进盒子（exit 退出）
instabox exec ib --cwd /data/workspace/agentping -- 'git status --porcelain'   # 看盒子上的状态
instabox ssh ib -- 'cd /data/workspace/agentping && ANDROID_HOME=/data/android-sdk gradle :app:testDebugUnitTest'
instabox get ib                                                          # 状态、运行时长、冷却
instabox suspend ib                                                      # 手动挂起（不用删盒子）
```

- `instabox exec` 最多 600 秒，长任务用 `instabox ssh`（无时间限制），或用 `instabox exec --background ib -- 'cmd'` 加 `instabox jobs logs ib <job> -f`。
- 看盒子上有什么进程：`instabox ps ib`。
- 盒子里的 git 没有远端凭据，只能编译，`git push` / `git pull` 在盒子上会失败；需要的话在盒子里 `gh auth login`。

## 注意

- **盒子是编译机，不要在盒子上改代码。** 脚本每次 `git reset --hard` + 推送，盒子上的改动会被覆盖。
- 签名文件和 `local.properties` 被 gitignore，同步不会碰它们，只在搭建盒子时拷过一次。
- 代码以本地为唯一真相，盒子掉了不影响仓库。
- 本地无改动但盒子行为诡异时，先 `instabox exec ib --cwd /data/workspace/agentping -- 'git status --porcelain'` 对比；盒子上的残留未跟踪文件不会被自动清理（`git reset --hard` 只管已跟踪文件），需要时手动 `git clean -nd` 看一眼再清。
- 磁盘：`df -h / /data` 看用量。`/data` 放代码和 gradle 缓存，满了用 `instabox resize ib --disk N`（只能变大，需要停机）。
- `/data` 根目录属于 root，普通用户只能写 `/data/workspace`、`/data/home`；装 SDK 时先 `sudo mkdir -p /data/android-sdk` 再 `sudo chown -R "$(id -un)": ...`。
- 依赖走 `settings.gradle.kts` 里的 aliyun 镜像，但盒子上（海外网络）aliyun 经常 502/404，所以在盒子 HOME 放了
  `~/.gradle/init.d/prefer-official-repos.gradle`，构建时把 aliyun 源移除、只剩官方 `google()` / `mavenCentral()`。
  仓库里的 `settings.gradle.kts` 不用改，本机仍走 aliyun。删掉这个 init 脚本就会退回 aliyun 顺序。
- 首次构建要下全部依赖（约 2 分钟）；之后加 `--offline` 更快，但不改依赖时才能用。

## 重建盒子

盒子被删或换新机器时的步骤。**这一步会新建机器，必须先得到用户明确同意**，不要为了「跑通」自行重建。
名字必须换或先 `rm`：

```sh
instabox create --name ib --meta repo=agentping
instabox ssh ib -- 'sudo apt-get update -qq && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk-headless'
instabox ssh ib -- 'curl -fsSL -o /tmp/g.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip && sudo unzip -q -o /tmp/g.zip -d /opt && sudo ln -sf /opt/gradle-8.9/bin/gradle /usr/local/bin/gradle'
instabox ssh ib -- 'sudo mkdir -p /data/android-sdk && sudo chown -R "$(id -un)": /data/android-sdk && mkdir -p /data/android-sdk/cmdline-tools && curl -fsSL -o /tmp/c.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && unzip -q -o /tmp/c.zip -d /tmp/ct && mv /tmp/ct/cmdline-tools /data/android-sdk/cmdline-tools/latest && yes | /data/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/data/android-sdk --licenses >/dev/null && /data/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/data/android-sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0" "build-tools;34.0.0"'
instabox ssh ib -- 'mkdir -p ~/.gradle/init.d && cat > ~/.gradle/init.d/prefer-official-repos.gradle' <<'GRADLE'
// instabox 上 aliyun 镜像经常 502/404（海外网络），移除 aliyun，只用官方 google()/mavenCentral()
def dropAliyun = { repos ->
    repos.removeAll { repo -> repo.hasProperty('url') && repo.url.toString().contains('maven.aliyun.com') }
}
beforeSettings { settings ->
    dropAliyun(settings.pluginManagement.repositories)
    dropAliyun(settings.dependencyResolutionManagement.repositories)
}
GRADLE
instabox ssh ib -- 'mkdir -p /data/workspace/agentping'
instabox cp agentping-release.keystore ib:/data/workspace/agentping/agentping-release.keystore
instabox cp keystore.properties ib:/data/workspace/agentping/keystore.properties
instabox ssh ib -- 'printf "sdk.dir=/data/android-sdk\n" > /data/workspace/agentping/local.properties'
tools/instabox-build.sh
```

（先建目录再拷签名，因为 `git reset --hard` 不会删掉被 ignore 的签名文件；最后一条脚本顺带把仓库传上去并出签名包。）
