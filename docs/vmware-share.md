# VMware 共享文件夹开机自动挂载

脚本必须在 **Ubuntu 虚拟机终端** 中运行。它安装 systemd 挂载单元，之后无需每次手动运行 `vmhgfs-fuse`。

1. 在 VMware 的“虚拟机设置 → 选项 → 共享文件夹”中选择“始终启用”，确认共享名为 `share`。
2. 把 `install-vmware-share.sh` 复制到虚拟机，例如桌面，然后运行一次：

   ```bash
   sudo bash ~/Desktop/install-vmware-share.sh
   ```

脚本也放在 Windows 项目的 `tools/` 下。若共享对应此前的目录结构，本次先手动挂载，再从共享目录执行即可，无需另外复制：

```bash
sudo mkdir -p /mnt/hgfs
mountpoint -q /mnt/hgfs || sudo vmhgfs-fuse .host:/ /mnt/hgfs -o allow_other
sudo bash /mnt/hgfs/share/robocup_UESTC_log/tools/install-vmware-share.sh
```

注意 `allow_other` 使用下划线。安装后，无需保留安装脚本，挂载配置已写入 Ubuntu 系统。

之后每次启动虚拟机，可直接打开文件管理器，按 Ctrl+L 输入：

```text
/mnt/hgfs/share
```

`share` 是 VMware 里的共享名，不是固定名称；脚本挂载所有已启用的 VMware 共享目录。

脚本会在缺少挂载工具时安装 `open-vm-tools`，生成 `/etc/systemd/system/mnt-hgfs.mount` 和 `/etc/modules-load.d/vmware-hgfs.conf`，并启用挂载。普通用户权限取运行 `sudo` 前的 UID/GID；不会卸载当前正在使用的共享目录。已有系统配置在被更改前会备份。若 `/etc/fstab` 已经配置相同挂载点，脚本停止并提示处理，避免重复配置。

排查挂载失败：

```bash
systemctl status mnt-hgfs.mount --no-pager
journalctl -b -u mnt-hgfs.mount --no-pager
vmware-hgfsclient
```

取消开机自动挂载（本次已挂载的目录继续可用，下次启动不再由此单元挂载）：

```bash
sudo systemctl disable mnt-hgfs.mount
```

实现依据：[VMware 官方 HGFS systemd 配置说明](https://knowledge.broadcom.com/external/article/316336)。此脚本已做本地语法和配置输出检查，未在你的 Ubuntu 虚拟机中实际安装。
