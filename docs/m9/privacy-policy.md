# ProxyFlow — Privacy policy

The privacy policy the app shows (About & privacy, then Privacy policy), for the public address Google Play asks for (owner decision D9: where it is published, and the support contact). Written from the app's own text by `scripts/privacy_policy.py`; CI fails if the two differ. Do not edit by hand.

*This policy describes ProxyFlow version 0.9.0 (9).*

ProxyFlow has no servers, no account, no ads and no analytics. What you enter stays on this phone, and ProxyFlow sends your traffic only to the proxy you choose. Your proxy provider, not ProxyFlow, can see the sites you open.

## Kept on this phone

Your proxy profiles: their names, protocol, server address and port, TLS settings, whether they need a sign-in, and the result of the last connection test. They are in ProxyFlow's private storage, which other apps can't read.

Usernames and passwords, encrypted with AES-256-GCM under a key that Android's Keystore holds for each profile. The key never leaves the Keystore, and deleting a profile deletes its key.

A diagnostic log of connection events, error codes, counts and durations. It never includes passwords, server or site names, addresses or page content. It is kept for the time you choose in Settings (24 hours unless you change it), at most 5,000 events, and Clear removes it.

Your settings, which profile you chose, Android's system kill switch as Android last reported it, and, while connected, a note of the connection, so that ProxyFlow can tell you at its next start if Android stopped it.

None of this is included in backups or copied to a new phone. Uninstalling ProxyFlow deletes all of it.

## Sent from this phone

While connected, apps' TCP connections go to the proxy server in your profile, which connects them to the sites. Your proxy looks up the sites' names and can see any data that isn't encrypted, such as pages that don't use HTTPS. Choose a provider you trust.

A profile's username and password are sent only to that profile's proxy. With TLS to the proxy they are encrypted; without it, anyone on the network between this phone and the proxy can read them.

If your proxy's address is a name, this phone looks it up through its usual DNS, outside the VPN.

When you connect, and when you test a profile, ProxyFlow asks your proxy to open one connection to connectivitycheck.gstatic.com on port 443, a Google service, and closes it without sending anything through it. Google then sees a connection from your proxy's address.

Nothing is sent to ProxyFlow's developer, who has no servers to receive it: no usage data, crash reports or logs.

If you copy or export the log, you decide where it goes. It never includes passwords, server or site names, or addresses.

## Android and Google

If your phone shares diagnostics with Google, Android may include ProxyFlow's crash reports. ProxyFlow removes sign-in details, server and site names and addresses from the error messages it hands to Android, though Android writes the original message to the phone's own log, which other apps can't read.

Some traffic of Android itself and of its built-in apps, such as Google Play services, doesn't use the VPN, so it doesn't go through your proxy.

## Permissions

VPN: Android asks you the first time you connect. It lets ProxyFlow send apps' traffic to your proxy.

Internet and network state: to reach your proxy, and to notice when the phone changes network.

Foreground service: to keep the connection running while you use other apps.

Notifications (Android 13 and later, optional): for the connection status and its Disconnect button. Profile names are hidden on the lock screen.

## Changes and contact

If a later version changes what ProxyFlow keeps or sends, its privacy policy will say so. ProxyFlow's developer can be reached through the contact details on its Google Play page.
