# shellcheck shell=bash
# Sourced by scripts/e2e_device.sh and scripts/m10_scenarios.sh: restarting the phone, and knowing when its new boot is up.
#
# Right after `adb reboot`, adbd still answers for a moment while Android shuts down, and sys.boot_completed still says
# 1 from the old boot: waiting for the device and that property alone can pass on the old boot, before the phone has
# gone down (M10's review). A new boot is told apart by the kernel's boot_id, which every boot draws anew.

# The runner kernel's last lines about a process killed or crashed — out of memory, a segfault, a trap — newest first,
# with their times (the emulator went away at a restart twice: M10-54, M10-97; M10-108), or "none".
kernel_kills() {
    local lines
    lines="$(sudo -n dmesg -T 2>/dev/null | grep -i -E 'out of memory|killed process|segfault|traps:|general protection' |
        tail -3 | tac | cut -c1-160 | paste -sd '|' -)"
    echo "${lines:-none}"
}

# The running boot's id, or nothing when adb could not say.
boot_id() {
    timeout 10 adb shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r[:space:]'
}

# Whether an emulator process is on this host.
emulator_running() {
    pgrep -f 'qemu-system|emulator64|/emulator ' >/dev/null 2>&1
}

# Starts the emulator again from the same AVD when its process is gone after `adb reboot` (M10-54: the process exits at
# the restart now and then, on no level in particular, and the host says nothing about why; M12-81). The AVD's disk
# persists, so the new boot is a cold start of the same phone: Android's settings, the device owner and the installed
# apps are still there, which is what scenario R needs. Only when PF_EMULATOR_RELAUNCH_OPTIONS is set (android.yml:
# the options the emulator was launched with, with -tcpdump to a second file that scripts/pcap_cat.py appends to the
# first). The new process's output goes to PF_EMULATOR_RELAUNCH_LOG. Sets REBOOT_RELAUNCHED to what was done.
emulator_relaunch() {
    [ -n "${PF_EMULATOR_RELAUNCH_OPTIONS:-}" ] || return 1
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" log="${PF_EMULATOR_RELAUNCH_LOG:-/tmp/pf-emulator-relaunch.log}"
    local avd="${PF_EMULATOR_AVD:-test}" avd_dir="${ANDROID_AVD_HOME:-$HOME/.android/avd}/${PF_EMULATOR_AVD:-test}.avd" locks
    [ -x "$sdk/emulator/emulator" ] || return 1
    # The dead process's locks: left in place, the new emulator takes the AVD as in use and starts read-only, on a
    # temporary copy of the disks — a phone with none of this one's data (run 42433155832, API 30: the test package
    # and the device owner gone after the relaunch).
    locks="$(find "$avd_dir" -maxdepth 1 -name '*.lock' -printf '%f ' 2>/dev/null)"
    find "$avd_dir" -maxdepth 1 -name '*.lock' -exec rm -rf {} + 2>/dev/null
    # shellcheck disable=SC2086 # the options are a command line, split as the shell splits it
    setsid nohup "$sdk/emulator/emulator" -port 5554 -avd "$avd" $PF_EMULATOR_RELAUNCH_OPTIONS \
        </dev/null >"$log" 2>&1 &
    REBOOT_RELAUNCHED="the emulator process had exited at the restart (M10-54), so it was started again from the same AVD $1 s after the restart was asked, a cold start of the same phone (its output: $log; the dead process's locks removed first: ${locks:-none})"
}

# What the relaunched emulator's log says that matters (read-only mode, a lock, a wipe, an error), and whether the
# phone that came back is the same one: for R's note after a relaunch.
emulator_relaunch_facts() { # <a file that must still be on the phone>
    local log="${PF_EMULATOR_RELAUNCH_LOG:-/tmp/pf-emulator-relaunch.log}" said there
    said="$(grep -iE 'read-only|read only|lock|wipe|userdata|ERROR|FATAL|snapshot' "$log" 2>/dev/null | grep -v 'Failed to process .ini' | tail -6 | cut -c1-160 | paste -sd '|' -)"
    there="$(timeout 10 adb shell "[ -f '$1' ] && echo yes || echo no" 2>/dev/null | tr -d '\r[:space:]')"
    echo "the relaunched emulator said: ${said:-nothing of note}; $1 still on the phone: ${there:-no answer}; data dir: $(timeout 10 adb shell ls -ld /data/data 2>&1 | tr -d '\r' | cut -c1-80)"
}

