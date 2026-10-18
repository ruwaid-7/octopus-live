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
>
> **Built with AI help.** Much of the code was written with [Claude](https://claude.ai), Anthropic's
> AI assistant. The design, testing and decisions are mine. Everything is open source, so you can
> read exactly what it does before you install it.

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
2. Open **Octopus Live** → **Settings**, paste your API key, tap **Save**, and allow notifications.
   The app has four tabs: **Home** (live dashboard), **Usage** (costs and history), **Alerts** and **Settings**.
3. **Widget:** long-press your home screen → **Widgets** → **Octopus Live**, and drag it on.
   It updates every minute while the live notification is running (otherwise every 30 minutes).
   Tap it to refresh, or turn on *Widget opens the app* in Settings to open the app instead.

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

**Gas warning:** with *Gas left on* switched on in **Alerts** (on by
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
widget and gas warning work with the phone locked). In **Settings → Background**, tap
**Unrestricted battery** and **Keep running when unused** until both say *On*.

**Warnings that stay on screen:** with *Keep warnings on screen* on (Alerts tab, on by default),
the gas-left-on and high-electricity warnings stay in your notifications until usage drops, and
come back if swiped away. Tap **Mute until off** to silence one until the gas goes off or usage
drops. Turn the setting off for one-off warnings you can dismiss.

**Tips**
- Samsung / Xiaomi / OnePlus phones can stop background apps. If the notification disappears, set
  *Settings → Apps → Octopus Live → Battery* to **Unrestricted**.
- On Android 14+ you can swipe the notification away; tap the widget or turn on **Live updates** in Settings to bring it back.

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

**Most people don't need anything else:** the Android app (or the Rainmeter skin) talks to
Octopus directly with just your API key. Add a relay only if you want several devices, 10-second
updates, or Home Assistant.

| Setup | Best for | What talks to Octopus |
|---|---|---|
| **Just the app** (above) | One phone, or phone + PC | Each device directly, once a minute |
| **Free Cloudflare Worker** | Several devices, no server, works anywhere | The Worker, once a minute |
| **Home server relay** | You already run a Linux box / Docker | Your server, once a minute |

Both relays are set up **in your browser**: open the relay's address, paste your API key and choose
a username and password. You then sign in to that page with them to change the key, make a new device
link or change the password — no terminal needed. Your devices get a separate **device link** that
can only read your usage: tap **Open in Octopus Live** on your phone (or scan the QR code shown on a
computer) and the app fills it in for you.

## Optional: free Cloudflare Worker (no server needed)

The [`worker`](worker) folder is the relay running on Cloudflare's free plan — nothing to install or
keep running, and you get an `https://` address that works at home and away.

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/ruwaid-7/octopus-live/tree/main/worker)

1. Click **Deploy to Cloudflare** and sign in (a free account is fine). It copies this folder into
   your own GitHub account and deploys it. There's nothing to fill in.
2. Open the address it shows, e.g. `https://octopus-live-worker.yourname.workers.dev`, **straight
   away** (until you do, anyone who guesses the address could set it up), paste your API key,
   choose a username and password and tap **Connect**.
3. Tap **Open in Octopus Live** on your phone, or scan the QR code with it. On the PC, copy the
   Rainmeter line into `Settings.inc`.

If the address doesn't load, open the Worker in the Cloudflare dashboard → **Domains** and make sure
the switch next to the **Production** `workers.dev` address is **on**. It only calls Octopus when a
device asks, at most once a minute — well inside Cloudflare's free allowance.

**Connected the repository yourself instead of using the button?** In the Worker's **Settings →
Build**, set **Root directory** to `/worker` and **Deploy command** to `npx wrangler deploy`, then
redeploy. **Settings → Bindings** should list a Durable Object called `RELAY`.

## Optional: one home server for all your devices

If you have an always-on Linux box (Ubuntu server, Raspberry Pi…), run the small **relay** in the
[`server`](server) folder. It calls Octopus once for everyone and your devices read from it.

