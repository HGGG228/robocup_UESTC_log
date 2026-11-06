# 按地图保存智能体日志

在项目根目录运行（服务器也应选择相应地图）：

```bash
chmod +x gradlew launch.sh
bash launch.sh --map-name kobe1 -all
```

也可以保留原来的智能体数量、服务器地址等参数：

```bash
bash launch.sh --map-name kobe1 -t 10,0,10,0,10,0 -h localhost
```

`--map-name` 只是本地日志的地图标签，不会让服务器切换地图。名称支持英文字母、数字、点、下划线、连字符，以字母或数字开头，最长 80 个字符。

运行期间仍写入 `logs/`，正常结束或启动任务失败后会归档为：

```text
log-archive/
  kobe1/
    20261029-223000-123-<UUID>/
      AmbulanceTeam/
      FireBrigade/
      PoliceForce/
      run-info.json
  other-map/
    <另一轮时间编号>/
```

同一地图重复运行会创建新的时间编号目录，不覆盖之前的记录。`run-info.json` 保存地图标签、开始时间、归档时间和结束状态。请等终端出现 `Agent logs saved:` 再查看本轮归档。

- 首次启动时，已有的 `logs/` 会先保存到 `log-archive/legacy/<时间编号>/`。
- 未指定地图名时使用 `unknown-map`。
- 若强制退出导致归档未执行，下次启动前会先根据 `.pending-run.json` 将遗留日志归档到上次的地图目录。归档失败会停止启动并保留现场。
- 同一项目目录同时只允许一个通过 Gradle `launch` 启动的智能体进程，避免共享 `logs/`。若强制杀死 Gradle，请先确认其智能体 Java 子进程也已停止，再启动下一轮。
- 不要在运行中删除或修改 `log-archive/.pending-run.json`、`.run.lock`，也不要手动移动 `logs/`。

直接使用 Gradle 时也支持地图标签：

```bash
./gradlew launch -PmapName=kobe1 --args='adf.impl.DefaultLoader -all'
```

直接运行 Java 主类会绕过 Gradle 的归档逻辑。通过 `start.sh` 启动则默认归入 `unknown-map`；需要地图标签时使用上面的 `launch.sh` 命令。

`launch.sh` 和 `gradlew` 已统一为 Linux LF 换行，`.gitattributes` 也固定了脚本的换行格式，避免经 Windows Git 检出后再次出现 `/bin/sh^M`。

这里只保存此项目的智能体日志。服务器的回放文件 `rescue.log.xz` 由服务器脚本管理；若要保留每轮服务器日志，使用服务器的时间目录选项：

```bash
cd ~/Apollo/rcrs-server-master1/scripts
bash start.sh -m ../maps/kobe1/map -c ../maps/kobe1/config -s
```

它保存在服务器项目的 `logs/log/<时间>-kobe1/`，与智能体项目的 `log-archive/` 分开。
