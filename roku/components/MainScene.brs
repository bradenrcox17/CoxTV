sub init()
    m.top.backgroundColor = "0x0A0C10FF"
    m.top.backgroundUri = ""
    m.screens = m.top.findNode("screens")
    m.stack = []
    m.resumePending = false

    ' Render-thread node shared with DataService: requests go in, state comes out.
    ' Keeping it render-owned means the UI never blocks on the task thread.
    bus = CreateObject("roSGNode", "Node")
    bus.addField("request", "assocarray", true)
    bus.addFields({ ready: false, status: "", playlistState: "idle", epgState: "idle", categories: [], channelCount: 0, epgVersion: 0, playerOpen: false, playingSince: 0 })
    ' playerOpen / playingSince (epoch secs, 0 = not playing) come from the player, so the data
    ' service can hold heavy guide work until a starting stream has settled.
    m.global.addFields({ bus: bus })
    bus.observeFieldScoped("playlistState", "onPlaylistState")

    svc = CreateObject("roSGNode", "DataService")
    m.global.addFields({ svc: svc })
    svc.control = "RUN"

    if regRead("m3uUrl") = "" then
        pushScreen("SetupScreen", { firstRun: true })
    else
        startLoad(true)
        pushScreen("HomeScreen", {})
    end if
end sub

sub startLoad(resume as boolean)
    m.resumePending = resume and regRead("lastKey") <> ""
    m.pendingLoad = { type: "load", m3u: regRead("m3uUrl"), epg: regRead("epgUrl"), resume: m.resumePending }
    if m.global.bus.ready then
        sendPendingLoad()
    else
        ' The task thread may not be listening yet on a cold start; wait for it.
        m.global.bus.observeFieldScoped("ready", "sendPendingLoad")
    end if
end sub

sub sendPendingLoad()
    if not m.global.bus.ready or m.pendingLoad = invalid then return
    m.global.bus.unobserveFieldScoped("ready")
    req = m.pendingLoad
    m.pendingLoad = invalid
    m.global.bus.request = req
end sub

' Last-watched channel on launch: once the playlist is ready, jump straight into it.
sub onPlaylistState()
    if m.global.bus.playlistState <> "ready" or not m.resumePending then return
    m.resumePending = false
    if m.stack.Count() = 1 and m.stack[0].subtype() = "HomeScreen" then
        pushScreen("PlayerScreen", { category: regRead("lastCategory", "__all__"), key: regRead("lastKey") })
    end if
end sub

sub pushScreen(name as string, params as object)
    if m.stack.Count() > 0 then
        top = m.stack.Peek()
        top.active = false
        top.visible = false
    end if
    screen = m.screens.createChild(name)
    screen.observeField("navigate", "onNavigate")
    screen.params = params
    m.stack.Push(screen)
    screen.active = true
end sub

sub closeScreen(screen as object)
    screen.active = false
    screen.closed = true
    screen.unobserveField("navigate")
    m.screens.removeChild(screen)
end sub

sub popScreen()
    if m.stack.Count() <= 1 then return
    closeScreen(m.stack.Pop())
    top = m.stack.Peek()
    top.visible = true
    top.active = true
end sub

sub onNavigate(event as object)
    nav = event.getData()
    if nav = invalid or nav.action = invalid then return
    params = nav.params
    if params = invalid then params = {}
    if nav.action = "push" then
        pushScreen(nav.screen, params)
    else if nav.action = "pop" then
        popScreen()
    else if nav.action = "replace" then
        if m.stack.Count() > 0 then closeScreen(m.stack.Pop())
        pushScreen(nav.screen, params)
    else if nav.action = "reset" then
        while m.stack.Count() > 0
            closeScreen(m.stack.Pop())
        end while
        if nav.load <> invalid and nav.load then startLoad(false)
        pushScreen(nav.screen, params)
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if press and key = "back" and m.stack.Count() > 1 then
        popScreen()
        return true
    end if
    return false
end function