**Install (Ubuntu/Debian, Python 3.8+):**
```bash
git clone https://github.com/ruwaid-7/octopus-live.git
cd octopus-live/server
sudo ./install.sh
```
Then open the address it prints (e.g. `http://192.168.1.50:8765/`), paste your API key and choose a
username and password. Logs:
`journalctl -u octopus-relay -f`. To update later: `git pull && sudo ./install.sh`.

**Or with Docker:** `cd octopus-live/server && sudo docker compose up -d`, then open
`http://SERVER-IP:8765/`. Settings are kept in `server/data`.

**Use it from anywhere with a Cloudflare tunnel** (no VPN needed on your phone): in Cloudflare
**Zero Trust → Networks → Tunnels → your tunnel → Routes → Add route → Published application**,
pick a subdomain (e.g. `energy`) and set the service URL to `http://octopus-relay:8765` if
cloudflared runs in Docker on the same network as the relay, or `http://SERVER-LAN-IP:8765`
otherwise. Set the relay up **before** adding the route. Then open `https://energy.yourdomain/`.

**Upgrading from 1.6 or earlier?** Your existing key and token are kept; the first time you open your
old link (with `?token=…`) the page asks you to create a username and password. With Docker, recreate the
container with a data folder so changes made on the page are saved:
```bash
sudo mkdir -p /opt/octopus-relay/data
sudo curl -fsSL -o /opt/octopus-relay/octopus_relay.py https://raw.githubusercontent.com/ruwaid-7/octopus-live/main/server/octopus_relay.py
sudo docker rm -f octopus-relay
sudo docker run -d --name octopus-relay --restart unless-stopped -p 8765:8765 --env-file /etc/octopus-relay.env -e DATA_DIR=/data -v /opt/octopus-relay/data:/data -v /opt/octopus-relay/octopus_relay.py:/app/relay.py:ro -v /usr/share/zoneinfo:/usr/share/zoneinfo:ro python:3.12-slim python -u /app/relay.py
```
(Add `--network YOUR_NETWORK` if cloudflared reaches it over a Docker network.)

### Forgot your password?

On the sign-in page, tap **Forgot username or password?**, enter your Octopus API key
([find it here](https://octopus.energy/dashboard/new/accounts/personal-details/api-access)) and
choose a new username and password. If you've made a new key on Octopus since, use the new one: it
works as long as it's for the same Octopus account, and it replaces the old key on the relay.

If that doesn't work (for example, the relay has never connected to Octopus):

- **Home server:** clear the password, then open your device link (`https://your-address/?token=…`)
  and the page asks you to create a new password (you can keep or change your username):
  ```bash
  # installer:  F=/var/lib/octopus-relay/settings.json     Docker:  F=/opt/octopus-relay/data/settings.json
  sudo python3 -c "import json,sys; f=sys.argv[1]; d=json.load(open(f)); d['passwordHash']=''; json.dump(d,open(f,'w'))" $F
  sudo systemctl restart octopus-relay      # or: sudo docker restart octopus-relay
  ```
  Forgot your username or lost the device link too? `sudo cat $F` shows them as `"username"` and `"token"`.
- **Cloudflare Worker:** in the dashboard, open the Worker → **Settings → Variables and Secrets →
  Add**, type **Secret**, name `ADMIN_TOKEN`, any long password as the value. Open
  `https://your-worker-address/?token=THAT_VALUE`, choose a username and new password, then delete the
  `ADMIN_TOKEN` secret again.

## Point your devices at a relay

Easiest: open your private link on the phone and tap **Open in Octopus Live**. Or by hand, with
the full address including `?token=…`:
- **Rainmeter:** in `Settings.inc` set `RelayUrl=https://…/?token=YOUR-TOKEN`, `Interval=15`, and
  you can clear `ApiKey`.
- **Android:** Settings → **Relay address**, set the refresh to 15 seconds, and tap Save.

If you also keep your API key on a device, it falls back to calling Octopus directly (at most once a
minute) when the relay can't be reached — e.g. a home-only server while you're out. The app's
costs, prices and history also use the API key.

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
