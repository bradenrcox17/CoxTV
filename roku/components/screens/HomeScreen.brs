sub init()
    m.side = m.top.findNode("side")
    m.list = m.top.findNode("channels")
    m.catTitle = m.top.findNode("catTitle")
    m.catCount = m.top.findNode("catCount")
    m.status = m.top.findNode("status")
    m.empty = m.top.findNode("empty")
    m.clock = m.top.findNode("clock")
    m.debounce = m.top.findNode("debounce")
    m.nowTimer = m.top.findNode("nowTimer")
    m.clockTimer = m.top.findNode("clockTimer")
    m.pageReply = invalid
    m.listTotal = 0

    m.side.observeField("itemFocused", "onSideFocused")
    m.side.observeField("itemSelected", "onSideSelected")
    m.list.observeField("itemSelected", "onChannelSelected")
    m.debounce.observeField("fire", "onDebounce")
    m.nowTimer.observeField("fire", "refreshNow")
    m.clockTimer.observeField("fire", "updateClock")
    m.top.observeField("active", "onActive")
    m.top.observeField("closed", "onClosed")

    svc = m.global.bus
    svc.observeFieldScoped("categories", "buildSide")
    svc.observeFieldScoped("playlistState", "onState")
    svc.observeFieldScoped("status", "onStatus")
    svc.observeFieldScoped("epgState", "onStatus")
    svc.observeFieldScoped("epgVersion", "refreshNow")
    svc.observeFieldScoped("prefsVersion", "onPrefsChanged") ' settings synced from another device

    m.sideKeys = []
    m.current = invalid
    m.pendingCat = invalid
    m.focusKey = ""
    m.focusListAfterLoad = false
    m.listReply = invalid
    m.nowReply = invalid
    m.lastFocus = m.side

    buildSide()
    updateClock()
    onStatus()
    onState()
    m.nowTimer.control = "start"
    m.clockTimer.control = "start"
end sub

sub onActive()
    if not m.top.active then return
    m.lastFocus.setFocus(true)
    buildSide()
    if m.current <> invalid and not hasGroup(m.current) then
        ' Turned off in Settings while we were away.
        m.lastFocus = m.side
        m.side.setFocus(true)
        loadCategory(firstCategory())
    else if isLiveCategory(m.current) then
        loadCategory(m.current) ' these change while you watch
    else
        refreshNow()
    end if
end sub

sub onClosed()
    svc = m.global.bus
    svc.unobserveFieldScoped("categories")
    svc.unobserveFieldScoped("playlistState")
    svc.unobserveFieldScoped("status")
    svc.unobserveFieldScoped("epgState")
    svc.unobserveFieldScoped("epgVersion")
    svc.unobserveFieldScoped("prefsVersion")
    m.nowTimer.control = "stop"
    m.clockTimer.control = "stop"
end sub

sub updateClock()
    m.clock.text = fmtClock(nowSecs())
end sub

sub buildSide()
    keys = ["#search", "#guide", "#settings"]
    labels = ["Search what's on", "TV Guide", "Settings"]
    counts = {}
    counts.SetModeCaseSensitive()
    cats = m.global.bus.categories
    if cats <> invalid then
        for each c in cats
            counts[c.name] = c.count
        end for
    end if
    ' Only the categories turned on in Settings, in the user's order.
    for each k in categoryOrder()
        if k = "__fav__" then
            keys.Push(k)
            labels.Push("Favorites  (" + favKeys().Count().ToStr() + ")")
        else if k = "__all__" then
            keys.Push(k)
            labels.Push("All Channels  (" + m.global.bus.channelCount.ToStr() + ")")
        else if k = "__recent__" then
            keys.Push(k)
            labels.Push("Recent")
        else if k = "__sports__" or k = "__cfb__" or Left(k, 11) = "__league__:" then
            keys.Push(k)
            labels.Push(categoryLabel(k))
        else if counts.DoesExist(k) then
            keys.Push(k)
            labels.Push(k + "  (" + counts[k].ToStr() + ")")
        end if
    end for
    if keys.Count() = 3 then
        keys.Push("__all__")
        labels.Push("All Channels  (" + m.global.bus.channelCount.ToStr() + ")")
    end if
    root = CreateObject("roSGNode", "ContentNode")
    for each label in labels
        root.CreateChild("ContentNode").title = label
    end for
    focused = m.side.itemFocused
    m.side.content = root
    m.sideKeys = keys
    if focused > 0 and focused < keys.Count() then m.side.jumpToItem = focused
end sub

sub onStatus()
    svc = m.global.bus
    text = svc.status
    if text = "" then
        if svc.epgState = "ready" then
            text = "Guide loaded"
        else if svc.epgState = "none" then
            text = "No guide URL set"
        else if svc.epgState = "error" then
            text = "Guide unavailable"
        end if
    end if
    m.status.text = text
