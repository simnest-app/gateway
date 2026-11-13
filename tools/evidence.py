#!/usr/bin/env python3
"""Evidence that the gateway works on a phone (docs/TESTING.md, CONTRIBUTING.md).

    tools/evidence.py collect <out-dir> [--serial S]    read the phone over adb into <out-dir>, with numbers and addresses masked
    tools/evidence.py check <dir>                       say what the files in <dir> prove; exit 1 when level 1 is not met

`collect` only reads: it installs nothing, changes no setting, places no call. It needs `adb` on the PATH (or ADB set
to its path) and the phone connected with USB debugging allowed.

What is collected: the phone's model and system (never its serial number or its phone numbers), the audio parts of its
audio policy, how the gateway app stands (version, permissions, default apps, service), the audio bridge's log and the
gateway's own lines of the system log. Everything goes through `mask` before it is written.
"""
import argparse, os, re, subprocess, sys

PACKAGES = ["app.simnest.gateway", "dev.cloudsim.gateway"]
BRIDGE_LOG = "/data/local/tmp/cloudsim-bridge.log"
PROPS = [
    "ro.product.manufacturer", "ro.product.brand", "ro.product.model", "ro.product.marketname", "ro.product.odm.marketname",
    "ro.config.marketing_name", "ro.product.device", "ro.product.cpu.abi",
    "ro.build.version.release", "ro.build.version.sdk", "ro.build.version.security_patch", "ro.build.display.id",
    "ro.soc.manufacturer", "ro.soc.model", "ro.board.platform", "ro.hardware",
    # The maker's own system on top of Android, whichever of these the phone has.
    "ro.miui.ui.version.name", "ro.mi.os.version.name", "ro.build.version.emui", "hw_sc.build.platform.version",
    "ro.build.version.oneui", "ro.build.version.opporom", "ro.vivo.os.version", "ro.build.version.magic",
    "persist.radio.multisim.config", "gsm.sim.state", "service.adb.tcp.port",
]
PERMISSIONS = ["READ_PHONE_STATE", "ANSWER_PHONE_CALLS", "READ_CALL_LOG", "CALL_PHONE", "RECEIVE_SMS", "SEND_SMS", "READ_SMS",
               "READ_PHONE_NUMBERS", "POST_NOTIFICATIONS", "READ_CONTACTS", "WRITE_CONTACTS", "WRITE_SECURE_SETTINGS"]

# A phone number, an address, a device serial: none belongs in evidence that is made public.
_MASKS = [
    # A device's ID as the gateway logs it in full (the short 8-character forms stay: they tell calls apart).
    (re.compile(r"(?<![0-9A-Fa-f])[0-9a-f]{32,64}(?![0-9A-Fa-f])"), "<id>"),
    (re.compile(r"\+\d{7,15}"), "<number>"),
    (re.compile(r"(?<![\d.])\d{10,15}(?![\d.])"), "<number>"),
    (re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])"), "<ip>"),
    (re.compile(r"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f:]{1,9}(?![0-9A-Fa-f:])"), "<ip6>"),
    (re.compile(r"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:])"), "<mac>"),
]


def mask(text, also=()):
    for pattern, to in _MASKS:
        text = pattern.sub(to, text)
    for secret in also:
        if secret:
            text = text.replace(secret, "<serial>")
    return text


def unmasked(text):
    """What `mask` would still change: evidence written by hand, or by something else, is checked with this."""
    return [m.group(0) for pattern, _ in _MASKS for m in pattern.finditer(text)]


class Phone:
    def __init__(self, serial):
        self.adb = [os.environ.get("ADB", "adb")] + (["-s", serial] if serial else [])
        self.serial = serial or self.run("get-serialno", shell=False).strip()

    def run(self, command, shell=True, timeout=60):
        r = subprocess.run(self.adb + (["shell", command] if shell else [command]), capture_output=True, timeout=timeout)
        return r.stdout.decode("utf-8", "replace")


