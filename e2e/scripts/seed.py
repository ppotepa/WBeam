#!/usr/bin/env python3
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

from vm import require_tool, run_cmd


PASSWORD_HASH = "$6$rounds=4096$wbeamE2E$gPvDZiTjYdK/FZl0RJ.kqJ1i7k15AJbBuwp4VewGfkqUZXeMQDWD4wh9YFbrHRWQZ20a2DA2pc0LuYRPrTR0V/"


def session_packages(distro_family: str, session: str) -> list[str]:
    if session == "headless":
        return []
    if distro_family == "fedora":
        base = ["@workstation-product-environment"]
        if session == "gnome-xorg":
            base.append("xorg-x11-server-Xorg")
        return base
    if distro_family == "ubuntu":
        base = ["gnome-session", "gdm3", "ubuntu-desktop-minimal"]
        if session == "gnome-xorg":
            base.extend(["xserver-xorg", "gnome-session-xsession"])
        return base
    if distro_family == "debian":
        base = [
            "gnome-core",
            "gdm3",
            "gnome-session",
            "dbus-x11",
            "pipewire",
            "wireplumber",
            "xdg-desktop-portal",
            "xdg-desktop-portal-gnome",
        ]
        if session == "gnome-xorg":
            base.append("xserver-xorg")
        return base
    return []


def session_name(session: str, distro_family: str | None = None) -> str:
    if session == "gnome-xorg":
        if distro_family == "fedora":
            return "gnome"
        return "gnome-xorg"
    return "gnome"


def desktop_shell_commands(session: str, ssh_user: str, distro_family: str | None = None) -> list[str]:
    if session == "headless":
        return []
    gdm_lines = ["[daemon]", "AutomaticLoginEnable=True", f"AutomaticLogin={ssh_user}"]
    if session == "gnome-xorg":
        gdm_lines.append("WaylandEnable=false")
    gdm_lines.extend(["[security]", "DisallowTCP=false"])
    gdm_printf = "printf '%s\\n' " + " ".join(f"'{line}'" for line in gdm_lines) + " > /etc/gdm/custom.conf"
    account_session = session_name(session, distro_family)
    account_lines = ["[User]", f"Session={account_session}", f"XSession={account_session}", "SystemAccount=false"]
    account_printf = (
        "printf '%s\\n' " + " ".join(f"'{line}'" for line in account_lines) + f" > /var/lib/AccountsService/users/{ssh_user}"
    )
    return [
        "mkdir -p /etc/gdm /var/lib/AccountsService/users",
        gdm_printf,
        account_printf,
        "systemctl set-default graphical.target || true",
        "systemctl enable gdm || systemctl enable gdm3 || true",
    ]


def make_iso(source_dir: Path, output: Path, label: str) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    if shutil.which("xorriso"):
        run_cmd(
            [
                require_tool("xorriso"),
                "-as",
                "mkisofs",
                "-output",
                str(output),
                "-volid",
                label,
                "-joliet",
                "-rock",
                str(source_dir),
            ]
        )
        return
    if shutil.which("genisoimage"):
        run_cmd(
            [
                require_tool("genisoimage"),
                "-output",
                str(output),
                "-volid",
                label,
                "-joliet",
                "-rock",
                str(source_dir),
            ]
        )
        return
    raise RuntimeError("missing seed ISO helper: install xorriso or genisoimage")


def xorriso_extract(iso: Path, source: str, target: Path) -> bool:
    target.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(
        [
            require_tool("xorriso"),
            "-osirrox",
            "on",
            "-indev",
            str(iso),
            "-extract",
            source,
            str(target),
        ],
        text=True,
        capture_output=True,
    )
    return proc.returncode == 0 and target.exists()


