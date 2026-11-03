; Shared by the Dial, Energy, Cost and Chart skins: runs the fetch script and reads data.txt.
; The script only fetches once per Interval, however many skins are open.

[MeasureTimer]
Measure=Calc
Formula=(MeasureTimer % #Interval#) + 1
IfCondition=MeasureTimer = 1
IfTrueAction=[!CommandMeasure MeasureRun "Run"]

[MeasureRun]
Measure=Plugin
Plugin=RunCommand
Program=powershell.exe
Parameter=-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "#@#Scripts\OctopusFetch.ps1"
State=Hide
Timeout=60000
FinishAction=[!CommandMeasure MeasureData "Update"]

[MeasureRunNow]
Measure=Plugin
Plugin=RunCommand
Program=powershell.exe
Parameter=-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "#@#Scripts\OctopusFetch.ps1" -Manual
State=Hide
Timeout=60000
FinishAction=[!CommandMeasure MeasureData "Update"]

; Re-read every 10 seconds as well, to pick up a fetch another skin made
[MeasureData]
Measure=WebParser
URL=file://#@#data.txt
CodePage=65001
RegExp=(?siU)Status=(.*)[\r\n]+Demand=(.*)[\r\n]+TodayKWh=(.*)[\r\n]+ReadAt=(.*)[\r\n]+Updated=(.*)[\r\n]+GasKWh=(.*)[\r\n]+GasAt=(.*)[\r\n]+GasKW=(.*)[\r\n]+ElecNum=(.*)[\r\n]+ElecUnit=(.*)[\r\n]+PerHour=(.*)[\r\n]+GasOn=(.*)[\r\n]+GasSub=(.*)[\r\n]+PriceNow=(.*)[\r\n]+PriceNote=(.*)[\r\n]+PriceCheaper=(.*)[\r\n]+CostTotal=(.*)[\r\n]+CostElec=(.*)[\r\n]+CostGas=(.*)[\r\n]+TodayLine=(.*)[\r\n]+Peak=(.*)[\r\n]+Slots=(.*)[\r\n]
UpdateRate=10
ForceReload=1
FinishAction=[!UpdateMeasureGroup Values][!UpdateMeterGroup Meters][!Redraw]

[MeasureStatus]
Measure=WebParser
URL=[MeasureData]
StringIndex=1
Group=Values

[MeasureDemand]
Measure=WebParser
URL=[MeasureData]
StringIndex=2
Group=Values

[MeasureTodayKWh]
Measure=WebParser
URL=[MeasureData]
StringIndex=3
Group=Values

[MeasureReadAt]
Measure=WebParser
URL=[MeasureData]
StringIndex=4
Group=Values

[MeasureUpdated]
Measure=WebParser
URL=[MeasureData]
StringIndex=5
Group=Values

[MeasureGasKWh]
Measure=WebParser
URL=[MeasureData]
StringIndex=6
Group=Values

[MeasureGasAt]
Measure=WebParser
URL=[MeasureData]
StringIndex=7
Group=Values

[MeasureGasKW]
Measure=WebParser
URL=[MeasureData]
StringIndex=8
Group=Values

[MeasureElecNum]
Measure=WebParser
URL=[MeasureData]
StringIndex=9
Group=Values

[MeasureElecUnit]
Measure=WebParser
URL=[MeasureData]
StringIndex=10
Group=Values

[MeasurePerHour]
Measure=WebParser
URL=[MeasureData]
StringIndex=11
Group=Values

[MeasureGasOn]
Measure=WebParser
URL=[MeasureData]
StringIndex=12
Group=Values

[MeasureGasSub]
Measure=WebParser
URL=[MeasureData]
StringIndex=13
Group=Values

[MeasurePriceNow]
Measure=WebParser
URL=[MeasureData]
StringIndex=14
Group=Values

[MeasurePriceNote]
Measure=WebParser
URL=[MeasureData]
StringIndex=15
Group=Values

[MeasurePriceCheaper]
Measure=WebParser
URL=[MeasureData]
StringIndex=16
Group=Values

[MeasureCostTotal]
Measure=WebParser
URL=[MeasureData]
StringIndex=17
Group=Values

[MeasureCostElec]
Measure=WebParser
URL=[MeasureData]
StringIndex=18
Group=Values

[MeasureCostGas]
Measure=WebParser
URL=[MeasureData]
StringIndex=19
Group=Values

[MeasureTodayLine]
Measure=WebParser
URL=[MeasureData]
StringIndex=20
Group=Values

[MeasurePeak]
Measure=WebParser
URL=[MeasureData]
StringIndex=21
Group=Values

[MeasureSlots]
Measure=WebParser
URL=[MeasureData]
StringIndex=22
Group=Values

; Live watts as a number, and the level colour: green < AmberWatts, amber < RedWatts, red above
[MeasureWatts]
Measure=Calc
Formula=MeasureDemand
Group=Values
IfCondition=MeasureWatts < #AmberWatts#
IfTrueAction=[!SetVariable ColorLevel "#ColorGreen#"]
IfCondition2=(MeasureWatts >= #AmberWatts#) && (MeasureWatts < #RedWatts#)
IfTrueAction2=[!SetVariable ColorLevel "#ColorAmber#"]
IfCondition3=MeasureWatts >= #RedWatts#
IfTrueAction3=[!SetVariable ColorLevel "#ColorRed#"]
IfConditionMode=1

[MeasureGasKWNum]
Measure=Calc
Formula=MeasureGasKW
Group=Values

