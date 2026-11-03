-- Chart.lua - draws today's 48 half-hour bars for the Chart skin, like the Android app's chart.
-- Reads the Slots line from data.txt (kWh per half-hour, from midnight up to now) and sets the
-- 48 rectangles of [MeterBars]. Colours: green < 0.5 kW, amber < 2 kW, red above (the half-hour's
-- average power). 3.4 kW fills the chart unless the day's peak is higher. Later half-hours are grey.

local last = nil

function Initialize()
    slots = SKIN:GetMeasure('MeasureSlots')
end

local function num(name, default)
    return tonumber(SKIN:GetVariable(name)) or default
end

function Update()
    local s = slots and slots:GetStringValue() or ''
    if s == last then return 0 end
    last = s

    local kwh = {}
    for v in string.gmatch(s, '[^,]+') do
        kwh[#kwh + 1] = tonumber(v) or 0
    end

    local x0, base, height = num('BarsX', 16), num('BarsBase', 118), num('BarsHeight', 78)
    local width, gap = num('BarWidth', 5), num('BarGap', 2)
    local green, amber, red = SKIN:GetVariable('ColorGreen'), SKIN:GetVariable('ColorAmber'), SKIN:GetVariable('ColorRed')
    local future = SKIN:GetVariable('ColorDivider')

    local peak = 0
    for i = 1, #kwh do
        if kwh[i] * 2 > peak then peak = kwh[i] * 2 end
    end
    local scale = math.max(3.4, peak)

    for i = 1, 48 do
        local h, colour
        local v = kwh[i]
        if v == nil then
            colour, h = future, height * 0.10
        else
            local kw = v * 2
            if kw < 0.5 then colour = green elseif kw < 2 then colour = amber else colour = red end
            h = math.min(height, math.max(height * 0.10, height * kw / scale))
        end
        h = math.floor(h + 0.5)
        local x = x0 + (i - 1) * (width + gap)
        local key = 'Shape'
        if i > 1 then key = 'Shape' .. i end
        -- Whole pixels only, so the numbers never depend on the PC's decimal separator
        SKIN:Bang('!SetOption', 'MeterBars', key,
            'Rectangle ' .. x .. ',' .. (base - h) .. ',' .. width .. ',' .. h .. ',1.5 | Fill Color ' .. colour .. ' | StrokeWidth 0')
    end
    SKIN:Bang('!UpdateMeter', 'MeterBars')
    SKIN:Bang('!Redraw')
    return 0
end