def collect(out, serial):
    p = Phone(serial)
    if not p.serial or "unknown" in p.serial:
        sys.exit("no phone: connect one with USB debugging allowed (several connected: name one with --serial)")
    os.makedirs(out, exist_ok=True)
    package = next((x for x in PACKAGES if f"package:{x}" in p.run(f"pm list packages {x}")), None)

    def write(name, text):
        with open(os.path.join(out, name), "w", encoding="utf-8") as f:
            f.write(mask(text, also=[p.serial]).rstrip("\n") + "\n")

    lines = [f"{k}={v}" for k in PROPS for v in [p.run(f"getprop {k}").strip()] if v]
    lines.append("wireless_debugging_setting=" + (p.run("settings get global adb_wifi_enabled").strip() or "absent"))
    policy = p.run("dumpsys media.audio_policy", timeout=120)
    audio = [l.strip() for l in policy.splitlines() if re.search(r"telephony|voice[ _](call|downlink|uplink)|VOICE_(CALL|DOWNLINK|UPLINK)", l, re.I)]
    write("device.txt", "\n".join(lines) + "\n\n# audio policy lines naming telephony or the call's voice\n" + "\n".join(audio[:200]))

    state = [f"package={package or 'not installed'}"]
    if package:
        dump = p.run(f"dumpsys package {package}", timeout=120)
        state.append("version=" + ", ".join(re.findall(r"version(?:Name|Code)=\S+", dump)[:2]))
        for perm in PERMISSIONS:
            m = re.search(rf"android\.permission\.{perm}: granted=(true|false)", dump)
            state.append(f"permission.{perm}=" + (m.group(1) if m else "not asked"))
        roles = p.run("dumpsys role", timeout=60)
        for role in ("DIALER", "SMS"):
            m = re.search(rf"name=android\.app\.role\.{role}\s+holders=(\S*)", roles)
            state.append(f"role.{role}=" + (m.group(1) if m else "unknown"))
        state.append("service_foreground=" + str("isForeground=true" in p.run(f"dumpsys activity services {package}", timeout=60)).lower())
        state.append("battery_whitelist=" + str(package in p.run("dumpsys deviceidle whitelist")).lower())
    state.append("bridge_process=" + str("BridgeMain" in p.run("ps -A -o ARGS 2>/dev/null | grep BridgeMain | grep -v grep")).lower())
    state.append("screen=" + ("on" if "mWakefulness=Awake" in p.run("dumpsys power | grep mWakefulness=") else "off"))
    write("state.txt", "\n".join(state))

    # Java stack traces of a shell process starting up say nothing of the phone and are long.
    bridge = [l for l in p.run(f"cat {BRIDGE_LOG} 2>/dev/null", timeout=120).splitlines() if not re.match(r"\s+at |\s+\.\.\. \d+ more|Caused by:", l)]
    write("bridge.log", "\n".join(bridge[-4000:]))
    own = [l for l in p.run("logcat -d -v time", shell=True, timeout=180).splitlines() if "CloudSimGateway" in l]
    write("gateway.log", "\n".join(own[-4000:]))

    report = os.path.join(out, "report.md")
    if not os.path.exists(report):
        with open(report, "w", encoding="utf-8") as f:
            f.write(REPORT)
    print(f"written to {out}: device.txt state.txt bridge.log gateway.log report.md")
    print("fill in report.md by hand, then: tools/evidence.py check " + out)


