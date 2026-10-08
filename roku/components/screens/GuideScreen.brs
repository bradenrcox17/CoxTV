' EPG grid: a fixed pool of rows/cells is re-rendered on each key press, and programme
' data is fetched from DataService a page of channels at a time, so huge categories
' scroll as fast as small ones.

sub init()
    m.ROW_COUNT = 7
    m.ROW_H = 100
    m.TOP_Y = 310
    m.CH_X = 60
    m.CH_W = 380
    m.GRID_X = 456
    m.GRID_W = 1404
    m.SLOT = 1800
    m.WIN = 7200
    m.PAGE = 25
    m.pps = m.GRID_W / m.WIN

    m.COLOR_PANEL = "0x141416FF"
    m.COLOR_AIRING = "0x1E1E21FF"
    m.COLOR_FOCUS = "0xFF8200FF"
    m.COLOR_ROW = "0x3A2410FF"

    m.title = m.top.findNode("title")
    m.meta = m.top.findNode("meta")
    m.desc = m.top.findNode("desc")
    m.clock = m.top.findNode("clock")
    m.categoryLabel = m.top.findNode("category")
    m.day = m.top.findNode("day")
    m.nowLine = m.top.findNode("nowLine")
    m.empty = m.top.findNode("empty")
    m.clockTimer = m.top.findNode("clockTimer")
    m.clockTimer.observeField("fire", "onClockTick")

    now = nowSecs()
    m.minStart = floorSlot(now) - m.SLOT * 2
    m.maxEnd = now + 9 * 3600
    m.winStart = floorSlot(now)
    m.focusTime = now
    m.focusRow = 0
    m.topRow = 0
    m.count = 0
    m.channels = invalid
    m.cache = {}
    m.pages = {}
    m.category = "__all__"
    m.listReply = invalid

    buildRuler()
    buildRows()
    m.clock.text = fmtClock(now)

    m.top.observeField("params", "onParams")
    m.top.observeField("active", "onActive")
    m.top.observeField("closed", "onClosed")
    m.global.bus.observeFieldScoped("epgVersion", "onEpgChanged")
    m.clockTimer.control = "start"
end sub

function floorSlot(t as integer) as integer
    return t - (t mod 1800)
end function

sub onParams()
    p = m.top.params
    if p.category <> invalid then m.category = p.category
    m.focusKey = p.key
    m.categoryLabel.text = categoryLabel(m.category)
    m.listReply = svcCall({ type: "list", category: m.category }, "onList")
end sub

sub onActive()
    if m.top.active then m.top.setFocus(true)
end sub

sub onClosed()
    m.clockTimer.control = "stop"
    m.global.bus.unobserveFieldScoped("epgVersion")
end sub

sub onList(event as object)
    if m.listReply = invalid or not event.getRoSGNode().isSameNode(m.listReply) then return
    m.listReply = invalid
    content = listToContent(event.getData().items)
    if (content = invalid or content.getChildCount() = 0) and m.category <> "__all__" then
        m.category = "__all__"
        m.categoryLabel.text = categoryLabel(m.category)
        m.listReply = svcCall({ type: "list", category: m.category }, "onList")
        return
    end if
    m.channels = content
    m.count = 0
    if content <> invalid then m.count = content.getChildCount()
    if m.count = 0 then
        m.empty.text = "No channels"
        return
    end if
    m.empty.visible = false
    if m.focusKey <> invalid then
        for i = 0 to m.count - 1
            if content.getChild(i).id = m.focusKey then
                m.focusRow = i
                exit for
            end if
        end for
    end if
    m.topRow = m.focusRow - 2
    clampTop()
    ensureData()
    render()
end sub

sub onEpgChanged()
    m.cache = {}
    m.pages = {}
    ensureData()
    render()
end sub

sub onClockTick()
    m.clock.text = fmtClock(nowSecs())
    if m.count > 0 then render()
end sub

' ---------------------------------------------------------------- building

sub buildRuler()
    ruler = m.top.findNode("ruler")
    m.rulerLabels = []
    for i = 0 to 3
        lbl = ruler.createChild("Label")
        lbl.translation = [m.GRID_X + i * (m.GRID_W / 4) + 8, 258]
        lbl.width = m.GRID_W / 4 - 16
        lbl.font = coxFont("regular", 28)
        lbl.color = "0xA3A3A8FF"
        m.rulerLabels.Push(lbl)
    end for
end sub