# Restarts the phone and waits, up to <seconds> in all, until a new boot has completed: a boot_id unlike the one before
# the restart (or, when that one could not be read, adb seen leaving the old boot first) and sys.boot_completed=1 in it.
# Succeeds once it has. REBOOT_WENT_DOWN is set when adb was seen leaving the old boot; REBOOT_RELAUNCHED when the
# emulator process was gone 10 s after the restart was asked and was started again (emulator_relaunch).
reboot_and_wait() { # <seconds>
    local old new state deadline=$((SECONDS + $1)) asked=$SECONDS
    old="$(boot_id)"
    REBOOT_WENT_DOWN=""
    REBOOT_RELAUNCHED=""
    REBOOT_SEEN="boot id before: ${old:-unreadable}"
    timeout 60 adb reboot >/dev/null 2>&1 || true
    # The old boot going: adb no longer has the device, or already a new boot.
    while [ "$SECONDS" -lt "$deadline" ]; do
        state="$(timeout 5 adb get-state 2>/dev/null | tr -d '\r[:space:]')"
        if [ "$state" != device ]; then
            REBOOT_WENT_DOWN=1
            break
        fi
        new="$(boot_id)"
        if [ -n "$old" ] && [ -n "$new" ] && [ "$new" != "$old" ]; then
            REBOOT_WENT_DOWN=1
            break
        fi
        sleep 1
    done
    local completed=""
    while [ "$SECONDS" -lt "$deadline" ]; do
        # The emulator process gone (not a slow boot): started again, once, from 10 s after the restart was asked.
        if [ -z "$REBOOT_RELAUNCHED" ] && [ $((SECONDS - asked)) -ge 10 ] && ! emulator_running; then
            emulator_relaunch $((SECONDS - asked)) || true
        fi
        # What this round reads, or nothing: an earlier round's, or the old boot's, would say a phone gone is still there.
        new=""
        completed=""
        if timeout 10 adb wait-for-device >/dev/null 2>&1; then
            new="$(boot_id)"
            completed="$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
            if [ -n "$new" ] && { { [ -n "$old" ] && [ "$new" != "$old" ]; } || { [ -z "$old" ] && [ -n "$REBOOT_WENT_DOWN" ]; }; } &&
                [ "$completed" = 1 ]; then
                return 0
            fi
        fi
        sleep 2
    done
    # What was last seen, for the error: whether adb ever left the old boot, the boot id now, the boot's progress.
    REBOOT_SEEN="$REBOOT_SEEN; adb $([ -n "$REBOOT_WENT_DOWN" ] && echo "left the old boot" || echo "never left the old boot"); boot id now: ${new:-unreadable}; sys.boot_completed: ${completed:-unset}; adb state: $(timeout 5 adb get-state 2>&1 | tr -d '\r' | head -c 80); emulator process: $(emulator_running && echo running || echo "none on this host")${REBOOT_RELAUNCHED:+ ($REBOOT_RELAUNCHED)}; the runner's disk: $(df -Ph / 2>/dev/null | awk 'NR==2 {print $4 " free of " $2}'); the runner kernel's last kills and crashes: $(kernel_kills)"
    return 1
}

# Installs an APK, up to three tries: adb can say "device offline" for a moment right after Gradle's connected tests
# hand the device back, with the phone there and answering a second later (M12-80: API 35, every test passed and the
# install after them failed at once). Between tries it waits for the device. Output goes to <log> (appended).
adb_install() { # <apk> <log> [adb install flags...]
    local apk=$1 log=$2 try
    shift 2
    for try in 1 2 3; do
        if timeout 120 adb install -r -t "$@" "$apk" >> "$log" 2>&1; then
            return 0
        fi
        if [ "$try" -lt 3 ]; then
            echo "install of $(basename "$apk") failed (try $try): $(tail -1 "$log"); waiting for the device" >> "$log"
            timeout 60 adb wait-for-device >/dev/null 2>&1 || true
            sleep "${ADB_INSTALL_PAUSE:-5}"
        fi
    done
    return 1
}