REPORT = """# Test report

Fill in by the person (or agent, with a person's answers) who ran the tests. Tick only what was really done on this
phone with this gateway version; leave the rest unticked and say why under Notes. See docs/TESTING.md for each item.

- Tester (GitHub name):
- Date:
- Gateway version:
- How the gateway was installed (write "official APK", or "built from commit <hash>"):

## Level 1: the phone can run the gateway

- [ ] L1.1 Installed, opens, terms accepted
- [ ] L1.2 All permissions allowed; default phone app and default SMS app are the gateway
- [ ] L1.3 Audio service set up (wireless debugging paired, or the debugging port opened from a computer)
- [ ] L1.4 The maker's own background limits lifted (auto-start, battery, app launch) as the setup guide says

## Level 2: calls and messages through it (needs a paired iPhone)

- [ ] L2.1 Incoming call answered on the iPhone, at least 60 s
- [ ] L2.2 Outgoing call placed from the iPhone, at least 60 s
- [ ] L2.3 HEARD: the person on the other phone heard the iPhone side clearly
- [ ] L2.4 HEARD: the iPhone side heard the person on the other phone clearly
- [ ] L2.5 The gateway phone itself stayed silent during the call (no sound from its earpiece or speaker)
- [ ] L2.6 SMS received on the iPhone
- [ ] L2.7 SMS sent from the iPhone arrived

## Level 3: it stays up

- [ ] L3.1 Screen off for 30 minutes, then an incoming call still reached the iPhone
- [ ] L3.2 After a reboot of the gateway phone, an incoming call reached the iPhone without touching the phone
- [ ] L3.3 24 hours running, calls at the start and at the end both fine

## Notes

What did not work, what had to be done differently on this phone, anything a later tester should know.
"""

# A call is asked to last 90 s (docs/TESTING.md); this much of it must show in the log.
MIN_CALL_S = 55
SESSION = re.compile(r"session (\w+) ended (\S+) injected=(\d+) dropped=(\d+) downlink=(\d+)/(\d+)/(\d+) uplink=(\d+)/(\d+)/(\d+)")