def append_file_to_gzip_initrd(*, initrd: Path, source: Path, target_name: str, work_dir: Path) -> None:
    work_dir.mkdir(parents=True, exist_ok=True)
    raw_initrd = work_dir / "initrd.cpio"
    append_dir = work_dir / "append"
    if append_dir.exists():
        shutil.rmtree(append_dir)
    append_dir.mkdir()
    shutil.copy2(source, append_dir / target_name)

    with raw_initrd.open("wb") as fh:
        subprocess.run([require_tool("gzip"), "-dc", str(initrd)], stdout=fh, check=True)
    with raw_initrd.open("ab") as fh:
        find = subprocess.Popen(
            [require_tool("find"), ".", "-print"],
            cwd=append_dir,
            stdout=subprocess.PIPE,
            text=True,
        )
        subprocess.run(
            [require_tool("cpio"), "-H", "newc", "-o"],
            cwd=append_dir,
            stdin=find.stdout,
            stdout=fh,
            stderr=subprocess.PIPE,
            check=True,
            text=True,
        )
        if find.stdout:
            find.stdout.close()
        rc = find.wait()
        if rc != 0:
            raise subprocess.CalledProcessError(rc, find.args)
    with initrd.open("wb") as fh:
        subprocess.run([require_tool("gzip"), "-9", "-c", str(raw_initrd)], stdout=fh, check=True)


def extract_boot_assets(*, iso: Path, distro: dict, output_dir: Path, seed_dir: Path | None = None) -> dict[str, str]:
    family = distro["family"]
    candidates = {
        "fedora": [
            ("/boot/x86_64/loader/linux", "/boot/x86_64/loader/initrd"),
            ("/images/pxeboot/vmlinuz", "/images/pxeboot/initrd.img"),
            ("/isolinux/vmlinuz", "/isolinux/initrd.img"),
        ],
        "ubuntu": [
            ("/casper/vmlinuz", "/casper/initrd"),
            ("/casper/vmlinuz", "/casper/initrd.gz"),
        ],
        "debian": [
            ("/install.amd/vmlinuz", "/install.amd/initrd.gz"),
            ("/install/vmlinuz", "/install/initrd.gz"),
        ],
    }.get(family, [])
    if not candidates:
        raise RuntimeError(f"unsupported boot asset extraction for distro family: {family}")
    if not shutil.which("xorriso"):
        raise RuntimeError("xorriso is required to extract kernel/initrd from installer ISO")

    output_dir.mkdir(parents=True, exist_ok=True)
    for kernel_src, initrd_src in candidates:
        kernel = output_dir / "vmlinuz"
        initrd = output_dir / "initrd"
        if kernel.exists():
            kernel.unlink()
        if initrd.exists():
            initrd.unlink()
        if xorriso_extract(iso, kernel_src, kernel) and xorriso_extract(iso, initrd_src, initrd):
            result = {
                "kernel": str(kernel),
                "initrd": str(initrd),
                "kernel_source": kernel_src,
                "initrd_source": initrd_src,
            }
            if family == "debian" and seed_dir:
                preseed = seed_dir / "preseed.cfg"
                if not preseed.exists():
                    raise RuntimeError(f"missing Debian preseed file: {preseed}")
                append_file_to_gzip_initrd(
                    initrd=initrd,
                    source=preseed,
                    target_name="preseed.cfg",
                    work_dir=output_dir / "initrd-preseed-work",
                )
                result["preseed_source"] = str(preseed)
                result["preseed_target"] = "/preseed.cfg"
            return result
    raise RuntimeError(f"could not extract kernel/initrd from ISO: {iso}")


def boot_append_args(*, distro: dict, session: str) -> str:
    family = distro["family"]
    common = "console=ttyS0,115200n8"
    if family == "fedora":
        stage2 = "inst.stage2=hd:LABEL=Fedora-E-dvd-x86_64-43"
        return f"{stage2} inst.text inst.sshd inst.ks=hd:LABEL=WBEAM-SEED:/ks.cfg {common}"
    if family == "ubuntu":
        return f"boot=casper autoinstall ds=nocloud {common} ---"
    if family == "debian":
        return f"auto=true priority=critical debian-installer=en_US.UTF-8 locale=en_US.UTF-8 keymap=us {common}"
    raise RuntimeError(f"unsupported boot append args for distro family: {family}")


