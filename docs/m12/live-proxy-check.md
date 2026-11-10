# The live proxy check

The release used with the owner's own proxy on the real internet, on a clean emulator, the way a person uses it —
a check the lab cannot make (its proxies are its own) and the physical-phone checklist (`docs/m10/physical-phone-checklist.md`)
makes by hand. Started by hand only: Actions → live-proxy-check → Run workflow, or

```
gh workflow run live-proxy-check.yml --ref main -f release-run=<the signed release run's id> \
  -f fingerprint=<the upload certificate's SHA-256> -f protocol=HTTP -f api-level=36
```

## The proxy's details

Four repository secrets, set from the owner's computer and never written anywhere in the repository or in a workflow
input (an input is printed in the log unmasked; a secret is masked by GitHub and again by the job's first step):

```
gh secret set PF_LIVE_PROXY_HOST       # the address or name
gh secret set PF_LIVE_PROXY_PORT
gh secret set PF_LIVE_PROXY_USERNAME   # leave unset for a proxy without sign-in
gh secret set PF_LIVE_PROXY_PASSWORD
```

The run types them into the Add proxy form with `adb shell input text`, as the release smoke types the lab's
password: for that moment they are on a command line on the runner and the emulator, nowhere else. Delete the secrets
after the check (`gh secret delete …`) when the proxy is not to stay in the repository's settings.

## What the run does (`scripts/live_proxy_check.sh`)

1. Installs the signed release run's `universal.apk` (the artifact `release-bundle-upload-key` of the run given: the
   same bytes a phone outside Play installs) and checks the package, the version and the signing certificate against
   the upload key's fingerprint, as the release smoke does.
2. Reads, with ProxyFlow off, what `api.ipify.org` sees of the phone: the runner's own public address.
3. Uses the app through its own screens: onboarding, Add proxy (the protocol chosen, the address, port and sign-in
   typed; no screenshot of the form — it shows the address, and keeps itself out of screenshots anyway), **Test
   connection** (must say *Proxy works*), Save, Connect, ProxyFlow's VPN explainer, Android's consent, **Connected**.
4. From the shell — a program outside the app, inside the VPN like every other app — fetches, the way a browser's
   first request does, `https://api.ipify.org` (the phone's trust store, the host name checked), the same over plain
   HTTP, and `https://www.google.com/generate_204`; a lookup of a name while connected. The address the sites saw must
   not be the runner's: it is the proxy's own, or the provider's exit side (not printed).
5. Photographs Home (the session's counts) and the Logs screen, and checks the Logs screen shows none of the address,
   username or password.
6. Disconnect, Not connected; `api.ipify.org` must see the runner's own address again.
7. Reads the emulator's own capture of its uplink (`scripts/live_proxy_evidence.py`): between Connected and
   Disconnect, every IPv4 packet the phone sent went to the proxy's address and port over TCP — a packet to any other
   address, a DNS query to any resolver among them, is a leak and fails the run. Before and after, the phone's direct
   traffic is counted and expected.

The screenshots (`live-check/*.png`) are uploaded as the run's artifact and pushed to the branch `ci-live-check`; the
capture stays on the runner (it names the proxy), the evidence text in the artifact only.

## What it proves, and what it cannot

It proves that this proxy works with this build as shipped, from the form to a page fetched through it, and that the
phone's traffic went through it and nowhere else while connected. It says nothing about the owner's phone, their
network or their provider's other servers — the physical-phone checklist does that — and nothing about UDP, which
ProxyFlow refuses by design (ADR-0011).
