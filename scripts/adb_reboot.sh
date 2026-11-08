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

# Restarts the phone and waits, up to <seconds> in all, until a new boot has completed: a boot_id unlike the one before
# the restart (or, when that one could not be read, adb seen leaving the old boot first) and sys.boot_completed=1 in it.
# Succeeds once it has. REBOOT_WENT_DOWN is set when adb was seen leaving the old boot.
reboot_and_wait() { # <seconds>
    local old new state deadline=$((SECONDS + $1))
    old="$(boot_id)"
    REBOOT_WENT_DOWN=""
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
    REBOOT_SEEN="$REBOOT_SEEN; adb $([ -n "$REBOOT_WENT_DOWN" ] && echo "left the old boot" || echo "never left the old boot"); boot id now: ${new:-unreadable}; sys.boot_completed: ${completed:-unset}; adb state: $(timeout 5 adb get-state 2>&1 | tr -d '\r' | head -c 80); emulator process: $(pgrep -f 'qemu-system|emulator64|/emulator ' >/dev/null 2>&1 && echo running || echo "none on this host"); the runner's disk: $(df -Ph / 2>/dev/null | awk 'NR==2 {print $4 " free of " $2}'); the runner kernel's last kills and crashes: $(kernel_kills)"
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