sub buildRows()
    rowsGroup = m.top.findNode("rows")
    m.rows = []
    for r = 0 to m.ROW_COUNT - 1
        g = rowsGroup.createChild("Group")
        g.translation = [0, m.TOP_Y + r * m.ROW_H]
        bg = g.createChild("Rectangle")
        bg.translation = [m.CH_X, 0]
        bg.width = m.CH_W
        bg.height = m.ROW_H - 8
        bg.color = m.COLOR_PANEL
        num = g.createChild("Label")
        num.translation = [m.CH_X + 12, 0]
        num.width = 70
        num.height = m.ROW_H - 8
        num.vertAlign = "center"
        num.font = coxFont("mono", 28)
        num.color = "0xA3A3A8FF"
        name = g.createChild("Label")
        name.translation = [m.CH_X + 90, 0]
        name.width = m.CH_W - 100
        name.height = m.ROW_H - 8
        name.vertAlign = "center"
        name.font = coxFont("semibold", 28)
        name.color = "0xEDEDEDFF"
        cells = g.createChild("Group")
        m.rows.Push({ group: g, bg: bg, num: num, name: name, cellGroup: cells, cells: [] })
    end for
end sub

function getCell(row as object, i as integer) as object
    while row.cells.Count() <= i
        rect = row.cellGroup.createChild("Rectangle")
        rect.height = m.ROW_H - 8
        lbl = rect.createChild("Label")
        lbl.translation = [14, 0]
        lbl.height = m.ROW_H - 8
        lbl.vertAlign = "center"
        lbl.font = coxFont("regular", 28)
        lbl.color = "0xEDEDEDFF"
        row.cells.Push({ rect: rect, label: lbl })
    end while
    return row.cells[i]
end function

' ---------------------------------------------------------------- rendering

sub render()
    winEnd = m.winStart + m.WIN
    now = nowSecs()
    for i = 0 to 3
        m.rulerLabels[i].text = fmtClock(m.winStart + i * m.SLOT)
    end for
    m.day.text = fmtDay(m.winStart)
    if now >= m.winStart and now < winEnd then
        m.nowLine.translation = [m.GRID_X + (now - m.winStart) * m.pps, m.TOP_Y - 6]
        m.nowLine.visible = true
    else
        m.nowLine.visible = false
    end if

    focusedProg = invalid
    for r = 0 to m.ROW_COUNT - 1
        row = m.rows[r]
        idx = m.topRow + r
        if m.channels = invalid or idx >= m.count then
            row.group.visible = false
        else
            row.group.visible = true
            ch = m.channels.getChild(idx)
            isFocusRow = (idx = m.focusRow)
            row.num.text = ch.shortDescriptionLine2
            row.name.text = ch.title
            if isFocusRow then
                row.bg.color = m.COLOR_ROW
            else
                row.bg.color = m.COLOR_PANEL
            end if
            progs = m.cache[ch.id]
            used = 0
            if progs <> invalid then
                for each p in progs
                    if p[1] > m.winStart and p[0] < winEnd then
                        focused = isFocusRow and p[0] <= m.focusTime and p[1] > m.focusTime
                        if focused then focusedProg = p
                        drawCell(row, used, p[0], p[1], p[2], focused, p[0] <= now and p[1] > now)
                        used = used + 1
                    end if
                end for
            end if
            if used = 0 then
                label = "No information"
                if progs = invalid then label = "Loading..."
                drawCell(row, 0, m.winStart, winEnd, label, isFocusRow, false)
                used = 1
            end if
            for k = used to row.cells.Count() - 1
                row.cells[k].rect.visible = false
            end for
        end if
    end for
    updateDetails(focusedProg)
end sub

sub drawCell(row as object, i as integer, s as integer, e as integer, text as string, focused as boolean, airing as boolean)
    cell = getCell(row, i)
    winEnd = m.winStart + m.WIN
    x0 = s
    if x0 < m.winStart then x0 = m.winStart
    x1 = e
    if x1 > winEnd then x1 = winEnd
    w = (x1 - x0) * m.pps - 4
    if w < 2 then w = 2
    cell.rect.translation = [m.GRID_X + (x0 - m.winStart) * m.pps, 0]
    cell.rect.width = w
    if focused then
        cell.rect.color = m.COLOR_FOCUS
    else if airing then
        cell.rect.color = m.COLOR_AIRING
    else
        cell.rect.color = m.COLOR_PANEL
    end if
    prefix = ""
    if s < m.winStart then prefix = "< "
    cell.label.text = prefix + text
    if w > 40 then
        cell.label.width = w - 24
        cell.label.visible = true
    else
        cell.label.visible = false
    end if
    cell.rect.visible = true
