[Variables]
; ---- Octopus Live settings ----
; After editing, right-click the skin and choose "Refresh skin".

; Your Octopus Energy API key. Find it at:
; https://octopus.energy/dashboard/new/accounts/personal-details/api-access
; It starts with sk_live_
ApiKey=

; Optional: address of your Octopus Live relay server (see the "server" folder on GitHub),
; e.g. http://192.168.1.50:8765  - the skin then reads from your server instead of Octopus,
; so you can use a short Interval like 15. If you also fill in ApiKey, the skin falls back
; to calling Octopus directly when the server can't be reached.
RelayUrl=

; Optional: your account number, e.g. A-1234ABCD. Leave blank to detect it automatically.
AccountNumber=

; Seconds between refreshes. Calling Octopus directly: 60 or more (90 if the phone app also
; calls Octopus directly). Using RelayUrl: 15 is fine - your server does the Octopus calls.
Interval=60

; Power (watts) that fills the bar completely, and the colour thresholds.
MaxWatts=4000
; Gas (kW) that fills the gas bar completely. A typical combi boiler runs at 10-30 kW.
MaxGasKW=10
AmberWatts=500
RedWatts=2000