end sub

sub onState()
    state = m.global.bus.playlistState
    if state = "loading" or state = "idle" then
        showEmpty("Loading channels...")
    else if state = "error" then
        showEmpty("Couldn't load the playlist." + chr(10) + m.global.bus.status + chr(10) + "Open Settings to check the URL.")
    else if state = "ready" then
        buildSide()
        if m.current = invalid then
            ' First load: last category watched, else Favorites if any, else All.
            last = regRead("lastCategory")
            hasFavs = favKeys().Count() > 0
            if last <> "" and hasGroup(last) and (last <> "__fav__" or hasFavs) then
                start = last
            else if hasFavs and hasGroup("__fav__") then
                start = "__fav__"
            else
                start = firstCategory()
            end if
            m.focusKey = regRead("lastKey")
            m.focusListAfterLoad = true
            for i = 0 to m.sideKeys.Count() - 1
                if m.sideKeys[i] = start then m.side.jumpToItem = i
            end for
            loadCategory(start)
        else
            loadCategory(m.current)
        end if
    end if
end sub

function hasGroup(name as string) as boolean
    for each k in m.sideKeys
        if k = name then return true
    end for
    return false
end function

' Category to open by default: All Channels, else the first playlist group, else whatever
' is first (not an empty Recent or Sports list).
function firstCategory() as string
    if hasGroup("__all__") then return "__all__"
    first = ""
    for each k in m.sideKeys
        if Left(k, 1) <> "#" then
            if not isBuiltInCategory(k) then return k
            if first = "" then first = k
        end if
    end for
    if first = "" then first = "__all__"
    return first
end function

sub showEmpty(text as string)
    m.empty.text = text
    m.empty.visible = true
end sub

sub onSideFocused()
    key = m.sideKeys[m.side.itemFocused]
    if key = invalid or Left(key, 1) = "#" then return
    m.pendingCat = key
    m.debounce.control = "stop"
    m.debounce.control = "start"
end sub

sub onDebounce()
    if m.pendingCat <> invalid and m.pendingCat <> m.current then loadCategory(m.pendingCat)
end sub

sub onSideSelected()
    key = m.sideKeys[m.side.itemSelected]
    if key = invalid then return
    if key = "#search" then
        m.top.navigate = { action: "push", screen: "SearchScreen" }
    else if key = "#guide" then
        cat = m.current
        if cat = invalid or cat = "__cfb__" then cat = "__all__"
        m.top.navigate = { action: "push", screen: "GuideScreen", params: { category: cat } }
    else if key = "#settings" then
        m.top.navigate = { action: "push", screen: "SetupScreen", params: { firstRun: false } }
    else if key <> m.current then
        m.focusListAfterLoad = true
        loadCategory(key)
    else
        focusList()
    end if
end sub

sub loadCategory(key as string)
    m.pageReply = invalid
    m.current = key
    m.pendingCat = invalid
    m.catTitle.text = categoryLabel(key)
    m.catCount.text = ""
    showEmpty("Loading...")
    ' First screenful only; the rest arrives page by page in the background.
    m.listReply = svcCall({ type: "list", category: key, start: 0, count: 200 }, "onList")
end sub

sub onList(event as object)
    if m.listReply = invalid or not event.getRoSGNode().isSameNode(m.listReply) then return
    m.listReply = invalid
    data = event.getData()
    items = data.items
    if items = invalid then items = []
    n = 0
    if data.listTotal <> invalid then n = data.listTotal
    content = CreateObject("roSGNode", "ContentNode")
    appendContentRows(content, items, 0, items.Count())
    m.list.content = content
    m.listTotal = n
    total = data.total
    if m.current = "__all__" and total <> invalid and total > n then
        m.catCount.text = "Showing " + n.ToStr() + " of " + total.ToStr() + " - open a category for all"
    else if m.current = "__cfb__" then
        m.catCount.text = n.ToStr() + " games  -  press * on a game to star your teams"
    else if m.current = "__sports__" or Left(m.current, 11) = "__league__:" then
        m.catCount.text = n.ToStr() + " live games"
    else
        m.catCount.text = n.ToStr() + " channels"
    end if
    if n = 0 then
        if m.current = "__fav__" then
            showEmpty("No favorites yet." + chr(10) + "Press * on a channel to add it.")
        else if m.current = "__recent__" then
            showEmpty("Channels you watch will show up here.")
        else if m.current = "__sports__" then
            showEmpty("No live games right now." + chr(10) + "(Games are found in the TV guide once it has loaded.)")
        else if Left(m.current, 11) = "__league__:" then
            showEmpty("No live " + Mid(m.current, 12) + " games right now.")
        else if m.current = "__cfb__" then
            if regRead("deviceToken") = "" then
                showEmpty("The College Football guide comes from tv.thecoxhome.com." + chr(10) + "Set this Roku up with a code (Settings > Enter setup code) to see it.")
            else
                showEmpty("No college football games this week.")
            end if
        else
            showEmpty("No channels in this category.")
        end if
        if m.lastFocus.isSameNode(m.list) then
            m.lastFocus = m.side
            if m.top.active then m.side.setFocus(true)
        end if
        return
    end if
    m.empty.visible = false
    tryFocusKey(content, 0)
    if m.restoreIndex <> invalid then
        ' After hiding a channel: stay at the same spot in the list.
        ri = m.restoreIndex
        m.restoreIndex = invalid
        if ri >= content.getChildCount() then ri = content.getChildCount() - 1
        if ri > 0 then m.list.jumpToItem = ri
    end if
    if m.focusListAfterLoad then
        m.focusListAfterLoad = false
        focusList()
    end if
    requestNextPage()
