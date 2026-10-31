# Ping probes from the second independent check, rerun after the fix

The second independent check wrote its own probe script (`inject.py`, `run_exp.sh`) and used it to show that large, fragmented IPv6 pings still got a fake reply. After the echo filter was fixed, the same script was run against the rebuilt `spike/bin/pfspike` (the binary of the canonical run `run-m1-20261030T003301Z`), each time in a fresh network namespace that the script removes afterwards:

| Folder | TUN MTU | Probes sent | Packets back from the engine | Engine stats |
|---|---|---|---|---|
| `mtu1500/` | 1500 | IPv4 small and 2,056-byte fragmented; IPv6 plain, behind destination options, behind hop-by-hop, atomic fragment, small echo in two fragments, 2,056-byte fragmented; then kernel `ping` small and `-s 2000` | 0 (kernel pings: 100 % loss) | 12 echo requests and 6 later fragments dropped; 0 replies sent |
| `mtu9000/` | 9000 | IPv6 plain, 2,056-byte fragmented, 12,296-byte fragmented | 0 | 3 echo requests and 2 later fragments dropped; 0 replies sent |

Reproduce (root, Linux; build `spike/bin/pfspike` first with `run_spike.sh` or `go build`): `KPING=1 bash run_exp.sh <outdir> ../../bin/pfspike v4_plain,v4_fragmented_2056B,v6_plain,v6_destopts,v6_hopbyhop,v6_atomic_fragment,v6_small_2frags,v6_large_fragmented_2056B` and `TUNMTU=9000 bash run_exp.sh <outdir> ../../bin/pfspike v6_plain,v6_large_fragmented_2056B,v6_jumbo_fragmented_12296B`.
