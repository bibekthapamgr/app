# shellcheck shell=bash
# Sourced by scripts/e2e_device.sh and scripts/m10_scenarios.sh: restarting the phone, and knowing when its new boot is up.
#
# Right after `adb reboot`, adbd still answers for a moment while Android shuts down, and sys.boot_completed still says
# 1 from the old boot: waiting for the device and that property alone can pass on the old boot, before the phone has
# gone down (M10's review). A new boot is told apart by the kernel's boot_id, which every boot draws anew.

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
    while [ "$SECONDS" -lt "$deadline" ]; do
        if timeout 10 adb wait-for-device >/dev/null 2>&1; then
            new="$(boot_id)"
            if [ -n "$new" ] && { { [ -n "$old" ] && [ "$new" != "$old" ]; } || { [ -z "$old" ] && [ -n "$REBOOT_WENT_DOWN" ]; }; } &&
                [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
                return 0
            fi
        fi
        sleep 2
    done
    return 1
}
