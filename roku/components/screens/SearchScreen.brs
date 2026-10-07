sub init()
    m.keyboard = m.top.findNode("keyboard")
    m.results = m.top.findNode("results")
    m.hint = m.top.findNode("hint")
    m.debounce = m.top.findNode("debounce")
    m.keyboard.observeField("text", "onText")
    m.debounce.observeField("fire", "doSearch")
    m.results.observeField("itemSelected", "onSelected")
    m.top.observeField("active", "onActive")
    m.resultData = []   ' one entry per row: { kind: "result" | "search" | "clear" | "game", ... }
    m.reply = invalid
    m.gamesReply = invalid
    m.lastFocus = m.keyboard
    showSuggestions()
end sub

sub onActive()
    if m.top.active then
        m.lastFocus.setFocus(true)
        ' Refresh "time left" (or the suggestions) when returning.
        if m.keyboard.text.Trim() <> "" then doSearch() else showSuggestions()
    end if
end sub

sub onText()
    m.debounce.control = "stop"
    m.debounce.control = "start"
end sub

sub doSearch()
    q = m.keyboard.text.Trim()
    if Len(q) < 2 then
        showSuggestions()
        return
    end if
    m.hint.text = "Searching..."
    m.reply = svcCall({ type: "search", query: q }, "onResults")
end sub

' Before typing: recent searches, then games on now.
sub showSuggestions()
    m.reply = invalid
    rows = []
    for each q in recentSearches()
        rows.Push({ kind: "search", query: q, title: q, sub: "Recent search" })
    end for
    if rows.Count() > 0 then rows.Push({ kind: "clear", title: "Clear recent searches", sub: "" })
    showRows(rows)
    if rows.Count() > 0 then m.hint.text = "Recent searches" else m.hint.text = "Type at least 2 letters"
    m.gamesReply = svcCall({ type: "list", category: "__sports__", start: 0, count: 6 }, "onGames")
end sub

sub onGames(event as object)
    if m.gamesReply = invalid or not event.getRoSGNode().isSameNode(m.gamesReply) then return
    m.gamesReply = invalid
    if Len(m.keyboard.text.Trim()) >= 2 then return ' typed on
    items = event.getData().items
    if items = invalid or items.Count() = 0 then return
    rows = []
    for each r in m.resultData
        if r.kind <> "game" then rows.Push(r)
    end for
    for each it in items
        label = it[7]
        if label = invalid or label = "" then label = it[1]
        rows.Push({ kind: "game", key: it[0], title: label, sub: it[1] + "   |   Game on now" })
    end for
    showRows(rows)
    if recentSearches().Count() > 0 then
        m.hint.text = "Recent searches and games on now"
    else
        m.hint.text = "Games on now - or type at least 2 letters"
    end if
end sub

sub showRows(rows as object)
    root = CreateObject("roSGNode", "ContentNode")
    for each r in rows
        root.CreateChild("ContentNode").setFields({ title: r.title, shortDescriptionLine1: r.sub, description: "" })
    end for
    m.results.content = root
    m.resultData = rows
    if rows.Count() = 0 and m.results.isInFocusChain() then
        m.keyboard.setFocus(true)
        m.lastFocus = m.keyboard
    end if
end sub

sub onResults(event as object)
    if m.reply = invalid or not event.getRoSGNode().isSameNode(m.reply) then return
    m.reply = invalid
    res = event.getData()
    list = res.results
    if list = invalid then list = []
    root = CreateObject("roSGNode", "ContentNode")
    rows = []
    for each r in list
        left = ""
        if r.endTime > 0 then left = minsLeftText(r.endTime)
        root.CreateChild("ContentNode").setFields({
            id: r.key
            title: r.title
            shortDescriptionLine1: r.channel
            shortDescriptionLine2: r.group
            description: left
        })
        rows.Push({ kind: "result", key: r.key, group: r.group, query: res.query })
    end for
    m.results.content = root
    m.resultData = rows
    state = m.global.bus.epgState
    if list.Count() > 0 then
        m.hint.text = list.Count().ToStr() + " matches for " + chr(34) + res.query + chr(34)
    else if state = "loading" then
        m.hint.text = "The guide is still loading - try again in a moment"
    else if state = "none" or state = "error" then
        m.hint.text = "No guide data available (set a guide URL in Settings)"
    else
        m.hint.text = "Nothing on now matches " + chr(34) + res.query + chr(34)
    end if
end sub

sub onSelected()
    r = m.resultData[m.results.itemSelected]
    if r = invalid then return
    if r.kind = "search" then
        m.keyboard.text = r.query
        m.keyboard.setFocus(true)
        m.lastFocus = m.keyboard
    else if r.kind = "clear" then
        regWrite("searches", "[]")
        showSuggestions()
    else if r.kind = "game" then
        m.top.navigate = { action: "push", screen: "PlayerScreen", params: { category: "__sports__", key: r.key } }
    else
        addSearchQuery(r.query)
        m.top.navigate = { action: "push", screen: "PlayerScreen", params: { category: r.group, key: r.key } }
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "right" and m.keyboard.isInFocusChain() then
        if m.resultData.Count() > 0 then
            m.results.setFocus(true)
            m.lastFocus = m.results
        end if
        return true
    else if key = "left" and m.results.isInFocusChain() then
        m.keyboard.setFocus(true)
        m.lastFocus = m.keyboard
        return true
    end if
    return false
end function
