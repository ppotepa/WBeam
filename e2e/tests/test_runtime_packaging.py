"""Exercise package staging and compatibility entrypoints without builds/install.

The real DEB/RPM scripts run against a copied source fixture. Compiler, ELF
inspection, adb and package-writer commands are stubs; all writes stay in the
temporary tree. These tests do not certify APK signatures or native binaries.
"""
import json
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VERSION = "0.1.3-rc.2"
APK_BYTES = b"fixture APK: staging test, not a signed application\n"

TOOL_STUB = r'''#!/usr/bin/env python3
import json
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

tool, args = Path(sys.argv[0]).name, sys.argv[1:]
capture = Path(os.environ["WBEAM_TEST_CAPTURE"])
with (capture / "calls.jsonl").open("a") as log:
    log.write(json.dumps([tool, args]) + "\n")
if tool in ("cargo", "npm"):
    sys.exit(0)
if tool == "dpkg":
    assert args == ["--print-architecture"], args
    print("amd64")
elif tool == "ldd":
    soname = os.environ["WBEAM_TEST_SONAME"]
    lib = Path(os.environ["WBEAM_EVDI_LIB_DIR"]) / soname
    if not lib.is_file():
        print("\t%s => not found" % soname)
    else:
        print("\t%s => %s (0x0123456789ab)" % (soname, lib))
elif tool == "readelf":
    soname = os.environ["WBEAM_TEST_SONAME"]
    print(" 0x0000000000000001 (NEEDED) Shared library: [%s]" % soname)
    if args[-1].endswith((".so.0", ".so.1")):
        actual = os.environ.get("WBEAM_TEST_ACTUAL_SONAME", soname)
        print(" 0x000000000000000e (SONAME) Library soname: [%s]" % actual)
elif tool == "dpkg-deb":
    assert args[0] == "--build", args
    shutil.copytree(args[1], capture / "deb-root", symlinks=True)
    Path(args[2]).write_text("fixture package marker\n")
elif tool == "rpmbuild":
    spec = Path(args[-1]).read_text()
    (capture / "wbeam.spec").write_text(spec)
    topdir = Path(args[args.index("--define") + 1].split(" ", 1)[1])
    source = next(topdir.glob("wbeam-*"))
    buildroot = capture / "rpm-root"
    install = spec.split("\n%install\n", 1)[1].split("\n%files\n", 1)[0]
    install = install.replace("%{buildroot}", shlex.quote(str(buildroot)))
    assert "%{" not in install, "Fixture needs support for new RPM macros"
    subprocess.run(["bash", "-euo", "pipefail", "-c", install], cwd=source, check=True)
    output = topdir / "RPMS/x86_64/wbeam-fixture.rpm"
    output.parent.mkdir(parents=True)
    output.write_text("fixture package marker\n")
elif tool == "adb":
    serial = "default"
    if args[:1] == ["-s"]:
        serial, args = args[1], args[2:]
    if args == ["devices"]:
        if os.environ.get("WBEAM_TEST_ADB_ENUMERATION_FAIL") == "1":
            print("fixture adb server error", file=sys.stderr)
            sys.exit(1)
        print("List of devices attached")
        for value in os.environ.get("WBEAM_TEST_DEVICES", "tablet-a,tablet-b").split(","):
            if value:
                print(value + "\tdevice")
    elif args[:1] == ["install"]:
        if serial == os.environ.get("WBEAM_TEST_FAIL_SERIAL"):
            print("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]")
            sys.exit(1)
        print("Success")
    elif args[:3] == ["shell", "am", "start"]:
        print("Starting fixture application")
    elif args[:1] == ["uninstall"] or args[:3] == ["shell", "pm", "uninstall"]:
        sys.exit("Unexpected destructive uninstall")
    elif args[:1] == ["reverse"] or args in (["start-server"], ["wait-for-device"]):
        pass
    else:
        sys.exit("Unexpected adb invocation: " + repr(args))
else:
    sys.exit("Unexpected fixture command: " + tool)
'''


class RuntimePackagingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="waybeam-packaging-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "source checkout"
        self.capture = self.base / "capture"
        self.capture.mkdir()
        self.bin = self.base / "tools"
        self.bin.mkdir()
        (self.base / "tmp").mkdir()
        for name in ("wbeam", "waybeam", "install-wbeam", "install-waybeam", "README.md"):
            self.copy(name)
        for directory in ("host/scripts", "host/rust/systemd", "host/rust/scripts", "scripts/tools"):
            shutil.copytree(ROOT / directory, self.root / directory)
        for directory in ("scripts", "scripts/ci"):
            for path in (ROOT / directory).glob("*.sh"):
                self.copy(str(path.relative_to(ROOT)))
        for name in ("wbeam.conf", "branding.json", "branding.sh", "waybeam.desktop"):
            self.copy("config/" + name)
        for relative in (
            "host/rust/target/release/wbeamd-server",
            "host/rust/target/release/wbeamd-streamer",
            "desktop/apps/desktop-tauri/src-tauri/target/release/wbeam-desktop-tauri",
        ):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("#!/bin/sh\nexit 99 # Must not execute fixture binaries.\n")
            path.chmod(0o755)
        self.copy("desktop/apps/desktop-tauri/src-tauri/icons/icon.png")
        self.dist = self.root / "dist"
        self.dist.mkdir()
        self.apk = self.dist / f"wbeam-android-{VERSION}.apk"
        self.apk.write_bytes(APK_BYTES)
        (self.dist / "VERSION.txt").write_text(f"WBEAM_VERSION={VERSION}\nWBEAM_VERSION_CODE=42\n")
        library = self.base / "libevdi"
        library.mkdir()
        (library / "libevdi.so").write_text("fixture linker library\n")
        for soname in ("libevdi.so.0", "libevdi.so.1"):
            (library / soname).write_text("fixture runtime " + soname + "\n")
        stub = self.bin / "fixture-tool"
        stub.write_text(TOOL_STUB)
        stub.chmod(0o755)
        for name in ("cargo", "npm", "dpkg", "ldd", "readelf", "dpkg-deb", "rpmbuild", "adb"):
            (self.bin / name).symlink_to(stub.name)
        platform = self.base / "platform.sh"
        platform.write_text('''source() {
  if [[ "$1" == /etc/os-release ]]; then
    ID="$WBEAM_TEST_OS_ID"
    VERSION_ID="$WBEAM_TEST_OS_VERSION"
  else
    builtin source "$@"
  fi
}
''')
        # Ignore caller-specific configuration and CI identity, while retaining
        # the real shell/Python/system tools needed to copy local fixture files.
        self.env = {
            key: value for key, value in os.environ.items()
            if not key.startswith(("WBEAM_", "CI_", "XDG_")) and key != "BASH_ENV"
        }
        self.env.update({
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "BASH_ENV": str(platform),
            "TMPDIR": str(self.base / "tmp"),
            "ROOT_DIR": str(self.root),
            "DIST_DIR": str(self.dist),
            "WBEAM_VERSION": VERSION,
            "WBEAM_EVDI_LIB_DIR": str(library),
            "WBEAM_TEST_CAPTURE": str(self.capture),
            "WBEAM_TEST_OS_ID": "debian",
            "WBEAM_TEST_OS_VERSION": "12",
            "WBEAM_TEST_SONAME": "libevdi.so.0",
            "XDG_CONFIG_HOME": str(self.base / "user-config"),
            "XDG_STATE_HOME": str(self.base / "user-state"),
            "WBEAM_ANDROID_FORCE_INSTALL": "1",
        })
        self.staged = self.base / "runtime"

    def copy(self, relative):
        destination = self.root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, destination)

    def run_command(self, args, **overrides):
        return subprocess.run(
            [str(arg) for arg in args], cwd=self.root,
            env=dict(self.env, **overrides), capture_output=True, text=True, timeout=30,
        )

    def assert_success(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def stage(self):
        result = self.run_command([
            "bash", "-c",
            'source "$ROOT_DIR/scripts/ci/runtime-assets.sh"; '
            'stage_wbeam_runtime_assets "$1"; assert_wbeam_runtime_assets "$1"',
            "fixture", self.staged,
        ])
        self.assert_success(result)
        return self.staged

    def calls(self, tool):
        log = self.capture / "calls.jsonl"
        if not log.exists():
            return []
        return [args for name, args in map(json.loads, log.read_text().splitlines()) if name == tool]

    def assert_runtime(self, runtime):
        self.assertEqual((runtime / "android/wbeam-android.apk").read_bytes(), APK_BYTES)
        self.assertIn("WBEAM_VERSION_CODE=42", (runtime / "VERSION.txt").read_text())
        self.assertEqual(json.loads((runtime / "config/branding.json").read_text())["displayName"], "WayBeam")
        for relative in (
            "wbeam", "waybeam", "host/scripts/run_wbeamd.sh", "host/scripts/usb_reverse.sh",
            "scripts/evdi-compat.sh",
            "scripts/virtual-deps-check.sh", "scripts/virtual-deps-install.sh",
        ):
            path = runtime / relative
            self.assertTrue(path.is_file(), relative)
            self.assertTrue(path.stat().st_mode & stat.S_IXUSR, relative)
        self.assertTrue((runtime / "host/scripts/wbeam_config.sh").is_file())

    def assert_package(self, package):
        runtime = package / "usr/share/wbeam"
        self.assert_runtime(runtime)
        bindir = package / "usr/local/bin"
        for alias, original in (("waybeam", "wbeam"), ("waybeam-desktop", "wbeam-desktop")):
            self.assertEqual((bindir / alias).readlink(), Path(original))
            self.assertTrue((bindir / alias).is_file())
        for executable in ("wbeamd-server", "wbeamd-streamer", "wbeam-desktop-tauri", "wbeam-desktop"):
            self.assertTrue(os.access(bindir / executable, os.X_OK), executable)
        entry = (package / "usr/share/applications/wbeam.desktop").read_text()
        self.assertIn("Name=WayBeam Desktop", entry)
        self.assertIn("Exec=/usr/local/bin/waybeam-desktop", entry)
        self.assertTrue((package / "usr/share/icons/hicolor/256x256/apps/wbeam.png").is_file())
        for command in ("wbeam", "waybeam"):
            result = self.run_command([bindir / command, "version", "current"], WBEAM_ROOT=str(runtime))
            self.assert_success(result)
            self.assertEqual(result.stdout.strip(), VERSION)

    def test_shared_runtime_assets_and_installed_version(self):
        self.assert_runtime(self.stage())
        result = self.run_command([self.staged / "waybeam", "version", "current"])
        self.assert_success(result)
        self.assertEqual(result.stdout.strip(), VERSION)
        self.assertFalse((self.staged / ".wbeam_build_version").exists())

    def test_staging_requires_apk_and_version_manifest(self):
        for missing in (self.apk, self.dist / "VERSION.txt"):
            with self.subTest(missing=missing.name):
                content = missing.read_bytes()
                missing.unlink()
                result = self.run_command([
                    "bash", "-c", 'source "$ROOT_DIR/scripts/ci/runtime-assets.sh"; '
                    'stage_wbeam_runtime_assets "$1"', "fixture", self.staged,
                ])
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.staged.exists())
                missing.write_bytes(content)

    def test_asset_contract_rejects_missing_transitive_helpers(self):
        self.stage()
        for relative in ("scripts/evdi-compat.sh", "host/scripts/usb_reverse.sh"):
            with self.subTest(asset=relative):
                path = self.staged / relative
                content = path.read_bytes()
                path.unlink()
                result = self.run_command([
                    "bash", "-c", 'source "$ROOT_DIR/scripts/ci/runtime-assets.sh"; '
                    'assert_wbeam_runtime_assets "$1"', "fixture", self.staged,
                ])
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(relative, result.stderr)
                path.write_bytes(content)
                path.chmod(0o755)

    def test_deb_profiles_ship_compatible_aliases_and_full_runtime(self):
        for os_id, os_version, target, soname in (
            ("debian", "12", "bookworm", "libevdi.so.0"),
            ("ubuntu", "24.04", "noble", "libevdi.so.1"),
        ):
            with self.subTest(target=target):
                package = self.capture / "deb-root"
                if package.exists():
                    shutil.rmtree(package)
                result = self.run_command([
                    "bash", self.root / "scripts/ci/build_deb.sh",
                ], WBEAM_TEST_OS_ID=os_id, WBEAM_TEST_OS_VERSION=os_version, WBEAM_TEST_SONAME=soname)
                self.assert_success(result)
                self.assert_package(package)
                control = (package / "DEBIAN/control").read_text()
                self.assertIn("Package: wbeam\n", control)
                self.assertIn(f"Version: 0.1.3~rc.2+{target}\n", control)
                self.assertIn("libevdi0" if target == "bookworm" else "libevdi1", control)
                self.assertIn("gstreamer1.0-plugins-ugly", control)

    def test_rpm_rejects_broken_evdi_runtime_abi(self):
        for override in ({"WBEAM_TEST_ACTUAL_SONAME": "libevdi.so.1"},
                         {"WBEAM_TEST_SONAME": "libevdi.so.9"}):
            with self.subTest(override=override):
                result = self.run_command(["bash", self.root / "scripts/ci/build_rpm.sh"], **override)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("EVDI runtime/linker validation failed", result.stderr)

    def test_rpm_staging_is_covered_by_files_manifest(self):
        self.assert_success(self.run_command(["bash", self.root / "scripts/ci/build_rpm.sh"]))
        package = self.capture / "rpm-root"
        self.assert_package(package)
        self.assertIn("Requires:       gstreamer1-plugin-openh264", (self.capture / "wbeam.spec").read_text())
        spec = (self.capture / "wbeam.spec").read_text()
        manifest = spec.split("\n%files\n", 1)[1].split("\n%changelog", 1)[0].splitlines()
        owned = [line.strip() for line in manifest if line.strip() and not line.startswith("#")]
        self.assertTrue(all(path.startswith("/") for path in owned), owned)
        for path in package.rglob("*"):
            if not path.is_file() and not path.is_symlink():
                continue
            absolute = "/" + str(path.relative_to(package))
            self.assertTrue(
                any(absolute == entry or (
                    (package / entry.lstrip("/")).is_dir() and absolute.startswith(entry + "/")
                ) for entry in owned),
                "RPM would omit staged asset: " + absolute,
            )

    def test_source_aliases_preserve_arguments_exit_and_symlink_resolution(self):
        arguments = ["path with spaces", "", "--literal=$HOME;$(exit 99)"]
        external = self.base / "aliases"
        external.mkdir()
        for alias, original in (("waybeam", "wbeam"), ("install-waybeam", "install-wbeam")):
            with self.subTest(alias=alias):
                target = self.root / original
                target.write_text("#!/usr/bin/env python3\nimport json, sys\nprint(json.dumps(sys.argv[1:]))\nsys.exit(23)\n")
                target.chmod(0o755)
                linked = external / alias
                linked.symlink_to(self.root / alias)
                for command in (self.root / alias, linked):
                    result = self.run_command([command, *arguments])
                    self.assertEqual(result.returncode, 23, result.stderr)
                    self.assertEqual(json.loads(result.stdout), arguments)

    def test_packaged_deploy_all_installs_bundled_apk_on_every_device(self):
        self.stage()
        self.assert_success(self.run_command([self.staged / "waybeam", "android", "deploy-all"]))
        installs = [args for args in self.calls("adb") if "install" in args]
        self.assertEqual(installs, [
            ["-s", serial, "install", "-r", str(self.staged / "android/wbeam-android.apk")]
            for serial in ("tablet-a", "tablet-b")
        ])
        launches = [args for args in self.calls("adb") if "am" in args]
        self.assertEqual(len(launches), 2)
        self.assertTrue(all(args[-1] == "com.wbeam/.MainActivity" for args in launches))
        self.assertEqual(self.calls("cargo"), [])
        self.assertEqual(self.calls("npm"), [])

    def test_packaged_deploy_all_fails_for_no_devices_or_enumeration_error(self):
        self.stage()
        for overrides in ({"WBEAM_TEST_DEVICES": ""}, {"WBEAM_TEST_ADB_ENUMERATION_FAIL": "1"}):
            with self.subTest(overrides=overrides):
                result = self.run_command([self.staged / "waybeam", "android", "deploy-all"], **overrides)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual([args for args in self.calls("adb") if "install" in args], [])

    def test_packaged_deploy_all_propagates_certificate_failure_without_uninstall(self):
        self.stage()
        result = self.run_command(
            [self.staged / "waybeam", "android", "deploy-all"],
            WBEAM_TEST_FAIL_SERIAL="tablet-a", WBEAM_ALLOW_DATA_DELETING_REINSTALL="1",
        )
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls("adb")
        self.assertFalse(any("uninstall" in args for args in calls))
        self.assertFalse(any(args[:2] == ["-s", "tablet-a"] and "am" in args for args in calls))
        self.assertTrue(any(args[:2] == ["-s", "tablet-b"] and "install" in args for args in calls))


if __name__ == "__main__":
    unittest.main()