def check(folder):
    def read(name):
        path = os.path.join(folder, name)
        return open(path, encoding="utf-8", errors="replace").read() if os.path.exists(path) else None

    rows, failed_l1 = [], False

    def row(level, item, ok, note=""):
        nonlocal failed_l1
        rows.append((level, item, "PASS" if ok else ("FAIL" if ok is False else "n/a"), note))
        if level == "L1" and ok is False:
            failed_l1 = True

    files = {n: read(n) for n in ("device.txt", "state.txt", "bridge.log", "gateway.log", "report.md")}
    for name, text in files.items():
        row("L1", f"file {name}", text is not None)
    leaks = sorted({x for n, t in files.items() if t and n != "report.md" for x in unmasked(t)})
    row("L1", "nothing private left in the files", not leaks, ("found: " + ", ".join(leaks[:5])) if leaks else "")

    device = dict(l.split("=", 1) for l in (files["device.txt"] or "").splitlines() if "=" in l and not l.startswith("#"))
    state = dict(l.split("=", 1) for l in (files["state.txt"] or "").splitlines() if "=" in l)
    bridge = files["bridge.log"] or ""
    print(f"phone: {device.get('ro.product.manufacturer', '?')} {device.get('ro.product.model', '?')}, Android {device.get('ro.build.version.release', '?')} (API {device.get('ro.build.version.sdk', '?')}), {device.get('ro.soc.model') or device.get('ro.board.platform', '?')}")
    print(f"gateway: {state.get('package', '?')} {state.get('version', '?')}\n")

    row("L1", "gateway installed", state.get("package", "not installed") != "not installed")
    need = ["READ_PHONE_STATE", "READ_CALL_LOG", "CALL_PHONE", "RECEIVE_SMS", "SEND_SMS", "READ_SMS"]
    missing = [x for x in need if state.get(f"permission.{x}") != "true"]
    row("L1", "phone and SMS permissions granted", not missing, "missing: " + ", ".join(missing) if missing else "")
    for role in ("DIALER", "SMS"):
        row("L1", f"default {role.lower()} app is the gateway", state.get(f"role.{role}") == state.get("package"), state.get(f"role.{role}", ""))
    # The bridge's own test of the phone: it starts under the shell, and it can open a track to the call's uplink.
    last = bridge.rfind("bridge ready")
    after = bridge[last:] if last >= 0 else ""
    row("L1", "audio bridge starts as shell", last >= 0, "" if last >= 0 else "no 'bridge ready' in bridge.log")
    # From gateway 0.43.1 the bridge says which commit it was built from; a report names the commit it claims.
    built = re.search(r"bridge ready[^\n]*\bbuild=(\S+)", after)
    claimed = re.search(r"built from commit\s+([0-9a-f]{7,40})", files["report.md"] or "")
    if built:
        print(f"bridge built from: {built.group(1)}")
        if claimed:
            same = built.group(1).split("+")[0].startswith(claimed.group(1)[:12]) or claimed.group(1).startswith(built.group(1).split("+")[0])
            row("L1", "the bridge ran the commit the report names", same and "+changes" not in built.group(1),
                "" if same and "+changes" not in built.group(1) else f"log says {built.group(1)}, report says {claimed.group(1)}")
    row("L1", "uplink (Telephony TX) can be opened", "tx warmed" in after and "tx warm failed" not in after,
        (re.search(r"tx warm failed.*", after) or [""])[0][:120] if "tx warm failed" in after else ("" if "tx warmed" in after else "no 'tx warmed' after the last start"))
    row("L1", "gateway service running in the foreground", state.get("service_foreground") == "true" if "service_foreground" in state else None)
    row("L1", "gateway connected to its bridge", "app connected" in after if state.get("service_foreground") == "true" else None)

    # 20 ms a frame. A call counts when it lasted a minute, both directions carried sound, and little was dropped.
    good = 0
    for m in SESSION.finditer(bridge):
        sid, reason, injected, dropped, df, dn, dp, uf, un, up = m.group(1), m.group(2), *map(int, m.groups()[2:])
        seconds = uf * 0.02
        problems = []
        if seconds < MIN_CALL_S: problems.append(f"only {seconds:.0f} s")
        if df == 0 or dn < 0.2 * df or dp < 1000: problems.append("downlink (far end to iPhone) nearly silent")
        if uf == 0 or un < 0.2 * uf or up < 1000: problems.append("uplink (iPhone to far end) nearly silent")
        if injected and dropped > 0.01 * injected: problems.append(f"dropped {100 * dropped / injected:.1f}% of the uplink")
        if not problems: good += 1
        row("L2", f"call {sid} ({seconds:.0f} s, ended {reason})", not problems, "; ".join(problems))
    row("L2", "at least two calls of a minute with sound both ways", good >= 2, f"{good} such call(s) in bridge.log")

    report = files["report.md"] or ""
    ticked = set(re.findall(r"- \[[xX]\] (L\d\.\d)", report))
    for item, what in (("L2.3", "far end heard the iPhone side (a person's word; the log cannot show it)"), ("L2.4", "iPhone side heard the far end"),
                       ("L2.5", "gateway phone stayed silent")):
        row("L2", what, item in ticked, "" if item in ticked else f"{item} not ticked in report.md")
    row("L2", "SMS both ways", {"L2.6", "L2.7"} <= ticked, "" if {"L2.6", "L2.7"} <= ticked else "L2.6 / L2.7 not ticked")
    row("L3", "stays up (screen off, reboot, 24 h)", {"L3.1", "L3.2", "L3.3"} <= ticked, "ticked: " + (", ".join(sorted(t for t in ticked if t.startswith("L3"))) or "none"))
    named = bool(re.search(r"Tester \(GitHub name\):[^\S\n]*\S", report)) and bool(re.search(r"Gateway version:[^\S\n]*\S", report))
    row("L1", "report.md names the tester and the gateway version", named)

    width = max(len(r[1]) for r in rows)
    for level, item, result, note in rows:
        print(f"{level}  {item.ljust(width)}  {result}" + (f"  ({note})" if note else ""))
    levels = {l: all(r[2] == "PASS" for r in rows if r[0] == l and r[2] != "n/a") for l in ("L1", "L2", "L3")}
    reached = "L3" if all(levels.values()) else "L2" if levels["L1"] and levels["L2"] else "L1" if levels["L1"] else "none"
    print(f"\nlevel reached by this evidence: {reached}")
    sys.exit(1 if failed_l1 else 0)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("collect")
    c.add_argument("out")
    c.add_argument("--serial")
    k = sub.add_parser("check")
    k.add_argument("dir")
    a = ap.parse_args()
    collect(a.out, a.serial) if a.cmd == "collect" else check(a.dir)
