#!/usr/bin/env bash
# Run once INSIDE Ubuntu: sudo bash install-vmware-share.sh
# Based on https://knowledge.broadcom.com/external/article/316336
set -euo pipefail

mount_dir=/mnt/hgfs
unit_name=mnt-hgfs.mount
unit_file=/etc/systemd/system/$unit_name
module_file=/etc/modules-load.d/vmware-hgfs.conf
owner_uid=${SUDO_UID:-$(id -u)}
owner_gid=${SUDO_GID:-$(id -g)}

if [[ ! $owner_uid =~ ^[0-9]+$ || ! $owner_gid =~ ^[0-9]+$ ]]; then
    echo '无法确定挂载目录的用户 UID/GID。请使用 sudo bash 运行。' >&2
    exit 1
fi

print_unit() {
    cat <<UNIT
[Unit]
Description=VMware shared folders at /mnt/hgfs
DefaultDependencies=no
ConditionVirtualization=vmware
Wants=open-vm-tools.service
After=open-vm-tools.service sys-fs-fuse-connections.mount
Before=umount.target
Conflicts=umount.target

[Mount]
What=vmhgfs-fuse
Where=/mnt/hgfs
Type=fuse
Options=default_permissions,allow_other,uid=$owner_uid,gid=$owner_gid,umask=022
TimeoutSec=15

[Install]
WantedBy=multi-user.target
UNIT
}

case "${1:-}" in
    --print-unit) print_unit; exit 0 ;;
    --help|-h)
        echo '在 Ubuntu 内安装：sudo bash install-vmware-share.sh'
        echo '只查看生成的配置：bash install-vmware-share.sh --print-unit'
        exit 0 ;;
    '') ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
esac

if [[ $(uname -s) != Linux || ! -d /run/systemd/system ]]; then
    echo '请在 Ubuntu 虚拟机终端中运行此脚本，不是在 Windows 中运行。' >&2
    exit 1
fi
if (( EUID != 0 )); then
    echo "请执行：sudo bash \"$0\"" >&2
    exit 1
fi
if [[ $(systemd-detect-virt --vm || true) != vmware ]]; then
    echo '未检测到 VMware 虚拟机，未修改系统配置。' >&2
    exit 1
fi

# Avoid competing with an existing persistent mount configured by the user.
if awk '$1 !~ /^#/ && $2 == "/mnt/hgfs" { found=1 } END { exit !found }' /etc/fstab; then
    echo '/etc/fstab 已有 /mnt/hgfs 挂载配置。请先注释该行，再运行本脚本，以免重复挂载。' >&2
    exit 1
fi
if mountpoint -q "$mount_dir"; then
    current_mount=$(findmnt -rn -M "$mount_dir" -o FSTYPE,SOURCE)
    if [[ $current_mount != *vmhgfs* && $current_mount != *'.host:/'* ]]; then
        echo "$mount_dir 已由其他文件系统使用，未修改配置：$current_mount" >&2
        exit 1
    fi
fi

if ! command -v vmhgfs-fuse >/dev/null 2>&1; then
    apt-get update
    apt-get install -y open-vm-tools
fi
modprobe fuse
mkdir -p "$mount_dir" /etc/modules-load.d

temporary_unit=$(mktemp /tmp/vmware-hgfs-unit.XXXXXX)
trap 'rm -f -- "$temporary_unit"' EXIT
print_unit > "$temporary_unit"

# Keep a backup if an existing local configuration needs to be changed.
for config_file in "$unit_file" "$module_file"; do
    if [[ -L $config_file ]]; then
        echo "配置文件是符号链接，未覆盖：$config_file" >&2
        exit 1
    fi
done
if [[ -f $unit_file ]] && ! cmp -s "$temporary_unit" "$unit_file"; then
    cp -p -- "$unit_file" "$unit_file.bak.$(date +%Y%m%d-%H%M%S).$$"
fi
if [[ -f $module_file ]] && [[ $(cat "$module_file") != fuse ]]; then
    cp -p -- "$module_file" "$module_file.bak.$(date +%Y%m%d-%H%M%S).$$"
fi
install -m 0644 "$temporary_unit" "$unit_file"
printf 'fuse\n' > "$module_file"
chmod 0644 "$module_file"

systemctl daemon-reload
systemctl enable "$unit_name"
# Do not unmount a live share, especially when this script is stored on it.
if ! mountpoint -q "$mount_dir"; then
    if ! systemctl start "$unit_name"; then
        echo '自动挂载已启用，但本次挂载失败。请确认 VMware 中共享文件夹为“始终启用”。' >&2
        journalctl -u "$unit_name" -n 20 --no-pager >&2
        exit 1
    fi
fi

echo '已设置开机自动挂载。共享名为 share 时，位置是 /mnt/hgfs/share。'
echo '下次开机后可检查：ls /mnt/hgfs/share'
if command -v vmware-hgfsclient >/dev/null 2>&1; then
    shares=$(vmware-hgfsclient) || shares=''
    if [[ -z $shares ]]; then
        echo '当前未发现宿主机共享，请在 VMware 设置中启用共享文件夹。'
    fi
fi