def marker_json(distro_id: str, session: str, ssh_user: str) -> str:
    return json.dumps(
        {
            "distro": distro_id,
            "session": session,
            "ssh_user": ssh_user,
            "created_by": "wbeam-e2e",
        },
        sort_keys=True,
    )


def double_quoted_shell_text(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("`", "\\`")


def single_quoted_shell_text(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


def write_fedora_seed(
    seed_dir: Path,
    *,
    distro_id: str,
    session: str,
    ssh_user: str,
    public_key: str,
) -> None:
    packages = "\n".join(session_packages("fedora", session))
    environment = "@core"
    if session != "headless":
        environment += "\n@workstation-product-environment"
    desktop_setup = desktop_shell_commands(session, ssh_user, "fedora")
    desktop_block = "\n".join(desktop_setup)
    if desktop_block:
        desktop_block += "\n"
    seed_dir.mkdir(parents=True, exist_ok=True)
    (seed_dir / "ks.cfg").write_text(
        f"""#version=DEVEL
text
lang en_US.UTF-8
keyboard us
timezone UTC --utc
rootpw --lock
user --name={ssh_user} --groups=wheel --password={PASSWORD_HASH} --iscrypted
sshkey --username={ssh_user} "{public_key}"
zerombr
clearpart --all --initlabel
autopart --type=lvm
poweroff

%packages
{environment}
openssh-server
sudo
python3
rsync
curl
jq
{packages}
%end

%post --erroronfail
set -e
echo '{ssh_user} ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/{ssh_user}
chmod 0440 /etc/sudoers.d/{ssh_user}
systemctl enable sshd
{desktop_block}mkdir -p /var/lib/wbeam-e2e
cat > /var/lib/wbeam-e2e/base-ready.json <<'EOF'
{marker_json(distro_id, session, ssh_user)}
EOF
%end
""",
        encoding="utf-8",
    )


def write_ubuntu_seed(
    seed_dir: Path,
    *,
    distro_id: str,
    session: str,
    ssh_user: str,
    public_key: str,
) -> None:
    packages = ["openssh-server", "sudo", "python3", "rsync", "curl", "jq"]
    packages.extend(session_packages("ubuntu", session))
    package_yaml = "\n".join(f"    - {package}" for package in packages)
    marker = double_quoted_shell_text(marker_json(distro_id, session, ssh_user))
    desktop_setup = desktop_shell_commands(session, ssh_user, "ubuntu")
    late_commands = [
        f"curtin in-target --target=/target -- sh -c \"echo '{ssh_user} ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/{ssh_user}\"",
        f"curtin in-target --target=/target -- chmod 0440 /etc/sudoers.d/{ssh_user}",
    ]
    if desktop_setup:
        for line in desktop_setup:
            escaped = line.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
            late_commands.append(f"curtin in-target --target=/target -- sh -c \"{escaped}\"")
    late_commands.extend(
        [
            "curtin in-target --target=/target -- mkdir -p /var/lib/wbeam-e2e",
            f"curtin in-target --target=/target -- sh -c \"printf '%s\\\\n' '{marker}' > /var/lib/wbeam-e2e/base-ready.json\"",
        ]
    )
    late_commands_yaml = "\n".join(f"    - {command}" for command in late_commands)
    seed_dir.mkdir(parents=True, exist_ok=True)
    (seed_dir / "meta-data").write_text(
        "instance-id: wbeam-e2e\nlocal-hostname: wbeam-e2e\n",
        encoding="utf-8",
    )
    (seed_dir / "user-data").write_text(
        f"""#cloud-config
autoinstall:
  version: 1
  locale: en_US.UTF-8
  keyboard:
    layout: us
  identity:
    hostname: wbeam-e2e
    username: {ssh_user}
    password: "{PASSWORD_HASH}"
  ssh:
    install-server: true
    allow-pw: false
    authorized-keys:
      - {public_key}
  packages:
{package_yaml}
  storage:
    layout:
      name: direct
  shutdown: poweroff
  late-commands:
{late_commands_yaml}
""",
        encoding="utf-8",
    )


def write_debian_seed(
    seed_dir: Path,
    *,
    distro_id: str,
    session: str,
    ssh_user: str,
    public_key: str,
) -> None:
    packages = ["openssh-server", "sudo", "python3", "rsync", "curl", "jq"]
    packages.extend(session_packages("debian", session))
    pkgsel_include = " ".join(packages)
    tasksel_tasks = "standard, ssh-server"
    seed_dir.mkdir(parents=True, exist_ok=True)
    (seed_dir / "preseed.cfg").write_text(
f"""d-i debian-installer/locale string en_US.UTF-8
d-i keyboard-configuration/xkb-keymap select us
d-i keyboard-configuration/model select Generic 105-key PC
d-i keyboard-configuration/modelcode string pc105
d-i keyboard-configuration/layout select English (US)
d-i keyboard-configuration/layoutcode string us
d-i keyboard-configuration/variant select English (US)
d-i keyboard-configuration/variantcode string
d-i keyboard-configuration/optionscode string
d-i console-setup/ask_detect boolean false
d-i console-setup/layoutcode string us
d-i netcfg/choose_interface select auto
d-i netcfg/get_hostname string wbeam-e2e
d-i passwd/root-login boolean false
d-i passwd/user-fullname string WBeam E2E
d-i passwd/username string {ssh_user}
d-i passwd/user-password-crypted password {PASSWORD_HASH}
d-i clock-setup/utc boolean true
d-i time/zone string UTC
d-i partman-auto/method string regular
d-i partman-auto/choose_recipe select atomic
d-i partman-partitioning/confirm_write_new_label boolean true
d-i partman/choose_partition select finish
d-i partman/confirm boolean true
d-i partman/confirm_nooverwrite boolean true
d-i apt-setup/use_mirror boolean true
d-i mirror/country string manual
d-i mirror/http/hostname string deb.debian.org
d-i mirror/http/directory string /debian
d-i mirror/http/proxy string
d-i apt-setup/cdrom/set-first boolean false
d-i apt-cdrom-setup/ask_scan_another boolean false
d-i apt-cdrom-setup/disable-cdrom-entries boolean true
d-i apt-setup/non-free-firmware boolean true
d-i pkgsel/include string {pkgsel_include}
tasksel tasksel/first multiselect {tasksel_tasks}
popularity-contest popularity-contest/participate boolean false
d-i grub-installer/only_debian boolean true
d-i grub-installer/with_other_os boolean true
d-i grub-installer/bootdev string /dev/vda
d-i cdrom-detect/eject boolean false
d-i debian-installer/exit/poweroff boolean true
d-i finish-install/reboot_in_progress note
d-i finish-install/poweroff_in_progress note
""",
        encoding="utf-8",
    )


def create_seed_iso(
    *,
    distro: dict,
    session: str,
    ssh_user: str,
    public_key: str,
    output: Path,
) -> Path:
    seed_dir = output.parent / "seed"
    if seed_dir.exists():
        shutil.rmtree(seed_dir)
    seed_dir.mkdir(parents=True)
    family = distro["family"]
    if family == "fedora":
        write_fedora_seed(seed_dir, distro_id=distro["id"], session=session, ssh_user=ssh_user, public_key=public_key)
        label = "WBEAM-SEED"
    elif family == "ubuntu":
        write_ubuntu_seed(seed_dir, distro_id=distro["id"], session=session, ssh_user=ssh_user, public_key=public_key)
        label = "cidata"
    elif family == "debian":
        write_debian_seed(seed_dir, distro_id=distro["id"], session=session, ssh_user=ssh_user, public_key=public_key)
        label = "WBEAM-SEED"
    else:
        raise RuntimeError(f"unsupported distro family: {family}")
    make_iso(seed_dir, output, label)
    return output