end sub

sub requestNextPage()
    content = m.list.content
    if content = invalid then return
    loaded = content.getChildCount()
    if loaded >= m.listTotal then
        m.pageReply = invalid
        m.focusKey = ""
        return
    end if
    m.pageReply = svcCall({ type: "list", category: m.current, start: loaded, count: 250 }, "onPage")
end sub

sub onPage(event as object)
    if m.pageReply = invalid or not event.getRoSGNode().isSameNode(m.pageReply) then return
    m.pageReply = invalid
    data = event.getData()
    content = m.list.content
    if content = invalid or data.items = invalid or data.category <> m.current or data.start <> content.getChildCount() then return
    appendContentRows(content, data.items, 0, data.items.Count())
    tryFocusKey(content, data.start)
    requestNextPage()
end sub

' Jump to the last-watched channel once its page has loaded.
sub tryFocusKey(content as object, fromIndex as integer)
    if m.focusKey = "" then return
    for i = fromIndex to content.getChildCount() - 1
        if content.getChild(i).id = m.focusKey then
            m.list.jumpToItem = i
            m.focusKey = ""
            return
        end if
    end for
end sub
sub focusList()
    if m.list.content <> invalid and m.list.content.getChildCount() > 0 then
        m.lastFocus = m.list
        ' Never steal focus while another screen (e.g. the resumed player) is on top.
        if m.top.active then m.list.setFocus(true)
    end if
end sub

sub onChannelSelected()
    content = m.list.content
    if content = invalid then return
    ch = content.getChild(m.list.itemSelected)
    if ch = invalid then return
    if m.current = "__cfb__" then
        ' A game: play the channel showing it (channel up/down then goes through all channels).
        if ch.id = "" then
            m.catCount.text = "No channel is showing this game yet"
        else
            m.top.navigate = { action: "push", screen: "PlayerScreen", params: { category: "__all__", key: ch.id } }
        end if
        return
    end if
    m.top.navigate = { action: "push", screen: "PlayerScreen", params: { category: m.current, key: ch.id, content: content } }
end sub

' Refresh "now playing" on the visible list in place (no rebuild, keeps focus).
sub refreshNow()
    content = m.list.content
    if content = invalid or content.getChildCount() = 0 then return
    n = content.getChildCount()
    if n > 600 then n = 600
    keys = []
    for i = 0 to n - 1
        keys.Push(content.getChild(i).id)
    end for
    m.nowReply = svcCall({ type: "now", keys: keys }, "onNow")
end sub

sub onNow(event as object)
    if m.nowReply = invalid or not event.getRoSGNode().isSameNode(m.nowReply) then return
    m.nowReply = invalid
    now = event.getData().now
    content = m.list.content
    if content = invalid or now = invalid then return
    n = content.getChildCount()
    if n > 600 then n = 600
    for i = 0 to n - 1
        node = content.getChild(i)
        v = now[node.id]
        if v <> invalid then
            if node.description <> v[0] or node.length <> v[2] then node.setFields({ description: v[0], playStart: v[1], length: v[2] })
        else if node.description <> "" then
            node.setFields({ description: "", playStart: 0, length: 0 })
        end if
    end for
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "right" and m.side.isInFocusChain() then
        focusList()
        return true
    else if key = "left" and m.list.isInFocusChain() then
        m.side.setFocus(true)
        m.lastFocus = m.side
        return true
    else if key = "options" and m.list.isInFocusChain() then
        content = m.list.content
        if content <> invalid then
            ch = content.getChild(m.list.itemFocused)
            if ch <> invalid and m.current = "__cfb__" then
                showTeamOptions(m.list.itemFocused)
            else if ch <> invalid then
                showChannelOptions(ch)
            end if
        end if
        return true
    end if
    return false
