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

**Gas:** if you have a smart gas meter, both apps also show today's gas usage in kWh and a **gas
bar** next to the electricity bar. Gas meters only send a reading every 30 minutes, so the gas bar
shows the average rate over the latest half-hour (e.g. 1.5 kWh in 30 min = 3.0 kW) rather than a
second-by-second figure. The bar is full at 10 kW (`MaxGasKW` in the Rainmeter settings).

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
   - If **Google Play Protect** says *"App blocked to protect your device"* or *"unsafe"*, that's
     because the app isn't from the Play Store and Google hasn't seen this developer before — not
     because anything was found in it. Tap **More details → Install anyway**. If that option isn't
     shown: Play Store → your profile → **Play Protect → ⚙** → turn off *Scan apps with Play
     Protect*, install, then turn it back on. The full source code is in this repository and
     every APK is built from it by GitHub Actions.
2. Open **Octopus Live**, paste your API key, tap **Save & start**, and allow notifications.
3. **Widget:** long-press your home screen → **Widgets** → **Octopus Live**, and drag it on.
   It updates every minute while the live notification is running (otherwise every 30 minutes).
   Tap it to refresh.

**Costs, prices and history** (needs your API key in the app): today's cost in £ including the
standing charge, the current electricity price and when it next changes, a half-hourly chart for
today, the last 7 days of electricity and gas, and your "always-on" use (lowest half-hour) — the
power your home never drops below. Works with fixed, Agile, Go, Intelligent and tracker tariffs
(not yet Economy 7). Prices come from Octopus's public tariff data and update by themselves:
prices are re-checked every 3 hours (and just after Agile's next-day prices come out around 4pm),
and your tariff every 6 hours, so price-cap changes or switching tariff need no app update.

**Alerts** (each can be switched off in the app):
- **Gas left on** — gas in use for 1½ hours or more (see below).
- **High electricity use** — above 3 kW for 45 minutes (both adjustable). Live, so it's prompt.
- **Home Mini offline** — no new meter readings for 30 minutes.
- **Cheap electricity** — on Agile / Go / smart tariffs, a heads-up for upcoming half-hours at or
  below your chosen price (default 0p, i.e. free or paid-to-use "plunge" pricing).
- **Daily summary** — each morning (default 8:00): yesterday's electricity and gas, cost, the
  change on the day before, and always-on use.

**Quick Settings tile:** swipe down twice → ✏ edit → drag **Live electricity** into your tiles to
see live watts and today's cost in the pull-down panel. Tap it to refresh.

**Gas warning:** with *Warn me if the gas has been on for 1½ hours or more* ticked (on by
default), you get a notification when the meter shows three half-hours in a row with gas in use.
Gas readings reach Octopus about an hour late, so the warning arrives roughly an hour after the
1½-hour mark — good for catching a boiler or hob left on, not an instant alarm.

**Gemini / assistants (Android 16+, early):** the app offers three functions an on-device
assistant can call: *live energy use* (watts, today's kWh and cost, current price), *gas status*
(on or off, for how long) and *cheapest electricity times* (next 24 hours). They use Google's
[AppFunctions](https://developer.android.com/ai/appfunctions/add-appfunctions), which Gemini
already uses for some apps. Google is still opening it up to other apps, so Gemini may not call
them yet; once it does, asking *"how much electricity am I using?"* will work with no app update.
Older Android versions are unaffected. Developers can test with
[adb](#testing-the-gemini-functions).

**Background running:** while the screen is off the app keeps updating every 5 minutes (so the
widget and gas warning work with the phone locked). In the app's **Background running** section,
tap **Allow unrestricted battery use** and **Don't pause app when unused** until both show ✓.

**Tips**
- Samsung / Xiaomi / OnePlus phones can stop background apps. If the notification disappears, set
  *Settings → Apps → Octopus Live → Battery* to **Unrestricted**.
- On Android 14+ you can swipe the notification away; tap the widget or **Save & start** to bring it back.

---

## Rate limits — please read

Octopus allows about **100 API requests per hour per account**, shared by everything using your
key. Each app makes **one request per refresh** (today's total is worked out from the meter's
running total and re-checked every 30 minutes), plus one gas request every 30 minutes, and
defaults to every **60 seconds** — the PC only while it's on, the phone only while its screen is on.

If you run **both** the PC skin and the phone app at the same time, set both intervals to **90
seconds or more** — or use a relay (below) so only one thing talks to Octopus. If you see "Rate
limited", raise the interval. Please don't set it lower than 60.

## Choose your setup

| Setup | Best for | What talks to Octopus |
|---|---|---|
| **Just the apps** (above) | One device, or PC + phone at 90 s | Each device directly |
| **Free Cloudflare Worker** | Several devices, no server, works anywhere | The Worker, once a minute |
| **Home server relay** | You already run a Linux box / Docker | Your server, once a minute |

With either relay, your devices read from the relay instead of Octopus, can refresh every 10–15 s,
and don't need your API key at all.

## Optional: free Cloudflare Worker (no server needed)

The [`worker`](worker) folder is the same relay running on Cloudflare's free plan — nothing to
install or keep running, and you get an `https://` address that works at home and away.

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/ruwaid-7/octopus-live/tree/main/worker)

1. Click **Deploy to Cloudflare** and sign in (a free account is fine). It copies this folder
   into your own GitHub account and deploys it.
2. When asked, fill in **OCTOPUS_API_KEY** (your `sk_live_…` key) and **RELAY_TOKEN**.
   **You make the relay token up yourself** — it's just a password that stops strangers reading
   your usage, and you'll add it to your address as `?token=…`. Use letters and numbers only,
   about 30 characters. To generate one on Windows, run this in PowerShell:
   `-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 32 | % {[char]$_})`
   Type the values in yourself — don't let the browser autofill a saved password here.
   If you weren't asked: open the Worker in the Cloudflare dashboard →
   **Settings → Variables and Secrets → Add**, type **Secret**, and add both.
3. Your address is shown after deploying, e.g. `https://octopus-live-worker.yourname.workers.dev`.
   Open `https://octopus-live-worker.yourname.workers.dev/?token=YOUR-TOKEN` to check it.
   If it doesn't load, open the Worker → **Domains** and make sure the switch next to the
   **Production** `workers.dev` address is **on**.
4. Put that full address (with `?token=…`) into the apps — see *Point your devices at a relay* below.

It only calls Octopus when one of your devices asks, at most once a minute, so it stays well
inside both Octopus's limit and Cloudflare's free allowance (100,000 requests a day).

**Connected the repository yourself instead of using the button?** In the Worker's **Settings →
Build**, set **Root directory** to `/worker` and **Deploy command** to `npx wrangler deploy`,
then redeploy. You'll know it worked when **Settings → Bindings** lists a Durable Object called
`RELAY`. Add the two secrets under **Settings → Variables and Secrets**.

Prefer the command line? `cd worker && npm install && npx wrangler deploy`, then
`npx wrangler secret put OCTOPUS_API_KEY` and `npx wrangler secret put RELAY_TOKEN`.

## Optional: one home server for all your devices

If you have an always-on Linux box (Ubuntu server, Raspberry Pi…), run the small **relay** in the
[`server`](server) folder. It calls Octopus once for everyone and your PC and phone read from it:

```
Octopus API  <-- one request a minute --  your server (relay)  -->  PC skin, phone app, widget
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

**Or run it in Docker** (handy if your other services are containers): see
[`server/docker-compose.yml`](server/docker-compose.yml).

**Use it from anywhere with a Cloudflare tunnel** (no VPN needed on your phone):
1. Make sure `RELAY_TOKEN` is set in `/etc/octopus-relay.env` (the installer generates one).
2. In Cloudflare **Zero Trust → Networks → Tunnels → your tunnel → Routes → Add route →
   Published application**: subdomain e.g. `energy`, your domain, service URL
   `http://octopus-relay:8765` if cloudflared runs in Docker on the same network as the relay
   container, or `http://SERVER-LAN-IP:8765` otherwise.
3. Check DNS has a Tunnel/CNAME record for that name (Cloudflare usually adds it). Don't delete
   the route or record afterwards — removing one can remove the other.
4. Test from outside: `https://energy.yourdomain/?token=YOURTOKEN` should show the status page,
   and without the token it should say "Relay token required".

If a new hostname shows "can't be reached" at home but works on mobile data, your router is
still caching the earlier "not found" answer — it clears within about 30 minutes.

## Point your devices at a relay

Works the same for the Cloudflare Worker and the home server — use the full address including
`?token=…`:
- **Rainmeter:** in `Settings.inc` set `RelayUrl=https://…/?token=YOUR-TOKEN`, `Interval=15`, and
  you can clear `ApiKey`.
- **Android:** enter the address in **Relay address**, set the interval to 15, Save & start. You
  can clear the API key.

If you also keep your API key on a device, it falls back to calling Octopus directly (at most once a
minute) when the relay can't be reached — e.g. a home-only server while you're out.

## Home Assistant

If you run [Home Assistant](https://www.home-assistant.io), it can read the relay (home server or
Cloudflare Worker). Add to `configuration.yaml`, replacing the address and token:

```yaml
rest:
  - resource: "https://energy.example.com/api/live?token=YOUR-TOKEN"
    scan_interval: 30
    sensor:
      - name: "Octopus Live power"
        value_template: "{{ value_json.demandW }}"
        unit_of_measurement: "W"
        device_class: power
        state_class: measurement
      - name: "Octopus Live electricity today"
        value_template: "{{ value_json.todayKwh }}"
        unit_of_measurement: "kWh"
        device_class: energy
        state_class: total_increasing
      - name: "Octopus Live gas today"
        value_template: "{{ value_json.gasKwh }}"
        unit_of_measurement: "kWh"
        device_class: energy
        state_class: total_increasing
      - name: "Octopus Live gas on (minutes)"
        value_template: "{{ value_json.gasRunMinutes }}"
        unit_of_measurement: "min"
```

The "today" sensors reset at midnight, which `total_increasing` handles, so they can feed the
Energy dashboard. (For full tariff and cost features in Home Assistant, the
[Octopus Energy integration](https://github.com/BottlecapDave/HomeAssistant-OctopusEnergy) is
excellent.)

## How it works

The Home Mini reads your smart meter about every 10 seconds and uploads the readings to Octopus.
Octopus makes them available through its GraphQL API:

1. Exchange the API key for a short-lived token (`obtainKrakenToken`) — cached ~50 minutes.
2. Find the meter's device ID from the account (`account → electricityAgreements → meters`) — cached.
3. Each refresh asks for `smartMeterTelemetry(deviceId)` → latest `demand` (watts), `readAt`
   and `consumption` (the meter's running total).
4. Every 30 minutes (and at midnight) it also asks for
   `smartMeterTelemetry(deviceId, grouping: HALF_HOURLY, start: midnight)`; the sum of
   `consumptionDelta` is today's usage. In between, today = that sum + how far the running total
   has moved since. This keeps it to one request per refresh.
5. Every 30 minutes, the same half-hourly query on the **gas** meter's device ID gives today's gas.

## Privacy

Your API key is stored only on your own PC (`Settings.inc`) and phone (private app storage) and is
sent only to `api.octopus.energy`. There's no server, analytics or tracking.

If you fork this repo, **don't commit `Settings.inc` with your key in it.**

## Building it yourself

- **Android:** open the `android` folder in Android Studio and build. If it reports an invalid
  Gradle JDK, choose *Use Embedded JDK*. Needs Android Studio with Android Gradle Plugin 9.4
  support and SDK 37. The only libraries are Google's AppFunctions (for Gemini) and Kotlin
  coroutines; networking and JSON use Android's built-in classes.
- **CI:** every push builds both files (see the *Actions* tab). Pushing a tag like `v1.0.0`
  publishes a Release. To sign releases with your own key (so updates install over old versions),
  add repository secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`
  and `SIGNING_KEY_PASSWORD`.

### Testing the Gemini functions

On an Android 16+ phone with USB debugging:

```sh
# Check they're registered
adb shell cmd app_function list-app-functions | grep -A 10 uk.octopuslive.app

# Run one
adb shell "cmd app_function execute-app-function --package uk.octopuslive.app \
  --function 'uk.octopuslive.BaseEnergyAppFunctionService#getLiveEnergyUsage' --parameters '{}'"
```

The other two are `#getGasStatus` and `#getCheapestElectricityTimes`. Google's
[AppFunctions testing agent](https://github.com/android/appfunctions) can try them with
natural-language prompts.

## Licence

MIT — see [LICENSE](LICENSE). Octopus Energy and Home Mini are trademarks of Octopus Energy Ltd.
