# Privacy policy

Last updated: 19 October 2026

Octopus Live is a free, open-source app made by an independent developer (ruwaid-7). This policy covers the Android app, the Windows Rainmeter skin and the optional relays (home server and Cloudflare Worker).

## The short version

- The developer doesn't collect, receive or store any of your data.
- There are no accounts, ads, analytics, trackers or crash reports.
- Your API key and readings stay on your own devices, and on your own relay if you set one up.

## What the app stores, and where

- Your Octopus API key, account number, relay link and settings are kept in the app's private storage on your phone. They're left out of Android backups.
- Recent readings, tariff prices and up to 7 days of daily totals are kept on your phone to show the dashboard, widget and history.
- Uninstalling the app, or clearing its storage, deletes all of it.

## Who the app talks to

- Octopus Energy (api.octopus.energy). Your API key is sent to Octopus to sign in, then the app reads your smart meter readings, account details and tariff. Octopus Energy's privacy policy covers what they do: https://octopus.energy/policies/privacy-policy/
- Your relay, if you add one. The app sends your device link and receives your live readings. A relay is software you run in your own Cloudflare account or on your own server. The developer has no access to it.
- Links you tap (Octopus, Cloudflare, GitHub) open in your browser under those sites' own policies.

The app doesn't send anything anywhere else.

## Relays

- A relay stores your API key, account number, device link, sign-in username and a salted hash of your password (never the password itself). The home server keeps these in a settings file on your server. The Cloudflare Worker keeps them in your own Cloudflare account, where Cloudflare's privacy policy also applies: https://www.cloudflare.com/privacypolicy/
- The relay's web page sets one cookie, ol_session, to keep you signed in for up to 30 days. It's strictly necessary for signing in, so it doesn't need consent. There are no other cookies or trackers. Signing out deletes it.
- Relay logs show short status messages, such as your account number and connection errors, never your API key or password. Cloudflare's Worker logs, visible only in your Cloudflare account, may record the addresses your devices used, including the device link.

## Permissions

- Internet: to reach Octopus or your relay.
- Notifications: to show your live watts in the status bar and your alerts.
- Foreground service, wake lock and start at boot: to keep live updates running.
- Battery optimisation: only if you choose to let the app run unrestricted.

## Gemini and other assistants

On Android 16 and later, the app offers a few functions (live usage, gas status, cheapest times) that an on-device assistant such as Gemini can use when you ask it. The answer is passed to that assistant, which handles it under its own privacy policy. The app never sends anything to an assistant by itself.

## Children

The app isn't aimed at children.

## Your choices and rights

Because the developer holds no personal data about you, there's nothing to request, correct or delete on our side. You stay in control: remove the app or clear its storage, delete your relay, or make a new API key in your Octopus account at any time, which stops the old one working. For data Octopus Energy holds about you, contact Octopus Energy.

## Changes

Any change is published here with a new date: https://github.com/ruwaid-7/octopus-live/blob/main/PRIVACY.md

## Contact

Questions or concerns: https://github.com/ruwaid-7/octopus-live/issues