end sub

sub updateDetails(p as dynamic)
    if m.channels = invalid or m.count = 0 then return
    ch = m.channels.getChild(m.focusRow)
    chText = ch.shortDescriptionLine2 + "  " + ch.title
    if p = invalid then
        m.title.text = ch.title
        m.meta.text = chText
        m.desc.text = "No program information"
    else
        m.title.text = p[2]
        m.meta.text = chText + "   |   " + fmtDay(p[0]) + "  " + fmtRange(p[0], p[1])
        m.desc.text = p[3]
    end if
end sub

' ---------------------------------------------------------------- data

' Requests EPG for the pages of channels around the visible rows (plus one ahead).
sub ensureData()
    if m.channels = invalid or m.count = 0 then return
    first = m.topRow \ m.PAGE
    last = (m.topRow + m.ROW_COUNT) \ m.PAGE + 1
    for page = first to last
        startIdx = page * m.PAGE
        if startIdx < m.count and not m.pages.DoesExist(page.ToStr()) then
            m.pages[page.ToStr()] = true
            endIdx = startIdx + m.PAGE - 1
            if endIdx >= m.count then endIdx = m.count - 1
            keys = []
            for i = startIdx to endIdx
                keys.Push(m.channels.getChild(i).id)
            end for
            svcCall({ type: "window", keys: keys, startTime: m.minStart, endTime: m.maxEnd }, "onWindow")
        end if
    end for
end sub

sub onWindow(event as object)
    progs = event.getData().programs
    if progs = invalid then return
    for each k in progs
        m.cache[k] = progs[k]
    end for
    render()
end sub

function programAt(progs as dynamic, t as integer) as dynamic
    if progs = invalid then return invalid
    for each p in progs
        if p[0] <= t and p[1] > t then return p
    end for
    return invalid
end function

' ---------------------------------------------------------------- navigation

sub clampTop()
    if m.focusRow < m.topRow then m.topRow = m.focusRow
    if m.focusRow >= m.topRow + m.ROW_COUNT then m.topRow = m.focusRow - m.ROW_COUNT + 1
    maxTop = m.count - m.ROW_COUNT
    if maxTop < 0 then maxTop = 0
    if m.topRow > maxTop then m.topRow = maxTop
    if m.topRow < 0 then m.topRow = 0
end sub

sub moveRow(delta as integer)
    m.focusRow = m.focusRow + delta
    if m.focusRow < 0 then m.focusRow = 0
    if m.focusRow >= m.count then m.focusRow = m.count - 1
    clampTop()
    ensureData()
    render()
end sub

sub moveTime(dir as integer)
    ch = m.channels.getChild(m.focusRow)
    progs = m.cache[ch.id]
    cur = programAt(progs, m.focusTime)
    if dir > 0 then
        if cur <> invalid then
            t = cur[1]
        else
            t = m.focusTime + m.SLOT
        end if
        if t >= m.maxEnd then return
        m.focusTime = t
        while m.focusTime >= m.winStart + m.WIN
            m.winStart = m.winStart + m.SLOT
        end while
    else
        if cur <> invalid then
            t = cur[0] - 1
        else
            t = m.focusTime - m.SLOT
        end if
        if t < m.minStart then return
        prev = programAt(progs, t)
        if prev <> invalid then t = prev[0]
        if t < m.minStart then t = m.minStart
        m.focusTime = t
        if m.focusTime < m.winStart then m.winStart = floorSlot(m.focusTime)
        if m.winStart < m.minStart then m.winStart = m.minStart
    end if
    ' Keep the time cursor inside the visible window so up/down stays intuitive.
    if m.focusTime < m.winStart then m.focusTime = m.winStart
    render()
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "back" then return false
    if m.count = 0 then return true
    if key = "up" then
        moveRow(-1)
    else if key = "down" then
        moveRow(1)
    else if key = "rewind" then
        moveRow(-m.ROW_COUNT)
    else if key = "fastforward" then
        moveRow(m.ROW_COUNT)
    else if key = "right" then
        moveTime(1)
    else if key = "left" then
        moveTime(-1)
    else if key = "OK" or key = "play" then
        ch = m.channels.getChild(m.focusRow)
        m.top.navigate = { action: "push", screen: "PlayerScreen", params: { category: m.category, key: ch.id, content: m.channels } }
    else if key = "replay" then
        ' Jump back to now.
        m.winStart = floorSlot(nowSecs())
        m.focusTime = nowSecs()
        render()
    end if
    return true
end function
