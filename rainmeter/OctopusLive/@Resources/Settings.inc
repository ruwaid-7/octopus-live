[Variables]
; ---- Octopus Live settings ----
; After editing, right-click the skin and choose "Refresh skin".

; Your Octopus Energy API key. Find it at:
; https://octopus.energy/dashboard/new/accounts/personal-details/api-access
; It starts with sk_live_
ApiKey=

; Optional: your account number, e.g. A-1234ABCD. Leave blank to detect it automatically.
AccountNumber=

; Seconds between refreshes. 60 is recommended - Octopus limits how often the API can be called.
; If you also run the Android app, keep both at 60 or more.
Interval=60

; Power (watts) that fills the bar completely, and the colour thresholds.
MaxWatts=4000
AmberWatts=500
RedWatts=2000