end function

' ---------------------------------------------------------------- channel options (*)

sub showChannelOptions(ch as object)
    m.optionsFor = ch
    isFav = ch.starRating >= 100
    favLabel = "Add to favorites"
    if isFav then favLabel = "Remove from favorites"
    dlg = CreateObject("roSGNode", "StandardMessageDialog")
    if dlg = invalid then dlg = CreateObject("roSGNode", "Dialog") ' Roku OS < 10
    dlg.title = ch.title
    if dlg.hasField("message") then dlg.message = ["Hidden channels can be brought back in Settings > Hidden channels."]
    dlg.buttons = [favLabel, "Hide this channel", "Cancel"]
    dlg.observeFieldScoped("buttonSelected", "onOptionsButton")
    dlg.observeFieldScoped("wasClosed", "onOptionsClosed")
    m.optionsDialog = dlg
    m.top.getScene().dialog = dlg
end sub

sub onOptionsButton()
    dlg = m.optionsDialog
    ch = m.optionsFor
    if dlg = invalid or ch = invalid then return
    choice = dlg.buttonSelected
    closeOptions()
    if choice = 0 then
        if toggleFavorite(ch.id) then
            ch.starRating = 100
        else
            ch.starRating = 0
        end if
        buildSide()
        if m.current = "__fav__" then loadCategory("__fav__")
    else if choice = 1 then
        setHidden(ch.id, true)
        focusIndex = m.list.itemFocused
        loadCategory(m.current)
        m.restoreIndex = focusIndex
    end if
end sub

sub onOptionsClosed()
    closeOptions()
end sub

sub closeOptions()
    dlg = m.optionsDialog
    m.optionsDialog = invalid
    if dlg <> invalid then
        dlg.unobserveFieldScoped("buttonSelected")
        dlg.unobserveFieldScoped("wasClosed")
        dlg.close = true
    end if
    m.top.getScene().dialog = invalid
    if m.lastFocus <> invalid and m.top.active then m.lastFocus.setFocus(true)
end sub

' Lists that change while you watch (refreshed whenever this screen comes back).
function isLiveCategory(key as dynamic) as boolean
    if key = invalid then return false
    return key = "__fav__" or key = "__recent__" or key = "__sports__" or key = "__cfb__" or Left(key, 11) = "__league__:"
end function

' Favorites, categories, hidden channels or teams changed on another device.
sub onPrefsChanged()
    buildSide()
    if m.current <> invalid and not hasGroup(m.current) then
        loadCategory(firstCategory())
    else if isLiveCategory(m.current) then
        loadCategory(m.current)
    end if
end sub

' ---------------------------------------------------------------- your teams (* on a game)

sub showTeamOptions(index as integer)
    m.teamsReply = svcCall({ type: "cfbTeams", index: index }, "onTeams")
end sub

sub onTeams(event as object)
    if m.teamsReply = invalid or not event.getRoSGNode().isSameNode(m.teamsReply) then return
    m.teamsReply = invalid
    teams = event.getData().teams
    if teams = invalid or teams.Count() = 0 then return
    m.teamChoices = teams
    buttons = []
    for each t in teams
        if t.mine then buttons.Push("Remove " + t.display + " from your teams") else buttons.Push("Star " + t.display)
    end for
    buttons.Push("Done")
    dlg = CreateObject("roSGNode", "StandardMessageDialog")
    if dlg = invalid then dlg = CreateObject("roSGNode", "Dialog") ' Roku OS < 10
    dlg.title = "Your teams"
    if dlg.hasField("message") then dlg.message = ["Your teams' games are listed first, here, in the phone app and on tv.thecoxhome.com."]
    dlg.buttons = buttons
    dlg.observeFieldScoped("buttonSelected", "onTeamButton")
    dlg.observeFieldScoped("wasClosed", "onOptionsClosed")
    m.optionsDialog = dlg
    m.top.getScene().dialog = dlg
end sub

sub onTeamButton()
    dlg = m.optionsDialog
    if dlg = invalid or m.teamChoices = invalid then return
    choice = dlg.buttonSelected
    closeOptions()
    if choice >= 0 and choice < m.teamChoices.Count() then
        t = m.teamChoices[choice]
        toggleTeam(t.key, t.display)
        focusIndex = m.list.itemFocused
        m.syncReply = svcCall({ type: "syncNow" }, "onTeamsSynced")
        m.restoreIndex = focusIndex
    end if
end sub

sub onTeamsSynced(event as object)
    if m.syncReply = invalid or not event.getRoSGNode().isSameNode(m.syncReply) then return
    m.syncReply = invalid
    if m.current = "__cfb__" then loadCategory("__cfb__")
end sub
