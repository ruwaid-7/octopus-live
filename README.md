# Octopus Live

**See your home's live electricity usage on your Windows desktop and Android phone**, using the
Octopus Home Mini you already have.

| Windows (Rainmeter skin) | Android |
|---|---|
| Live watts, colour-coded green / amber / red | Ongoing notification with live watts and today's kWh |
| Today's electricity and **gas** kWh | Live watts drawn into the **status-bar icon** |
| Usage bar + ~3.5 h mini graph | **Home-screen widget** |
| Click to refresh | Pauses while the screen is off to save battery |

> **Unofficial.** This project is not made, endorsed or supported by Octopus Energy. It uses the
> public [Octopus Energy API](https://developer.octopus.energy/) with your own API key.

**Gas:** if you have a smart gas meter, both apps also show today's gas usage in kWh. Gas meters
only send a reading every 30 minutes, so gas is a running total for the day rather than a live
figure, and is fetched every 30 minutes.

---

## What you need

- An **Octopus Energy** (UK) electricity account with a smart meter
- An **Octopus Home Mini**, set up and showing live data in the Octopus app
  (free from Octopus — [setup guide](https://octopus.energy/octopus-home-mini-faq/))
- Your **API key**: log in at
  [octopus.energy → Personal details → API access](https://octopus.energy/dashboard/new/accounts/personal-details/api-access)
  on the website (it isn't shown in the mobile app). It starts with `sk_live_`. Keep it private.

You don't need your account number or meter ID — both apps find them automatically.

## Download

Get the latest files from the **[Releases page](../../releases/latest)**:

- `OctopusLive-Rainmeter.zip` — Windows skin
- `OctopusLive.apk` — Android app (Android 10 or newer)

---

## Windows: Rainmeter skin

1. Install [Rainmeter](https://www.rainmeter.net) 4.5 or newer.
2. Unzip `OctopusLive-Rainmeter.zip` into `Documents\Rainmeter\Skins\`, so you have
   `Documents\Rainmeter\Skins\OctopusLive\OctopusLive.ini`.
3. Open `OctopusLive\@Resources\Settings.inc` in Notepad, paste your key after `ApiKey=`, and save.
4. In Rainmeter choose **Refresh all**, then load **OctopusLive → OctopusLive.ini**.

**Using it:** click the skin to refresh. Right-click for *Edit settings* (interval, colour
thresholds) and *Open error log*. If the top-right corner says anything other than "live", that's
the problem message.

**Test from PowerShell:**
```powershell
powershell -ExecutionPolicy Bypass -File "$env:USERPROFILE\Documents\Rainmeter\Skins\OctopusLive\@Resources\Scripts\OctopusFetch.ps1"
```

The skin uses the PowerShell built into Windows — nothing else to install.

## Android app

1. Download `OctopusLive.apk` to your phone and open it. Allow "install unknown apps" when asked.
2. Open **Octopus Live**, paste your API key, tap **Save & start**, and allow notifications.
3. **Widget:** long-press your home screen → **Widgets** → **Octopus Live**, and drag it on.
   It updates every minute while the live notification is running (otherwise every 30 minutes).
   Tap it to refresh.

**Tips**
- Samsung / Xiaomi / OnePlus phones can stop background apps. If the notification disappears, set
  *Settings → Apps → Octopus Live → Battery* to **Unrestricted**.
- On Android 14+ you can swipe the notification away; tap the widget or **Save & start** to bring it back.

---

## Rate limits — please read

Octopus allows about **100 API requests per hour per account**, shared by everything using your
key. Each app makes **one request per refresh** (live reading + today's electricity together), plus
one gas request every 30 minutes, and defaults to every **60 seconds** — the PC only while it's on,
the phone only while its screen is on.

If you run **both** the PC skin and the phone app at the same time, set both intervals to **90
seconds or more** to stay under the limit. If you see "Rate limited", raise the interval. Please
don't set it lower than 60.

## Optional: one home server for all your devices

If you have an always-on Linux box (Ubuntu server, Raspberry Pi…), run the small **relay** in the
[`server`](server) folder. It calls Octopus once for everyone and your PC and phone read from it:

```
Octopus API  <-- one request every 45 s --  your server (relay)  -->  PC skin, phone app, widget
```

- No rate-limit juggling: refresh the PC and phone every 10–15 s from your server.
- Your API key lives only on the server.
- A status page at `http://SERVER-IP:8765` you can open in any browser.

**Install (Ubuntu/Debian, needs Python 3.8+ — already installed on Ubuntu):**
```bash
git clone https://github.com/ruwaid-7/octopus-live.git
cd octopus-live/server
sudo ./install.sh          # asks for your API key, starts the service
```
It prints the address to use, e.g. `http://192.168.1.50:8765`. Settings live in
`/etc/octopus-relay.env`; logs: `journalctl -u octopus-relay -f`. To update later:
`git pull && sudo ./install.sh`.

**Point your devices at it:**
- **Rainmeter:** set `RelayUrl=http://192.168.1.50:8765` in `Settings.inc`, and `Interval=15`.
- **Android:** enter the address in **Home server address**, set the interval to 15, Save & start.

If you also keep your API key on a device, it falls back to calling Octopus directly (at most once a
minute) when the server can't be reached — e.g. your phone away from home. To use the server from
outside your home without that fallback, put it on a VPN such as Tailscale and use its Tailscale address.
Optional: set `RELAY_TOKEN` in `/etc/octopus-relay.env` and add `?token=YOURSECRET` to the address on
each device.

## How it works

The Home Mini reads your smart meter about every 10 seconds and uploads the readings to Octopus.
Octopus makes them available through its GraphQL API:

1. Exchange the API key for a short-lived token (`obtainKrakenToken`) — cached ~50 minutes.
2. Find the meter's device ID from the account (`account → electricityAgreements → meters`) — cached.
3. Each refresh runs one query with two parts:
   - `smartMeterTelemetry(deviceId)` → latest `demand` (watts) and `readAt`
   - `smartMeterTelemetry(deviceId, grouping: HALF_HOURLY, start: midnight)` → sum of
     `consumptionDelta` = today's usage
4. Every 30 minutes, the same half-hourly query on the **gas** meter's device ID gives today's gas.

## Privacy

Your API key is stored only on your own PC (`Settings.inc`) and phone (private app storage) and is
sent only to `api.octopus.energy`. There's no server, analytics or tracking.

If you fork this repo, **don't commit `Settings.inc` with your key in it.**

## Building it yourself

- **Android:** open the `android` folder in Android Studio and build. If it reports an invalid
  Gradle JDK, choose *Use Embedded JDK*. The app has no third-party dependencies.
- **CI:** every push builds both files (see the *Actions* tab). Pushing a tag like `v1.0.0`
  publishes a Release. To sign releases with your own key (so updates install over old versions),
  add repository secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`
  and `SIGNING_KEY_PASSWORD`.

## Licence

MIT — see [LICENSE](LICENSE). Octopus Energy and Home Mini are trademarks of Octopus Energy Ltd.
