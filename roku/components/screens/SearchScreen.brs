sub init()
    m.keyboard = m.top.findNode("keyboard")
    m.results = m.top.findNode("results")
    m.hint = m.top.findNode("hint")
    m.debounce = m.top.findNode("debounce")
    m.keyboard.observeField("text", "onText")
    m.debounce.observeField("fire", "doSearch")
    m.results.observeField("itemSelected", "onSelected")
    m.top.observeField("active", "onActive")
    m.resultData = []
    m.reply = invalid
    m.lastFocus = m.keyboard
end sub

sub onActive()
    if m.top.active then
        m.lastFocus.setFocus(true)
        if m.keyboard.text.Trim() <> "" then doSearch() ' refresh "time left" when returning
    end if
end sub

sub onText()
    m.debounce.control = "stop"
    m.debounce.control = "start"
end sub

sub doSearch()
    q = m.keyboard.text.Trim()
    if Len(q) < 2 then
        m.results.content = invalid
        m.resultData = []
        m.hint.text = "Type at least 2 letters"
        return
    end if
    m.hint.text = "Searching..."
    m.reply = svcCall({ type: "search", query: q }, "onResults")
end sub

sub onResults(event as object)
    if m.reply = invalid or not event.getRoSGNode().isSameNode(m.reply) then return
    m.reply = invalid
    res = event.getData()
    list = res.results
    if list = invalid then list = []
    root = CreateObject("roSGNode", "ContentNode")
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
    end for
    m.results.content = root
    m.resultData = list
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
    m.top.navigate = { action: "push", screen: "PlayerScreen", params: { category: r.group, key: r.key } }
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
